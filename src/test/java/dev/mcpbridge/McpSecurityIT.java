package dev.mcpbridge;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSecurityIT extends AbstractIT {

    private static final String INIT = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},
             "clientInfo":{"name":"curl","version":"1"}}}""";

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(INIT));
        headers.forEach(b::header);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void mcpEndpointRequiresAKey() throws Exception {
        HttpResponse<String> none = post("/mcp", Map.of());
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.headers().firstValue("WWW-Authenticate")).hasValue("Bearer realm=\"mcp-bridge\"");
        assertThat(post("/mcp", Map.of("Authorization", "Bearer wrong-key-0123456789")).statusCode()).isEqualTo(401);
    }

    @Test
    void acceptsBearerAndXApiKeyHeaders() throws Exception {
        assertThat(post("/mcp", Map.of("Authorization", "Bearer " + READ_KEY)).statusCode()).isEqualTo(200);
        assertThat(post("/mcp", Map.of("X-API-Key", READ_KEY)).statusCode()).isEqualTo(200);
    }

    @Test
    void sdkClientWithoutKeyCannotConnect() {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl()).endpoint("/mcp").build();
        var client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build();
        assertThatThrownBy(client::initialize).isInstanceOf(RuntimeException.class);
        client.closeGracefully();
    }

    @Test
    void foreignBrowserOriginsAreRefusedLocalhostIsAllowed() throws Exception {
        assertThat(post("/mcp", Map.of("X-API-Key", READ_KEY, "Origin", "https://evil.example")).statusCode()).isEqualTo(403);
        assertThat(post("/mcp", Map.of("X-API-Key", READ_KEY, "Origin", "http://localhost:6274")).statusCode()).isEqualTo(200);
    }

    @Test
    void readOnlyKeyCannotUseWriteToolsAndTheAttemptIsAudited() {
        long orderId = newOrder(10_000);
        var c = client(READ_KEY);
        assertThat(error(call(c, "prepare_refund", Map.of("orderId", orderId, "amountUsd", 10, "reason", "damaged item"))))
                .contains("read-only");
        assertThat(error(call(c, "confirm_action", Map.of("token", "act_whatever")))).contains("read-only");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pending_actions WHERE payload->>'orderId' = ?", Long.class,
                String.valueOf(orderId))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT outcome FROM tool_audit WHERE api_key = 'analyst' AND tool = 'prepare_refund' ORDER BY id DESC LIMIT 1""",
                String.class)).isEqualTo("DENIED");
    }

    @Test
    void adminApiUsesTheSameKeys() throws Exception {
        var req = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/bridge/whoami")).header("X-API-Key", WRITE_KEY).build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"name\":\"support-agent\"").contains("write");
        var anon = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/bridge/audit")).build();
        assertThat(http.send(anon, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void shopApiNeedsItsServiceToken() throws Exception {
        var anon = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/shop/orders/10001")).build();
        assertThat(http.send(anon, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var withToken = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/shop/orders/10001")).header("X-Shop-Token", SHOP_TOKEN).build();
        assertThat(http.send(withToken, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void healthIsPublic() throws Exception {
        var req = HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health")).build();
        assertThat(http.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
}
