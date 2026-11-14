package dev.mcpbridge.mcp;

import dev.mcpbridge.config.BridgeProperties;
import dev.mcpbridge.shop.Models.OrderDetail;
import dev.mcpbridge.shop.Models.StockLevel;
import dev.mcpbridge.shop.Money;
import dev.mcpbridge.shop.ShopBackend;
import dev.mcpbridge.shop.ShopException;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static dev.mcpbridge.mcp.ToolGuard.args;

/**
 * Write tools. Nothing changes in the shop until {@code confirm_action} is called with the token that a
 * {@code prepare_*} tool returned. Keys without the 'write' scope cannot call any of these.
 */
@Component
public class ActionTools {

    private final ShopBackend shop;
    private final ToolGuard guard;
    private final PendingActionService actions;
    private final BridgeProperties.Guardrails limits;

    public ActionTools(ShopBackend shop, ToolGuard guard, PendingActionService actions, BridgeProperties props) {
        this.shop = shop;
        this.guard = guard;
        this.actions = actions;
        this.limits = props.guardrails();
    }

    @McpTool(name = "prepare_refund", title = "Prepare a refund",
            description = """
                    Step 1 of 2 for refunding an order. Checks the amount against what is still refundable and the
                    per-refund limit, then returns a confirmation token and a summary. Does NOT move money.
                    Show the summary to the user; only after they clearly approve, call confirm_action with the token.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public PendingActionService.Prepared prepareRefund(
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Order number") long orderId,
            @McpToolParam(description = "Amount to refund in USD, e.g. 24.90") BigDecimal amountUsd,
            @McpToolParam(description = "Why the customer is refunded; stored with the refund") String reason) {
        return guard.write(exchange, "prepare_refund", args("orderId", orderId, "amountUsd", amountUsd, "reason", reason), who -> {
            requireReason(reason);
            if (amountUsd == null || amountUsd.signum() <= 0) {
                throw new ShopException.InvalidRequest("amountUsd must be greater than zero.");
            }
            long cents = Money.cents(amountUsd);
            OrderDetail order = shop.getOrder(orderId)
                    .orElseThrow(() -> new ShopException.NotFound("Order " + orderId + " does not exist."));
            if ("CANCELLED".equals(order.status())) {
                throw new ShopException.RuleViolation("Order " + orderId + " was cancelled; there is nothing to refund.");
            }
            if (cents > Money.cents(order.refundableUsd())) {
                throw new ShopException.RuleViolation("Only " + order.refundableUsd() + " USD of order " + orderId
                        + " can still be refunded (total " + order.totalUsd() + ", refunded " + order.refundedUsd() + ").");
            }
            if (amountUsd.compareTo(limits.maxRefundUsd()) > 0) {
                throw new ShopException.RuleViolation("Refunds above " + limits.maxRefundUsd()
                        + " USD need a human in the back office; the bridge will not prepare them.");
            }
            String summary = "Refund " + Money.usd(cents) + " USD on order #" + orderId + " (" + order.customer().name()
                    + ", " + order.customer().email() + "; order total " + order.totalUsd() + ", already refunded "
                    + order.refundedUsd() + "). Reason: " + reason.trim();
            return actions.prepare(PendingActionService.REFUND,
                    Map.of("orderId", orderId, "amountCents", cents, "reason", reason.trim()), summary, who.name());
        });
    }

    @McpTool(name = "prepare_stock_adjustment", title = "Prepare a stock adjustment",
            description = """
                    Step 1 of 2 for correcting stock (positive delta = add units, negative = remove). Checks the SKU,
                    the per-adjustment limit and that stock cannot go below zero, then returns a confirmation token.
                    Does NOT change stock until confirm_action is called after the user approves.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public PendingActionService.Prepared prepareStockAdjustment(
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Product SKU, e.g. GR-110") String sku,
            @McpToolParam(description = "Units to add (positive) or remove (negative)") int delta,
            @McpToolParam(description = "Why, e.g. 'cycle count 2026-11-14' or 'received PO 4411'") String reason) {
        return guard.write(exchange, "prepare_stock_adjustment", args("sku", sku, "delta", delta, "reason", reason), who -> {
            requireReason(reason);
            if (delta == 0) {
                throw new ShopException.InvalidRequest("delta must not be zero.");
            }
            if (Math.abs(delta) > limits.maxStockAdjustment()) {
                throw new ShopException.RuleViolation("Adjustments are limited to " + limits.maxStockAdjustment()
                        + " units at a time.");
            }
            StockLevel product = shop.product(sku)
                    .orElseThrow(() -> new ShopException.NotFound("Product " + sku + " does not exist."));
            if (product.stock() + delta < 0) {
                throw new ShopException.RuleViolation("Stock for " + product.sku() + " is " + product.stock()
                        + "; removing " + -delta + " would make it negative.");
            }
            String summary = (delta > 0 ? "Add " + delta : "Remove " + -delta) + " units of " + product.sku() + " ("
                    + product.name() + "): stock " + product.stock() + " -> " + (product.stock() + delta) + ". Reason: " + reason.trim();
            return actions.prepare(PendingActionService.STOCK_ADJUSTMENT,
                    Map.of("sku", product.sku(), "delta", delta, "reason", reason.trim()), summary, who.name());
        });
    }

    @McpTool(name = "confirm_action", title = "Confirm a prepared action",
            description = """
                    Step 2 of 2: executes an action returned by prepare_refund or prepare_stock_adjustment.
                    Only call this after the user explicitly approved the summary. Tokens expire after a few minutes,
                    work once, and only for the API key that prepared them.""",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false))
    public PendingActionService.Outcome confirmAction(McpSyncServerExchange exchange,
                                                      @McpToolParam(description = "Token from a prepare_* tool, starts with act_") String token) {
        return guard.write(exchange, "confirm_action", args("token", token),
                who -> actions.confirm(token == null ? "" : token.trim(), who.name()));
    }

    @McpTool(name = "cancel_action", title = "Cancel a prepared action",
            description = "Discards a prepared action the user did not approve.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public PendingActionService.Outcome cancelAction(McpSyncServerExchange exchange,
                                                     @McpToolParam(description = "Token from a prepare_* tool") String token) {
        return guard.write(exchange, "cancel_action", args("token", token),
                who -> actions.cancel(token == null ? "" : token.trim(), who.name()));
    }

    @McpTool(name = "list_pending_actions", title = "List pending actions",
            description = "Actions this API key prepared that are still waiting for confirmation.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<PendingActionService.PendingView> listPendingActions(McpSyncServerExchange exchange) {
        return guard.write(exchange, "list_pending_actions", Map.of(), who -> actions.pending(who.name()));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new ShopException.InvalidRequest("Give a short reason (at least 5 characters); it is stored with the change.");
        }
    }
}
