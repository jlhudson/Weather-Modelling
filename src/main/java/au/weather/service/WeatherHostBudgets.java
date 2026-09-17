package au.weather.service;

import au.weather.http.HostBudgets;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * The weather hosts, which are called on demand rather than polled and so have no {@code @Source} to
 * carry their budget.
 *
 * <p>None of these are the real limiter. The anchor cache is: a fire ground with twenty appliances
 * costs one upstream call, and the free allowance each provider declares in its own {@code Spec} is what
 * stops the month running out. These numbers only stop a burst of cache misses arriving as a burst of
 * requests.
 */
@Component
public class WeatherHostBudgets implements HostBudgets {

    /**
     * A second between calls to any one of them, which is the same answer five times over because the
     * reasoning is the same five times over: none of these hosts publishes a per-second limit, MET
     * Norway asks only for under 20 a second and an identifying User-Agent, and Google is metered in
     * money rather than in requests. A second is politeness, not compliance. Five named constants all
     * holding one second said less than this sentence does.
     */
    private static final Duration POLITE = Duration.ofSeconds(1);

    @Override
    public Map<String, Duration> hostBudgets() {
        return Map.of(
                "api.open-meteo.com", POLITE,
                "archive-api.open-meteo.com", POLITE,
                "flood-api.open-meteo.com", POLITE,
                "weather.googleapis.com", POLITE);
    }
}
