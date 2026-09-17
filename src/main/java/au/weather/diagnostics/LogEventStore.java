package au.weather.diagnostics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns {@code log_event} (D-234): drains what {@link LogCapture} queued into one row per signature,
 * applies the two retention windows, and answers the diagnostics layer's reads and its clear.
 *
 * <p>Errors are kept seven days after they were last seen and warnings two (the defaults of
 * {@link DiagnosticsProperties}); a clear deletes, so the next read after one holds only what has
 * happened since. Both are the operator's decision — James, 15 September 2026: <em>"errors kept for
 * 7 days, warnings kept for 2 days ... we should be able to clear off the logs once done so we are
 * discovering NEW bugs and issues."</em>
 */
@Slf4j
@Component
public class LogEventStore {

    private final LogEventRepository events;
    private final LogCapture capture;
    private final DiagnosticsProperties properties;

    public LogEventStore(LogEventRepository events, LogCapture capture, DiagnosticsProperties properties,
                         @Qualifier("weatherTaskScheduler") TaskScheduler scheduler) {
        this.events = events;
        this.capture = capture;
        this.properties = properties;
        Instant now = Instant.now();
        scheduler.scheduleWithFixedDelay(this::drain, now.plus(properties.drainEvery()), properties.drainEvery());
        scheduler.scheduleWithFixedDelay(this::sweep, now.plus(Duration.ofMinutes(2)), properties.sweepEvery());
    }

    /**
     * The queue into rows: many lines of one signature in a batch are one update with the count added.
     * Any failure here is swallowed at DEBUG, because a warning raised by the write would be the next
     * thing to write.
     */
    void drain() {
        List<LogCapture.Captured> batch = capture.drain(1_000);
        if (batch.isEmpty()) {
            return;
        }
        LogCapture.muted(() -> {
            try {
                Map<String, Pending> pending = new LinkedHashMap<>();
                for (LogCapture.Captured line : batch) {
                    String pattern = LogSignatures.pattern(line.message());
                    String signature = LogSignatures.signature(line.level(), line.logger(), pattern);
                    pending.merge(signature, new Pending(line, pattern, 1), (a, b) -> new Pending(b.latest(), pattern, a.count() + 1));
                }
                for (Map.Entry<String, Pending> e : pending.entrySet()) {
                    store(e.getKey(), e.getValue());
                }
            } catch (RuntimeException e) {
                log.debug("diagnostics drain failed: {}", e.toString());
            }
        });
    }

    private void store(String signature, Pending p) {
        LogCapture.Captured line = p.latest();
        LogEventEntity row = events.findBySignature(signature).orElseGet(() -> {
            LogEventEntity fresh = new LogEventEntity();
            fresh.setSignature(signature);
            fresh.setLevel(line.level());
            fresh.setLogger(cut(line.logger(), 192));
            fresh.setPattern(p.pattern());
            fresh.setFirstSeenAt(line.at());
            fresh.setCount(0);
            return fresh;
        });
        row.setThread(cut(line.thread(), 64));
        // Always null here. The Hub matched a line against its registered source ids so a failing feed's
        // rows could be filtered to it; there are no sources in this service and so nothing to match. The
        // column stays — it is part of the docs/26 row shape the two services share, and one null column
        // is cheaper than a second shape to read.
        row.setSourceId(null);
        row.setMessage(line.message());
        row.setException(line.exception());
        row.setTrace(line.trace());
        row.setCount(row.getCount() + p.count());
        row.setLastSeenAt(line.at());
        events.save(row);
    }

    /**
     * The retention windows, applied from each row's last occurrence: a problem that keeps happening
     * keeps its row; one that stopped ages out.
     */
    void sweep() {
        LogCapture.muted(() -> {
            try {
                Instant now = Instant.now();
                int errors = events.deleteByLevelAndLastSeenAtBefore("ERROR", now.minus(properties.keepErrors()));
                int warnings = events.deleteByLevelAndLastSeenAtBefore("WARN", now.minus(properties.keepWarnings()));
                if (errors + warnings > 0) {
                    log.info("diagnostics: {} error and {} warning signatures aged out", errors, warnings);
                }
            } catch (RuntimeException e) {
                log.debug("diagnostics sweep failed: {}", e.toString());
            }
        });
    }

    // ---------------------------------------------------------------- reads

    /**
     * Signatures last seen since {@code since}, most recent first; {@code level} and {@code sourceId}
     * narrow it when given.
     */
    public List<LogEventEntity> recent(String level, String sourceId, Instant since, int limit) {
        return events.recent(blank(level), blank(sourceId), since, PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }

    public Optional<LogEventEntity> byId(long id) {
        return events.findById(id);
    }

    public long countSince(String level, Instant since) {
        return events.countByLevelAndLastSeenAtAfter(level, since);
    }

    /**
     * How many signatures are held in all, whatever their age.
     */
    public long held() {
        return events.count();
    }

    public LogCapture capture() {
        return capture;
    }

    public DiagnosticsProperties properties() {
        return properties;
    }

    // ---------------------------------------------------------------- clear

    /**
     * Deletes what matches — all of it when nothing narrows — and says how many.
     */
    public int clear(String level, String sourceId, Instant before, String by) {
        // No bound means everything held now; a line logged during the call is next time's.
        int cleared = events.clear(blank(level), blank(sourceId), before == null ? Instant.now() : before);
        log.info("diagnostics: {} signatures cleared by {}{}{}{}", cleared, by,
                level == null ? "" : " level=" + level, sourceId == null ? "" : " source=" + sourceId,
                before == null ? "" : " before=" + before);
        return cleared;
    }

    public boolean clear(long id, String by) {
        if (!events.existsById(id)) {
            return false;
        }
        events.deleteById(id);
        log.info("diagnostics: signature {} cleared by {}", id, by);
        return true;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private record Pending(LogCapture.Captured latest, String pattern, int count) {
    }

    public interface LogEventRepository extends JpaRepository<LogEventEntity, Long> {

        Optional<LogEventEntity> findBySignature(String signature);

        long countByLevelAndLastSeenAtAfter(String level, Instant since);

        @Query("select e from LogEventEntity e where (:level is null or e.level = :level) and (:sourceId is null or e.sourceId = :sourceId)"
                + " and e.lastSeenAt >= :since order by e.lastSeenAt desc")
        List<LogEventEntity> recent(String level, String sourceId, Instant since, PageRequest page);

        @Transactional
        @Modifying
        @Query("delete from LogEventEntity e where e.level = :level and e.lastSeenAt < :before")
        int deleteByLevelAndLastSeenAtBefore(String level, Instant before);

        @Transactional
        @Modifying
        @Query("delete from LogEventEntity e where (:level is null or e.level = :level) and (:sourceId is null or e.sourceId = :sourceId)"
                + " and e.lastSeenAt <= :before")
        int clear(String level, String sourceId, Instant before);
    }
}
