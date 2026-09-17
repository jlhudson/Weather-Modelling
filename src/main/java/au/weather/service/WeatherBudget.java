package au.weather.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static au.weather.core.Numbers.round1;

/**
 * What each provider has spent, against what it is allowed to spend.
 * <p>
 * This is the piece that makes "use the free one until it runs out, then fall back to the paid one" a
 * fact rather than a hope. Every upstream call is written to {@code weather_call} before it is counted,
 * so the month's total survives a restart; the in-memory ledger is rebuilt from that table at startup
 * and is only an index over it.
 * <p>
 * <strong>Guarded, not exhausted.</strong> A provider is retired at {@code guardFraction} of its limit
 * rather than at the limit, because the first call to be refused upstream is the one somebody is
 * waiting on, and because a 429 costs the same round trip as a success.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherBudget {

    /**
     * Above this many remembered calls per provider, the in-memory ledger stops growing and defers to the table.
     */
    private static final int LEDGER_CAP = 50_000;

    private final WeatherRepositories.WeatherCallRepository calls;
    private final List<WeatherProvider> providers;
    private final Map<String, WeatherProvider.Spec> specs = new ConcurrentHashMap<>();
    private final Map<String, List<Spend>> ledger = new ConcurrentHashMap<>();
    private final Map<String, Failure> failures = new ConcurrentHashMap<>();

    /**
     * Phase 2: rebuild the month's ledger from the table so a restart does not hand back a spent allowance.
     */
    public void rehydrate() {
        Instant since = Instant.now().minus(Duration.ofDays(31));
        ledger.clear();
        specs.clear();
        providers.forEach(p -> specs.put(p.id(), p.spec()));
        for (String id : specs.keySet()) {
            List<Spend> spends = new ArrayList<>();
            for (WeatherCallEntity row : calls.findByProviderAndAtGreaterThanEqual(id, since)) {
                spends.add(new Spend(row.getAt(), row.getWeight()));
            }
            ledger.put(id, spends);
            if (!spends.isEmpty()) {
                log.info("weather budget: {} has spent {} allowance units across {} calls in the last 31 days",
                        id, round1(spent(id, Duration.ofDays(30))), spends.size());
            }
        }
        // Failure state is deliberately not persisted: a restart is a fresh chance for every provider.
        failures.clear();
    }

    /**
     * Whether this provider may be called right now: inside every published window, and not inside a
     * cooldown from its last failure.
     */
    public Decision check(String providerId) {
        return check(providerId, spec(providerId).callWeight());
    }

    /**
     * As above, for a call whose cost is not the provider's usual one. A year of daily history is
     * several allowance units against the same free tier as a single current-conditions lookup, and
     * charging it as one would let the expensive path quietly overrun the cheap path's budget.
     */
    public Decision check(String providerId, double weight) {
        WeatherProvider.Spec cfg = spec(providerId);
        Failure failure = failures.get(providerId);
        Instant now = Instant.now();
        if (failure != null && failure.until().isAfter(now)) {
            return new Decision(false, "cooling down after " + failure.reason());
        }
        WeatherProvider.Limits limits = cfg.limits();
        Decision minute = window(providerId, cfg, weight, limits.perMinute(), Duration.ofMinutes(1), "minute");
        if (!minute.allowed()) return minute;
        Decision hour = window(providerId, cfg, weight, limits.perHour(), Duration.ofHours(1), "hour");
        if (!hour.allowed()) return hour;
        Decision day = window(providerId, cfg, weight, limits.perDay(), Duration.ofDays(1), "day");
        if (!day.allowed()) return day;
        Decision month = window(providerId, cfg, weight, limits.perMonth(), Duration.ofDays(30), "month");
        if (!month.allowed()) return month;
        return new Decision(true, "inside allowance");
    }

    private Decision window(String providerId, WeatherProvider.Spec cfg, double weight, Integer limit,
                            Duration window, String name) {
        if (limit == null || limit <= 0) {
            return new Decision(true, "no published " + name + " limit");
        }
        double spent = spent(providerId, window);
        double ceiling = limit * cfg.guardFraction();
        if (spent + weight > ceiling) {
            return new Decision(false, "at " + round1(spent) + " of " + limit + " per " + name
                    + " (guard " + Math.round(cfg.guardFraction() * 100) + "%)");
        }
        return new Decision(true, "inside allowance");
    }

    /**
     * Allowance spent since an instant, rather than over a trailing window.
     * <p>
     * The governor needs this and {@link #spent(String, Duration)} cannot give it. A rolling twenty-four
     * hours and a calendar day are different questions, and dividing one by the elapsed fraction of the
     * other is incoherent: at five past midnight the trailing window still holds all of yesterday
     * afternoon while the fraction of today elapsed is three thousandths, which makes a provider that has
     * spent nothing today look like one burning its allowance three hundred times too fast.
     */
    public double spentSince(String providerId, Instant since) {
        return spentFrom(providerId, since);
    }

    /**
     * Allowance spent inside a window, from the in-memory ledger where it is complete and the table otherwise.
     */
    public double spent(String providerId, Duration window) {
        return spentFrom(providerId, Instant.now().minus(window));
    }

    /**
     * The provider's declared facts, for a caller deriving a daily allowance from a monthly one.
     */
    public WeatherProvider.Spec specOf(String providerId) {
        return spec(providerId);
    }

    private double spentFrom(String providerId, Instant since) {
        List<Spend> spends = ledger.get(providerId);
        if (spends == null) {
            return calls.weightSince(providerId, since);
        }
        synchronized (spends) {
            if (spends.size() >= LEDGER_CAP) {
                return calls.weightSince(providerId, since);
            }
            double total = 0;
            for (Spend s : spends) {
                if (!s.at().isBefore(since)) {
                    total += s.weight();
                }
            }
            return total;
        }
    }

    /**
     * Records a call, successful or not. Both count: a failed request was still a request.
     */
    @Transactional
    public void record(String providerId, boolean ok, Duration latency, String detail) {
        record(providerId, spec(providerId).callWeight(), ok, latency, detail);
    }

    /**
     * As above, charging a stated weight rather than the provider's usual one.
     */
    @Transactional
    public void record(String providerId, double weight, boolean ok, Duration latency, String detail) {
        Instant now = Instant.now();
        WeatherCallEntity row = new WeatherCallEntity();
        row.setProvider(providerId);
        row.setAt(now);
        row.setWeight(weight);
        row.setOk(ok);
        row.setLatencyMs(latency == null ? null : latency.toMillis());
        row.setDetail(detail == null ? null : detail.substring(0, Math.min(512, detail.length())));
        calls.save(row);

        List<Spend> spends = ledger.computeIfAbsent(providerId, k -> new ArrayList<>());
        synchronized (spends) {
            if (spends.size() < LEDGER_CAP) {
                spends.add(new Spend(now, weight));
            }
            spends.removeIf(s -> s.at().isBefore(now.minus(Duration.ofDays(31))));
        }
        if (ok) {
            failures.remove(providerId);
        } else {
            Duration cooldown = cooldownFor(providerId, detail);
            failures.put(providerId, new Failure(now.plus(cooldown), detail == null ? "a failure" : detail, now));
            log.info("weather budget: {} left alone for {} after {}", providerId, cooldown, detail == null ? "a failure" : detail);
        }
    }

    /**
     * The provider reads its own refusal where it can; a budget key with no provider behind it takes the spec's number.
     */
    private Duration cooldownFor(String providerId, String detail) {
        return providers.stream().filter(p -> p.id().equals(providerId)).findFirst()
                .map(p -> p.cooldownAfter(detail))
                .orElseGet(() -> spec(providerId).cooldownAfterFailure());
    }

    /**
     * Removes ledger rows older than any window cares about; called from the anchor sweep.
     */
    @Transactional
    public void prune() {
        calls.deleteByAtBefore(Instant.now().minus(Duration.ofDays(90)));
    }

    /**
     * Everything the console shows about one provider's spending.
     */
    public Map<String, Double> spending(String providerId) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("minute", round1(spent(providerId, Duration.ofMinutes(1))));
        out.put("hour", round1(spent(providerId, Duration.ofHours(1))));
        out.put("day", round1(spent(providerId, Duration.ofDays(1))));
        out.put("month", round1(spent(providerId, Duration.ofDays(30))));
        return out;
    }

    public Failure lastFailure(String providerId) {
        return failures.get(providerId);
    }

    /**
     * A provider this budget has never been told about spends against no published limit at all.
     */
    private WeatherProvider.Spec spec(String providerId) {
        WeatherProvider.Spec spec = specs.get(providerId);
        if (spec == null) {
            spec = providers.stream().map(WeatherProvider::spec).filter(s -> s.id().equals(providerId))
                    .findFirst().orElseGet(() -> WeatherProvider.Spec.of(providerId, "https://localhost", null, null,
                            false, 1.0, WeatherProvider.Limits.NONE));
            specs.put(providerId, spec);
        }
        return spec;
    }

    /**
     * @param reason plain English, shown in the console beside the provider
     */
    public record Decision(boolean allowed, String reason) {
    }

    public record Failure(Instant until, String reason, Instant at) {
    }

    private record Spend(Instant at, double weight) {
    }
}
