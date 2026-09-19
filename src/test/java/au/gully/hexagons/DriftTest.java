package au.gully.hexagons;

import au.gully.bureau.Observation;
import au.gully.science.Conditions;
import au.gully.upstreams.Forecast;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The station against the forecast (W-12): the four differences, the worst of them as the score,
 * the tolerance that throws a forecast out, and the rain compared over the Bureau's rain day only
 * when the series reaches back to 9 am.
 */
class DriftTest {

    private static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");
    // 2026-09-19 14:00 ACST = 04:30 UTC. The series runs from midnight local (14:30 UTC the day before).
    private static final Instant NOON = Instant.parse("2026-09-19T04:30:00Z");

    private static Forecast forecast(Instant first, double... hourlyRainMm) {
        List<Conditions> hours = new java.util.ArrayList<>();
        for (int i = 0; i < hourlyRainMm.length; i++) {
            hours.add(Conditions.at(first.plusSeconds(3600L * i)).temperature(20.0 + i * 0.5).humidity(50 - i).wind(20.0)
                    .windDirection(270).precipitation(hourlyRainMm[i]).build());
        }
        return new Forecast("open-meteo", "best_match", "cc", first, 100.0, ADELAIDE.getId(), hours.getFirst(), hours, List.of());
    }

    private static Observation station(Instant at, Double t, Integer rh, Double wind, Double rainSince9) {
        return new Observation("023000", at, t, null, null, rh, wind, 250, "W", null, null, rainSince9, null, null, null, null, null, null, null);
    }

    @Test
    void theDifferencesAreStationMinusForecastAtTheStationsMomentAndTheScoreIsTheWorst() {
        Instant first = Instant.parse("2026-09-18T14:30:00Z");
        Forecast f = forecast(first, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        // At 14:00 local the series is 14 hours in: 27 °C, 36 %, 20 km/h. The station reads 28.5, 30, 26.
        Drift d = Drift.of(station(NOON, 28.5, 30, 26.0, 0.0), f, ADELAIDE).orElseThrow();
        assertThat(d.temperatureC()).isEqualTo(1.5);
        assertThat(d.humidityPct()).isEqualTo(-6);
        assertThat(d.windKmh()).isEqualTo(6.0);
        assertThat(d.rainMm()).as("no rain either side since 9 am").isEqualTo(0.0);
        // 1.5/3 = .5, 6/20 = .3, 6/15 = .4: the temperature is the worst.
        assertThat(d.score()).isEqualTo(0.5);
        assertThat(d.worst()).isEqualTo("temperature");
        assertThat(d.drifted()).isFalse();
        assertThat(d.describe()).isEqualTo("drift 0.5 (temperature +1.5 °C)");
    }

    @Test
    void oneVariableFarEnoughOutThrowsTheForecastOutWhateverTheOthersSay() {
        Forecast f = forecast(Instant.parse("2026-09-18T14:30:00Z"), new double[16]);
        // Temperature and wind spot on; the humidity 28 points wetter than the model.
        Drift d = Drift.of(station(NOON, 27.0, 64, 20.0, 0.0), f, ADELAIDE).orElseThrow();
        assertThat(d.humidityPct()).isEqualTo(28);
        assertThat(d.score()).isEqualTo(1.4);
        assertThat(d.worst()).isEqualTo("humidity");
        assertThat(d.drifted()).isTrue();
    }

    @Test
    void rainIsComparedSinceNineAmAndOnlyWhenTheSeriesReachesBackThatFar() {
        // 9 am local is 23:30 UTC; the series starts at midnight local, so it reaches back. The model
        // had 2 mm in the 10 am hour and 3 mm in the 1 pm hour; the station's gauge has 1 mm since 9.
        Instant first = Instant.parse("2026-09-18T14:30:00Z");
        double[] rain = new double[16];
        rain[10] = 2.0;
        rain[13] = 3.0;
        Drift d = Drift.of(station(NOON, 27.0, 36, 20.0, 1.0), forecast(first, rain), ADELAIDE).orElseThrow();
        assertThat(d.rainMm()).isEqualTo(-4.0);
        assertThat(d.worst()).isEqualTo("rain");
        assertThat(d.score()).isEqualTo(0.8);

        // A series that starts at 10 am cannot say what fell since 9: no rain comparison, the rest stand.
        Forecast late = forecast(Instant.parse("2026-09-19T00:30:00Z"), 0, 0, 0, 0, 0, 0);
        Drift e = Drift.of(station(NOON, 26.0, 36, 20.0, 1.0), late, ADELAIDE).orElseThrow();
        assertThat(e.rainMm()).isNull();
        assertThat(e.worst()).isEqualTo("temperature");
    }

    @Test
    void nothingToCompareIsNoComparison() {
        Forecast f = forecast(Instant.parse("2026-09-18T14:30:00Z"), new double[4]);
        Optional<Drift> none = Drift.of(station(NOON, null, null, null, null), f, ADELAIDE);
        assertThat(none).isEmpty();
        assertThat(Drift.of(null, f, ADELAIDE)).isEmpty();
    }
}
