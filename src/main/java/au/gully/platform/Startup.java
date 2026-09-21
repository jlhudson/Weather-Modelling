package au.gully.platform;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.platform.access.ConsoleUsers;
import au.gully.platform.diagnostics.StartupHistory;
import au.gully.reach.ReachRule;
import au.gully.reach.TerrainSampler;
import au.gully.reach.TerrainStore;
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
 * say what happened:
 * <ol>
 *   <li>the console user;</li>
 *   <li>the registers back into memory from the database;</li>
 *   <li>the timers: the Bureau's file every ten minutes, the terrain sampler until every station has
 *       its terrain, and the hourly sweep.</li>
 * </ol>
 * Nothing touches the network before the service is ready.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Startup implements ApplicationRunner {

    private final ConsoleUsers consoleUsers;
    private final StationRegistry stations;
    private final StationReader stationReader;
    private final TerrainStore terrain;
    private final TerrainSampler sampler;
    private final ReachRule reachRule;
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
            stations.rehydrate();
            terrain.rehydrate();
            reachRule.rehydrate();
        });
        step(3, "timers", this::schedule);
        history.ready();
    }

    private void schedule() {
        Instant soon = Instant.now().plusSeconds(2);
        if (properties.enabled()) {
            scheduler.scheduleWithFixedDelay(guarded("bureau", stationReader::read), soon, StationReader.EVERY);
            // The terrain, one station a tick until every station has it: a few hundred elevation calls, once.
            scheduler.scheduleWithFixedDelay(guarded("terrain", sampler::tick), soon.plusSeconds(10), TerrainSampler.EVERY);
        } else {
            log.info("gully.enabled is false: the Bureau's file is not read and no terrain is sampled");
        }
        scheduler.scheduleWithFixedDelay(guarded("sweep", ledger::prune), soon.plusSeconds(60), Duration.ofHours(1));
    }

    /**
     * An exception out of a scheduled task cancels the schedule silently, and a service that quietly
     * stops polling looks exactly like one that is idle.
     */
    private static Runnable guarded(String name, Runnable work) {
        return () -> {
            try {
                work.run();
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
