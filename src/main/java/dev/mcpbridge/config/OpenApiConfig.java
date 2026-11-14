package dev.mcpbridge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info().title("MCP Bridge").version("0.1.0")
                        .description("HTTP side of the MCP bridge. The MCP endpoint itself is POST/GET /mcp (Streamable HTTP); "
                                + "this page documents the bridge admin API and the simulated existing shop API it wraps.")
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes("mcpApiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-API-Key"))
                        .addSecuritySchemes("shopToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Shop-Token")));
    }
}
