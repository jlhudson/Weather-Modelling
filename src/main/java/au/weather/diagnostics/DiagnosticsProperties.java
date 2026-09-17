package au.weather.diagnostics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The bounds on what the diagnostics layer keeps (D-234), defaulted here rather than in the yml (D-147).
 *
 * @param keepErrors    how long an error signature is kept after it was last seen
 * @param keepWarnings  how long a warning signature is kept after it was last seen — shorter, because
 *                      a warning that has stopped is not worth a morning's attention
 * @param queueCapacity how many captured lines may wait for the drain; past it lines are dropped and
 *                      counted, never blocked on, because the logger must never wait for the database
 * @param drainEvery    how often the queue is written to {@code log_event}
 * @param sweepEvery    how often the retention windows are applied
 * @param window        the default look-back of every diagnostics read
 */
@ConfigurationProperties(prefix = "weather.diagnostics")
public record DiagnosticsProperties(
        @DefaultValue("7d") Duration keepErrors,
        @DefaultValue("2d") Duration keepWarnings,
        @DefaultValue("2000") int queueCapacity,
        @DefaultValue("5s") Duration drainEvery,
        @DefaultValue("1h") Duration sweepEvery,
        @DefaultValue("24h") Duration window
) {
}
