package dev.mcpbridge.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcpbridge.config.BridgeProperties;
import dev.mcpbridge.shop.Models.RefundRequest;
import dev.mcpbridge.shop.Models.StockAdjustmentRequest;
import dev.mcpbridge.shop.ShopBackend;
import dev.mcpbridge.shop.ShopException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Two-phase writes. {@code prepare} stores what would happen and returns a short-lived token; {@code confirm}
 * claims the token atomically (single use, not expired, same API key) and only then calls the shop. The token is
 * also sent to the shop as the idempotency reference, so a retried confirm can never refund twice.
 */
@Service
public class PendingActionService {

    public static final String REFUND = "refund";
    public static final String STOCK_ADJUSTMENT = "stock_adjustment";

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Prepared(String token, String action, String summary, OffsetDateTime expiresAt, String next) {
    }

    public record Outcome(String token, String action, String status, String summary, Object result) {
    }

    public record PendingView(String token, String action, String summary, OffsetDateTime expiresAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ShopBackend shop;
    private final BridgeProperties props;

    public PendingActionService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, ShopBackend shop, BridgeProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.shop = shop;
        this.props = props;
    }

    public Prepared prepare(String kind, Map<String, Object> payload, String summary, String requestedBy) {
        String token = "act_" + randomToken(22);
        OffsetDateTime expires = jdbc.queryForObject("""
                INSERT INTO pending_actions (token, kind, payload, summary, requested_by, expires_at)
                VALUES (:token, :kind, CAST(:payload AS jsonb), :summary, :by, now() + CAST(:ttl AS interval))
                RETURNING expires_at
                """, new MapSqlParameterSource("token", token).addValue("kind", kind).addValue("payload", json(payload))
                .addValue("summary", summary).addValue("by", requestedBy)
                .addValue("ttl", props.guardrails().confirmationTtl().toSeconds() + " seconds"), OffsetDateTime.class);
        return new Prepared(token, kind, summary, expires.withOffsetSameInstant(ZoneOffset.UTC),
                "Show this summary to the user and wait for an explicit yes. Then call confirm_action with the token. "
                        + "The token expires in " + props.guardrails().confirmationTtl().toMinutes()
                        + " minutes and works once. Call cancel_action if the user declines.");
    }

    public Outcome confirm(String token, String who) {
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                UPDATE pending_actions SET status = 'EXECUTING', decided_at = now()
                WHERE token = :token AND status = 'PENDING' AND expires_at > now() AND requested_by = :by
                RETURNING kind, payload::text AS payload, summary
                """, new MapSqlParameterSource("token", token).addValue("by", who));
        if (claimed.isEmpty()) {
            throw new ShopException.RuleViolation(whyNotClaimable(token, who));
        }
        String kind = (String) claimed.getFirst().get("kind");
        String summary = (String) claimed.getFirst().get("summary");
        JsonNode payload = readTree((String) claimed.getFirst().get("payload"));
        try {
            Object result = switch (kind) {
                case REFUND -> shop.refund(payload.get("orderId").asLong(), new RefundRequest(payload.get("amountCents").asLong(),
                        payload.get("reason").asText(), "mcp:" + who, token));
                case STOCK_ADJUSTMENT -> shop.adjustStock(payload.get("sku").asText(), new StockAdjustmentRequest(
                        payload.get("delta").asInt(), payload.get("reason").asText(), "mcp:" + who, token));
                default -> throw new IllegalStateException("Unknown action kind " + kind);
            };
            finish(token, "CONFIRMED", json(result));
            return new Outcome(token, kind, "CONFIRMED", summary, result);
        } catch (RuntimeException e) {
            finish(token, "FAILED", e instanceof ShopException ? e.getMessage() : "internal error");
            throw e;
        }
    }

    public Outcome cancel(String token, String who) {
        List<String> rows = jdbc.queryForList("""
                UPDATE pending_actions SET status = 'CANCELLED', decided_at = now()
                WHERE token = :token AND status = 'PENDING' AND requested_by = :by RETURNING kind
                """, new MapSqlParameterSource("token", token).addValue("by", who), String.class);
        if (rows.isEmpty()) {
            throw new ShopException.RuleViolation(whyNotClaimable(token, who));
        }
        String summary = jdbc.queryForObject("SELECT summary FROM pending_actions WHERE token = :t",
                new MapSqlParameterSource("t", token), String.class);
        return new Outcome(token, rows.getFirst(), "CANCELLED", summary, null);
    }

    public List<PendingView> pending(String who) {
        return jdbc.query("""
                SELECT token, kind, summary, expires_at FROM pending_actions
                WHERE requested_by = :by AND status = 'PENDING' AND expires_at > now() ORDER BY created_at
                """, new MapSqlParameterSource("by", who), (rs, i) -> new PendingView(rs.getString("token"), rs.getString("kind"),
                rs.getString("summary"), rs.getObject("expires_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)));
    }

    private String whyNotClaimable(String token, String who) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, requested_by, expires_at <= now() AS expired FROM pending_actions WHERE token = :token
                """, new MapSqlParameterSource("token", token));
        if (rows.isEmpty()) {
            return "Unknown confirmation token. Prepare the action again.";
        }
        Map<String, Object> r = rows.getFirst();
        if (!who.equals(r.get("requested_by"))) {
            return "This token was issued to a different API key.";
        }
        return switch ((String) r.get("status")) {
            case "CONFIRMED", "EXECUTING" -> "This action was already confirmed; tokens work only once.";
            case "CANCELLED" -> "This action was cancelled.";
            case "FAILED" -> "This action already failed; prepare it again if it is still needed.";
            default -> Boolean.TRUE.equals(r.get("expired"))
                    ? "The confirmation token expired. Prepare the action again and confirm within "
                      + props.guardrails().confirmationTtl().toMinutes() + " minutes."
                    : "The token cannot be used.";
        };
    }

    private void finish(String token, String status, String result) {
        jdbc.update("UPDATE pending_actions SET status = :status, result = :result WHERE token = :token",
                new MapSqlParameterSource("status", status).addValue("token", token)
                        .addValue("result", result != null && result.length() > 1000 ? result.substring(0, 1000) : result));
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readTree(String s) {
        try {
            return mapper.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String randomToken(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
