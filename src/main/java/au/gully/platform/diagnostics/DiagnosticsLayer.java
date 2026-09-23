package au.gully.platform.diagnostics;

import au.gully.platform.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The service's own state as one consumer reads it: an agent looking for what is wrong. The same
 * {@code app}, {@code startup} and {@code logs} blocks as The Hub's, so one agent reads both, and a
 * {@code gully} block in place of the Hub's sources and managers: the upstreams, the Bureau's file, what
 * is held.
 */
@Component
@RequiredArgsConstructor
public class DiagnosticsLayer {

    static final int TOP_SIGNATURES = 10;

    private final LogEventStore logs;
    private final StartupHistory startup;
    private final Status status;

    public Map<String, Object> summary(Duration window) {
        Instant now = Instant.now();
        Instant since = now.minus(window);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", window.toString());
        out.put("readMe", "Start with logs.top, gully.bureau and gully.upstreams; the evidence is /api/diagnostics/logs; "
                + "DELETE /api/diagnostics/logs when the morning is done.");

        Map<String, Object> app = new LinkedHashMap<>();
        app.put("startedAt", startup.startedAt());
        app.put("readyAt", startup.readyAt());
        app.put("uptime", Duration.between(startup.startedAt(), now).toString());
        out.put("app", app);

        List<StartupHistory.PhaseRecord> phases = startup.records();
        Map<String, Object> boot = new LinkedHashMap<>();
        boot.put("phases", phases.stream().map(p -> Map.of("phase", p.phase(), "name", p.name(), "millis", p.took().toMillis(), "outcome", p.outcome())).toList());
        boot.put("failed", phases.stream().filter(StartupHistory.PhaseRecord::failed).map(p -> p.name() + ": " + p.outcome()).toList());
        out.put("startup", boot);

        Map<String, Object> log = new LinkedHashMap<>();
        log.put("errors", logs.countSince("ERROR", since));
        log.put("warnings", logs.countSince("WARN", since));
        log.put("held", logs.held());
        log.put("retention", Map.of("errors", logs.properties().keepErrors().toString(), "warnings", logs.properties().keepWarnings().toString()));
        log.put("capture", Map.of("waiting", logs.capture().waiting(), "captured", logs.capture().captured(), "dropped", logs.capture().dropped()));
        log.put("top", logs.recent(null, since, 500).stream()
                .sorted(Comparator.comparingLong(LogEvent::count).reversed().thenComparing(LogEvent::lastSeenAt, Comparator.reverseOrder()))
                .limit(TOP_SIGNATURES).map(DiagnosticsLayer::brief).toList());
        out.put("logs", log);

        out.put("gully", status.status());
        return out;
    }

    public Map<String, Object> logs(String level, Duration window, int limit) {
        Instant since = Instant.now().minus(window);
        List<LogEvent> rows = logs.recent(level, since, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", since);
        out.put("count", rows.size());
        out.put("events", rows.stream().map(DiagnosticsLayer::brief).toList());
        return out;
    }

    public Optional<Map<String, Object>> logEvent(long id) {
        return logs.byId(id).map(e -> {
            Map<String, Object> m = brief(e);
            m.put("trace", e.trace());
            return m;
        });
    }

    public int clear(String level, Instant before, String by) {
        return logs.clear(level, before, by);
    }

    public int clear(String level, Instant since, Instant before, String by) {
        return logs.clear(level, since, before, by);
    }

    public boolean clear(long id, String by) {
        return logs.clear(id, by);
    }

    /**
     * One log row, in the shape the three applications share. {@code sourceId} is always null here
     * and carried anyway, because the shape is shared.
     */
    static Map<String, Object> brief(LogEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("level", e.level());
        m.put("count", e.count());
        m.put("firstSeenAt", e.firstSeenAt());
        m.put("lastSeenAt", e.lastSeenAt());
        m.put("logger", e.logger());
        m.put("thread", e.thread());
        m.put("sourceId", e.sourceId());
        m.put("pattern", e.pattern());
        m.put("message", e.message());
        m.put("exception", e.exception());
        m.put("hasTrace", e.hasTrace());
        return m;
    }
}
