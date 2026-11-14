package dev.mcpbridge;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class WriteActionsIT extends AbstractIT {

    private JsonNode prepareRefund(McpSyncClient c, long orderId, Object amount) {
        return ok(call(c, "prepare_refund", Map.of("orderId", orderId, "amountUsd", amount, "reason", "Grinder arrived damaged")));
    }

    private long refundedCents(long orderId) {
        return jdbc.queryForObject("SELECT refunded_cents FROM orders WHERE id = ?", Long.class, orderId);
    }

    @Test
    void prepareChangesNothingConfirmRefunds() {
        long orderId = newOrder(8_900);
        McpSyncClient c = client(WRITE_KEY);

        JsonNode prepared = prepareRefund(c, orderId, 25.5);
        assertThat(prepared.get("token").asText()).startsWith("act_").hasSize(26);
        assertThat(prepared.get("summary").asText()).contains("Refund 25.50 USD on order #" + orderId).contains("Grinder arrived damaged");
        assertThat(prepared.get("next").asText()).contains("explicit yes");
        assertThat(refundedCents(orderId)).isZero();

        JsonNode done = ok(call(c, "confirm_action", Map.of("token", prepared.get("token").asText())));
        assertThat(done.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(done.get("result").get("orderStatus").asText()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(refundedCents(orderId)).isEqualTo(2_550);
        assertThat(jdbc.queryForObject("SELECT created_by FROM refunds WHERE order_id = ?", String.class, orderId))
                .isEqualTo("mcp:support-agent");
    }

    @Test
    void tokensWorkOnceEvenUnderConcurrentConfirms() throws Exception {
        long orderId = newOrder(10_000);
        McpSyncClient c = client(WRITE_KEY);
        String token = prepareRefund(c, orderId, 40).get("token").asText();

        try (var pool = Executors.newFixedThreadPool(4)) {
            Callable<Boolean> confirm = () -> !Boolean.TRUE.equals(call(client(WRITE_KEY), "confirm_action", Map.of("token", token)).isError());
            var futures = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(confirm)).toList();
            long successes = 0;
            for (Future<Boolean> f : futures) {
                successes += f.get() ? 1 : 0;
            }
            assertThat(successes).isEqualTo(1);
        }
        assertThat(refundedCents(orderId)).isEqualTo(4_000);
        assertThat(error(call(c, "confirm_action", Map.of("token", token)))).contains("already confirmed");
    }

    @Test
    void expiredTokensAreRejected() {
        long orderId = newOrder(10_000);
        McpSyncClient c = client(WRITE_KEY);
        String token = prepareRefund(c, orderId, 10).get("token").asText();
        jdbc.update("UPDATE pending_actions SET expires_at = now() - interval '1 second' WHERE token = ?", token);
        assertThat(error(call(c, "confirm_action", Map.of("token", token)))).contains("expired");
        assertThat(refundedCents(orderId)).isZero();
    }

    @Test
    void tokenBelongsToTheKeyThatPreparedIt() {
        long orderId = newOrder(10_000);
        String token = prepareRefund(client(WRITE_KEY), orderId, 10).get("token").asText();
        assertThat(error(call(client(OTHER_WRITE_KEY), "confirm_action", Map.of("token", token)))).contains("different API key");
        assertThat(refundedCents(orderId)).isZero();
    }

    @Test
    void cancelledActionsCannotBeConfirmed() {
        long orderId = newOrder(10_000);
        McpSyncClient c = client(WRITE_KEY);
        String token = prepareRefund(c, orderId, 10).get("token").asText();
        assertThat(ok(call(c, "list_pending_actions", Map.of()))).extracting(n -> n.get("token").asText()).contains(token);
        assertThat(ok(call(c, "cancel_action", Map.of("token", token))).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(error(call(c, "confirm_action", Map.of("token", token)))).contains("cancelled");
        assertThat(error(call(c, "confirm_action", Map.of("token", "act_doesnotexist")))).contains("Unknown confirmation token");
    }

    @Test
    void guardrailsStopBadRefundsAtPrepareTime() {
        McpSyncClient c = client(WRITE_KEY);
        long small = newOrder(5_000);
        assertThat(error(call(c, "prepare_refund", Map.of("orderId", small, "amountUsd", 50.01, "reason", "damaged item"))))
                .contains("Only 50.00 USD").contains("can still be refunded");
        long big = newOrder(150_000);
        assertThat(error(call(c, "prepare_refund", Map.of("orderId", big, "amountUsd", 600, "reason", "damaged item"))))
                .contains("above 500.00 USD");
        assertThat(error(call(c, "prepare_refund", Map.of("orderId", small, "amountUsd", 10, "reason", "x")))).contains("reason");
        assertThat(error(call(c, "prepare_refund", Map.of("orderId", small, "amountUsd", 10.123, "reason", "damaged item"))))
                .contains("two decimals");
        long cancelled = newOrder(5_000);
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", cancelled);
        assertThat(error(call(c, "prepare_refund", Map.of("orderId", cancelled, "amountUsd", 10, "reason", "damaged item"))))
                .contains("cancelled");
    }

    @Test
    void stateIsRecheckedAtConfirmTime() {
        long orderId = newOrder(10_000);
        McpSyncClient c = client(WRITE_KEY);
        String first = prepareRefund(c, orderId, 70).get("token").asText();
        String second = prepareRefund(c, orderId, 70).get("token").asText();   // both valid when prepared
        ok(call(c, "confirm_action", Map.of("token", first)));
        assertThat(error(call(c, "confirm_action", Map.of("token", second)))).contains("exceeds the refundable amount");
        assertThat(refundedCents(orderId)).isEqualTo(7_000);
        assertThat(jdbc.queryForObject("SELECT status FROM pending_actions WHERE token = ?", String.class, second)).isEqualTo("FAILED");
    }

    @Test
    void stockAdjustmentsAreTwoPhaseAndCannotGoNegative() {
        McpSyncClient c = client(WRITE_KEY);
        int before = stockOf("KT-410");
        assertThat(error(call(c, "prepare_stock_adjustment", Map.of("sku", "KT-410", "delta", -(before + 1), "reason", "cycle count"))))
                .contains("would make it negative");
        assertThat(error(call(c, "prepare_stock_adjustment", Map.of("sku", "KT-410", "delta", 500, "reason", "received PO"))))
                .contains("limited to 100");
        assertThat(error(call(c, "prepare_stock_adjustment", Map.of("sku", "NOPE-1", "delta", 5, "reason", "received PO"))))
                .contains("does not exist");

        JsonNode prepared = ok(call(c, "prepare_stock_adjustment", Map.of("sku", "kt-410", "delta", 24, "reason", "received PO 4411")));
        assertThat(prepared.get("summary").asText()).contains("Add 24 units of KT-410").contains(before + " -> " + (before + 24));
        assertThat(stockOf("KT-410")).isEqualTo(before);
        JsonNode done = ok(call(c, "confirm_action", Map.of("token", prepared.get("token").asText())));
        assertThat(done.get("result").get("newStock").asInt()).isEqualTo(before + 24);
        assertThat(stockOf("KT-410")).isEqualTo(before + 24);
    }
}
