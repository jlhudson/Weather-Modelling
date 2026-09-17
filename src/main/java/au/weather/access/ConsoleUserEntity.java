package au.weather.access;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One user (D-123). The lockout is the one piece of the model that matters when there is exactly one account to attack.
 */
@Entity
@Table(name = "console_user")
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@Setter
@NoArgsConstructor
public class ConsoleUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Include
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, length = 128)
    private String codeHash;

    @Column(nullable = false)
    private int failedAttempts;

    private Instant lockedUntil;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastLoginAt;
}
