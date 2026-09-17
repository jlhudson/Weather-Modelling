package au.weather.startup;

import au.weather.diagnostics.StartupHistory;
import au.weather.service.WeatherProperties;
import au.weather.service.WeatherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * The fetching half of the Hub's {@code WeatherManager}: rehydrate at boot, then govern and sweep on a
 * timer.
 *
 * <p>The other half stayed behind. {@code WeatherManager} decided <em>when an incident</em> was worth
 * asking the weather about — the stagger across open incidents, the re-ask on an upgrade or a move
 * beyond its own positional uncertainty, the per-tick ceiling — and D-249 put that on the Hub's side of
 * the line: this service holds the cache, the Hub holds the question. So there is no incident walk here,
 * no event bus and no attachment; the sweep governs and sweeps and nothing else.
 *
 * <p>Three things happen at boot, in the order the Hub's startup phases put them, each timed into
 * {@link StartupHistory} so {@code /api/diagnostics} can say what the start did:
 * <ol>
 *   <li><strong>Rehydrate.</strong> The anchors, the call ledger, the drought cells and the river cells
 *       all survive a restart, and none of it costs a call.</li>
 *   <li><strong>Derive.</strong> The governor's first tuning, logged. The ledger rehydrated a moment
 *       ago, so this is available immediately rather than an hour into the run on whatever the
 *       configured nominals were.</li>
 *   <li><strong>Schedule.</strong> The tick, if weather is enabled at all.</li>
 * </ol>
 *
 * <p>Each tick governs before it sweeps, not after: the tick's own work should use the numbers it just
 * decided on. The governor no-ops unless its own interval has elapsed, so this costs a comparison
 * eleven times an hour and saves a second timer. {@code WeatherCache.sweep} runs the terrain backfill on
 * its way through — bounded per pass, and this is the phase where a lookup that calls out is allowed,
 * unlike rehydrate.
 */
@Slf4j
@Component
@Order(WeatherSweeper.ORDER)
@RequiredArgsConstructor
public class WeatherSweeper implements ApplicationRunner {

    /**
     * After {@link ConsoleUserBootstrap}, which runs at the highest precedence.
     */
    static final int ORDER = 0;

    private final WeatherService weather;
    private final WeatherProperties properties;
    private final StartupHistory history;
    private final TaskScheduler weatherTaskScheduler;

    @Override
    public void run(ApplicationArguments args) {
        step(2, "weather anchors and the upstream call ledger", weather::rehydrate);
        step(3, "weather reach and reuse windows",
                () -> log.info("weather governor: {}", weather.governor().tuning().reason()));
        step(4, "weather sweep", () -> {
            if (!properties.enabled()) {
                log.info("weather is disabled; no sweep scheduled");
                return;
            }
            weatherTaskScheduler.scheduleWithFixedDelay(this::tick, properties.refresh().tickInterval());
            log.info("weather sweep every {}", properties.refresh().tickInterval());
        });
        history.ready();
    }

    /**
     * One startup step, timed and recorded. A failure is recorded and swallowed: this service can serve
     * {@code /api/weather} and its own diagnostics with a cold cache, and a startup that refuses to
     * finish is how a failure nobody can read about becomes an outage.
     */
    private void step(int phase, String name, Runnable work) {
        Instant began = Instant.now();
        String outcome = "OK";
        try {
            work.run();
        } catch (RuntimeException e) {
            outcome = "FAILED: " + e;
            log.warn("startup step '{}' failed: {}", name, e.toString());
        }
        history.record(phase, name, Duration.between(began, Instant.now()), outcome);
    }

    /**
     * One tick. Wrapped because an exception out of a {@code scheduleWithFixedDelay} task cancels the
     * schedule silently, and a service that quietly stops sweeping looks exactly like one that is idle.
     */
    void tick() {
        try {
            weather.govern(Instant.now());
            int swept = weather.sweep();
            if (swept > 0) {
                log.debug("weather cache swept: {} anchors expired", swept);
            }
        } catch (RuntimeException e) {
            log.warn("weather sweep failed: {}", e.toString());
        }
    }
}
