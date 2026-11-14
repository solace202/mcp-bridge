package dev.mcpbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Boots the whole app on a random port against a real PostgreSQL (Testcontainers, one container for all test
 * classes) and talks to it with the official MCP Java SDK client over Streamable HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bridge.security.keys[0].name=support-agent",
        "bridge.security.keys[0].key=" + AbstractIT.WRITE_KEY,
        "bridge.security.keys[0].scopes=read,write",
        "bridge.security.keys[1].name=analyst",
        "bridge.security.keys[1].key=" + AbstractIT.READ_KEY,
        "bridge.security.keys[1].scopes=read",
        "bridge.security.keys[2].name=second-agent",
        "bridge.security.keys[2].key=" + AbstractIT.OTHER_WRITE_KEY,
        "bridge.security.keys[2].scopes=read,write",
        "shop.api-token=" + AbstractIT.SHOP_TOKEN,
        "bridge.backend.token=" + AbstractIT.SHOP_TOKEN
})
public abstract class AbstractIT {

    public static final String WRITE_KEY = "test-support-key-0123456789";
    public static final String READ_KEY = "test-analyst-key-0123456789";
    public static final String OTHER_WRITE_KEY = "test-second-key-0123456789";
    public static final String SHOP_TOKEN = "test-shop-token-0123456789";

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shop").withUsername("shop").withPassword("shop");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    protected static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    private final List<McpSyncClient> clients = java.util.Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void closeClients() {
        synchronized (clients) { clients.forEach(McpSyncClient::closeGracefully); }
        clients.clear();
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected McpSyncClient client(String key) {
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl()).endpoint("/mcp")
                .customizeRequest(b -> b.header("Authorization", "Bearer " + key)).build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20))
                .clientInfo(new McpSchema.Implementation("it-client", "1.0")).build();
        client.initialize();
        clients.add(client);
        return client;
    }

    protected static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> args) {
        return client.callTool(new McpSchema.CallToolRequest(tool, args));
    }

    protected static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    /** Successful tool result parsed as JSON. */
    protected static JsonNode ok(McpSchema.CallToolResult result) {
        if (Boolean.TRUE.equals(result.isError())) {
            throw new AssertionError("Tool returned an error: " + text(result));
        }
        try {
            return JSON.readTree(text(result));
        } catch (Exception e) {
            throw new AssertionError("Not JSON: " + text(result), e);
        }
    }

    /** Error tool result's message. */
    protected static String error(McpSchema.CallToolResult result) {
        if (!Boolean.TRUE.equals(result.isError())) {
            throw new AssertionError("Expected a tool error but got: " + text(result));
        }
        return text(result);
    }

    /** A fresh DELIVERED order (customer 1, product GR-120) so write tests never interfere with each other. */
    protected long newOrder(long totalCents) {
        Long id = jdbc.queryForObject("""
                INSERT INTO orders (customer_id, status, total_cents, created_at)
                VALUES (1, 'DELIVERED', ?, now() - interval '10 days') RETURNING id
                """, Long.class, totalCents);
        jdbc.update("""
                INSERT INTO order_items (order_id, product_id, quantity, unit_price_cents)
                SELECT ?, id, 1, ? FROM products WHERE sku = 'GR-120'
                """, id, totalCents);
        return id;
    }

    protected int stockOf(String sku) {
        return jdbc.queryForObject("SELECT stock FROM products WHERE sku = ?", Integer.class, sku);
    }
}
