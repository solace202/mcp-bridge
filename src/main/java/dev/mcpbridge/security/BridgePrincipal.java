package dev.mcpbridge.security;

import java.util.Set;

/** Who is calling: the API key's name (or the local STDIO client) and what it may do. */
public record BridgePrincipal(String name, Set<String> scopes) {

    public static final String REQUEST_ATTRIBUTE = BridgePrincipal.class.getName();
    public static final String CONTEXT_KEY = "bridge.principal";

    public boolean canWrite() {
        return scopes.contains("write");
    }

    public boolean canRead() {
        return scopes.contains("read") || canWrite();
    }
}
