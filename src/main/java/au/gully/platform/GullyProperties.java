package au.gully.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * The settings — every one of them (docs/06 item 10). One group per thing a deployment genuinely
 * varies; everything else is a constant beside the code that knows why. Each value carries its
 * default here, so the shipped {@code application.yml} is the handful that differ between one
 * machine and the next, and {@link Settings} prints every effective value once at startup.
 *
 * @param enabled      off, nothing is fetched and nothing is polled; the API answers from what it holds
 * @param contact      who is running this deployment, sent to every upstream in the User-Agent. The
 *                     Bureau asks for it, and it is polite everywhere else
 * @param zone         the zone a daily aggregate is cut on where a hexagon has no zone of its own yet
 * @param refreshAhead how early before a reading's expiry a served hexagon is refreshed in the
 *                     background, so the next ask is already fresh
 * @param coldAfter    how long a hexagon nobody asks about keeps its forecast in memory. The hexagon
 *                     itself — its elevation, land use, district — stays
 */
@ConfigurationProperties(prefix = "gully")
public record GullyProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("https://github.com/jlhudson/Weather-Modelling") String contact,
        @DefaultValue("Australia/Adelaide") String zone,
        @DefaultValue("3m") Duration refreshAhead,
        @DefaultValue("24h") Duration coldAfter,
        @DefaultValue Upstreams upstreams,
        @DefaultValue Sources sources,
        @DefaultValue History history,
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
     * Which of the free public sources are read.
     *
     * @param bureau the Bureau's station files (every ten minutes) and warnings (every five)
     * @param cfs    the CFS district ratings (hourly) and fire ban district shapes (daily)
     * @param rivers GloFAS river discharge, once a day per river cell
     */
    public record Sources(@DefaultValue("true") boolean bureau,
                          @DefaultValue("true") boolean cfs,
                          @DefaultValue("true") boolean rivers) {
    }

    /**
     * The incident history.
     *
     * @param keep    how long a reading snapshot is kept. Nothing is ever deleted from the table by the
     *                service (docs/06 item 2); this bounds what is rebuilt into memory at start
     * @param backups the directory the nightly export of the history is written to, or empty for none
     */
    public record History(@DefaultValue("365d") Duration keep,
                          @DefaultValue("") String backups) {
    }

    /**
     * One user, an 8-digit code, lockout on consecutive failures.
     */
    public record Console(@DefaultValue("12345678") String code,
                          @DefaultValue("5") int lockoutAfter,
                          @DefaultValue("15m") Duration lockoutFor) {
    }

    /**
     * @param corsOrigins          empty by default: same-origin only until a consumer is actually named
     * @param requestsPerMinutePerKey the per-minute ceiling on one key, answered with the RateLimit headers
     * @param requestsPerDayPerKey    the daily cap on one key, so a runaway consumer stops at a number
     *                                rather than at the month's allowance
     */
    public record Api(@DefaultValue List<String> corsOrigins,
                      @DefaultValue("600") int requestsPerMinutePerKey,
                      @DefaultValue("100000") int requestsPerDayPerKey) {
    }
}
