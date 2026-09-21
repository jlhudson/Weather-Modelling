package au.gully.upstreams;

import au.gully.platform.GullyProperties;
import au.gully.platform.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The upstreams in the configured order, each behind its budget, its breaker and its pacer: the
 * first that is configured, inside its allowance and not paused answers; when the primary's
 * allowance for the day is used up or it is not answering, the fetch goes to the overflow at the
 * same point with the same shape of answer.
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
     * One forecast for a point, from the first upstream that can answer.
     *
     * @throws NoUpstream when none could, naming why each was skipped
     */
    public Forecast fetch(double lat, double lon) throws NoUpstream {
        if (!properties.enabled()) {
            throw new NoUpstream("gully.enabled is false");
        }
        String where = OpenMeteo.fixed(lat) + "," + OpenMeteo.fixed(lon);
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
                Forecast f = u.fetch(lat, lon);
                ledger.record(id, u.spec().unitsPerFetch(), true, Duration.ofNanos(System.nanoTime() - started), "forecast " + where);
                breaker.succeeded(id);
                return f;
            } catch (UpstreamException | RuntimeException e) {
                failed(u, e, Duration.ofNanos(System.nanoTime() - started), "forecast " + where, u.spec().unitsPerFetch());
                skipped.add(id + ": " + e.getMessage());
            }
        }
        throw new NoUpstream(String.join("; ", skipped));
    }

    /**
     * A range of the daily record from Open-Meteo's reanalysis archive, at the archive's cost; empty
     * when the call could not be made or failed (the ledger and the breaker know why).
     */
    public Optional<List<OpenMeteo.DailyRow>> archive(double lat, double lon, LocalDate from, LocalDate to, String what) {
        return spend(OpenMeteo.archiveUnits(from, to), "archive " + from + " to " + to + " " + what, () -> openMeteo.archive(lat, lon, from, to));
    }

    /**
     * The last few complete days from the forecast endpoint, at one unit.
     */
    public Optional<List<OpenMeteo.DailyRow>> recentDays(double lat, double lon, int pastDays, String what) {
        return spend(OpenMeteo.RECENT_UNITS, "recent " + pastDays + " days " + what, () -> openMeteo.recent(lat, lon, pastDays));
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
            failed(openMeteo, e, Duration.ofNanos(System.nanoTime() - started), what, units);
            return Optional.empty();
        }
    }

    @FunctionalInterface
    private interface Call<T> {
        T run() throws UpstreamException;
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
        if (!pacer.acquire(u.id(), u.spec().perMinute(), units)) {
            return "at the per-minute limit";
        }
        return null;
    }

    /**
     * A call that failed is still a call made: charged at what it would have cost, counted by the
     * breaker, and the one warning about it.
     */
    private void failed(Upstream u, Exception e, Duration latency, String what, double units) {
        int status = e instanceof UpstreamException ue ? ue.status() : 0;
        String detail = e.getMessage() == null ? e.toString() : e.getMessage();
        ledger.record(u.id(), units, false, latency, what + ": " + detail);
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

    public Ledger ledger() {
        return ledger;
    }

    public Breaker breaker() {
        return breaker;
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

        /**
         * The allowance for a window - {@code minute · hour · day · month} - or null where there is none.
         */
        public Integer limit(String window) {
            if (limits == null) {
                return null;
            }
            return switch (window) {
                case "minute" -> limits.perMinute();
                case "hour" -> limits.perHour();
                case "day" -> limits.perDay();
                case "month" -> limits.perMonth();
                default -> null;
            };
        }

        /**
         * The window's spend over its allowance, capped at one; zero where there is no allowance.
         */
        public double fraction(String window) {
            Integer limit = limit(window);
            return limit == null || limit <= 0 ? 0 : Math.min(1.0, spent.getOrDefault(window, 0.0) / limit);
        }

        public double dayFraction() {
            return fraction("day");
        }

        public double monthFraction() {
            return fraction("month");
        }
    }
}
