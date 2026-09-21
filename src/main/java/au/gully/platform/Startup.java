package au.gully.platform;

import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
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
 * say what happened:
 * <ol>
 *   <li>the console user;</li>
 *   <li>the registers back into memory from the database;</li>
 *   <li>the timers: the Bureau's file every ten minutes, and the hourly sweep.</li>
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
    private final Ledger ledger;
    private final GullyProperties properties;
    private final StartupHistory history;
    private final TaskScheduler scheduler;
    private final Settings settings;

    @Override
    public void run(ApplicationArguments args) {
        settings.print();
        step(1, "console user", consoleUsers::ensureUser);
        step(2, "registers from the database", stations::rehydrate);
        step(3, "timers", this::schedule);
        history.ready();
    }

    private void schedule() {
        Instant soon = Instant.now().plusSeconds(2);
        if (properties.enabled()) {
            scheduler.scheduleWithFixedDelay(guarded("bureau", stationReader::read), soon, StationReader.EVERY);
        } else {
            log.info("gully.enabled is false: the Bureau's file is not read");
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
