package au.gully.upstreams;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-minute limit, held to: a sliding window of the last minute's calls per
 * upstream, and a short wait when it is full rather than a refused round trip. Nothing here is a
 * budget; the budget is the {@link Ledger}. This only stops a burst of cache misses arriving at the
 * upstream as a burst.
 * <p>
 * The window is weighed the way the upstream weighs it: a fetch counts what it costs, so a year of
 * the archive takes twenty-six slots and a burst of spin-ups meets the limit here, in a wait, rather
 * than at the upstream in a 429 that pauses everything for the minute.
 */
@Component
public class Pacer {

    /**
     * How long a caller will wait for a slot before giving up. A fetch is on a request's critical
     * path; a longer wait would be a slower answer, not a better one.
     */
    static final Duration WAIT_CEILING = Duration.ofSeconds(5);

    private final Map<String, Deque<Call>> windows = new ConcurrentHashMap<>();

    /**
     * Takes the slots for one call weighing {@code units}, waiting briefly for them if the minute is
     * full. A call heavier than the whole limit is let through alone rather than never.
     *
     * @return whether the slots were taken; false means the caller should treat the upstream as busy
     */
    public boolean acquire(String upstream, int perMinute, double units) {
        Deque<Call> window = windows.computeIfAbsent(upstream, k -> new ArrayDeque<>());
        double weight = Math.max(1.0, units);
        Instant deadline = Instant.now().plus(WAIT_CEILING);
        while (true) {
            synchronized (window) {
                Instant now = Instant.now();
                Instant cutoff = now.minus(Duration.ofMinutes(1));
                while (!window.isEmpty() && window.peekFirst().at().isBefore(cutoff)) {
                    window.pollFirst();
                }
                double taken = window.stream().mapToDouble(Call::units).sum();
                if (taken == 0 || taken + weight <= Math.max(1, perMinute)) {
                    window.addLast(new Call(now, weight));
                    return true;
                }
                if (now.isAfter(deadline)) {
                    return false;
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * The calls made in the last minute, counted as calls: what the console shows beside the limit.
     */
    public int inLastMinute(String upstream) {
        Deque<Call> window = windows.get(upstream);
        if (window == null) {
            return 0;
        }
        synchronized (window) {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
            return (int) window.stream().filter(c -> !c.at().isBefore(cutoff)).count();
        }
    }

    private record Call(Instant at, double units) {
    }
}
