package au.weather.api;

import au.weather.service.WeatherProvider;
import au.weather.service.WeatherStatus;
import au.weather.service.WeatherTuning;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of {@code GET /api/weather/status}, which is a contract rather than a convenience: the
 * Hub deserialises these names, and a field quietly renamed here is a field quietly missing there.
 * <p>
 * No Spring context and no database. The map builders take their inputs rather than reading beans,
 * precisely so the thing that has to be right can be asserted without standing an application up.
 */
class WeatherApiControllerTest {

    private static final Instant AT = Instant.parse("2026-09-17T02:10:00Z");

    private static WeatherStatus openMeteo() {
        return new WeatherStatus("open-meteo", "api.open-meteo.com", "best_match", true, "", true,
                "within allowance", false, 1.0, 0.9,
                new WeatherProvider.Limits(600, 5000, 10000, 300000),
                Map.of("minute", 0.0, "hour", 3.0, "day", 120.0, "month", 2400.0),
                "Open-Meteo.com", null, null, null);
    }

    private static WeatherTuning tuning() {
        return new WeatherTuning(15_000, 25_000, 5_000, Duration.ofMinutes(30), 3, 72,
                0.0, 0.1, 0.0, AT, "pressure 0.10: everything at its floor");
    }

    @Test
    void theStatusBodyCarriesEveryBlockTheContractNames() {
        Map<String, Object> body = WeatherApiController.statusBody(true, List.of(openMeteo()),
                Map.of("anchors", 12), WeatherApiController.tuningBlock(tuning()), 3, 4);

        assertThat(body).containsKeys("generatedAt", "enabled", "providers", "cache", "tuning", "drought", "flood");
        assertThat(body.get("enabled")).isEqualTo(true);
        assertThat(body.get("drought")).isEqualTo(Map.of("cells", 3));
        assertThat(body.get("flood")).isEqualTo(Map.of("cells", 4));
        assertThat((List<?>) body.get("providers")).hasSize(1);
    }

    @Test
    void aProviderRowCarriesTheSixteenFieldsTheHubReads() {
        Map<String, Object> row = WeatherApiController.providerBlock(openMeteo());

        assertThat(row).containsOnlyKeys("id", "host", "model", "configured", "unavailableReason",
                "withinBudget", "budgetReason", "commercialSafe", "callWeight", "guardFraction",
                "limits", "spent", "attribution", "lastFailure", "lastFailureAt", "coolingDownUntil");
        assertThat(row.get("id")).isEqualTo("open-meteo");
        assertThat(row.get("host")).isEqualTo("api.open-meteo.com");
        assertThat(row.get("commercialSafe")).isEqualTo(false);
        // Present and null rather than absent: a caller reading "has it failed recently" must be able to
        // tell "no" from "this service does not say".
        assertThat(row).containsEntry("lastFailure", null).containsEntry("coolingDownUntil", null);
    }

    @Test
    void theLimitsBlockKeepsItsNullsAndSurvivesNoLimitsAtAll() {
        assertThat(WeatherApiController.limits(new WeatherProvider.Limits(600, null, 10000, null)))
                .containsOnlyKeys("perMinute", "perHour", "perDay", "perMonth")
                .containsEntry("perMinute", 600)
                .containsEntry("perHour", null)
                .containsEntry("perDay", 10000)
                .containsEntry("perMonth", null);
        assertThat(WeatherApiController.limits(null))
                .containsOnlyKeys("perMinute", "perHour", "perDay", "perMonth")
                .allSatisfy((k, v) -> assertThat(v).as(k).isNull());
    }

    /**
     * Kilometres and ISO strings, because that is what the contract quotes and what a person compares
     * against a model grid.
     */
    @Test
    void theTuningBlockIsInKilometresAndIsoStrings() {
        Map<String, Object> t = WeatherApiController.tuningBlock(tuning());

        assertThat(t).containsOnlyKeys("anchorReachKm", "droughtCellRadiusKm", "riverCellRadiusKm", "ttl",
                "forecastDays", "forecastHours", "verticalWeight", "pressure", "applied", "computedAt", "reason");
        assertThat(t.get("anchorReachKm")).isEqualTo(15.0);
        assertThat(t.get("droughtCellRadiusKm")).isEqualTo(25.0);
        assertThat(t.get("riverCellRadiusKm")).isEqualTo(5.0);
        assertThat(t.get("ttl")).isEqualTo("PT30M");
        assertThat(t.get("computedAt")).isEqualTo("2026-09-17T02:10:00Z");
        assertThat(t.get("reason")).asString().isNotBlank();
    }

    /**
     * Sixty-two days is the cap the daily spend read enforces, and it is a 400 rather than a quiet
     * truncation.
     */
    @Test
    void theDailySpendCapIsTwoMonths() {
        assertThat(WeatherApiController.MAX_SPEND_DAYS).isEqualTo(62);
    }
}
