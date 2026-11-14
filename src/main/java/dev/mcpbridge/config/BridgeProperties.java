package dev.mcpbridge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@ConfigurationProperties("bridge")
public record BridgeProperties(@DefaultValue Backend backend, @DefaultValue Guardrails guardrails,
                               @DefaultValue Security security, @DefaultValue Stdio stdio) {

    public enum BackendType { LOCAL, REST }

    /** Where the shop data comes from: its database (local) or its HTTP API (rest). */
    public record Backend(@DefaultValue("local") BackendType type, String baseUrl, String token,
                          @DefaultValue("5s") Duration timeout) {
    }

    /** Limits enforced before an action can even be prepared. */
    public record Guardrails(@DefaultValue("500.00") BigDecimal maxRefundUsd,
                             @DefaultValue("100") int maxStockAdjustment,
                             @DefaultValue("5m") Duration confirmationTtl) {
    }

    public record ApiKey(String name, String key, @DefaultValue("read") Set<String> scopes) {
    }

    public record Security(@DefaultValue List<ApiKey> keys, @DefaultValue List<String> allowedOrigins) {
    }

    /** Scopes granted to the local STDIO client (Claude Desktop / Cursor spawning the jar). */
    public record Stdio(@DefaultValue({"read", "write"}) Set<String> scopes) {
    }
}
