package au.gully.upstreams;

import au.gully.platform.UpstreamException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upstream machinery without a network: Open-Meteo's answer parsed into a forecast with the
 * upstream's own expiry, the pause its refusals ask for, the budget against a ledger, and the breaker.
 */
class UpstreamsTest {

    private static final String OPEN_METEO = """
            {"latitude":-35.04,"longitude":138.78,"utc_offset_seconds":34200,"timezone":"Australia/Adelaide","elevation":482.0,
             "current":{"time":1789740000,"interval":900,"temperature_2m":10.7,"relative_humidity_2m":81,"wind_speed_10m":9.4,
                        "wind_direction_10m":200,"wind_gusts_10m":18.0,"weather_code":2,"is_day":0,"vapour_pressure_deficit":0.25},
             "hourly":{"time":[1789740000,1789743600,1789747200],"temperature_2m":[10.7,10.2,9.8],"relative_humidity_2m":[81,83,85],
                       "wind_speed_10m":[9.4,10.1,11.0],"wind_direction_10m":[200,205,210],"precipitation":[0.0,0.1,0.0],"weather_code":[2,3,3]},
             "daily":{"time":[1789699800,1789786200],"temperature_2m_max":[23.4,19.0],"temperature_2m_min":[8.1,9.2],
                      "precipitation_sum":[0.0,2.4],"wind_speed_10m_max":[24.0,31.0],"wind_gusts_10m_max":[40.0,55.0],
                      "wind_direction_10m_dominant":[210,240],"weather_code":[2,61],"sunrise":[1789719600,1789806000],"sunset":[1789762800,1789849200]}}
            """;

    @Test
    void openMeteoIsParsedAndItsSeriesReadsAtAnyMoment() throws Exception {
        OpenMeteo om = new OpenMeteo(null);
        Forecast f = om.parse(JsonMapper.builder().build().readTree(OPEN_METEO));
        assertThat(f.upstream()).isEqualTo("open-meteo");
        assertThat(f.zoneId()).isEqualTo("Australia/Adelaide");
        assertThat(f.modelElevationM()).isEqualTo(482.0);
        assertThat(f.current().at()).isEqualTo(Instant.ofEpochSecond(1789740000L));
        assertThat(f.current().temperatureC()).isEqualTo(10.7);
        assertThat(f.current().humidityPct()).isEqualTo(81);
        assertThat(f.current().condition()).isEqualTo("Partly cloudy");
        assertThat(f.current().daytime()).isFalse();
        // No expiry of its own: the service keeps it for its life. "Now" is the series read at the moment:
        // halfway between the first two hours the values blend, the words come from the nearer hour.
        assertThat(f.hourly()).hasSize(3);
        var half = f.at(Instant.ofEpochSecond(1789741800L));
        assertThat(half.temperatureC()).isCloseTo(10.45, org.assertj.core.api.Assertions.offset(0.001));
        assertThat(half.humidityPct()).isEqualTo(82);
        assertThat(half.windDirectionDeg()).isEqualTo(203);
        assertThat(half.precipitationMm()).as("the hour's own total").isEqualTo(0.0);
        assertThat(f.at(Instant.ofEpochSecond(1789740000L)).temperatureC()).isEqualTo(10.7);
        assertThat(f.at(Instant.ofEpochSecond(1789800000L)).temperatureC()).as("past the series: its last hour").isEqualTo(9.8);
        assertThat(f.at(Instant.ofEpochSecond(1789000000L)).temperatureC()).as("before the series: its first hour").isEqualTo(10.7);
        assertThat(f.daily()).hasSize(2);
        // Midnight local expressed as a UTC epoch comes back as the local date.
        assertThat(f.daily().getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(f.daily().getFirst().minHumidityPct()).as("from the hourly series of that local day").isEqualTo(81);
        assertThat(f.daily().get(1).condition()).isEqualTo("Light rain");
    }

    @Test
    void anAllNullAnswerIsRefusedRatherThanHeld() {
        String empty = OPEN_METEO.replace("\"temperature_2m\":10.7,\"relative_humidity_2m\":81,\"wind_speed_10m\":9.4,",
                "\"temperature_2m\":null,\"relative_humidity_2m\":null,\"wind_speed_10m\":null,");
        OpenMeteo om = new OpenMeteo(null);
        assertThatThrownBy(() -> om.parse(JsonMapper.builder().build().readTree(empty)))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("no values");
    }

    @Test
    void theRefusalNamesItsWindowAndThePauseFollowsIt() {
        Instant at = Instant.parse("2026-09-18T10:20:00Z");
        Duration otherwise = Duration.ofMinutes(5);
        assertThat(OpenMeteo.pauseFor("HTTP 429: Daily API request limit exceeded", at, otherwise))
                .isEqualTo(Duration.ofHours(13).plusMinutes(41));
        assertThat(OpenMeteo.pauseFor("Hourly API request limit exceeded", at, otherwise)).isEqualTo(Duration.ofMinutes(41));
        assertThat(OpenMeteo.pauseFor("Minutely API request limit exceeded", at, otherwise)).isEqualTo(Duration.ofMinutes(1));
        assertThat(OpenMeteo.pauseFor("unreachable", at, otherwise)).isEqualTo(otherwise);
    }

    /**
     * A ledger that answers from a number rather than a table.
     */
    private static Ledger ledgerSpent(double units) {
        return new Ledger(null) {
            @Override
            public double spentSince(String upstream, Instant since) {
                return units;
            }
        };
    }

    @Test
    void theBudgetRetiresAnUpstreamAtTheGuardNotTheLimit() {
        Budget budget = new Budget(ledgerSpent(8_996));
        // 8,996 spent; the guard is 90% of 10,000 = 9,000; one more fetch of 5 does not fit.
        Budget.Decision d = budget.check(OpenMeteo.SPEC, OpenMeteo.SPEC.unitsPerFetch());
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("per minute").contains("guard 90%");
        assertThat(new Budget(ledgerSpent(100)).check(OpenMeteo.SPEC, 5).allowed()).isTrue();
        // Google publishes only a monthly limit: a big daily spend is no reason to refuse.
        assertThat(new Budget(ledgerSpent(5_000)).check(GoogleWeather.SPEC, 3).allowed()).isTrue();
        assertThat(new Budget(ledgerSpent(8_999)).check(GoogleWeather.SPEC, 3).allowed()).isFalse();
    }

    @Test
    void theBreakerOpensOnTheThirdFailureOrAtOnceOnANamedWindow() {
        Breaker b = new Breaker();
        b.failed("x", "boom", Duration.ofMinutes(5), false);
        b.failed("x", "boom", Duration.ofMinutes(5), false);
        assertThat(b.check("x").allowed()).as("two failures are a bad minute").isTrue();
        b.failed("x", "boom", Duration.ofMinutes(5), false);
        assertThat(b.check("x").allowed()).isFalse();
        assertThat(b.status("x").openings()).isEqualTo(1);
        b.succeeded("x");
        assertThat(b.check("x").allowed()).isTrue();
        assertThat(b.status("x").consecutiveFailures()).isZero();

        b.failed("y", "Daily API request limit exceeded", Duration.ofHours(10), true);
        assertThat(b.check("y").allowed()).as("a named window opens at once").isFalse();
        assertThat(b.history()).extracting(Breaker.Event::upstream).contains("x", "y");
    }

    @Test
    void thePacerHoldsCallsToThePerMinuteLimit() {
        Pacer pacer = new Pacer();
        for (int i = 0; i < 5; i++) {
            assertThat(pacer.acquire("z", 5)).isTrue();
        }
        assertThat(pacer.inLastMinute("z")).isEqualTo(5);
        long started = System.nanoTime();
        assertThat(pacer.acquire("z", 5)).as("the sixth waits up to the ceiling and gives up").isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Pacer.WAIT_CEILING.minusMillis(300));
    }

    @Test
    void thePacerWeighsACallAtWhatItCosts() {
        Pacer pacer = new Pacer();
        // Eight a minute: two forecasts at three leave room for one elevation call at one, not a third forecast.
        assertThat(pacer.acquire("z", 8, OpenMeteo.SPEC.unitsPerFetch())).isTrue();
        assertThat(pacer.acquire("z", 8, OpenMeteo.SPEC.unitsPerFetch())).isTrue();
        assertThat(pacer.acquire("z", 8, OpenMeteo.SPEC.unitsPerFetch())).as("6 + 3 > 8: the minute is full").isFalse();
        assertThat(pacer.acquire("z", 8, OpenMeteo.ELEVATION_UNITS)).as("6 + 1 fits").isTrue();
        assertThat(pacer.inLastMinute("z")).as("counted as calls for the console").isEqualTo(3);
        // A call heavier than the whole limit goes through on an empty minute rather than never.
        assertThat(pacer.acquire("y", 2, OpenMeteo.SPEC.unitsPerFetch())).isTrue();
    }

    @Test
    void theSpecsSayWhatTheyCost() {
        assertThat(OpenMeteo.SPEC.unitsPerFetch()).isEqualTo(3.0);
        assertThat(OpenMeteo.ELEVATION_UNITS).isEqualTo(1.0);
        assertThat(OpenMeteo.ELEVATION_POINTS_PER_CALL).isEqualTo(100);
        assertThat(OpenMeteo.SPEC.limits().perDay()).isEqualTo(10_000);
        assertThat(OpenMeteo.SPEC.bills()).isFalse();
        assertThat(GoogleWeather.SPEC.unitsPerFetch()).isEqualTo(3.0);
        assertThat(GoogleWeather.SPEC.bills()).isTrue();
        assertThat(List.of(OpenMeteo.ID, GoogleWeather.ID)).containsExactly("open-meteo", "google");
    }
}
