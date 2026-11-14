package dev.mcpbridge.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcpbridge.config.BridgeProperties;
import dev.mcpbridge.shop.ShopBackend;
import dev.mcpbridge.shop.ShopException;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springaicommunity.mcp.annotation.McpArg;
import org.springaicommunity.mcp.annotation.McpPrompt;
import org.springaicommunity.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

import java.util.List;

/** Context the model can pull in without calling tools: the data model, the refund policy, and orders by URI. */
@Component
public class ShopResources {

    private final ShopBackend shop;
    private final ObjectMapper mapper;
    private final BridgeProperties.Guardrails limits;

    public ShopResources(ShopBackend shop, ObjectMapper mapper, BridgeProperties props) {
        this.shop = shop;
        this.mapper = mapper;
        this.limits = props.guardrails();
    }

    @McpResource(uri = "shop://schema", name = "shop-schema", title = "Shop data model", mimeType = "text/markdown",
            description = "Entities, order statuses and units used by the shop tools")
    public String schema() {
        return """
                # Shop data model
                - **Customer**: id, name, email, country (ISO-2).
                - **Product**: SKU (e.g. `GR-110`), name, category, price, stock, reorder level. `lowStock` = stock <= reorder level.
                - **Order**: number (from 10001), customer, lines (SKU x quantity at the price paid), total, refunded, status.
                - **Order status**: PAID -> SHIPPED -> DELIVERED. CANCELLED orders cannot be refunded.
                  Refunds move an order to PARTIALLY_REFUNDED or REFUNDED.
                - Money is **USD** with two decimals. Times are **UTC**.
                - Writes are two-phase: `prepare_*` returns a token, `confirm_action` executes it after the user approves.
                """;
    }

    @McpResource(uri = "shop://policies/refunds", name = "refund-policy", title = "Refund policy", mimeType = "text/markdown",
            description = "What the assistant may refund and how")
    public String refundPolicy() {
        return """
                # Refund policy for assistants
                1. Look the order up first (`get_order`) and quote the refundable amount.
                2. Partial refunds are fine for damaged or missing items; refund the line total of the affected items.
                3. A single refund through the assistant is capped at **%s USD**. Larger refunds go to the back office.
                4. Never refund a CANCELLED order or more than the refundable amount (the bridge enforces both).
                5. Always show the summary from `prepare_refund` and get an explicit "yes" before `confirm_action`.
                   Confirmation tokens expire after %d minutes and work once.
                """.formatted(limits.maxRefundUsd(), limits.confirmationTtl().toMinutes());
    }

    @McpResource(uri = "shop://orders/{orderId}", name = "order", title = "Order by number", mimeType = "application/json",
            description = "An order with lines and refunds, as JSON")
    public String order(String orderId) {
        long id;
        try {
            id = Long.parseLong(orderId);
        } catch (NumberFormatException e) {
            throw new ShopException.InvalidRequest("Order numbers are numeric.");
        }
        try {
            return mapper.writeValueAsString(shop.getOrder(id)
                    .orElseThrow(() -> new ShopException.NotFound("Order " + id + " does not exist.")));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @McpPrompt(name = "handle_damaged_item", title = "Handle a damaged-item complaint",
            description = "Guides the assistant through checking an order and proposing a partial refund for a damaged item")
    public GetPromptResult damagedItem(@McpArg(name = "orderId", description = "Order number", required = true) String orderId,
                                       @McpArg(name = "item", description = "Which item arrived damaged", required = false) String item) {
        String text = """
                A customer says %s from order %s arrived damaged.
                1. Call get_order to confirm the order exists, is not cancelled, and contains that item.
                2. Propose a refund of that line's total (respecting the refundable amount and the refund policy resource).
                3. Call prepare_refund with a clear reason, show me the summary, and wait for my approval before confirm_action.
                """.formatted(item == null || item.isBlank() ? "an item" : "the " + item, orderId);
        return new GetPromptResult("Damaged item on order " + orderId,
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }
}
