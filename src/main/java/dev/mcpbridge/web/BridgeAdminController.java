package dev.mcpbridge.web;

import dev.mcpbridge.security.BridgePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Read-only view of what the MCP clients did. Same API keys as /mcp. */
@RestController
@RequestMapping("/api/bridge")
@Tag(name = "Bridge admin", description = "Audit log and pending actions (requires an MCP API key)")
public class BridgeAdminController {

    private final NamedParameterJdbcTemplate jdbc;

    public BridgeAdminController(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/whoami")
    @Operation(summary = "The API key name and scopes the request was authenticated with")
    public BridgePrincipal whoami(HttpServletRequest request) {
        return (BridgePrincipal) request.getAttribute(BridgePrincipal.REQUEST_ATTRIBUTE);
    }

    @GetMapping("/audit")
    @Operation(summary = "Latest MCP tool calls")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT id, api_key, tool, arguments::text AS arguments, outcome, detail, duration_ms, created_at
                FROM tool_audit ORDER BY id DESC LIMIT :limit
                """, new MapSqlParameterSource("limit", Math.min(Math.max(limit, 1), 500)));
    }

    @GetMapping("/actions")
    @Operation(summary = "Prepared write actions and what happened to them")
    public List<Map<String, Object>> actions(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.queryForList("""
                SELECT token, kind, summary, requested_by, status, result, created_at, expires_at, decided_at
                FROM pending_actions ORDER BY created_at DESC LIMIT :limit
                """, new MapSqlParameterSource("limit", Math.min(Math.max(limit, 1), 500)));
    }
}
