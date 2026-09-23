package au.gully.platform;

import au.gully.bureau.StationRegistry;
import au.gully.reach.TerrainSampler;
import au.gully.reading.Points;
import au.gully.record.Backfill;
import au.gully.record.Record;
import au.gully.upstreams.Ledger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The second of the service's two timers (W-15): once a day, after the Bureau's day has closed at
 * 9 am, everything the record needs doing, in order - the history, the cleaning, and what a station
 * lacks. The first timer reads the Bureau's file every ten minutes; nothing else runs on a clock,
 * and everything else is on demand. It also runs once shortly after the start, so a fresh
 * deployment has its terrain and its year within minutes rather than by tomorrow morning; that run
 * costs nothing when there is nothing to do.
 * <ol>
 *   <li>the fold: each Bureau station's last three days lacking a row of its own, from its stored readings;</li>
 *   <li>the pruning: readings older than three days, windows and days older than 548, the upstream
 *       ledger, the forecasts older than a day (W-20), and the points of ours no ask has used for 548 days;</li>
 *   <li>the terrain of any station lacking it;</li>
 *   <li>the year of any Bureau station missing days, as far as the day's allowance allows.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Housekeeping {

    /**
     * When the daily run happens, local: the 9 am reading that closes the day is in the file by then.
     */
    public static final LocalTime AT = LocalTime.of(9, 30);
    /**
     * How long after the start the first run happens.
     */
    public static final Duration AFTER_START = Duration.ofSeconds(60);

    private final StationRegistry stations;
    private final Record record;
    private final Ledger ledger;
    private final Points points;
    private final TerrainSampler sampler;
    private final Backfill backfill;
    private final au.gully.reading.Forecasts forecasts;

    private volatile Map<String, Object> last;

    /**
     * One run, every step guarded so a failure in one leaves the rest done, and the outcome kept
     * for the diagnostics.
     */
    public synchronized Map<String, Object> run(Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now.toString());
        step(out, "daysFolded", () -> record.fold(now));
        step(out, "readingsPruned", () -> stations.pruneReadings(now));
        step(out, "recordPruned", () -> record.prune(now));
        step(out, "ledgerPruned", ledger::prune);
        step(out, "pointsExpired", () -> points.expire(now));
        step(out, "forecastsPruned", () -> forecasts.prune(now));
        step(out, "terrainSampled", sampler::sampleMissing);
        step(out, "daysFilled", () -> backfill.fillPending(now));
        out.put("took", Duration.between(now, Instant.now()).toString());
        last = out;
        log.info("housekeeping: {}", out);
        return out;
    }

    private static void step(Map<String, Object> out, String name, java.util.function.IntSupplier work) {
        try {
            out.put(name, work.getAsInt());
        } catch (RuntimeException e) {
            out.put(name, "failed: " + e);
            log.warn("housekeeping {} failed: {}", name, e.toString());
        }
    }

    /**
     * What the last run did, or null before the first.
     */
    public Map<String, Object> last() {
        return last;
    }
}
