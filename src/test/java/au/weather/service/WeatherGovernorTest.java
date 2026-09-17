package au.weather.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The governor, against a hand-driven ledger.
 * <p>
 * The first test is a regression, and it is first because the defect it pins was in the specified formula
 * rather than in the code: a trailing twenty-four-hour spend divided by the elapsed fraction of a calendar
 * day reads as a crisis every night just after midnight, and slams every reuse window to its ceiling for
 * an hour a day forever.
 */
class WeatherGovernorTest {

    private static final ZoneId ZONE = ZoneId.of("Australia/Adelaide");

    private static WeatherProperties properties(String... order) {
        return new WeatherProperties(true, Duration.ofSeconds(15), "test@example.org", ZONE.getId(), 3, 72,
                List.of(order),
                new WeatherProperties.Cache(Duration.ofMinutes(30), Duration.ofHours(3), 20, 67, 500,
                        Duration.ofMinutes(15), Duration.ofDays(2)),
                new WeatherProperties.Refresh(Duration.ofMinutes(5)),
                new WeatherProperties.Fire(true, 8, "assumed"),
                new WeatherProperties.Drought(true, 365, 50, 5, 6, 550),
                new WeatherProperties.Flood(true, true, 5, 1, 7),
                new WeatherProperties.Governor(true, 15, 50, 25, 100, 5, 15, Duration.ofMinutes(90), 7,
                        0.15, 8, Duration.ofHours(1), 2));
    }

    private static Instant localTime(int hour, int minute) {
        return LocalDate.of(2026, 9, 7).atStartOfDay(ZONE).toInstant()
                .plus(Duration.ofHours(hour)).plus(Duration.ofMinutes(minute));
    }

    private static WeatherGovernor governor(FakeBudget budget, WeatherProperties props) {
        budget.specs.put("open-meteo", OpenMeteoProvider.GLOBAL);
        budget.specs.put("google", GoogleWeatherProvider.SPEC);
        return new WeatherGovernor(budget, props);
    }

    /**
     * The regression. Five past midnight, nothing spent today: the governor must read this as headroom and
     * sit at its floor, not as a three-hundred-fold overspend.
     */
    @Test
    void justAfterMidnightWithNothingSpentTodayIsHeadroomNotACrisis() {
        FakeBudget budget = new FakeBudget();
        WeatherGovernor g = governor(budget, properties("open-meteo"));

        g.maybeRecompute(localTime(0, 5));
        WeatherTuning t = g.tuning();

        assertThat(t.pressure()).isLessThan(1.0);
        assertThat(t.anchorReachKm()).isLessThanOrEqualTo(20.0);
        assertThat(t.reason()).contains("headroom");
    }

    /**
     * The everyday state: an allowance ten thousand wide against a few hundred calls sits at the floor.
     */
    @Test
    void aNormalDaySettlesAtTheFloorAndSaysSo() {
        FakeBudget budget = new FakeBudget();
        budget.sinceMidnight.put("open-meteo", 200.0);
        WeatherGovernor g = governor(budget, properties("open-meteo"));

        // Eight steps of one notch, an hour apart, walks it all the way down.
        for (int hour = 1; hour <= 20; hour++) {
            g.maybeRecompute(localTime(hour, 0));
        }
        WeatherTuning t = g.tuning();
        // Not exactly at the floor, and that is the dead band doing its job rather than a shortfall: once
        // the remaining move is smaller than the band, holding still is the correct answer. The difference
        // between a 15.0 km reach and a 15.6 km one is not worth a step, and a governor that chased the
        // last fraction would be one that never stopped moving.
        assertThat(t.applied()).isLessThan(-0.8);
        assertThat(t.anchorReachKm()).isBetween(15.0, 16.0);
        assertThat(t.droughtCellRadiusKm()).isBetween(25.0, 29.0);
        assertThat(t.riverCellRadiusKm()).isEqualTo(5.0);
        assertThat(t.ttl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(t.forecastDays()).isEqualTo(7);
        assertThat(t.reason()).contains("tightest");
    }

    /**
     * It must never step past its bounds, however long it runs in one direction.
     */
    @Test
    void itNeverOvershootsItsFloorOrItsCeiling() {
        FakeBudget budget = new FakeBudget();
        WeatherGovernor g = governor(budget, properties("open-meteo", "google"));
        for (int hour = 0; hour < 24; hour++) {
            budget.sinceMidnight.put("google", hour % 2 == 0 ? 0.0 : 5000.0);
            g.maybeRecompute(localTime(hour, 0));
            assertThat(g.tuning().applied()).isBetween(-1.0, 1.0);
            assertThat(g.tuning().anchorReachKm()).isBetween(15.0, 50.0);
            assertThat(g.tuning().droughtCellRadiusKm()).isBetween(25.0, 100.0);
            assertThat(g.tuning().riverCellRadiusKm()).isBetween(5.0, 15.0);
            assertThat(g.tuning().ttl()).isBetween(Duration.ofMinutes(30), Duration.ofMinutes(90));
            assertThat(g.tuning().forecastDays()).isBetween(3, 7);
        }
    }

    /**
     * Under sustained pressure everything widens and the forecast is cut back, which is the inverse
     * direction working. Spend is raised in step with the day so the pressure stays high: the ratio is
     * against allowance the day <em>should</em> have spent by now, so a fixed total looks less alarming
     * every hour, which is itself the point of measuring it that way.
     */
    @Test
    void forecastDaysShrinkAndWindowsWidenUnderSustainedPressure() {
        FakeBudget budget = new FakeBudget();
        WeatherGovernor g = governor(budget, properties("google"));
        for (int hour = 1; hour <= 20; hour++) {
            // Three times the pace the day can afford, held all day.
            budget.sinceMidnight.put("google", 300.0 * 3 * (hour / 24.0));
            g.maybeRecompute(localTime(hour, 0));
        }
        WeatherTuning t = g.tuning();
        assertThat(t.pressure()).isGreaterThan(2.0);
        assertThat(t.applied()).isGreaterThan(0.8);
        assertThat(t.forecastDays()).isEqualTo(3);
        assertThat(t.anchorReachKm()).isBetween(46.0, 50.0);
        assertThat(t.ttl()).isBetween(Duration.ofMinutes(80), Duration.ofMinutes(90));
    }

    /**
     * One notch per interval, and nothing at all in between.
     */
    @Test
    void itStepsOncePerIntervalAndIgnoresTicksInBetween() {
        FakeBudget budget = new FakeBudget();
        budget.sinceMidnight.put("google", 400.0);
        WeatherGovernor g = governor(budget, properties("google"));

        g.maybeRecompute(localTime(8, 0));
        double first = g.tuning().applied();
        assertThat(g.maybeRecompute(localTime(8, 5))).isFalse();
        assertThat(g.tuning().applied()).isEqualTo(first);

        g.maybeRecompute(localTime(9, 1));
        assertThat(g.tuning().applied()).isGreaterThan(first);
        // One notch is an eighth of the range, so a single step never crosses more than that.
        assertThat(g.tuning().applied() - first).isLessThanOrEqualTo(0.125 + 1e-9);
    }

    /**
     * Relief is immediate, spending more has to be earned. A single interval of headroom must not move the
     * reach toward its floor; the second one may.
     */
    @Test
    void steppingTowardTheFloorNeedsTwoConsecutiveIntervalsOfHeadroom() {
        FakeBudget budget = new FakeBudget();
        budget.sinceMidnight.put("google", 400.0);
        WeatherGovernor g = governor(budget, properties("google"));
        for (int hour = 1; hour <= 12; hour++) {
            g.maybeRecompute(localTime(hour, 0));
        }
        double widened = g.tuning().applied();
        assertThat(widened).isGreaterThan(0);

        budget.sinceMidnight.put("google", 0.0);
        g.maybeRecompute(localTime(13, 0));
        assertThat(g.tuning().applied()).isEqualTo(widened);
        g.maybeRecompute(localTime(14, 0));
        assertThat(g.tuning().applied()).isLessThan(widened);
    }

    /**
     * The time-to-live floor is a constant, and it holds even when the ceiling is configured below it.
     */
    @Test
    void theTimeToLiveNeverGoesBelowHalfAnHour() {
        WeatherProperties bad = new WeatherProperties(true, Duration.ofSeconds(15), "t@e.org", ZONE.getId(), 3, 72,
                List.of("open-meteo"),
                new WeatherProperties.Cache(Duration.ofMinutes(30), Duration.ofHours(3), 20, 67, 500,
                        Duration.ofMinutes(15), Duration.ofDays(2)),
                new WeatherProperties.Refresh(Duration.ofMinutes(5)),
                new WeatherProperties.Fire(true, 8, "assumed"),
                new WeatherProperties.Drought(true, 365, 50, 5, 6, 550),
                new WeatherProperties.Flood(true, true, 5, 1, 7),
                // A ceiling of five minutes, which is below the hard floor and must not invert anything.
                new WeatherProperties.Governor(true, 15, 50, 25, 100, 5, 15, Duration.ofMinutes(5), 7,
                        0.15, 8, Duration.ofHours(1), 2));
        FakeBudget budget = new FakeBudget();
        WeatherGovernor g = governor(budget, bad);
        for (int hour = 1; hour <= 20; hour++) {
            g.maybeRecompute(localTime(hour, 0));
        }
        assertThat(g.tuning().ttl()).isGreaterThanOrEqualTo(Duration.ofMinutes(30));
    }

    /**
     * A per-minute limit is a burst rate, not an allowance. Counting it as one would make a provider look
     * eighty-six thousand calls a day rich and drown out every real constraint.
     */
    @Test
    void aProviderPublishingOnlyAPerMinuteLimitContributesNoPressure() {
        FakeBudget budget = new FakeBudget();
        budget.specs.put("burst-only", WeatherProvider.Spec.of("burst-only", "https://example.org", "m", "a",
                true, 1.0, WeatherProvider.Limits.perMinute(60)));
        budget.sinceMidnight.put("burst-only", 5000.0);
        WeatherGovernor g = new WeatherGovernor(budget, properties("burst-only"));
        g.maybeRecompute(localTime(12, 0));
        assertThat(g.tuning().pressure()).isZero();
    }

    /**
     * The binding provider decides, not the average one.
     */
    @Test
    void pressureIsTakenFromTheProviderNearestItsGuard() {
        FakeBudget budget = new FakeBudget();
        budget.sinceMidnight.put("open-meteo", 10.0);
        budget.sinceMidnight.put("google", 400.0);
        WeatherGovernor g = governor(budget, properties("open-meteo", "google"));
        g.maybeRecompute(localTime(12, 0));
        assertThat(g.tuning().pressure()).isGreaterThan(1.0);
        assertThat(g.tuning().reason()).contains("google");
    }

    @Test
    void aDisabledGovernorHoldsTheConfiguredNominalsAndSaysWhy() {
        WeatherProperties off = properties("open-meteo");
        WeatherProperties disabled = new WeatherProperties(off.enabled(), off.timeout(), off.contact(), off.zone(),
                off.forecastDays(), off.forecastHours(), off.order(), off.cache(), off.refresh(), off.fire(),
                off.drought(), off.flood(),
                new WeatherProperties.Governor(false, 15, 50, 25, 100, 5, 15, Duration.ofMinutes(90), 7,
                        0.15, 8, Duration.ofHours(1), 2));
        WeatherGovernor g = governor(new FakeBudget(), disabled);
        assertThat(g.maybeRecompute(localTime(12, 0))).isFalse();
        assertThat(g.tuning().anchorReachKm()).isEqualTo(20.0);
        assertThat(g.tuning().reason()).contains("disabled");
    }

    @Test
    void everyPathProducesANonBlankReason() {
        FakeBudget budget = new FakeBudget();
        WeatherGovernor g = governor(budget, properties("open-meteo", "google"));
        assertThat(g.tuning().reason()).isNotBlank();
        for (int hour = 0; hour < 24; hour++) {
            budget.sinceMidnight.put("google", hour * 30.0);
            g.maybeRecompute(localTime(hour, 30));
            assertThat(g.tuning().reason()).isNotBlank();
        }
    }

    /**
     * A budget whose ledger is whatever the test says it is, with no database and no clock of its own.
     */
    private static final class FakeBudget extends WeatherBudget {
        private final Map<String, Double> sinceMidnight = new HashMap<>();
        private final Map<String, WeatherProvider.Spec> specs = new HashMap<>();

        FakeBudget() {
            super(null, List.of());
        }

        @Override
        public double spentSince(String providerId, Instant since) {
            return sinceMidnight.getOrDefault(providerId, 0.0);
        }

        @Override
        public WeatherProvider.Spec specOf(String providerId) {
            return specs.get(providerId);
        }
    }
}
