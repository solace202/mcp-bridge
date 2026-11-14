package dev.mcpbridge.security;

import dev.mcpbridge.config.BridgeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Guards the MCP endpoints (Streamable HTTP {@code /mcp}, legacy SSE {@code /sse} + {@code /mcp/message}) and the
 * bridge admin API. Accepts {@code Authorization: Bearer <key>} or {@code X-API-Key: <key>}. Browser requests from
 * origins that are not allowed are refused, which blocks DNS-rebinding attacks against a locally running server.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final ApiKeyRegistry keys;
    private final List<String> allowedOrigins;

    public ApiKeyFilter(ApiKeyRegistry keys, BridgeProperties props) {
        this.keys = keys;
        this.allowedOrigins = props.security().allowedOrigins();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !(path.equals("/mcp") || path.startsWith("/mcp/") || path.equals("/sse") || path.startsWith("/api/bridge/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && !originAllowed(origin)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "forbidden_origin", "Origin " + origin + " is not allowed");
            return;
        }
        Optional<BridgePrincipal> principal = keys.authenticate(presentedKey(request));
        if (principal.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"mcp-bridge\"");
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                    "Missing or invalid API key. Send 'Authorization: Bearer <key>' or 'X-API-Key: <key>'.");
            return;
        }
        request.setAttribute(BridgePrincipal.REQUEST_ATTRIBUTE, principal.get());
        chain.doFilter(request, response);
    }

    static String presentedKey(HttpServletRequest request) {
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7);
        }
        return request.getHeader("X-API-Key");
    }

    private boolean originAllowed(String origin) {
        try {
            String host = URI.create(origin).getHost();
            if ("localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host)) {
                return true;
            }
        } catch (IllegalArgumentException e) {
            return false;
        }
        return allowedOrigins.stream().anyMatch(o -> StringUtils.hasText(o) && o.equalsIgnoreCase(origin));
    }

    private static void reject(HttpServletResponse response, int status, String error, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
