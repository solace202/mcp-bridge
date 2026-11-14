package dev.mcpbridge;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MCP_PROTOCOL=SSE: the legacy HTTP+SSE transport for older clients, same keys and scopes. */
@TestPropertySource(properties = "spring.ai.mcp.server.protocol=SSE")
class SseTransportIT extends AbstractIT {

    private io.modelcontextprotocol.client.McpSyncClient sse(String key) {
        var b = HttpClientSseClientTransport.builder(baseUrl()).sseEndpoint("/sse");
        if (key != null) {
            b.customizeRequest(r -> r.header("X-API-Key", key));
        }
        return McpClient.sync(b.build()).requestTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void sseClientCanListAndCallTools() {
        var c = sse(READ_KEY);
        try {
            c.initialize();
            assertThat(c.listTools().tools()).hasSize(10);
            assertThat(ok(c.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("get_order", Map.of("orderId", 10001))))
                    .get("id").asLong()).isEqualTo(10001);
            assertThat(error(c.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("prepare_refund",
                    Map.of("orderId", 10001, "amountUsd", 1, "reason", "damaged item"))))).contains("read-only");
        } finally {
            c.closeGracefully();
        }
    }

    @Test
    void sseStreamRequiresAKey() {
        var c = sse(null);
        assertThatThrownBy(c::initialize).isInstanceOf(RuntimeException.class);
        c.closeGracefully();
    }
}
