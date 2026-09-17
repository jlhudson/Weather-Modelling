package au.weather.diagnostics;

import jakarta.persistence.*;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One distinct warning or error this system has logged, with how often and when (D-234). Owned by
 * {@link LogEventStore}, its only writer.
 *
 * <p>A row is a <em>signature</em>, not a line: the level, the logger and the message with its
 * numbers, ids and timestamps masked. Four hundred identical lines are one row with a count of four
 * hundred, and the latest line verbatim, which is what an agent reading this needs — the problem,
 * how often, and one example — rather than the log itself. Cleared rows are deleted; a signature that
 * recurs after a clear is a new row, which is what "new since the last clear" means.
 */
@Entity
@Table(name = "log_event", indexes = {
        @Index(name = "ix_log_event_signature", columnList = "signature", unique = true),
        @Index(name = "ix_log_event_level_seen", columnList = "level, last_seen_at"),
        @Index(name = "ix_log_event_source", columnList = "source_id")
})
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Getter
@Setter
@NoArgsConstructor
public class LogEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * {@code sha256(level|logger|pattern)}, forty hex characters: the identity of the problem.
     */
    @EqualsAndHashCode.Include
    @Column(nullable = false, length = 40)
    private String signature;

    /**
     * {@code WARN} or {@code ERROR}.
     */
    @Column(nullable = false, length = 8)
    private String level;

    @Column(nullable = false, length = 192)
    private String logger;

    /**
     * The thread of the latest occurrence. The inboxes are named for their managers and the grabber's
     * threads for their sources, so this is often the whole diagnosis.
     */
    @Column(length = 64)
    private String thread;

    /**
     * The register id the message names, when it names one, so a source's own page can list its lines.
     */
    @Column(name = "source_id", length = 64)
    private String sourceId;

    /**
     * The message with its variable parts masked to {@code #}: what the signature was taken from.
     */
    @Column(nullable = false, length = 512)
    private String pattern;

    /**
     * The latest occurrence, verbatim, secrets redacted.
     */
    @Column(nullable = false, length = 2048)
    private String message;

    /**
     * {@code Class: message} of the throwable on the latest occurrence, when there was one.
     */
    @Column(length = 256)
    private String exception;

    /**
     * The trace's own frames and every cause, trimmed to what places the failure in this code.
     */
    @Column(columnDefinition = "text")
    private String trace;

    @Column(nullable = false)
    private long count;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /**
     * The class name alone, which is enough to find it.
     */
    public String shortLogger() {
        return logger == null ? "" : logger.substring(logger.lastIndexOf('.') + 1);
    }
}
