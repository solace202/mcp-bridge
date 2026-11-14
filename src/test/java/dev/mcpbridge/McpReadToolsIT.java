package dev.mcpbridge;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpReadToolsIT extends AbstractIT {

    @Test
    void listsAllToolsWithSchemasAndHints() {
        McpSyncClient c = client(READ_KEY);
        List<McpSchema.Tool> tools = c.listTools().tools();
        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder(
                "search_orders", "get_order", "find_customers", "check_inventory", "sales_summary",
                "prepare_refund", "prepare_stock_adjustment", "confirm_action", "cancel_action", "list_pending_actions");
        McpSchema.Tool getOrder = tools.stream().filter(t -> t.name().equals("get_order")).findFirst().orElseThrow();
        assertThat(getOrder.annotations().readOnlyHint()).isTrue();
        assertThat(getOrder.inputSchema().required()).containsExactly("orderId");
        McpSchema.Tool confirm = tools.stream().filter(t -> t.name().equals("confirm_action")).findFirst().orElseThrow();
        assertThat(confirm.annotations().destructiveHint()).isTrue();
        assertThat(c.getServerInfo().name()).isEqualTo("shop-mcp-bridge");
    }

    @Test
    void searchOrdersFiltersByCustomerStatusAndAmount() {
        McpSyncClient c = client(READ_KEY);
        String email = jdbc.queryForObject("SELECT email FROM customers WHERE id = 7", String.class);
        JsonNode orders = ok(call(c, "search_orders", Map.of("customer", email, "limit", 100)));
        int expected = jdbc.queryForObject("SELECT count(*) FROM orders WHERE customer_id = 7", Integer.class);
        assertThat(orders).hasSize(Math.min(expected, 100));
        orders.forEach(o -> assertThat(o.get("customerEmail").asText()).isEqualTo(email));

        JsonNode big = ok(call(c, "search_orders", Map.of("status", "delivered", "minTotalUsd", 1000, "limit", 5)));
        assertThat(big).isNotEmpty().allSatisfy(o -> {
            assertThat(o.get("status").asText()).isEqualTo("DELIVERED");
            assertThat(o.get("totalUsd").decimalValue()).isGreaterThanOrEqualTo(new java.math.BigDecimal("1000"));
        });
        // newest first
        assertThat(big.get(0).get("createdAt").asText()).isGreaterThanOrEqualTo(big.get(big.size() - 1).get("createdAt").asText());
    }

    @Test
    void searchOrdersRejectsBadInputWithAReadableMessage() {
        McpSyncClient c = client(READ_KEY);
        assertThat(error(call(c, "search_orders", Map.of("status", "LOST")))).contains("Unknown status 'LOST'");
        assertThat(error(call(c, "search_orders", Map.of("fromDate", "14/11/2026")))).contains("Dates must look like");
    }

    @Test
    void getOrderReturnsLinesRefundsAndRefundable() {
        McpSyncClient c = client(READ_KEY);
        long id = jdbc.queryForObject("SELECT min(id) FROM orders WHERE status = 'PARTIALLY_REFUNDED'", Long.class);
        JsonNode order = ok(call(c, "get_order", Map.of("orderId", id)));
        assertThat(order.get("lines")).isNotEmpty();
        assertThat(order.get("refunds")).hasSize(1);
        assertThat(order.get("refundableUsd").decimalValue())
                .isEqualByComparingTo(order.get("totalUsd").decimalValue().subtract(order.get("refundedUsd").decimalValue()));
        assertThat(order.get("customer").get("email").asText()).endsWith("@example.com");
    }

    @Test
    void unknownOrderIsAToolErrorNotAProtocolError() {
        assertThat(error(call(client(READ_KEY), "get_order", Map.of("orderId", 999_999)))).isEqualTo("Order 999999 does not exist.");
    }

    @Test
    void findCustomersAndInventoryAndSales() {
        McpSyncClient c = client(READ_KEY);
        JsonNode customers = ok(call(c, "find_customers", Map.of("query", "example.com", "limit", 3)));
        assertThat(customers).hasSize(3);
        assertThat(customers.get(0).get("lifetimeValueUsd").decimalValue())
                .isGreaterThanOrEqualTo(customers.get(1).get("lifetimeValueUsd").decimalValue());

        JsonNode low = ok(call(c, "check_inventory", Map.of("lowStockOnly", true)));
        assertThat(low).isNotEmpty().allSatisfy(p -> assertThat(p.get("lowStock").asBoolean()).isTrue());
        assertThat(low).extracting(p -> p.get("sku").asText()).contains("ES-220", "AC-650");

        JsonNode sales = ok(call(c, "sales_summary", Map.of("days", 30)));
        assertThat(sales.get("orders").asInt()).isPositive();
        assertThat(sales.get("topProducts")).hasSize(5);
        assertThat(sales.get("ordersByStatus").has("DELIVERED")).isTrue();
    }

    @Test
    void resourcesAndPrompt() {
        McpSyncClient c = client(READ_KEY);
        assertThat(c.listResources().resources()).extracting(McpSchema.Resource::uri)
                .contains("shop://schema", "shop://policies/refunds");
        assertThat(c.listResourceTemplates().resourceTemplates()).extracting(McpSchema.ResourceTemplate::uriTemplate)
                .contains("shop://orders/{orderId}");

        var policy = (McpSchema.TextResourceContents) c.readResource(new McpSchema.ReadResourceRequest("shop://policies/refunds"))
                .contents().getFirst();
        assertThat(policy.text()).contains("500.00 USD");

        var order = (McpSchema.TextResourceContents) c.readResource(new McpSchema.ReadResourceRequest("shop://orders/10001"))
                .contents().getFirst();
        assertThat(order.text()).contains("\"id\":10001");

        var prompt = c.getPrompt(new McpSchema.GetPromptRequest("handle_damaged_item", Map.of("orderId", "10001", "item", "grinder")));
        assertThat(((McpSchema.TextContent) prompt.messages().getFirst().content()).text())
                .contains("the grinder from order 10001").contains("prepare_refund");
    }

    @Test
    void everyCallIsAudited() {
        McpSyncClient c = client(READ_KEY);
        long before = jdbc.queryForObject("SELECT count(*) FROM tool_audit WHERE api_key = 'analyst' AND tool = 'sales_summary'", Long.class);
        ok(call(c, "sales_summary", Map.of("days", 7)));
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT outcome, arguments::text AS args FROM tool_audit
                WHERE api_key = 'analyst' AND tool = 'sales_summary' ORDER BY id DESC LIMIT 1""");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tool_audit WHERE api_key = 'analyst' AND tool = 'sales_summary'", Long.class))
                .isEqualTo(before + 1);
        assertThat(row.get("outcome")).isEqualTo("OK");
        assertThat(row.get("args").toString()).contains("\"days\": 7");
    }
}
