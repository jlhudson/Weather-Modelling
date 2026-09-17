package au.weather.service;

import au.weather.core.Ranges;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

import static au.weather.core.Numbers.round1;

/**
 * How far the cache is allowed to stretch today, decided from how much of the day's allowance is gone.
 *
 * <p>The trade this makes is simple and it only runs one way at a time. Allowance spent slowly means the
 * reuse windows can be tight, which costs upstream calls and buys weather that is genuinely closer to the
 * point somebody asked about. Allowance disappearing fast means the windows widen, which costs accuracy and
 * buys the day: a stale reading from twenty kilometres away is a poor answer and a great deal better than
 * the nothing that arrives once the allowance is gone at four in the afternoon.
 *
 * <p><strong>Read this as insurance, not as a daily tuner.</strong> Open-Meteo publishes ten thousand units
 * a day and a busy incident day spends a few hundred, so pressure sits near zero and everything rests at its
 * floor essentially always. The console says so in as many words rather than implying something is being
 * actively managed. The machinery matters on the day Open-Meteo is down and Google's three hundred guarded
 * calls are carrying the load, which is precisely the day nobody has time to edit a configuration file.
 *
 * <p><strong>The loop is negative feedback with dead time, so it is damped hard.</strong> Tightening the
 * reach raises the miss rate, which raises usage, which raises pressure, which loosens the reach again -
 * stable, but the effect of a step only appears over the following hour. That is a proportional controller
 * with lag, and an ungoverned gain would oscillate. Hence one notch per interval, a dead band, and
 * hysteresis that is deliberately asymmetric: step toward the ceiling on the first interval of pressure,
 * but toward the floor only after {@code headroomHolds} consecutive intervals of quiet. Relief should be
 * immediate; spending more should have to be earned.
 *
 * <p>Everything except the published snapshot is touched only from the manager's writer thread, which is
 * the same argument {@code PrefetchJob} makes for its own unsynchronised state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherGovernor {

    private final WeatherBudget budget;
    private final WeatherProperties properties;

    /**
     * The only field read off the writer thread, swapped whole so a reader never sees a torn set.
     */
    private volatile WeatherTuning tuning;

    private Instant lastComputed;
    private double applied;
    private int consecutiveHeadroom;

    private static double step(WeatherProperties.Governor cfg) {
        return 1.0 / Math.max(1, cfg.steps());
    }

    /**
     * A provider's allowance for one day, derived where it is not published as one.
     *
     * <p>Never from the per-minute figure. That is a burst limit rather than an allowance, and treating it
     * as one would have made a provider publishing only sixty a minute look like it had eighty-six thousand
     * a day - rich enough to drown out every real constraint in the maximum above.
     */
    private static Double dailyAllowance(WeatherProvider.Spec spec) {
        WeatherProvider.Limits limits = spec.limits();
        if (limits.perDay() != null) {
            return (double) limits.perDay();
        }
        if (limits.perHour() != null) {
            return limits.perHour() * 24.0;
        }
        if (limits.perMonth() != null) {
            return limits.perMonth() / 30.0;
        }
        return null;
    }

    /**
     * The values in force right now. Never null: before the first step it is the configured nominals.
     */
    public WeatherTuning tuning() {
        WeatherTuning current = tuning;
        if (current == null) {
            current = WeatherTuning.nominal(properties, properties.governor().enabled()
                    ? "configured defaults; the governor has not run yet"
                    : "the governor is disabled: weather.governor.enabled=false");
            tuning = current;
        }
        return current;
    }

    /**
     * Steps the tuning if the interval has elapsed, and does nothing otherwise.
     *
     * <p>Called from the weather sweep tick, which already runs every five minutes on the writer thread, so
     * the governor needs no timer, no event type of its own and no second exception to the wiring DAG's
     * no-self-edges rule. The cost of being asked twelve times an hour and declining eleven of them is one
     * comparison.
     *
     * @return true when the values actually moved
     */
    public boolean maybeRecompute(Instant at) {
        WeatherProperties.Governor cfg = properties.governor();
        if (!cfg.enabled()) {
            tuning = WeatherTuning.nominal(properties, "the governor is disabled: weather.governor.enabled=false");
            return false;
        }
        if (lastComputed != null && Duration.between(lastComputed, at).compareTo(cfg.interval()) < 0) {
            return false;
        }
        Duration since = lastComputed == null ? null : Duration.between(lastComputed, at);
        lastComputed = at;

        Pressure p = pressure(at);
        double signal = Ranges.clamp(p.value() - 1.0, -1.0, 1.0);
        double delta = signal - applied;
        double before = applied;

        String movement;
        if (Math.abs(delta) < cfg.deadBand()) {
            consecutiveHeadroom = 0;
            movement = String.format(Locale.ROOT, "held: pressure %.2f is inside the %.2f dead band",
                    p.value(), cfg.deadBand());
        } else if (delta < 0) {
            // Toward the floor: tighter windows, more upstream calls. Has to be earned.
            consecutiveHeadroom++;
            if (consecutiveHeadroom < cfg.headroomHolds()) {
                movement = "one interval of headroom is not " + cfg.headroomHolds() + "; the step toward the floor waits";
            } else {
                consecutiveHeadroom = 0;
                applied += Ranges.clamp(delta, -step(cfg), step(cfg));
                movement = "tightening";
            }
        } else {
            consecutiveHeadroom = 0;
            applied += Ranges.clamp(delta, -step(cfg), step(cfg));
            movement = "widening to conserve allowance";
        }
        applied = Ranges.clamp(applied, -1.0, 1.0);

        tuning = build(p, applied, at, reason(p, movement, since, cfg));
        boolean changed = Math.abs(applied - before) > 1e-9;
        if (changed) {
            log.info("weather governor: reach {} km, drought {} km, ttl {}, forecast {} days - {}",
                    round1(tuning.anchorReachKm()), round1(tuning.droughtCellRadiusKm()),
                    tuning.ttl(), tuning.forecastDays(), tuning.reason());
        } else {
            log.debug("weather governor unchanged: {}", tuning.reason());
        }
        return changed;
    }

    private WeatherTuning build(Pressure p, double applied, Instant at, String reason) {
        WeatherProperties.Governor g = properties.governor();
        Duration ttl = Ranges.clamp(
                Ranges.ramp(properties.cache().ttl(), properties.cache().ttl(), g.ttlCeiling(), applied),
                WeatherProperties.Governor.TTL_FLOOR,
                // A ceiling misconfigured below the hard floor must not invert the clamp.
                g.ttlCeiling().compareTo(WeatherProperties.Governor.TTL_FLOOR) < 0
                        ? WeatherProperties.Governor.TTL_FLOOR : g.ttlCeiling());
        // Forecast days run the other way: headroom buys more of them. The ends are swapped here rather
        // than behind a flag, so the direction is readable at the call site.
        //
        // Pressure does not cut them back below the configured figure, and that is deliberate rather than
        // an oversight: `forecastDays` is already documented as short on purpose, so it is the floor. The
        // positive half of this ramp is flat by construction - nominal and ceiling are the same value -
        // and there is no `forecastDaysFloor` to ramp toward. Adding one would mean choosing a number for
        // "how little outlook is still worth fetching", which is a question for whoever needs it.
        int days = (int) Math.round(Ranges.ramp(g.forecastDaysCeiling(), properties.forecastDays(),
                properties.forecastDays(), applied));
        return new WeatherTuning(
                Ranges.ramp(g.reachFloorKm(), properties.cache().reachKm(), g.reachCeilingKm(), applied) * 1000,
                Ranges.ramp(g.droughtFloorKm(), properties.drought().cellRadiusKm(), g.droughtCeilingKm(), applied) * 1000,
                Ranges.ramp(g.riverFloorKm(), properties.flood().cellRadiusKm(), g.riverCeilingKm(), applied) * 1000,
                ttl, days,
                // Kept consistent rather than tuned separately: an hourly series shorter than the daily
                // outlook would roll up fewer days than the forecast claims to cover.
                Math.max(properties.forecastHours(), days * 24),
                properties.cache().verticalWeight(),
                p.value(), applied, at, reason);
    }

    /**
     * How fast today's allowance is going, for whichever provider is nearest its guard.
     *
     * <p>The maximum rather than the mean, because a wide-open Open-Meteo would otherwise mask a Google
     * that is one call from its monthly ceiling, and it is the constrained one that decides what the system
     * can still afford.
     */
    private Pressure pressure(Instant at) {
        ZoneId zone = properties.zoneId();
        // The day of the instant being asked about, not the day it happens to be while asking. Reading
        // the clock here made the answer depend on something the caller did not pass in: handed an `at`
        // on any other date it measured the spend against the wrong midnight, got a negative elapsed
        // fraction, floored it at a twenty-fourth of a day and read a quiet morning as a crisis. In
        // production the two agree except across a midnight boundary; in a test they stop agreeing the
        // moment the date rolls over, which is how this was found.
        Instant midnight = LocalDate.ofInstant(at, zone).atStartOfDay(zone).toInstant();
        double secondsIn = Math.max(1, Duration.between(midnight, at).toSeconds());
        // Floored at a twenty-fourth: in the first hour of the day, judge spend against an hour's worth of
        // allowance rather than against a minute's, or every night just after midnight reads as a crisis.
        double elapsed = Ranges.clamp(secondsIn / 86_400.0, 1.0 / 24, 1.0);

        double worst = 0;
        String who = "nothing configured";
        for (String id : properties.order()) {
            WeatherProvider.Spec spec = budget.specOf(id);
            if (spec == null) {
                continue;
            }
            Double daily = dailyAllowance(spec);
            if (daily == null || daily <= 0) {
                continue;
            }
            double spent = budget.spentSince(id, midnight);
            double value = spent / (daily * spec.guardFraction() * elapsed);
            if (value >= worst) {
                worst = value;
                who = String.format(Locale.ROOT, "%s at %s of %s guarded per day",
                        id, round1(spent), Math.round(daily * spec.guardFraction()));
            }
        }
        return new Pressure(worst, who, elapsed);
    }

    private String reason(Pressure p, String movement, Duration since, WeatherProperties.Governor cfg) {
        String cadence = since == null || since.compareTo(cfg.interval().multipliedBy(2)) < 0
                ? "" : String.format(Locale.ROOT, "; recomputed %d min after the last step", since.toMinutes());
        if (p.value() < 0.25) {
            return String.format(Locale.ROOT,
                    "headroom: %s, %.0f%% of the day gone; every reuse window at its tightest%s",
                    p.who(), p.elapsed() * 100, cadence);
        }
        return String.format(Locale.ROOT, "pressure %.2f (%s, %.0f%% of the day gone): %s%s",
                p.value(), p.who(), p.elapsed() * 100, movement, cadence);
    }

    private record Pressure(double value, String who, double elapsed) {
    }
}
