package au.gully.platform.diagnostics;

import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Owns {@code log_event}: drains what {@link LogCapture} queued into one row per signature, applies
 * the two retention windows, and answers the diagnostics reads and the clear. Errors are kept seven
 * days after they were last seen and warnings two; a clear deletes, so the next read holds only
 * what has happened since.
 */
@Slf4j
@Component
public class LogEventStore {

    private final JdbcClient db;
    private final LogCapture capture;
    private final DiagnosticsProperties properties;

    public LogEventStore(JdbcClient db, LogCapture capture, DiagnosticsProperties properties, TaskScheduler scheduler) {
        this.db = db;
        this.capture = capture;
        this.properties = properties;
        Instant now = Instant.now();
        scheduler.scheduleWithFixedDelay(this::drain, now.plus(properties.drainEvery()), properties.drainEvery());
        scheduler.scheduleWithFixedDelay(this::sweep, now.plus(Duration.ofMinutes(2)), properties.sweepEvery());
    }

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
        db.sql("""
                insert into log_event (signature, level, logger, thread, source_id, pattern, message, exception, trace, count, first_seen_at, last_seen_at)
                values (:sig, :level, :logger, :thread, null, :pattern, :message, :exception, :trace, :count, :at, :at)
                on conflict (signature) do update set thread = excluded.thread, message = excluded.message,
                  exception = excluded.exception, trace = excluded.trace, count = log_event.count + excluded.count,
                  last_seen_at = excluded.last_seen_at""")
                .param("sig", signature).param("level", line.level()).param("logger", cut(line.logger(), 192))
                .param("thread", cut(line.thread(), 64)).param("pattern", p.pattern()).param("message", line.message())
                .param("exception", line.exception()).param("trace", line.trace()).param("count", (long) p.count())
                .param("at", Db.ts(line.at())).update();
    }

    void sweep() {
        LogCapture.muted(() -> {
            try {
                Instant now = Instant.now();
                int errors = db.sql("delete from log_event where level = 'ERROR' and last_seen_at < :before")
                        .param("before", Db.ts(now.minus(properties.keepErrors()))).update();
                int warnings = db.sql("delete from log_event where level = 'WARN' and last_seen_at < :before")
                        .param("before", Db.ts(now.minus(properties.keepWarnings()))).update();
                if (errors + warnings > 0) {
                    log.info("diagnostics: {} error and {} warning signatures aged out", errors, warnings);
                }
            } catch (RuntimeException e) {
                log.debug("diagnostics sweep failed: {}", e.toString());
            }
        });
    }

    // ---------------------------------------------------------------- reads

    public List<LogEvent> recent(String level, Instant since, int limit) {
        String lv = blank(level);
        return db.sql("select * from log_event where (:level::text is null or level = :level) and last_seen_at >= :since"
                        + " order by last_seen_at desc limit :n")
                .param("level", lv).param("since", Db.ts(since)).param("n", Math.max(1, Math.min(limit, 500)))
                .query().listOfRows().stream().map(LogEventStore::event).toList();
    }

    public Optional<LogEvent> byId(long id) {
        return db.sql("select * from log_event where id = :id").param("id", id).query().listOfRows().stream()
                .findFirst().map(LogEventStore::event);
    }

    public long countSince(String level, Instant since) {
        Long n = db.sql("select count(*) from log_event where level = :level and last_seen_at > :since")
                .param("level", level).param("since", Db.ts(since)).query(Long.class).single();
        return n == null ? 0 : n;
    }

    public long held() {
        Long n = db.sql("select count(*) from log_event").query(Long.class).single();
        return n == null ? 0 : n;
    }

    public LogCapture capture() {
        return capture;
    }

    public DiagnosticsProperties properties() {
        return properties;
    }

    // ---------------------------------------------------------------- clear

    public int clear(String level, Instant before, String by) {
        return clear(level, null, before, by);
    }

    /**
     * The signatures last seen in a span - from {@code since} (or ever) to {@code before} (or now) - gone.
     */
    public int clear(String level, Instant since, Instant before, String by) {
        String lv = blank(level);
        int cleared = db.sql("delete from log_event where (:level::text is null or level = :level) and last_seen_at <= :before and (:since::timestamptz is null or last_seen_at >= :since)")
                .param("level", lv).param("before", Db.ts(before == null ? Instant.now() : before)).param("since", Db.ts(since)).update();
        log.info("diagnostics: {} signatures cleared by {}{}{}", cleared, by,
                lv == null ? "" : " level=" + lv, before == null ? "" : " before=" + before);
        return cleared;
    }

    public boolean clear(long id, String by) {
        int n = db.sql("delete from log_event where id = :id").param("id", id).update();
        if (n > 0) {
            log.info("diagnostics: signature {} cleared by {}", id, by);
        }
        return n > 0;
    }

    private static LogEvent event(Map<String, Object> row) {
        return new LogEvent(((Number) row.get("id")).longValue(), (String) row.get("signature"), (String) row.get("level"),
                (String) row.get("logger"), (String) row.get("thread"), (String) row.get("source_id"), (String) row.get("pattern"),
                (String) row.get("message"), (String) row.get("exception"), (String) row.get("trace"),
                ((Number) row.get("count")).longValue(), Db.instant(row.get("first_seen_at")), Db.instant(row.get("last_seen_at")));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private record Pending(LogCapture.Captured latest, String pattern, int count) {
    }
}
