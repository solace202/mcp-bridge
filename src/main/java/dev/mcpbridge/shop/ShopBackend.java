package dev.mcpbridge.shop;

import dev.mcpbridge.shop.Models.*;

import java.util.List;
import java.util.Optional;

/**
 * The bridge talks to the existing system through this port. Two adapters ship: {@code local} reads the shop's
 * PostgreSQL database directly, {@code rest} calls the shop's HTTP API (the usual setup when the database is
 * owned by another team).
 */
public interface ShopBackend {

    List<OrderSummary> searchOrders(OrderQuery query);

    Optional<OrderDetail> getOrder(long orderId);

    List<CustomerInfo> findCustomers(String query, int limit);

    List<StockLevel> inventory(String query, String category, boolean lowStockOnly, int limit);

    Optional<StockLevel> product(String sku);

    SalesSummary salesSummary(int days);

    /** Idempotent on {@code reference}: repeating a call with the same reference returns the first result. */
    RefundResult refund(long orderId, RefundRequest request);

    /** Idempotent on {@code reference}. */
    StockAdjustment adjustStock(String sku, StockAdjustmentRequest request);
}
