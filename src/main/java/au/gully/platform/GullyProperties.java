package au.gully.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * The settings — every one of them. One group per thing a deployment genuinely varies; everything
 * else is a constant beside the code that knows why. Each value carries its default here, so the
 * shipped {@code application.yml} is the handful that differ between one machine and the next, and
 * {@link Settings} prints every effective value once at startup.
 *
 * @param enabled off, nothing is fetched and nothing is polled; the console shows what is held
 * @param contact who is running this deployment, sent to every upstream in the User-Agent. The
 *                Bureau asks for it, and it is polite everywhere else
 * @param zone    the zone a day is cut on
 */
@ConfigurationProperties(prefix = "gully")
public record GullyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://github.com/jlhudson/Weather-Modelling") String contact,
        @DefaultValue("Australia/Adelaide") String zone,
        @DefaultValue Upstreams upstreams,
        @DefaultValue Console console,
        @DefaultValue Api api
) {

    public ZoneId zoneId() {
        return ZoneId.of(zone);
    }

    /**
     * Which upstreams are on, in the order they are tried. The first is the primary; each after it is
     * the overflow for the one before, taken when that one is out of allowance or not answering.
     */
    public record Upstreams(@DefaultValue({"open-meteo", "google"}) List<String> order) {
    }

    /**
     * One user, an 8-digit code, lockout on consecutive failures.
     */
    public record Console(@DefaultValue("12345678") String code,
                          @DefaultValue("5") int lockoutAfter,
                          @DefaultValue("15m") Duration lockoutFor) {
    }

    /**
     * @param corsOrigins             empty by default: same-origin only until a consumer is actually named
     * @param requestsPerMinutePerKey the per-minute ceiling on one key, answered with the RateLimit headers
     * @param requestsPerDayPerKey    the daily cap on one key, so a runaway consumer stops at a number
     *                                rather than at the month's allowance
     */
    public record Api(@DefaultValue List<String> corsOrigins,
                      @DefaultValue("600") int requestsPerMinutePerKey,
                      @DefaultValue("100000") int requestsPerDayPerKey) {
    }
}
