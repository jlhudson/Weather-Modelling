package au.gully.upstreams;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static au.gully.science.Numbers.round1;

/**
 * What each upstream has spent, from the ledger, against what it is allowed to spend (docs/06 item
 * 6). A plain budget: inside every published window, at the guard fraction rather than the limit,
 * because the first call to be refused upstream is the one somebody is waiting on and a 429 costs the
 * same round trip as a success.
 */
@Component
public class Budget {

    private final Ledger ledger;

    public Budget(Ledger ledger) {
        this.ledger = ledger;
    }

    /**
     * Whether a call of this cost fits inside every window right now.
     */
    public Decision check(Upstream.Spec spec, double units) {
        Upstream.Limits l = spec.limits();
        Decision d = window(spec, units, l.perMinute(), Duration.ofMinutes(1), "minute");
        if (!d.allowed()) return d;
        d = window(spec, units, l.perHour(), Duration.ofHours(1), "hour");
        if (!d.allowed()) return d;
        d = window(spec, units, l.perDay(), Duration.ofDays(1), "day");
        if (!d.allowed()) return d;
        d = window(spec, units, l.perMonth(), Duration.ofDays(30), "month");
        if (!d.allowed()) return d;
        return new Decision(true, "inside allowance");
    }

    private Decision window(Upstream.Spec spec, double units, Integer limit, Duration window, String name) {
        if (limit == null || limit <= 0) {
            return new Decision(true, "no published " + name + " limit");
        }
        double spent = ledger.spent(spec.id(), window);
        double ceiling = limit * spec.guard();
        if (spent + units > ceiling) {
            return new Decision(false, "at " + round1(spent) + " of " + limit + " per " + name
                    + " (guard " + Math.round(spec.guard() * 100) + "%)");
        }
        return new Decision(true, "inside allowance");
    }

    /**
     * Spend per window, for the console and the status read: {@code minute · hour · day · month}.
     */
    public Map<String, Double> spending(String upstream) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("minute", round1(ledger.spent(upstream, Duration.ofMinutes(1))));
        out.put("hour", round1(ledger.spent(upstream, Duration.ofHours(1))));
        out.put("day", round1(ledger.spent(upstream, Duration.ofDays(1))));
        out.put("month", round1(ledger.spent(upstream, Duration.ofDays(30))));
        return out;
    }

    public record Decision(boolean allowed, String reason) {
    }
}
