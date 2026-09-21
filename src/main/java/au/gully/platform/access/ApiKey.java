package au.gully.platform.access;

import java.time.Instant;

/**
 * A key per consumer, rotatable and revocable individually, with a scope: what it
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
        /** The readings under {@code /api/v1}: the stations and what they say. */
        READINGS,
        /** The diagnostics reads and clears only: the morning agent. */
        DIAGNOSTICS;

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
                case READINGS -> path.startsWith("/api/v1/");
                case DIAGNOSTICS -> path.startsWith("/api/diagnostics");
            };
        }
    }
}
