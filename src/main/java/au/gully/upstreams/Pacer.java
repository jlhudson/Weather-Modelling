package au.gully.upstreams;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-minute limit, held to (docs/06 item 6): a sliding window of the last minute's calls per
 * upstream, and a short wait when it is full rather than a refused round trip. Nothing here is a
 * budget; the budget is the {@link Ledger}. This only stops a burst of cache misses arriving at the
 * upstream as a burst.
 */
@Component
public class Pacer {

    /**
     * How long a caller will wait for a slot before giving up. A fetch is on a request's critical
     * path; a longer wait would be a slower answer, not a better one.
     */
    static final Duration WAIT_CEILING = Duration.ofSeconds(5);

    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();

    /**
     * Takes a slot for one call, waiting briefly for one if the minute is full.
     *
     * @return whether a slot was taken; false means the caller should treat the upstream as busy
     */
    public boolean acquire(String upstream, int perMinute) {
        Deque<Instant> window = windows.computeIfAbsent(upstream, k -> new ArrayDeque<>());
        Instant deadline = Instant.now().plus(WAIT_CEILING);
        while (true) {
            synchronized (window) {
                Instant now = Instant.now();
                Instant cutoff = now.minus(Duration.ofMinutes(1));
                while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
                    window.pollFirst();
                }
                if (window.size() < Math.max(1, perMinute)) {
                    window.addLast(now);
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

    public int inLastMinute(String upstream) {
        Deque<Instant> window = windows.get(upstream);
        if (window == null) {
            return 0;
        }
        synchronized (window) {
            Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
            return (int) window.stream().filter(t -> !t.isBefore(cutoff)).count();
        }
    }
}
