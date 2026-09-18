package au.gully.upstreams;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A circuit breaker per upstream (docs/06 item 6): after {@link #TRIPS_AFTER} consecutive failures
 * the upstream is left alone for the pause it asked for — a refusal that names the window that ran out
 * gets that window; anything else gets the spec's pause — and is tried again once. A single failure
 * on its own does not trip it: one dropped connection is a fact about the minute, not about the day.
 * <p>
 * State is in memory only. A restart is a fresh chance for every upstream, and the console keeps the
 * last few openings so the day's story can be read back.
 */
@Slf4j
@Component
public class Breaker {

    static final int TRIPS_AFTER = 3;
    private static final int HISTORY = 50;

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Deque<Event> history = new ArrayDeque<>();

    /**
     * Whether calls may be made right now, and if not, until when.
     */
    public Decision check(String upstream) {
        State s = states.get(upstream);
        Instant now = Instant.now();
        if (s == null || s.openUntil == null || !s.openUntil.isAfter(now)) {
            return new Decision(true, null, null);
        }
        return new Decision(false, s.openUntil, s.reason);
    }

    public void succeeded(String upstream) {
        State s = states.computeIfAbsent(upstream, k -> new State());
        if (s.openUntil != null) {
            note(new Event(Instant.now(), upstream, "closed", "answered again"));
        }
        s.failures = 0;
        s.openUntil = null;
        s.reason = null;
    }

    /**
     * A failure, with the pause the upstream asked for. The breaker opens on the third in a row, and
     * opens at once on a refusal that names its window — there is nothing to learn from retrying a
     * day's limit every five minutes.
     */
    public void failed(String upstream, String reason, Duration pause, boolean namedWindow) {
        State s = states.computeIfAbsent(upstream, k -> new State());
        s.failures++;
        s.lastFailure = reason;
        s.lastFailureAt = Instant.now();
        if (s.failures >= TRIPS_AFTER || namedWindow) {
            s.openUntil = Instant.now().plus(pause);
            s.reason = reason;
            s.openings++;
            note(new Event(Instant.now(), upstream, "opened for " + pause, reason));
            log.info("breaker: {} left alone for {} after {}", upstream, pause, reason);
        }
    }

    public Status status(String upstream) {
        State s = states.get(upstream);
        if (s == null) {
            return new Status(0, null, null, null, 0);
        }
        return new Status(s.failures, s.lastFailure, s.lastFailureAt,
                s.openUntil != null && s.openUntil.isAfter(Instant.now()) ? s.openUntil : null, s.openings);
    }

    private synchronized void note(Event e) {
        history.addFirst(e);
        while (history.size() > HISTORY) {
            history.removeLast();
        }
    }

    public synchronized List<Event> history() {
        return List.copyOf(history);
    }

    private static final class State {
        int failures;
        int openings;
        String lastFailure;
        Instant lastFailureAt;
        Instant openUntil;
        String reason;
    }

    public record Decision(boolean allowed, Instant until, String reason) {
    }

    public record Status(int consecutiveFailures, String lastFailure, Instant lastFailureAt, Instant openUntil, int openings) {
    }

    public record Event(Instant at, String upstream, String what, String reason) {
    }
}
