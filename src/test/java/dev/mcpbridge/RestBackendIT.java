package dev.mcpbridge;

import dev.mcpbridge.backend.RestShopBackend;
import dev.mcpbridge.shop.ShopBackend;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * bridge.backend.type=rest: the MCP tools reach the shop only through its HTTP API (here the API hosted by the
 * same app, on a fixed free port), exactly as they would call a client's existing service.
 */
class RestBackendIT extends AbstractIT {

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void restMode(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("bridge.backend.type", () -> "rest");
        registry.add("bridge.backend.base-url", () -> "http://localhost:" + PORT);
    }

    @Autowired
    ShopBackend backend;

    @Test
    void toolsGoThroughTheShopApi() {
        assertThat(backend).isInstanceOf(RestShopBackend.class);
        var c = client(READ_KEY);
        assertThat(ok(call(c, "get_order", Map.of("orderId", 10001))).get("id").asLong()).isEqualTo(10001);
        assertThat(ok(call(c, "check_inventory", Map.of("query", "GR-1")))).hasSizeGreaterThanOrEqualTo(3);
        assertThat(ok(call(c, "sales_summary", Map.of("days", 30))).get("orders").asInt()).isPositive();
        assertThat(error(call(c, "get_order", Map.of("orderId", 999_998)))).contains("does not exist");
        assertThat(error(call(c, "search_orders", Map.of("status", "LOST")))).contains("Unknown status");
    }

    @Test
    void writesAndUpstreamRuleViolationsWorkOverHttp() {
        long orderId = newOrder(10_000);
        var c = client(WRITE_KEY);
        String first = ok(call(c, "prepare_refund", Map.of("orderId", orderId, "amountUsd", 60, "reason", "damaged item"))).get("token").asText();
        String second = ok(call(c, "prepare_refund", Map.of("orderId", orderId, "amountUsd", 60, "reason", "damaged item"))).get("token").asText();
        assertThat(ok(call(c, "confirm_action", Map.of("token", first))).get("result").get("refundedTotalUsd").decimalValue())
                .isEqualByComparingTo("60.00");
        // the shop API answers 422; the model sees the shop's own message
        assertThat(error(call(c, "confirm_action", Map.of("token", second)))).contains("exceeds the refundable amount");
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
