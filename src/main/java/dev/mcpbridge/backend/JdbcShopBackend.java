package dev.mcpbridge.backend;

import dev.mcpbridge.shop.Models.*;
import dev.mcpbridge.shop.Money;
import dev.mcpbridge.shop.ShopBackend;
import dev.mcpbridge.shop.ShopException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Reads and writes the shop database directly. Also backs the shop's own REST API in this demo. */
public class JdbcShopBackend implements ShopBackend {

    static final Set<String> STATUSES = Set.of("PAID", "SHIPPED", "DELIVERED", "CANCELLED", "REFUNDED", "PARTIALLY_REFUNDED");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcShopBackend(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public List<OrderSummary> searchOrders(OrderQuery q) {
        var sql = new StringBuilder("""
                SELECT o.id, o.status, c.name, c.email, o.total_cents, o.refunded_cents, o.created_at,
                       (SELECT count(*) FROM order_items i WHERE i.order_id = o.id) AS lines
                FROM orders o JOIN customers c ON c.id = o.customer_id
                WHERE 1 = 1
                """);
        var p = new MapSqlParameterSource();
        if (hasText(q.customer())) {
            sql.append(" AND (c.email ILIKE :customer OR c.name ILIKE :customer)");
            p.addValue("customer", "%" + escapeLike(q.customer().trim()) + "%");
        }
        if (hasText(q.status())) {
            String status = q.status().trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(status)) {
                throw new ShopException.InvalidRequest("Unknown status '" + q.status() + "'. Use one of " + STATUSES);
            }
            sql.append(" AND o.status = :status");
            p.addValue("status", status);
        }
        if (q.from() != null) {
            sql.append(" AND o.created_at >= :from");
            p.addValue("from", q.from().atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (q.to() != null) {
            sql.append(" AND o.created_at < :to");
            p.addValue("to", q.to().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
        }
        if (q.minTotalUsd() != null) {
            sql.append(" AND o.total_cents >= :minTotal");
            p.addValue("minTotal", Money.cents(q.minTotalUsd()));
        }
        sql.append(" ORDER BY o.created_at DESC, o.id DESC LIMIT :limit");
        p.addValue("limit", q.effectiveLimit());
        return jdbc.query(sql.toString(), p, (rs, i) -> new OrderSummary(rs.getLong("id"), rs.getString("status"),
                rs.getString("name"), rs.getString("email"), rs.getInt("lines"), Money.usd(rs.getLong("total_cents")),
                Money.usd(rs.getLong("refunded_cents")), ts(rs, "created_at")));
    }

    @Override
    public Optional<OrderDetail> getOrder(long orderId) {
        var p = new MapSqlParameterSource("id", orderId);
        List<OrderDetail> found = jdbc.query("""
                SELECT o.id, o.status, o.total_cents, o.refunded_cents, o.created_at,
                       c.id AS cid, c.name, c.email, c.country
                FROM orders o JOIN customers c ON c.id = o.customer_id WHERE o.id = :id
                """, p, (rs, i) -> {
            long total = rs.getLong("total_cents");
            long refunded = rs.getLong("refunded_cents");
            String status = rs.getString("status");
            long refundable = "CANCELLED".equals(status) ? 0 : total - refunded;
            return new OrderDetail(rs.getLong("id"), status,
                    new CustomerRef(rs.getLong("cid"), rs.getString("name"), rs.getString("email"), rs.getString("country")),
                    List.of(), Money.usd(total), Money.usd(refunded), Money.usd(refundable), List.of(), ts(rs, "created_at"));
        });
        if (found.isEmpty()) {
            return Optional.empty();
        }
        OrderDetail o = found.getFirst();
        List<OrderLine> lines = jdbc.query("""
                SELECT p.sku, p.name, i.quantity, i.unit_price_cents
                FROM order_items i JOIN products p ON p.id = i.product_id WHERE i.order_id = :id ORDER BY i.id
                """, p, (rs, i) -> new OrderLine(rs.getString("sku"), rs.getString("name"), rs.getInt("quantity"),
                Money.usd(rs.getLong("unit_price_cents")), Money.usd(rs.getLong("unit_price_cents") * rs.getInt("quantity"))));
        List<RefundInfo> refunds = jdbc.query("""
                SELECT id, amount_cents, reason, created_by, created_at FROM refunds WHERE order_id = :id ORDER BY id
                """, p, (rs, i) -> new RefundInfo(rs.getLong("id"), Money.usd(rs.getLong("amount_cents")),
                rs.getString("reason"), rs.getString("created_by"), ts(rs, "created_at")));
        return Optional.of(new OrderDetail(o.id(), o.status(), o.customer(), lines, o.totalUsd(), o.refundedUsd(),
                o.refundableUsd(), refunds, o.createdAt()));
    }

    @Override
    public List<CustomerInfo> findCustomers(String query, int limit) {
        if (!hasText(query) || query.trim().length() < 2) {
            throw new ShopException.InvalidRequest("Give at least two characters of a name or email.");
        }
        return jdbc.query("""
                SELECT c.id, c.name, c.email, c.country,
                       count(o.id) AS orders, coalesce(sum(o.total_cents - o.refunded_cents), 0) AS ltv,
                       max(o.created_at) AS last_order
                FROM customers c LEFT JOIN orders o ON o.customer_id = c.id AND o.status <> 'CANCELLED'
                WHERE c.name ILIKE :q OR c.email ILIKE :q
                GROUP BY c.id ORDER BY ltv DESC, c.id LIMIT :limit
                """, new MapSqlParameterSource("q", "%" + escapeLike(query.trim()) + "%").addValue("limit", clamp(limit, 50)),
                (rs, i) -> new CustomerInfo(rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                        rs.getString("country"), rs.getInt("orders"), Money.usd(rs.getLong("ltv")), ts(rs, "last_order")));
    }

    @Override
    public List<StockLevel> inventory(String query, String category, boolean lowStockOnly, int limit) {
        var sql = new StringBuilder(STOCK_SELECT).append(" WHERE 1 = 1");
        var p = new MapSqlParameterSource();
        if (hasText(query)) {
            sql.append(" AND (p.sku ILIKE :q OR p.name ILIKE :q)");
            p.addValue("q", "%" + escapeLike(query.trim()) + "%");
        }
        if (hasText(category)) {
            sql.append(" AND p.category ILIKE :category");
            p.addValue("category", escapeLike(category.trim()));
        }
        if (lowStockOnly) {
            sql.append(" AND p.stock <= p.reorder_level");
        }
        sql.append(" ORDER BY (p.stock <= p.reorder_level) DESC, p.stock, p.sku LIMIT :limit");
        p.addValue("limit", clamp(limit, 100));
        return jdbc.query(sql.toString(), p, JdbcShopBackend::stockLevel);
    }

    @Override
    public Optional<StockLevel> product(String sku) {
        return jdbc.query(STOCK_SELECT + " WHERE p.sku = :sku", new MapSqlParameterSource("sku", sku.trim().toUpperCase(Locale.ROOT)),
                JdbcShopBackend::stockLevel).stream().findFirst();
    }

    @Override
    public SalesSummary salesSummary(int days) {
        if (days < 1 || days > 365) {
            throw new ShopException.InvalidRequest("days must be between 1 and 365");
        }
        OffsetDateTime since = OffsetDateTime.now(ZoneOffset.UTC).minusDays(days).withNano(0);
        var p = new MapSqlParameterSource("since", since);
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE status <> 'CANCELLED') AS orders,
                       coalesce(sum(total_cents) FILTER (WHERE status <> 'CANCELLED'), 0) AS revenue,
                       coalesce(sum(refunded_cents), 0) AS refunds
                FROM orders WHERE created_at >= :since
                """, p);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        jdbc.query("SELECT status, count(*) AS n FROM orders WHERE created_at >= :since GROUP BY status ORDER BY n DESC",
                p, rs -> {
                    byStatus.put(rs.getString("status"), rs.getInt("n"));
                });
        List<TopProduct> top = jdbc.query("""
                SELECT p.sku, p.name, sum(i.quantity) AS units, sum(i.quantity * i.unit_price_cents) AS revenue
                FROM order_items i JOIN orders o ON o.id = i.order_id JOIN products p ON p.id = i.product_id
                WHERE o.created_at >= :since AND o.status <> 'CANCELLED'
                GROUP BY p.sku, p.name ORDER BY revenue DESC LIMIT 5
                """, p, (rs, i) -> new TopProduct(rs.getString("sku"), rs.getString("name"), rs.getInt("units"),
                Money.usd(rs.getLong("revenue"))));
        int orders = ((Number) totals.get("orders")).intValue();
        long revenue = ((Number) totals.get("revenue")).longValue();
        return new SalesSummary(days, since, orders, Money.usd(revenue), Money.usd(((Number) totals.get("refunds")).longValue()),
                Money.usd(orders == 0 ? 0 : Math.round((double) revenue / orders)), byStatus, top);
    }

    @Override
    public RefundResult refund(long orderId, RefundRequest r) {
        if (r.amountCents() <= 0) {
            throw new ShopException.InvalidRequest("Refund amount must be positive.");
        }
        if (!hasText(r.reason())) {
            throw new ShopException.InvalidRequest("A refund reason is required.");
        }
        return tx.execute(status -> {
            if (r.reference() != null) {
                List<RefundResult> previous = jdbc.query("""
                        SELECT r.id, r.order_id, r.amount_cents, o.refunded_cents, o.status
                        FROM refunds r JOIN orders o ON o.id = r.order_id WHERE r.reference = :ref
                        """, new MapSqlParameterSource("ref", r.reference()), (rs, i) -> new RefundResult(rs.getLong("id"),
                        rs.getLong("order_id"), Money.usd(rs.getLong("amount_cents")), Money.usd(rs.getLong("refunded_cents")),
                        rs.getString("status")));
                if (!previous.isEmpty()) {
                    return previous.getFirst();
                }
            }
            var p = new MapSqlParameterSource("id", orderId);
            List<long[]> rows = jdbc.query("SELECT total_cents, refunded_cents, status FROM orders WHERE id = :id FOR UPDATE", p,
                    (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), "CANCELLED".equals(rs.getString(3)) ? 1 : 0});
            if (rows.isEmpty()) {
                throw new ShopException.NotFound("Order " + orderId + " does not exist.");
            }
            long total = rows.getFirst()[0];
            long refunded = rows.getFirst()[1];
            if (rows.getFirst()[2] == 1) {
                throw new ShopException.RuleViolation("Order " + orderId + " was cancelled; there is nothing to refund.");
            }
            if (refunded + r.amountCents() > total) {
                throw new ShopException.RuleViolation("Refund of " + Money.usd(r.amountCents()) + " exceeds the refundable amount "
                        + Money.usd(total - refunded) + " on order " + orderId + ".");
            }
            long newRefunded = refunded + r.amountCents();
            String newStatus = newRefunded == total ? "REFUNDED" : "PARTIALLY_REFUNDED";
            Long refundId = jdbc.queryForObject("""
                    INSERT INTO refunds (order_id, amount_cents, reason, created_by, reference)
                    VALUES (:id, :amount, :reason, :actor, :ref) RETURNING id
                    """, p.addValue("amount", r.amountCents()).addValue("reason", truncate(r.reason(), 500))
                    .addValue("actor", truncate(r.actor(), 100)).addValue("ref", r.reference()), Long.class);
            jdbc.update("UPDATE orders SET refunded_cents = :refunded, status = :status WHERE id = :id",
                    new MapSqlParameterSource("refunded", newRefunded).addValue("status", newStatus).addValue("id", orderId));
            return new RefundResult(refundId, orderId, Money.usd(r.amountCents()), Money.usd(newRefunded), newStatus);
        });
    }

    @Override
    public StockAdjustment adjustStock(String sku, StockAdjustmentRequest r) {
        if (r.delta() == 0) {
            throw new ShopException.InvalidRequest("delta must not be zero.");
        }
        if (!hasText(r.reason())) {
            throw new ShopException.InvalidRequest("A reason is required for stock adjustments.");
        }
        String normalized = sku.trim().toUpperCase(Locale.ROOT);
        return tx.execute(status -> {
            if (r.reference() != null) {
                List<StockAdjustment> previous = jdbc.query("""
                        SELECT m.id, p.sku, m.delta, p.stock FROM stock_movements m JOIN products p ON p.id = m.product_id
                        WHERE m.reference = :ref
                        """, new MapSqlParameterSource("ref", r.reference()), (rs, i) -> new StockAdjustment(rs.getString("sku"),
                        rs.getInt("stock") - rs.getInt("delta"), rs.getInt("stock"), rs.getLong("id")));
                if (!previous.isEmpty()) {
                    return previous.getFirst();
                }
            }
            var p = new MapSqlParameterSource("sku", normalized);
            List<long[]> rows = jdbc.query("SELECT id, stock FROM products WHERE sku = :sku FOR UPDATE", p,
                    (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
            if (rows.isEmpty()) {
                throw new ShopException.NotFound("Product " + normalized + " does not exist.");
            }
            long productId = rows.getFirst()[0];
            int stock = (int) rows.getFirst()[1];
            if (stock + r.delta() < 0) {
                throw new ShopException.RuleViolation("Stock for " + normalized + " is " + stock + "; it cannot go below zero.");
            }
            Long movementId = jdbc.queryForObject("""
                    INSERT INTO stock_movements (product_id, delta, reason, created_by, reference)
                    VALUES (:pid, :delta, :reason, :actor, :ref) RETURNING id
                    """, new MapSqlParameterSource("pid", productId).addValue("delta", r.delta())
                    .addValue("reason", truncate(r.reason(), 500)).addValue("actor", truncate(r.actor(), 100))
                    .addValue("ref", r.reference()), Long.class);
            jdbc.update("UPDATE products SET stock = stock + :delta WHERE id = :pid",
                    new MapSqlParameterSource("delta", r.delta()).addValue("pid", productId));
            return new StockAdjustment(normalized, stock, stock + r.delta(), movementId);
        });
    }

    private static final String STOCK_SELECT = """
            SELECT p.sku, p.name, p.category, p.price_cents, p.stock, p.reorder_level,
                   coalesce((SELECT sum(i.quantity) FROM order_items i JOIN orders o ON o.id = i.order_id
                             WHERE i.product_id = p.id AND o.status <> 'CANCELLED'
                               AND o.created_at >= now() - interval '30 days'), 0) AS sold_30d
            FROM products p
            """;

    private static StockLevel stockLevel(ResultSet rs, int i) throws SQLException {
        int stock = rs.getInt("stock");
        int reorder = rs.getInt("reorder_level");
        return new StockLevel(rs.getString("sku"), rs.getString("name"), rs.getString("category"),
                Money.usd(rs.getLong("price_cents")), stock, reorder, stock <= reorder, rs.getInt("sold_30d"));
    }

    private static OffsetDateTime ts(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.withOffsetSameInstant(ZoneOffset.UTC);
    }

    static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static int clamp(int limit, int max) {
        return limit <= 0 ? Math.min(20, max) : Math.min(limit, max);
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

}
