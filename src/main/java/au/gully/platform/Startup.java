package au.gully.platform;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.WarningsReader;
import au.gully.cfs.Curing;
import au.gully.cfs.Districts;
import au.gully.cfs.Ratings;
import au.gully.drought.DroughtAreas;
import au.gully.drought.Rivers;
import au.gully.hexagons.HexagonStore;
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
 *   <li>the timers: the Bureau's files, the warnings, the CFS feeds, the drought step, the sweeps
 *       and the nightly backup, each first firing a moment after the service is up.</li>
 * </ol>
 * The first poll of each source runs right after start, off the startup thread, and the hexagons'
 * pictures are computed as each answers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Startup implements ApplicationRunner {

    private final ConsoleUsers consoleUsers;
    private final StationRegistry stations;
    private final Curing curing;
    private final HexagonStore store;
    private final Rivers rivers;
    private final DroughtAreas droughtAreas;
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
            stations.rehydrate();
            curing.rehydrate();
            rivers.rehydrate();
            droughtAreas.rehydrate();
            store.rehydrate();
        });
        step(3, "timers", this::schedule);
        history.ready();
    }

    private void schedule() {
        Instant soon = Instant.now().plusSeconds(2);
        // The registries feed the hexagons: each change recomputes the pictures.
        stationReader.onUpdate(at -> store.stationsChanged());
        warnings.onUpdate(at -> store.recomputeAll());
        ratings.onUpdate(at -> store.recomputeAll());
        districts.onUpdate(at -> store.districtsChanged());
        curing.onUpdate(at -> store.recomputeAll());

        // The district shapes first: the station poll creates a hexagon per station, and each wants its district.
        if (properties.enabled() && properties.sources().cfs()) {
            scheduler.scheduleWithFixedDelay(guarded("cfs districts", districts::poll), soon, Districts.EVERY);
            scheduler.scheduleWithFixedDelay(guarded("cfs ratings", ratings::poll), soon.plusSeconds(8), Ratings.EVERY);
        }
        if (properties.enabled() && properties.sources().bureau()) {
            scheduler.scheduleWithFixedDelay(guarded("bureau stations", stationReader::poll), soon.plusSeconds(3), StationReader.EVERY);
            scheduler.scheduleWithFixedDelay(guarded("bureau warnings", warnings::poll), soon.plusSeconds(5), WarningsReader.EVERY);
        }
        // Once the stations are in, the pictures of every hexagon that had none.
        scheduler.schedule(guarded("first pictures", () -> {
            store.stationsChanged();
            return store.size();
        }), soon.plusSeconds(20));
        scheduler.scheduleWithFixedDelay(guarded("drought step", store::stepDrought), soon.plusSeconds(30), Duration.ofMinutes(15));
        scheduler.scheduleWithFixedDelay(guarded("rivers", store::refreshRivers), soon.plusSeconds(40), Duration.ofHours(1));
        scheduler.scheduleWithFixedDelay(guarded("sweep", () -> store.sweep() + ledger.prune()), soon.plusSeconds(60), Duration.ofHours(1));
        log.info("timers: stations every {}, warnings every {}, ratings every {}, drought step every 15m, sweep hourly",
                StationReader.EVERY, WarningsReader.EVERY, Ratings.EVERY);
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
