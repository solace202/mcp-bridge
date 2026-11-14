package dev.mcpbridge.security;

import dev.mcpbridge.config.BridgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * API keys from configuration (env vars in practice). Only SHA-256 digests are kept in memory and comparison is
 * constant-time, so a heap dump or a timing probe does not reveal keys.
 */
@Component
public class ApiKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRegistry.class);
    private static final Set<String> KNOWN_SCOPES = Set.of("read", "write");

    private record Entry(byte[] digest, BridgePrincipal principal) {
    }

    private final List<Entry> entries;

    public ApiKeyRegistry(BridgeProperties props) {
        this.entries = props.security().keys().stream()
                .filter(k -> StringUtils.hasText(k.key()))
                .map(k -> {
                    if (k.key().length() < 16) {
                        throw new IllegalStateException("API key '" + k.name() + "' is shorter than 16 characters");
                    }
                    Set<String> scopes = k.scopes().stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
                    if (!KNOWN_SCOPES.containsAll(scopes)) {
                        throw new IllegalStateException("API key '" + k.name() + "' has unknown scopes " + scopes);
                    }
                    return new Entry(sha256(k.key()), new BridgePrincipal(k.name(), Set.copyOf(scopes)));
                })
                .toList();
        if (entries.isEmpty()) {
            log.warn("No MCP API keys configured: every HTTP MCP request will be rejected");
        } else {
            log.info("MCP API keys loaded: {}", entries.stream().map(e -> e.principal().name() + e.principal().scopes()).toList());
        }
    }

    public Optional<BridgePrincipal> authenticate(String presentedKey) {
        if (!StringUtils.hasText(presentedKey)) {
            return Optional.empty();
        }
        byte[] digest = sha256(presentedKey.trim());
        BridgePrincipal match = null;
        for (Entry e : entries) {   // no early exit: same work whichever key matches
            if (MessageDigest.isEqual(e.digest(), digest)) {
                match = e.principal();
            }
        }
        return Optional.ofNullable(match);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
