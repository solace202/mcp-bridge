package dev.mcpbridge;

import dev.mcpbridge.config.BridgeProperties;
import dev.mcpbridge.security.ApiKeyRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyRegistryTest {

    private static BridgeProperties props(BridgeProperties.ApiKey... keys) {
        return new BridgeProperties(new BridgeProperties.Backend(BridgeProperties.BackendType.LOCAL, null, null, Duration.ofSeconds(5)),
                new BridgeProperties.Guardrails(new BigDecimal("500"), 100, Duration.ofMinutes(5)),
                new BridgeProperties.Security(List.of(keys), List.of()), new BridgeProperties.Stdio(Set.of("read")));
    }

    @Test
    void authenticatesKnownKeysOnly() {
        var registry = new ApiKeyRegistry(props(
                new BridgeProperties.ApiKey("support", "support-key-0123456789", Set.of("read", "write")),
                new BridgeProperties.ApiKey("analyst", "analyst-key-0123456789", Set.of("READ"))));
        assertThat(registry.authenticate("support-key-0123456789")).get().satisfies(p -> {
            assertThat(p.name()).isEqualTo("support");
            assertThat(p.canWrite()).isTrue();
        });
        assertThat(registry.authenticate(" analyst-key-0123456789 ")).get().satisfies(p -> assertThat(p.canWrite()).isFalse());
        assertThat(registry.authenticate("analyst-key-012345678")).isEmpty();
        assertThat(registry.authenticate(null)).isEmpty();
    }

    @Test
    void rejectsWeakKeysAndUnknownScopesAtStartup() {
        assertThatThrownBy(() -> new ApiKeyRegistry(props(new BridgeProperties.ApiKey("x", "short", Set.of("read")))))
                .hasMessageContaining("shorter than 16");
        assertThatThrownBy(() -> new ApiKeyRegistry(props(new BridgeProperties.ApiKey("x", "long-enough-key-123", Set.of("admin")))))
                .hasMessageContaining("unknown scopes");
    }

    @Test
    void blankKeysAreIgnored() {
        var registry = new ApiKeyRegistry(props(new BridgeProperties.ApiKey("unset", "", Set.of("read"))));
        assertThat(registry.authenticate("")).isEmpty();
    }
}
