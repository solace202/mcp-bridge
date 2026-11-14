package dev.mcpbridge.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcpbridge.config.BridgeProperties;
import dev.mcpbridge.security.BridgePrincipal;
import dev.mcpbridge.shop.ShopException;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Every tool call goes through here: resolve the caller, enforce the read/write scope, run, and write one audit row.
 * Unexpected errors are logged with an id and reported to the client without internals (no SQL, no stack traces).
 */
@Component
public class ToolGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolGuard.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final boolean stdio;
    private final BridgePrincipal stdioPrincipal;

    public ToolGuard(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, BridgeProperties props,
                     @Value("${spring.ai.mcp.server.stdio:false}") boolean stdio) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.stdio = stdio;
        this.stdioPrincipal = new BridgePrincipal("stdio-local", props.stdio().scopes());
    }

    public <T> T read(McpSyncServerExchange exchange, String tool, Map<String, Object> args, Supplier<T> body) {
        return run(exchange, tool, args, false, p -> body.get());
    }

    public <T> T write(McpSyncServerExchange exchange, String tool, Map<String, Object> args,
                       java.util.function.Function<BridgePrincipal, T> body) {
        return run(exchange, tool, args, true, body);
    }

    public BridgePrincipal principal(McpSyncServerExchange exchange) {
        Object p = exchange == null ? null : exchange.transportContext().get(BridgePrincipal.CONTEXT_KEY);
        if (p instanceof BridgePrincipal bp) {
            return bp;
        }
        if (stdio) {
            return stdioPrincipal;
        }
        throw new ToolDenied("Not authenticated.");
    }

    private <T> T run(McpSyncServerExchange exchange, String tool, Map<String, Object> args, boolean write,
                      java.util.function.Function<BridgePrincipal, T> body) {
        long start = System.nanoTime();
        BridgePrincipal principal;
        try {
            principal = principal(exchange);
        } catch (ToolDenied e) {
            audit("anonymous", tool, args, "DENIED", e.getMessage(), start);
            throw e;
        }
        if (write ? !principal.canWrite() : !principal.canRead()) {
            String msg = "API key '" + principal.name() + "' is not allowed to use " + tool
                    + (write ? " (it needs the 'write' scope; this key is read-only)." : ".");
            audit(principal.name(), tool, args, "DENIED", msg, start);
            throw new ToolDenied(msg);
        }
        try {
            T result = body.apply(principal);
            audit(principal.name(), tool, args, "OK", null, start);
            return result;
        } catch (ShopException | ToolDenied e) {
            audit(principal.name(), tool, args, e instanceof ToolDenied ? "DENIED" : "REJECTED", e.getMessage(), start);
            throw e;
        } catch (RuntimeException e) {
            String errorId = UUID.randomUUID().toString().substring(0, 8);
            log.error("Tool {} failed (error id {})", tool, errorId, e);
            audit(principal.name(), tool, args, "ERROR", "error id " + errorId, start);
            throw new IllegalStateException("Internal error while running " + tool + " (error id " + errorId + ").");
        }
    }

    private void audit(String key, String tool, Map<String, Object> args, String outcome, String detail, long startNanos) {
        try {
            jdbc.update("""
                    INSERT INTO tool_audit (api_key, tool, arguments, outcome, detail, duration_ms)
                    VALUES (:key, :tool, CAST(:args AS jsonb), :outcome, :detail, :ms)
                    """, new MapSqlParameterSource("key", key).addValue("tool", tool)
                    .addValue("args", mapper.writeValueAsString(args)).addValue("outcome", outcome)
                    .addValue("detail", detail == null ? null : detail.length() > 500 ? detail.substring(0, 500) : detail)
                    .addValue("ms", (int) ((System.nanoTime() - startNanos) / 1_000_000)));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Could not write audit row for {}: {}", tool, e.toString());
        }
    }

    /** Builds an argument map that tolerates nulls (Map.of does not). */
    public static Map<String, Object> args(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                m.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return m;
    }

    public static final class ToolDenied extends RuntimeException {
        public ToolDenied(String message) {
            super(message);
        }
    }
}
