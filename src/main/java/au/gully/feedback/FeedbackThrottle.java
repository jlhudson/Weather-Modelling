package au.gully.feedback;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * At most {@link #SENDS} sends in {@link #WINDOW} from one address, in memory: the times of each address's recent
 * sends, a sliding window. An address quiet for the window is forgotten, and the map is bounded, so a flood of
 * addresses evicts the oldest rather than growing without end. A restart forgets them all, which is fine.
 */
@Component
public class FeedbackThrottle {

    public static final int SENDS = 5;
    public static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final Cache<String, Deque<Instant>> recent = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(WINDOW)
            .build();

    public FeedbackThrottle() {
        this(Clock.systemUTC());
    }

    FeedbackThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * One send counted against the address, or none when it has had its {@link #SENDS} in the window.
     *
     * @return whether this send may go
     */
    public boolean take(String address) {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        Deque<Instant> times = recent.get(address == null ? "" : address, a -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
                times.pollFirst();
            }
            if (times.size() >= SENDS) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }
}
