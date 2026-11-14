package dev.mcpbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the bridge as a child process with the stdio profile, the way Claude Desktop or Cursor would, and speaks
 * MCP over its stdin/stdout. Proves nothing but JSON-RPC reaches stdout (no banner, no logs).
 */
class StdioTransportIT {

    @Test
    void desktopClientsCanSpawnTheServer() throws Exception {
        var pg = AbstractIT.POSTGRES;
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var params = ServerParameters.builder(java)
                .args("-cp", System.getProperty("java.class.path"), "dev.mcpbridge.McpBridgeApplication",
                        "--spring.profiles.active=stdio",
                        "--spring.datasource.url=" + pg.getJdbcUrl(),
                        "--spring.datasource.username=" + pg.getUsername(),
                        "--spring.datasource.password=" + pg.getPassword())
                .build();
        McpSyncClient client = McpClient.sync(new StdioClientTransport(params, new JacksonMcpJsonMapper(new ObjectMapper())))
                .requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(90)).build();
        try {
            client.initialize();
            assertThat(client.listTools().tools()).hasSize(10);
            var result = client.callTool(new McpSchema.CallToolRequest("check_inventory", Map.of("lowStockOnly", true)));
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            JsonNode items = new ObjectMapper().readTree(((McpSchema.TextContent) result.content().getFirst()).text());
            assertThat(items).isNotEmpty();
            // The local stdio user gets the configured scopes (read+write by default), so prepare works without a key.
            var prepared = client.callTool(new McpSchema.CallToolRequest("prepare_stock_adjustment",
                    Map.of("sku", "AC-650", "delta", 3, "reason", "stdio smoke test")));
            assertThat(((McpSchema.TextContent) prepared.content().getFirst()).text()).contains("act_");
        } finally {
            client.closeGracefully();
        }
    }
}
