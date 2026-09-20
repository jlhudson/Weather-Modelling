package au.gully.platform;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.WarningsReader;
import au.gully.cfs.Curing;
import au.gully.cfs.Districts;
import au.gully.cfs.Ratings;
import au.gully.drought.Rivers;
import au.gully.hexagons.HexagonStore;
import au.gully.hexagons.Reach;
import au.gully.platform.access.ConsoleUsers;
import au.gully.platform.diagnostics.StartupHistory;
import au.gully.upstreams.Ledger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * What the start does, in order, each step timed into {@link StartupHistory} so the diagnostics can
 * say what happened (docs/06 item 11 asks for about two seconds; the database is one round trip
 * per table and nothing here touches the network before the service is ready):
 * <ol>
 *   <li>the console user;</li>
 *   <li>the registers back into memory — stations, curing, hexagons, river cells — from the database;</li>
 *   <li>the housekeeping timers: the hourly sweep and the nightly backup.</li>
 * </ol>
 * Nothing is polled (W-14): the Bureau's files, the warnings and the CFS feeds are read on request,
 * when an ask finds them older than their cadence, and a hexagon's picture is drawn on the ask.
 * The service starts knowing what it knew, and learns what it is asked about.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Startup implements ApplicationRunner {

    private final ConsoleUsers consoleUsers;
    private final StationRegistry stations;
    private final Reach reach;
    private final Curing curing;
    private final HexagonStore store;
    private final Rivers rivers;
    private final au.gully.hexagons.Drifts drifts;
    private final StationReader stationReader;
    private final WarningsReader warnings;
    private final Ratings ratings;
    private final Districts districts;
    private final Ledger ledger;
    private final GullyProperties properties;
    private final StartupHistory history;
    private final TaskScheduler scheduler;
    private final Settings settings;

    @Override
    public void run(ApplicationArguments args) {
        settings.print();
        step(1, "console user", consoleUsers::ensureUser);
        step(2, "registers from the database", () -> {
            store.ensureGrid();
            reach.rehydrate();
            stations.rehydrate();
            curing.rehydrate();
            rivers.rehydrate();
            drifts.rehydrate();
            store.rehydrate();
        });
        step(3, "timers", this::schedule);
        history.ready();
    }

    private void schedule() {
        Instant soon = Instant.now().plusSeconds(2);
        // The registers feed the hexagons: a station file read re-links every hexagon to its station and gives
        // each station a hexagon; the district shapes join the hexagons to their districts. Nothing recomputes a
        // picture here: pictures are drawn on the ask (W-14).
        stationReader.onUpdate(at -> store.stationsChanged());
        districts.onUpdate(at -> store.districtsChanged());

        // Housekeeping only. Every source is read on request, when an ask finds it older than its cadence.
        scheduler.scheduleWithFixedDelay(guarded("sweep", () -> store.sweep() + ledger.prune()), soon.plusSeconds(60), Duration.ofHours(1));
        log.info("timers: none but the hourly sweep; the Bureau files (every {}), warnings ({}), CFS ratings ({}) and shapes ({}) are read on request",
                StationReader.EVERY, WarningsReader.EVERY, Ratings.EVERY, Districts.EVERY);
    }

    /**
     * An exception out of a scheduled task cancels the schedule silently, and a service that quietly
     * stops polling looks exactly like one that is idle.
     */
    private static Runnable guarded(String name, java.util.function.IntSupplier work) {
        return () -> {
            try {
                int n = work.getAsInt();
                log.debug("{}: {}", name, n);
            } catch (RuntimeException e) {
                log.warn("{} failed: {}", name, e.toString());
            }
        };
    }

    private void step(int phase, String name, Runnable work) {
        Instant began = Instant.now();
        String outcome = "OK";
        try {
            work.run();
        } catch (RuntimeException e) {
            outcome = "FAILED: " + e;
            log.warn("startup step '{}' failed: {}", name, e.toString());
            if (phase == 1) {
                throw e;
            }
        } finally {
            history.record(phase, name, Duration.between(began, Instant.now()), outcome);
        }
    }
}
