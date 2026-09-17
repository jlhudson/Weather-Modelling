package au.weather.startup;

import au.weather.access.ConsoleAuthenticationProvider;
import au.weather.diagnostics.StartupHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase 1: the single console user exists before anything can be served.
 *
 * <p>An {@code ApplicationRunner} rather than the Hub's phased-startup step, because the phase
 * machinery ({@code StartupParticipant}, {@code StartupStep}, {@code PhasedStartup}) is the Hub's
 * ordering of forty sources and a dozen managers and there are two ordered things here. {@link Order}
 * puts this first, ahead of {@link WeatherSweeper}: a login that arrives in the second the sweeper
 * spends rehydrating should still find a user.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ConsoleUserBootstrap implements ApplicationRunner {

    /**
     * The phase number this step reports as, so the diagnostics startup block reads in order.
     */
    public static final int PHASE = 1;

    private final ConsoleAuthenticationProvider provider;
    private final StartupHistory history;

    @Override
    public void run(ApplicationArguments args) {
        Instant began = Instant.now();
        String outcome = "OK";
        try {
            provider.ensureUser();
        } catch (RuntimeException e) {
            // Recorded and rethrown: a console nobody can log into is not something to start quietly past.
            outcome = "FAILED: " + e;
            throw e;
        } finally {
            history.record(PHASE, "console user", Duration.between(began, Instant.now()), outcome);
        }
    }
}
