package dev.mcpbridge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Map;

/**
 * Replaces Spring AI's default MCP transports with the same ones plus a context extractor, so each tool call can see
 * which API key the {@link ApiKeyFilter} authenticated. That is what makes per-key scopes and audit possible.
 */
@Configuration
@EnableConfigurationProperties({McpServerSseProperties.class, McpServerStreamableHttpProperties.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio", havingValue = "false", matchIfMissing = true)
public class McpTransportConfig {

    static final McpTransportContextExtractor<ServerRequest> PRINCIPAL_EXTRACTOR = request -> {
        Object principal = request.servletRequest().getAttribute(BridgePrincipal.REQUEST_ATTRIBUTE);
        return principal == null ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(BridgePrincipal.CONTEXT_KEY, principal));
    };

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "STREAMABLE")
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(ObjectMapper mapper,
                                                                                    McpServerStreamableHttpProperties props) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .mcpEndpoint(props.getMcpEndpoint())
                .keepAliveInterval(props.getKeepAliveInterval())
                .disallowDelete(props.isDisallowDelete())
                .contextExtractor(PRINCIPAL_EXTRACTOR)
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "SSE")
    WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(ObjectMapper mapper, McpServerSseProperties props) {
        var b = WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(mapper))
                .baseUrl(props.getBaseUrl())
                .sseEndpoint(props.getSseEndpoint())
                .messageEndpoint(props.getSseMessageEndpoint())
                .contextExtractor(PRINCIPAL_EXTRACTOR);
        if (props.getKeepAliveInterval() != null) {
            b.keepAliveInterval(props.getKeepAliveInterval());
        }
        return b.build();
    }

    /** Spring AI skips its whole SSE auto-configuration (router included) once a provider bean exists, so map it here. */
    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "SSE")
    RouterFunction<ServerResponse> bridgeSseRouterFunction(WebMvcSseServerTransportProvider provider) {
        return provider.getRouterFunction();
    }
}
