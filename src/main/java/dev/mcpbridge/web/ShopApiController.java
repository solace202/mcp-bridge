package dev.mcpbridge.web;

import dev.mcpbridge.backend.JdbcShopBackend;
import dev.mcpbridge.shop.Models.*;
import dev.mcpbridge.shop.ShopException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Stand-in for the existing shop system's REST API. In a real engagement this already exists (and is often the
 * only way in); the bridge's {@code rest} backend calls it exactly like it would call a client's API.
 */
@RestController
@RequestMapping("/api/shop")
@Tag(name = "Shop API (existing system)", description = "The legacy orders/inventory API the MCP bridge wraps")
public class ShopApiController {

    private final JdbcShopBackend shop;

    public ShopApiController(JdbcShopBackend shop) {
        this.shop = shop;
    }

    @GetMapping("/orders")
    @Operation(summary = "Search orders, newest first")
    public List<OrderSummary> orders(@RequestParam(required = false) String customer,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) BigDecimal minTotalUsd,
                                     @RequestParam(required = false) Integer limit) {
        return shop.searchOrders(new OrderQuery(customer, status, from, to, minTotalUsd, limit));
    }

    @GetMapping("/orders/{id}")
    @Operation(summary = "One order with lines and refunds")
    public OrderDetail order(@PathVariable long id) {
        return shop.getOrder(id).orElseThrow(() -> new ShopException.NotFound("Order " + id + " does not exist."));
    }

    @PostMapping("/orders/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Refund part or all of an order (idempotent on reference)")
    public RefundResult refund(@PathVariable long id, @RequestBody RefundRequest request) {
        return shop.refund(id, request);
    }

    @GetMapping("/customers")
    @Operation(summary = "Find customers by name or email")
    public List<CustomerInfo> customers(@RequestParam String q, @RequestParam(defaultValue = "20") int limit) {
        return shop.findCustomers(q, limit);
    }

    @GetMapping("/inventory")
    @Operation(summary = "Stock levels, low stock first")
    public List<StockLevel> inventory(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) String category,
                                      @RequestParam(defaultValue = "false") boolean lowStockOnly,
                                      @RequestParam(defaultValue = "50") int limit) {
        return shop.inventory(q, category, lowStockOnly, limit);
    }

    @GetMapping("/inventory/{sku}")
    @Operation(summary = "Stock level for one SKU")
    public StockLevel product(@PathVariable String sku) {
        return shop.product(sku).orElseThrow(() -> new ShopException.NotFound("Product " + sku + " does not exist."));
    }

    @PostMapping("/inventory/{sku}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Adjust stock up or down (idempotent on reference)")
    public StockAdjustment adjust(@PathVariable String sku, @RequestBody StockAdjustmentRequest request) {
        return shop.adjustStock(sku, request);
    }

    @GetMapping("/sales/summary")
    @Operation(summary = "Revenue, refunds and top products over the last N days")
    public SalesSummary sales(@RequestParam(defaultValue = "30") int days) {
        return shop.salesSummary(days);
    }
}
