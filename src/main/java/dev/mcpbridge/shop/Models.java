package dev.mcpbridge.shop;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Data shapes shared by the shop REST API, both backends and the MCP tools. Money is USD. */
public final class Models {

    private Models() {
    }

    public record OrderQuery(String customer, String status, LocalDate from, LocalDate to, BigDecimal minTotalUsd,
                             Integer limit) {
        public int effectiveLimit() {
            return limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        }
    }

    public record OrderSummary(long id, String status, String customerName, String customerEmail, int lineCount,
                               BigDecimal totalUsd, BigDecimal refundedUsd, OffsetDateTime createdAt) {
    }

    public record OrderLine(String sku, String product, int quantity, BigDecimal unitPriceUsd, BigDecimal lineTotalUsd) {
    }

    public record RefundInfo(long id, BigDecimal amountUsd, String reason, String createdBy, OffsetDateTime createdAt) {
    }

    public record CustomerRef(long id, String name, String email, String country) {
    }

    public record OrderDetail(long id, String status, CustomerRef customer, List<OrderLine> lines,
                              BigDecimal totalUsd, BigDecimal refundedUsd, BigDecimal refundableUsd,
                              List<RefundInfo> refunds, OffsetDateTime createdAt) {
    }

    public record CustomerInfo(long id, String name, String email, String country, int orders,
                               BigDecimal lifetimeValueUsd, OffsetDateTime lastOrderAt) {
    }

    public record StockLevel(String sku, String name, String category, BigDecimal priceUsd, int stock,
                             int reorderLevel, boolean lowStock, int soldLast30Days) {
    }

    public record TopProduct(String sku, String name, int unitsSold, BigDecimal revenueUsd) {
    }

    public record SalesSummary(int days, OffsetDateTime since, int orders, BigDecimal revenueUsd, BigDecimal refundsUsd,
                               BigDecimal averageOrderUsd, Map<String, Integer> ordersByStatus,
                               List<TopProduct> topProducts) {
    }

    public record RefundRequest(long amountCents, String reason, String actor, String reference) {
    }

    public record RefundResult(long refundId, long orderId, BigDecimal amountUsd, BigDecimal refundedTotalUsd,
                               String orderStatus) {
    }

    public record StockAdjustmentRequest(int delta, String reason, String actor, String reference) {
    }

    public record StockAdjustment(String sku, int previousStock, int newStock, long movementId) {
    }
}
