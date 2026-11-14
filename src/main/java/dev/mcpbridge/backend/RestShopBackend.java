package dev.mcpbridge.backend;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mcpbridge.shop.Models.*;
import dev.mcpbridge.shop.ShopBackend;
import dev.mcpbridge.shop.ShopException;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Talks to the shop through its HTTP API instead of its database. Upstream 404/422 become NotFound/RuleViolation so
 * the model gets the same readable errors in both modes.
 */
public class RestShopBackend implements ShopBackend {

    private final RestClient http;

    public RestShopBackend(RestClient http) {
        this.http = http;
    }

    @Override
    public List<OrderSummary> searchOrders(OrderQuery q) {
        return call(() -> http.get().uri(b -> b.path("/api/shop/orders")
                        .queryParamIfPresent("customer", Optional.ofNullable(q.customer()))
                        .queryParamIfPresent("status", Optional.ofNullable(q.status()))
                        .queryParamIfPresent("from", Optional.ofNullable(q.from()))
                        .queryParamIfPresent("to", Optional.ofNullable(q.to()))
                        .queryParamIfPresent("minTotalUsd", Optional.ofNullable(q.minTotalUsd()))
                        .queryParam("limit", q.effectiveLimit()).build())
                .retrieve().onStatus(HttpStatusCode::isError, RestShopBackend::raise)
                .body(new ParameterizedTypeReference<List<OrderSummary>>() { }));
    }

    @Override
    public Optional<OrderDetail> getOrder(long orderId) {
        try {
            return Optional.ofNullable(get(b -> b.path("/api/shop/orders/{id}").build(orderId), OrderDetail.class));
        } catch (ShopException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public List<CustomerInfo> findCustomers(String query, int limit) {
        return call(() -> http.get().uri(b -> b.path("/api/shop/customers").queryParam("q", query).queryParam("limit", limit).build())
                .retrieve().onStatus(HttpStatusCode::isError, RestShopBackend::raise)
                .body(new ParameterizedTypeReference<List<CustomerInfo>>() { }));
    }

    @Override
    public List<StockLevel> inventory(String query, String category, boolean lowStockOnly, int limit) {
        return call(() -> http.get().uri(b -> b.path("/api/shop/inventory")
                        .queryParamIfPresent("q", Optional.ofNullable(query))
                        .queryParamIfPresent("category", Optional.ofNullable(category))
                        .queryParam("lowStockOnly", lowStockOnly).queryParam("limit", limit).build())
                .retrieve().onStatus(HttpStatusCode::isError, RestShopBackend::raise)
                .body(new ParameterizedTypeReference<List<StockLevel>>() { }));
    }

    @Override
    public Optional<StockLevel> product(String sku) {
        try {
            return Optional.ofNullable(get(b -> b.path("/api/shop/inventory/{sku}").build(sku), StockLevel.class));
        } catch (ShopException.NotFound e) {
            return Optional.empty();
        }
    }

    @Override
    public SalesSummary salesSummary(int days) {
        return get(b -> b.path("/api/shop/sales/summary").queryParam("days", days).build(), SalesSummary.class);
    }

    @Override
    public RefundResult refund(long orderId, RefundRequest request) {
        return call(() -> http.post().uri("/api/shop/orders/{id}/refunds", orderId).body(request)
                .retrieve().onStatus(HttpStatusCode::isError, RestShopBackend::raise).body(RefundResult.class));
    }

    @Override
    public StockAdjustment adjustStock(String sku, StockAdjustmentRequest request) {
        return call(() -> http.post().uri("/api/shop/inventory/{sku}/adjustments", sku).body(request)
                .retrieve().onStatus(HttpStatusCode::isError, RestShopBackend::raise).body(StockAdjustment.class));
    }

    private <T> T get(Function<UriBuilder, URI> uri, Class<T> type) {
        return call(() -> http.get().uri(uri).retrieve().onStatus(HttpStatusCode::isError, RestShopBackend::raise).body(type));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException e) {
            throw new ShopException.Unavailable("The shop API is not reachable right now. Try again in a minute.");
        }
    }

    private static void raise(org.springframework.http.HttpRequest request, ClientHttpResponse response) throws IOException {
        String message = readMessage(response);
        int status = response.getStatusCode().value();
        switch (status) {
            case 404 -> throw new ShopException.NotFound(message);
            case 409, 422 -> throw new ShopException.RuleViolation(message);
            case 400 -> throw new ShopException.InvalidRequest(message);
            default -> throw new ShopException.Unavailable("The shop API answered HTTP " + status + ".");
        }
    }

    private static String readMessage(ClientHttpResponse response) {
        try {
            JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
            return body != null && body.hasNonNull("message") ? body.get("message").asText() : "Request rejected by the shop API.";
        } catch (IOException e) {
            return "Request rejected by the shop API.";
        }
    }
}
