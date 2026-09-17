package au.weather.http;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared per-host budget (docs/03-sources.md 3.1). A caller decides when it wants to run; this
 * decides when it may. Every on-demand client acquires from the same bucket per host, which is what
 * keeps the bursty on-demand path throttled.
 * <p>
 * Resilience4j {@code RateLimiter}, one instance per host; a host nobody declared gets a conservative
 * default rather than none.
 * <p>
 * The budgets used to be a list in {@code application.yml}, which put the number a long way from the
 * feed it was learned from and made the reason a {@code note:} nobody reading the source would see.
 * They are declared in code now: a client implements {@link HostBudgets} and states its spacing beside
 * the code that knows why. Where two declarations name the same host the
 * longest interval wins — a budget is a promise not to exceed a rate, and only the most cautious
 * promise keeps all of them.
 * <p>
 * The Hub built this table from two places: the scheduled sources' {@code @Source} annotations and the
 * {@link HostBudgets} declarations. There is no source registry in this service, so the declarations
 * are the whole of it.
 */
@Slf4j
@Component
public class HostLimiter {

    /**
     * A host nobody declared. Ten seconds is slow enough to be rude to nobody.
     */
    private static final Duration DEFAULT_MIN_INTERVAL = Duration.ofSeconds(10);

    /**
     * How long a caller will queue for a host before it gives up rather than pile up behind it.
     */
    private static final Duration WAIT_CEILING = Duration.ofMinutes(5);

    private final RateLimiterRegistry registry = RateLimiterRegistry.ofDefaults();
    private final List<HostBudgets> declarations;
    private volatile Map<String, Duration> configured;

    /**
     * Lazy on the declarations. A client that declares a budget may also want the limiter it is
     * declaring to — wiring the list eagerly would make that a cycle for no gain, since nothing
     * acquires a permit during construction.
     */
    public HostLimiter(@Lazy List<HostBudgets> declarations) {
        this.declarations = declarations;
    }

    private static void claim(Map<String, Duration> into, String host, Duration interval) {
        if (host == null || host.isBlank() || interval == null) {
            return;
        }
        into.merge(host.toLowerCase(), interval, (a, b) -> a.compareTo(b) >= 0 ? a : b);
    }

    /**
     * Blocks until the host may be hit again. Returns how long the caller waited.
     */
    public Duration acquire(String host) {
        RateLimiter limiter = limiterFor(host);
        long started = System.nanoTime();
        if (!limiter.acquirePermission()) {
            throw new IllegalStateException("host budget for " + host + " unavailable after " + WAIT_CEILING);
        }
        return Duration.ofNanos(System.nanoTime() - started);
    }

    public Duration minInterval(String host) {
        return budgets().getOrDefault(host.toLowerCase(), DEFAULT_MIN_INTERVAL);
    }

    /**
     * What each host's spacing is and who asked for it, for the console and the logs.
     */
    public Map<String, Duration> all() {
        return budgets();
    }

    private Map<String, Duration> budgets() {
        Map<String, Duration> known = configured;
        if (known != null) {
            return known;
        }
        synchronized (this) { // built once: four pollers racing here at boot used to log the same table four times
            if (configured != null) {
                return configured;
            }
            configured = build();
            log.info("host budgets: {}", configured);
            return configured;
        }
    }

    private Map<String, Duration> build() {
        Map<String, Duration> built = new HashMap<>();
        for (HostBudgets declaration : declarations) {
            declaration.hostBudgets().forEach((host, interval) -> claim(built, host, interval));
        }
        return Map.copyOf(built);
    }

    private RateLimiter limiterFor(String host) {
        String key = host.toLowerCase();
        return registry.rateLimiter(key, () -> RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(minInterval(key))
                .timeoutDuration(WAIT_CEILING)
                .build());
    }
}
