package au.weather.diagnostics;

import au.weather.api.WeatherApiController;
import au.weather.service.WeatherProperties;
import au.weather.service.WeatherService;
import au.weather.service.WeatherStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The system's own state as one consumer reads it: an agent looking for what is wrong (D-234). Every
 * read is over a short window, answered from what the console already computes — the startup record
 * and the weather service's own counters — plus the warnings and errors {@link LogEventStore} keeps.
 * Nothing here is computed for the agent that the operator could not already see; it is served in one
 * shape, with the derivations an agent would otherwise repeat.
 *
 * <p>Two reads and a clear. The summary says where to look; the log reads hold the evidence; and the
 * clear is how a morning ends, so the next one finds only what has happened since.
 *
 * <p><strong>The same shape as the Hub's, minus what does not exist here.</strong> The Hub's summary
 * also carried {@code sources}, {@code managers}, {@code notifications}, {@code jobs} and
 * {@code load}, and it had a source list and a per-source page. This service has no sources, no event
 * bus, no notifier and no nightly job, so those blocks and those two reads are gone and a
 * {@code weather} block stands in their place. {@code app}, {@code startup} and {@code logs} are
 * identical, which is the point: one agent reads all three applications (docs/27 §27.11.6).
 */
@Component
@RequiredArgsConstructor
public class DiagnosticsLayer {

    static final int TOP_SIGNATURES = 10;

    private final LogEventStore logs;
    private final StartupHistory startup;
    private final WeatherService weather;
    private final WeatherProperties properties;

    // ---------------------------------------------------------------- the summary

    public Map<String, Object> summary(Duration window) {
        Instant now = Instant.now();
        Instant since = now.minus(window);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", now);
        out.put("window", window.toString());
        out.put("readMe", "Start with logs.top and weather.providers; the evidence is /api/diagnostics/logs; "
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
        log.put("top", logs.recent(null, null, since, 500).stream()
                .sorted(Comparator.comparingLong(LogEventEntity::getCount).reversed().thenComparing(LogEventEntity::getLastSeenAt, Comparator.reverseOrder()))
                .limit(TOP_SIGNATURES).map(this::brief).toList());
        out.put("logs", log);

        out.put("weather", weather());
        return out;
    }

    /**
     * The service block, per the split contract. The provider rows are the short form — whether it may
     * be called, why not, and what it has spent — because the long form is a read of its own at
     * {@code /api/weather/status} and an agent looking for what is wrong needs the verdict, not the
     * licence terms.
     */
    Map<String, Object> weather() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", properties.enabled());
        List<Map<String, Object>> providers = new ArrayList<>();
        for (WeatherStatus s : weather.status()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", s.id());
            p.put("usable", s.usable());
            p.put("reason", s.configured() ? s.budgetReason() : s.unavailableReason());
            p.put("spent", s.spent());
            providers.add(p);
        }
        m.put("providers", providers);
        m.put("cache", WeatherApiController.cacheBlock(weather.cache(), properties, weather.governor().tuning()));
        m.put("tuning", WeatherApiController.tuningBlock(weather.governor().tuning()));
        m.put("droughtCells", weather.drought().cells().size());
        m.put("riverCells", weather.flood().cells().size());
        return m;
    }

    // ---------------------------------------------------------------- logs

    public Map<String, Object> logs(String level, String sourceId, Duration window, int limit) {
        Instant since = Instant.now().minus(window);
        List<LogEventEntity> rows = logs.recent(level, sourceId, since, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("since", since);
        out.put("count", rows.size());
        out.put("events", rows.stream().map(this::brief).toList());
        return out;
    }

    public Optional<Map<String, Object>> logEvent(long id) {
        return logs.byId(id).map(e -> {
            Map<String, Object> m = brief(e);
            m.put("trace", e.getTrace());
            return m;
        });
    }

    public int clear(String level, String sourceId, Instant before, String by) {
        return logs.clear(level, sourceId, before, by);
    }

    public boolean clear(long id, String by) {
        return logs.clear(id, by);
    }

    /**
     * One log row, in the shape the split contract names. {@code sourceId} is always null here and is
     * carried anyway: the Hub fills it, the shape is shared, and a key that is present and null says
     * "no source" where an absent key would say nothing at all.
     */
    private Map<String, Object> brief(LogEventEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("level", e.getLevel());
        m.put("count", e.getCount());
        m.put("firstSeenAt", e.getFirstSeenAt());
        m.put("lastSeenAt", e.getLastSeenAt());
        m.put("logger", e.getLogger());
        m.put("thread", e.getThread());
        m.put("sourceId", e.getSourceId());
        m.put("pattern", e.getPattern());
        m.put("message", e.getMessage());
        m.put("exception", e.getException());
        m.put("hasTrace", e.getTrace() != null);
        return m;
    }
}
