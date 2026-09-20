package au.gully.platform.access;

import java.time.Instant;
import java.util.Set;

/**
 * A key per consumer, rotatable and revocable individually, with a scope (docs/06 item 11): what it
 * may read. {@code ALL} is what every key held before scopes existed and what the console issues by
 * default; a second consumer that needs less than everything gets less.
 *
 * @param scope one of {@link Scope}, stored as its name
 */
public record ApiKey(long id, String consumer, String keyPrefix, String keyHash, String scope, Instant createdAt,
                     String createdBy, Instant revokedAt, Instant lastUsedAt) {

    public boolean active() {
        return revokedAt == null;
    }

    /**
     * Whether this key may read a path.
     */
    public boolean allows(String path) {
        Scope s = Scope.parse(scope);
        return s == Scope.ALL || s.covers(path);
    }

    /**
     * The scopes, and the paths each covers.
     */
    public enum Scope {
        ALL,
        /** The readings, the fire-indices calculator, the status and the spend reads. */
        READINGS,
        /** The hexagon layer and list only: a map. */
        LAYER,
        /** The diagnostics reads and clears only: the morning agent. */
        DIAGNOSTICS;

        static final Set<String> READING_PREFIXES = Set.of("/api/v1/readings", "/api/v1/now", "/api/v1/forecast", "/api/v1/drought",
                "/api/v1/fire-indices", "/api/v1/status", "/api/v1/drift",
                "/api/v1/upstreams", "/api/weather");

        public static Scope parse(String name) {
            if (name == null) {
                return ALL;
            }
            try {
                return valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return ALL;
            }
        }

        boolean covers(String path) {
            return switch (this) {
                case ALL -> true;
                case READINGS -> READING_PREFIXES.stream().anyMatch(path::startsWith);
                case LAYER -> path.startsWith("/api/v1/hexagons");
                case DIAGNOSTICS -> path.startsWith("/api/diagnostics");
            };
        }
    }
}
