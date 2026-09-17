package au.weather.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static au.weather.core.Numbers.round1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one forecast structure, and the join that builds it.
 * <p>
 * Worth pinning because the failure is silent in exactly the wrong way. The daily weather, the fire
 * outlook and the flood outlook used to be three arrays that every reader joined for itself on a date
 * string; the map popup, the console and the incident page each did it differently, and a join that
 * quietly matches nothing produces an empty table rather than an error. These assert that a day arrives
 * whole, that the join is on the date rather than the position, and that no caller can still reach a
 * per-day figure through the "now" blocks.
 */
class WeatherForecastJsonTest {

    private static final LocalDate MON = LocalDate.of(2026, 9, 7);
    private static final LocalDate TUE = LocalDate.of(2026, 9, 8);
    private static final LocalDate WED = LocalDate.of(2026, 9, 9);
    private static final ZoneId ZONE = ZoneId.of("Australia/Adelaide");

    private static DayOutlook weatherDay(LocalDate date, double max) {
        return new DayOutlook(date, max, 9.0, null, 30, 25.0, 45.0, 180, 1.5, 40, 6.0,
                Instant.parse("2026-09-07T21:00:00Z"), Instant.parse("2026-09-08T08:00:00Z"), "Overcast");
    }

    private static FireWeather fire(List<FireOutlook> outlook) {
        return new FireWeather(12.0, "HIGH", 8.0, 90.0, "moderate", 600.0, null, null, null, null,
                null, null, null, null, outlook, false, "test");
    }

    private static FloodWeather flood(List<FloodOutlook> outlook) {
        return new FloodWeather(1.0, 2.0, 3.0, 7.0, null, null, 4.0, 5.0, 6.0, 60, null, null,
                12.0, 20.0, 0.6, "STEADY", outlook, "test");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> hours(Map<String, Object> forecast) {
        return (List<Map<String, Object>>) forecast.get("hours");
    }

    private static Map<String, Object> hour(Map<String, Object> forecast) {
        return hours(forecast).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fireOf(Map<String, Object> hour) {
        return (Map<String, Object>) hour.get("fire");
    }

    /** A fire-weather hour: 30 degrees, 20 per cent, 30 km/h, which is Very High at a drought factor of 8. */
    private static Conditions fireHour(String at) {
        return Conditions.at(Instant.parse(at)).temperature(30.0).humidity(20).wind(30.0).windDirection(315).build();
    }

    @Test
    void aDayCarriesItsOwnWeatherFireAndFlood() {
        Map<String, Object> forecast = WeatherJson.forecast(
                List.of(weatherDay(MON, 22), weatherDay(TUE, 31)),
                fire(List.of(new FireOutlook(MON, 11.0, "HIGH", 90.0, 8.0, 22.0, 30, 25.0, 45.0, 1.5),
                        new FireOutlook(TUE, 48.0, "SEVERE", 95.0, 9.0, 31.0, 12, 45.0, 70.0, 0.0))),
                flood(List.of(new FloodOutlook(MON, 1.5, 40, 12.0, 0.6),
                        new FloodOutlook(TUE, 0.0, 5, 10.0, 0.5))),
                List.of(), true, ZONE);

        List<?> days = (List<?>) forecast.get("days");
        assertThat(days).hasSize(2);
        Map<?, ?> tuesday = (Map<?, ?>) days.get(1);
        assertThat(tuesday.get("date")).isEqualTo("2026-09-08");
        assertThat(tuesday.get("maxTemperatureC")).isEqualTo(31.0);
        assertThat(((Map<?, ?>) tuesday.get("fire")).get("ffdiRating")).isEqualTo("SEVERE");
        assertThat(((Map<?, ?>) tuesday.get("flood")).get("riverDischargeCumecs")).isEqualTo(10.0);
    }

    /**
     * The join is on the date, not the index. A provider answering five days of weather and three of
     * discharge is ordinary, and zipping by position would put Wednesday's river under Monday.
     */
    @Test
    void aShorterSeriesLandsOnTheRightDaysRatherThanTheFirstOnes() {
        Map<String, Object> forecast = WeatherJson.forecast(
                List.of(weatherDay(MON, 22), weatherDay(TUE, 31), weatherDay(WED, 19)),
                fire(List.of()),
                flood(List.of(new FloodOutlook(WED, 0.0, 5, 33.0, 1.9))),
                List.of(), true, ZONE);

        List<?> days = (List<?>) forecast.get("days");
        assertThat(days).hasSize(3);
        assertThat(((Map<?, ?>) days.get(0)).get("flood")).isNull();
        assertThat(((Map<?, ?>) days.get(1)).get("flood")).isNull();
        assertThat(((Map<?, ?>) ((Map<?, ?>) days.get(2)).get("flood")).get("riverDischargeCumecs")).isEqualTo(33.0);
    }

    /**
     * A fire day the weather series does not reach is still a day. Dropping it would be the quiet kind
     * of wrong: the index for that day exists and would simply never be shown.
     */
    @Test
    void aDayOnlyTheFireOutlookKnowsAboutIsStillADay() {
        Map<String, Object> forecast = WeatherJson.forecast(
                List.of(weatherDay(MON, 22)),
                fire(List.of(new FireOutlook(TUE, 48.0, "SEVERE", 95.0, 9.0, 31.0, 12, 45.0, 70.0, 0.0))),
                null, List.of(), true, ZONE);

        List<?> days = (List<?>) forecast.get("days");
        assertThat(days).hasSize(2);
        Map<?, ?> tuesday = (Map<?, ?>) days.get(1);
        assertThat(tuesday.get("date")).isEqualTo("2026-09-08");
        assertThat(tuesday.get("maxTemperatureC")).isNull();
        assertThat(((Map<?, ?>) tuesday.get("fire")).get("ffdiRating")).isEqualTo("SEVERE");
    }

    /**
     * The "now" blocks describe now. Leaving a per-day series on them is what let three readers each
     * invent their own join, so there is exactly one place a forecast day can be reached from.
     */
    @Test
    void theNowBlocksNoLongerCarryASeries() {
        assertThat(WeatherJson.fire(fire(List.of(new FireOutlook(MON, 11.0, "HIGH", 90.0, 8.0, 22.0, 30, 25.0, 45.0, 1.5)))))
                .doesNotContainKey("outlook")
                .containsEntry("ffdiRating", "HIGH");
        assertThat(WeatherJson.flood(flood(List.of(new FloodOutlook(MON, 1.5, 40, 12.0, 0.6)))))
                .doesNotContainKey("outlook")
                .containsEntry("riverTrend", "STEADY");
    }

    @Test
    void theHoursAreTheBriefRowWhenAskedForBrieflyAndTheWholeThingOtherwise() {
        Conditions c = Conditions.at(Instant.parse("2026-09-07T03:00:00Z"))
                .temperature(21.0).humidity(44).wind(18.0).condition("Clear").build();
        Map<String, Object> brief = hour(WeatherJson.forecast(List.of(), null, null, List.of(c), true, ZONE));
        Map<String, Object> full = hour(WeatherJson.forecast(List.of(), null, null, List.of(c), false, ZONE));
        assertThat(brief).doesNotContainKey("soilMoistureRootZone").containsEntry("temperatureC", 21.0);
        assertThat(full).containsKey("soilMoistureRootZone").containsEntry("temperatureC", 21.0);
    }

    // ---------------------------------------------------------------- G6: the hour's own index

    /**
     * An hour is indexed with the drought factor of the day it falls on - the projected one, which is
     * what {@code days[].fire.droughtFactor} carries - and with the current factor only where the
     * outlook does not reach. The day is the point's local day: one in the morning on Tuesday in
     * Adelaide is still Monday in UTC, and it must take Tuesday's factor.
     */
    @Test
    void anHourIsIndexedWithItsOwnDaysDroughtFactorAndFallsBackToTodays() {
        // Monday's projected factor is 8; a soaking Monday night takes Tuesday's down to 4. The current factor is 8.
        FireWeather f = fire(List.of(
                new FireOutlook(MON, 11.0, "HIGH", 90.0, 8.0, 22.0, 30, 25.0, 45.0, 1.5),
                new FireOutlook(TUE, 5.0, "LOW-MODERATE", 60.0, 4.0, 31.0, 12, 45.0, 70.0, 0.0)));
        Conditions mondayNoon = fireHour("2026-09-07T02:30:00Z");      // 12:00 Monday, Adelaide
        Conditions tuesdaySmallHours = fireHour("2026-09-07T15:30:00Z"); // 01:00 Tuesday in Adelaide, still Monday in UTC
        Conditions tuesdayNoon = fireHour("2026-09-08T02:30:00Z");
        Conditions thursdayNoon = fireHour("2026-09-10T02:30:00Z");     // beyond the outlook

        List<Map<String, Object>> hours = hours(WeatherJson.forecast(List.of(), f, null,
                List.of(mondayNoon, tuesdaySmallHours, tuesdayNoon, thursdayNoon), false, ZONE));

        assertThat(fireOf(hours.get(0)).get("droughtFactor")).isEqualTo(8.0);
        assertThat(fireOf(hours.get(1)).get("droughtFactor")).as("the local day, not the UTC day").isEqualTo(4.0);
        assertThat(fireOf(hours.get(2)).get("droughtFactor")).isEqualTo(4.0);
        assertThat(fireOf(hours.get(3)).get("droughtFactor")).as("past the outlook: the current factor").isEqualTo(8.0);

        double onMonday = (Double) fireOf(hours.get(0)).get("ffdi");
        double onTuesday = (Double) fireOf(hours.get(2)).get("ffdi");
        assertThat(onMonday).isEqualTo(round1(FireDanger.ffdi(30, 20, 30, 8)));
        assertThat(onTuesday).isEqualTo(round1(FireDanger.ffdi(30, 20, 30, 4))).isLessThan(onMonday);
        assertThat(fireOf(hours.get(0)).get("ffdiRating")).isEqualTo(FireDanger.rating(onMonday));
        assertThat(fireOf(hours.get(0))).containsEntry("estimated", false);
    }

    @Test
    void anHourMissingAnInputCarriesNoIndexButIsStillARow() {
        Conditions noHumidity = Conditions.at(Instant.parse("2026-09-07T02:30:00Z")).temperature(30.0).wind(30.0).build();
        Map<?, ?> fire = fireOf(hour(WeatherJson.forecast(List.of(), fire(List.of()), null, List.of(noHumidity), false, ZONE)));
        assertThat(fire.get("ffdi")).isNull();
        assertThat(fire.get("ffdiRating")).isNull();
        assertThat(fire.get("droughtFactor")).isEqualTo(8.0);
    }

    /**
     * No fire block means no drought factor, and an index built on an assumed one is the confidently
     * wrong number docs/13 is about - so the hour carries no {@code fire} at all rather than a guess.
     */
    @Test
    void withoutAFireBlockTheHoursCarryNoIndex() {
        Map<String, Object> forecast = WeatherJson.forecast(List.of(), null, null, List.of(fireHour("2026-09-07T02:30:00Z")), false, ZONE);
        assertThat(hour(forecast)).doesNotContainKey("fire");
        assertThat(forecast).containsKey("windChange").containsEntry("windChange", null);
    }

    @Test
    void anEstimatedFireBlockMarksEveryHourEstimated() {
        FireWeather assumed = new FireWeather(12.0, "HIGH", 8.0, null, null, null, null, null, null, null,
                null, null, null, null, List.of(), true, "assumed drought factor");
        Map<String, Object> brief = hour(WeatherJson.forecast(List.of(), assumed, null, List.of(fireHour("2026-09-07T02:30:00Z")), true, ZONE));
        assertThat(fireOf(brief)).as("the brief row carries the index too").containsEntry("estimated", true).containsKey("ffdi");
    }

    // ---------------------------------------------------------------- G6: the wind change

    /**
     * The structure carries the change as {@link WindChange#find} found it; the rule itself is pinned
     * in {@code WindChangeTest}. A north-westerly at 25 km/h swinging south-west at hour six is the
     * South Australian afternoon everyone on a fire ground is waiting for.
     */
    @Test
    void theForecastCarriesTheFirstWindChangeInTheWindow() {
        Instant t0 = Instant.parse("2026-09-07T02:00:00Z");
        List<Conditions> series = new ArrayList<>();
        for (int h = 0; h < 12; h++) {
            boolean changed = h >= 6;
            series.add(Conditions.at(t0.plus(Duration.ofHours(h))).temperature(30.0).humidity(20)
                    .windDirection(changed ? 225 : 315).wind(changed ? 32.0 : 25.0).gust(changed ? 55.0 : 40.0).build());
        }
        Map<String, Object> forecast = WeatherJson.forecast(List.of(), null, null, series, false, ZONE);
        Map<?, ?> change = (Map<?, ?>) forecast.get("windChange");
        assertThat(change).isNotNull();
        assertThat(change.get("at")).isEqualTo("2026-09-07T08:00:00Z");
        assertThat(change.get("fromDeg")).isEqualTo(315);
        assertThat(change.get("toDeg")).isEqualTo(225);
        assertThat(change.get("speedKmh")).isEqualTo(32.0);
        assertThat(change.get("gustKmh")).isEqualTo(55.0);
    }
}
