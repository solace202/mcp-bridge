package dev.mcpbridge.mcp;

import dev.mcpbridge.shop.Models.*;
import dev.mcpbridge.shop.ShopBackend;
import dev.mcpbridge.shop.ShopException;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import static dev.mcpbridge.mcp.ToolGuard.args;

/** Read-only tools. Any key with the 'read' scope can call them. */
@Component
public class ShopTools {

    private final ShopBackend shop;
    private final ToolGuard guard;

    public ShopTools(ShopBackend shop, ToolGuard guard) {
        this.shop = shop;
        this.guard = guard;
    }

    @McpTool(name = "search_orders", title = "Search orders",
            description = """
                    Search shop orders, newest first. All filters are optional and combine with AND.
                    Returns id, status, customer, line count, total and refunded amount (USD) and creation time (UTC).
                    Use get_order for line items and refund history.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<OrderSummary> searchOrders(
            McpSyncServerExchange exchange,
            @McpToolParam(required = false, description = "Part of the customer's name or email") String customer,
            @McpToolParam(required = false, description = "PAID, SHIPPED, DELIVERED, CANCELLED, REFUNDED or PARTIALLY_REFUNDED") String status,
            @McpToolParam(required = false, description = "Earliest order date, YYYY-MM-DD (UTC)") String fromDate,
            @McpToolParam(required = false, description = "Latest order date, YYYY-MM-DD (UTC), inclusive") String toDate,
            @McpToolParam(required = false, description = "Only orders with a total of at least this many USD") BigDecimal minTotalUsd,
            @McpToolParam(required = false, description = "Max results, 1-100 (default 20)") Integer limit) {
        return guard.read(exchange, "search_orders",
                args("customer", customer, "status", status, "fromDate", fromDate, "toDate", toDate, "minTotalUsd", minTotalUsd, "limit", limit),
                () -> shop.searchOrders(new OrderQuery(customer, status, date(fromDate), date(toDate), minTotalUsd, limit)));
    }

    @McpTool(name = "get_order", title = "Get order",
            description = "One order with customer, line items, refunds so far and the amount that can still be refunded (USD).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public OrderDetail getOrder(McpSyncServerExchange exchange,
                                @McpToolParam(description = "Order number, e.g. 10234") long orderId) {
        return guard.read(exchange, "get_order", args("orderId", orderId),
                () -> shop.getOrder(orderId).orElseThrow(() -> new ShopException.NotFound("Order " + orderId + " does not exist.")));
    }

    @McpTool(name = "find_customers", title = "Find customers",
            description = "Find customers by part of their name or email. Returns order count, lifetime value (USD, net of refunds) and last order time.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<CustomerInfo> findCustomers(McpSyncServerExchange exchange,
                                            @McpToolParam(description = "At least two characters of a name or email") String query,
                                            @McpToolParam(required = false, description = "Max results, 1-50 (default 20)") Integer limit) {
        return guard.read(exchange, "find_customers", args("query", query, "limit", limit),
                () -> shop.findCustomers(query, limit == null ? 20 : limit));
    }

    @McpTool(name = "check_inventory", title = "Check inventory",
            description = """
                    Stock levels by SKU with reorder level, a lowStock flag and units sold in the last 30 days.
                    Low-stock items are listed first. Filter by SKU/name text, category, or lowStockOnly.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<StockLevel> checkInventory(McpSyncServerExchange exchange,
                                           @McpToolParam(required = false, description = "Part of a SKU or product name") String query,
                                           @McpToolParam(required = false, description = "Grinders, Espresso, Brewing, Kettles, Scales, Accessories, Beans or Cups") String category,
                                           @McpToolParam(required = false, description = "Only items at or below their reorder level") Boolean lowStockOnly,
                                           @McpToolParam(required = false, description = "Max results, 1-100 (default 50)") Integer limit) {
        return guard.read(exchange, "check_inventory",
                args("query", query, "category", category, "lowStockOnly", lowStockOnly, "limit", limit),
                () -> shop.inventory(query, category, Boolean.TRUE.equals(lowStockOnly), limit == null ? 50 : limit));
    }

    @McpTool(name = "sales_summary", title = "Sales summary",
            description = "Orders, revenue, refunds, average order value, orders by status and the top 5 products for the last N days (USD).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SalesSummary salesSummary(McpSyncServerExchange exchange,
                                     @McpToolParam(required = false, description = "Look-back window in days, 1-365 (default 30)") Integer days) {
        int d = days == null ? 30 : days;
        return guard.read(exchange, "sales_summary", args("days", d), () -> shop.salesSummary(d));
    }

    static LocalDate date(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new ShopException.InvalidRequest("Dates must look like 2026-11-14, got '" + s + "'.");
        }
    }
}
