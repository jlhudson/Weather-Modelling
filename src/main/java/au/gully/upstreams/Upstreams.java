package au.gully.upstreams;

import au.gully.hexagons.Cell;
import au.gully.platform.GullyProperties;
import au.gully.platform.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The upstreams in the configured order, each behind its budget, its breaker and its pacer (docs/06
 * item 6): the first that is configured, inside its allowance and not paused answers; when the
 * primary's allowance for the day is used up or it is not answering, the fetch goes to the overflow
 * on the same hexagon with the same shape of answer. When none can answer the caller serves what it
 * last held, with its time.
 */
@Slf4j
@Service
public class Upstreams {

    private final List<Upstream> upstreams;
    private final GullyProperties properties;
    private final Ledger ledger;
    private final Budget budget;
    private final Breaker breaker;
    private final Pacer pacer;
    private final OpenMeteo openMeteo;

    public Upstreams(List<Upstream> upstreams, GullyProperties properties, Ledger ledger, Budget budget,
                     Breaker breaker, Pacer pacer, OpenMeteo openMeteo) {
        this.upstreams = upstreams;
        this.properties = properties;
        this.ledger = ledger;
        this.budget = budget;
        this.breaker = breaker;
        this.pacer = pacer;
        this.openMeteo = openMeteo;
    }

    public Optional<Upstream> upstream(String id) {
        return upstreams.stream().filter(u -> u.id().equals(id)).findFirst();
    }

    /**
     * The ids in the order they are tried.
     */
    public List<String> order() {
        return properties.upstreams().order();
    }

    /**
     * One forecast for a cell, from the first upstream that can answer.
     *
     * @throws NoUpstream when none could, naming why each was skipped
     */
    public Forecast fetch(Cell cell) throws NoUpstream {
        if (!properties.enabled()) {
            throw new NoUpstream("gully.enabled is false");
        }
        List<String> skipped = new ArrayList<>();
        for (String id : order()) {
            Upstream u = upstream(id).orElse(null);
            if (u == null) {
                skipped.add(id + ": no such upstream");
                continue;
            }
            String held = gate(u, u.spec().unitsPerFetch());
            if (held != null) {
                skipped.add(id + ": " + held);
                continue;
            }
            long started = System.nanoTime();
            try {
                Forecast f = u.fetch(cell);
                Duration latency = Duration.ofNanos(System.nanoTime() - started);
                ledger.record(id, u.spec().unitsPerFetch(), true, latency, "forecast " + cell.id());
                breaker.succeeded(id);
                if (!skipped.isEmpty()) {
                    log.debug("fetched {} from {} after skipping {}", cell.id(), id, String.join("; ", skipped));
                }
                return f;
            } catch (UpstreamException | RuntimeException e) {
                Duration latency = Duration.ofNanos(System.nanoTime() - started);
                failed(u, e, latency, "forecast " + cell.id());
                skipped.add(id + ": " + e.getMessage());
            }
        }
        throw new NoUpstream(String.join("; ", skipped));
    }

    /**
     * A year (or whatever is asked for) of daily rain and maximum temperature from the reanalysis
     * archive, on Open-Meteo's budget at the archive's cost.
     */
    public Optional<List<OpenMeteo.DailyRow>> archive(double lat, double lon, LocalDate from, LocalDate to) {
        return spend(OpenMeteo.ARCHIVE_UNITS, "archive " + from + " to " + to, () -> openMeteo.archive(lat, lon, from, to));
    }

    public Optional<List<OpenMeteo.DailyRow>> recentDays(double lat, double lon, int pastDays) {
        return spend(OpenMeteo.SMALL_UNITS, "recent " + pastDays + " days", () -> openMeteo.recent(lat, lon, pastDays));
    }

    public Optional<List<OpenMeteo.DischargeRow>> discharge(double lat, double lon, int pastDays, int forecastDays) {
        return spend(OpenMeteo.SMALL_UNITS, "river discharge", () -> openMeteo.discharge(lat, lon, pastDays, forecastDays));
    }

    private <T> Optional<T> spend(double units, String what, Call<T> call) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        String held = gate(openMeteo, units);
        if (held != null) {
            log.debug("{} skipped: {}", what, held);
            return Optional.empty();
        }
        long started = System.nanoTime();
        try {
            T out = call.run();
            ledger.record(openMeteo.id(), units, true, Duration.ofNanos(System.nanoTime() - started), what);
            breaker.succeeded(openMeteo.id());
            return Optional.of(out);
        } catch (UpstreamException | RuntimeException e) {
            failed(openMeteo, e, Duration.ofNanos(System.nanoTime() - started), what);
            log.warn("{} failed: {}", what, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Why an upstream may not be called right now, or null when it may. Checked in the order that is
     * cheapest first: configuration, the breaker, the budget, and last the pacer, which may wait.
     */
    private String gate(Upstream u, double units) {
        if (!u.configured()) {
            return u.unavailableReason();
        }
        Breaker.Decision b = breaker.check(u.id());
        if (!b.allowed()) {
            return "paused until " + b.until() + " after " + b.reason();
        }
        Budget.Decision d = budget.check(u.spec(), units);
        if (!d.allowed()) {
            return d.reason();
        }
        if (!pacer.acquire(u.id(), u.spec().perMinute())) {
            return "at the per-minute limit";
        }
        return null;
    }

    private void failed(Upstream u, Exception e, Duration latency, String what) {
        int status = e instanceof UpstreamException ue ? ue.status() : 0;
        String detail = e.getMessage() == null ? e.toString() : e.getMessage();
        ledger.record(u.id(), u.spec().unitsPerFetch(), false, latency, what + ": " + detail);
        Duration pause = u.pauseAfter(detail, status);
        boolean named = !pause.equals(u.spec().pauseAfterFailure());
        breaker.failed(u.id(), detail, pause, named);
        log.warn("upstream {} failed ({}): {}", u.id(), what, detail);
    }

    /**
     * Everything the console and the status read say about every upstream, in the configured order.
     */
    public List<Status> status() {
        List<Status> out = new ArrayList<>();
        for (String id : order()) {
            Upstream u = upstream(id).orElse(null);
            if (u == null) {
                out.add(new Status(id, null, null, false, "no such upstream", false, "", false, 0, null, Map.of(),
                        new Breaker.Status(0, null, null, null, 0), 0, null));
                continue;
            }
            Upstream.Spec s = u.spec();
            Budget.Decision d = budget.check(s, s.unitsPerFetch());
            Breaker.Status b = breaker.status(id);
            out.add(new Status(id, s.host(), s.model(), u.configured(), u.unavailableReason(),
                    d.allowed(), d.reason(), s.bills(), s.unitsPerFetch(), s.limits(), budget.spending(id),
                    b, pacer.inLastMinute(id), s.attribution()));
        }
        return out;
    }

    /**
     * How much of the day's allowance the upstream a fetch would go to has used, 0 to 1: the first
     * configured upstream in the order whose breaker is closed, else 1 when none is usable (nothing
     * fetched is the tightest budget there is). Cheap: two ledger sums.
     */
    public double dayFraction() {
        for (String id : order()) {
            Upstream u = upstream(id).orElse(null);
            if (u == null || !u.configured() || breaker.status(id).openUntil() != null) {
                continue;
            }
            Upstream.Limits limits = u.spec().limits();
            Integer perDay = limits == null ? null : limits.perDay();
            if (perDay == null || perDay <= 0) {
                return 0;
            }
            return Math.min(1.0, ledger.spent(id, Duration.ofDays(1)) / perDay);
        }
        return 1;
    }

    public Ledger ledger() {
        return ledger;
    }

    public Breaker breaker() {
        return breaker;
    }

    @FunctionalInterface
    private interface Call<T> {
        T run() throws UpstreamException;
    }

    /**
     * Nothing upstream could answer.
     */
    public static class NoUpstream extends Exception {
        public NoUpstream(String message) {
            super(message);
        }
    }

    /**
     * One upstream as the console shows it.
     *
     * @param spent units per window, keyed {@code minute · hour · day · month}
     */
    public record Status(String id, String host, String model, boolean configured, String unavailableReason,
                         boolean withinBudget, String budgetReason, boolean bills, double unitsPerFetch,
                         Upstream.Limits limits, Map<String, Double> spent, Breaker.Status breaker,
                         int callsInLastMinute, String attribution) {

        public boolean usable() {
            return configured && withinBudget && breaker.openUntil() == null;
        }

        public double dayFraction() {
            Integer perDay = limits == null ? null : limits.perDay();
            return perDay == null || perDay <= 0 ? 0 : Math.min(1.0, spent.getOrDefault("day", 0.0) / perDay);
        }

        public double monthFraction() {
            Integer perMonth = limits == null ? null : limits.perMonth();
            return perMonth == null || perMonth <= 0 ? 0 : Math.min(1.0, spent.getOrDefault("month", 0.0) / perMonth);
        }
    }
}
