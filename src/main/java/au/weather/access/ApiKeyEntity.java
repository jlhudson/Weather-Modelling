package au.weather.access;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A key per consumer, rotatable and revocable individually. A key currently grants everything
 * (D-122); the {@code scope} column exists from the first commit so the day a second consumer needs
 * less than everything is a row update, not a migration.
 */
@Entity
@Table(name = "api_key")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyEntity {

    public static final String SCOPE_ALL = "ALL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String consumer;

    /**
     * The first characters of the key, so an operator can tell keys apart without seeing them.
     */
    @Column(nullable = false, length = 16)
    private String keyPrefix;

    @EqualsAndHashCode.Include
    @Column(nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(nullable = false, length = 256)
    private String scope = SCOPE_ALL;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 64)
    private String createdBy;

    private Instant revokedAt;

    private Instant lastUsedAt;

    public boolean active() {
        return revokedAt == null;
    }
}
