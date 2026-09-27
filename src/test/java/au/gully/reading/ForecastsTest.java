package au.gully.reading;

import au.gully.bureau.Station;
import au.gully.upstreams.Conditions;
import au.gully.upstreams.DayOutlook;
import au.gully.upstreams.Forecast;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForecastsTest {

    static final Station ADELAIDE = new Station("023000", "94648", "ADELAIDE", -34.9257, 138.5832, 29.32, "Australia/Adelaide", "SA_PW001", "sa");

    @Test
    @SuppressWarnings("unchecked")
    void anAskIsShownSeventyTwoHoursFromTheOneRunningAndSevenDaysFromToday() {
        // Fetched at 01:00 UTC; asked at 02:40 UTC (1:10 pm in Adelaide). The series runs from two days back to seven ahead (W-44).
        Instant fetched = Instant.parse("2026-09-23T01:00:00Z"), now = Instant.parse("2026-09-23T02:40:00Z");
        List<Conditions> hourly = new ArrayList<>();
        for (int h = -48; h < au.gully.upstreams.OpenMeteo.FORECAST_HOURS; h++) {
            hourly.add(Conditions.at(fetched.plus(Duration.ofHours(h))).temperature(20.0 + h % 12).apparent(18.0).dewPoint(5.0).humidity(25).wind(30.0)
                    .pressure(1012.0).visibility(24_000.0).uv(4.0).daytime(true).build());
        }
        List<DayOutlook> daily = new ArrayList<>();
        for (int d = -1; d < 7; d++) {
            daily.add(new DayOutlook(LocalDate.parse("2026-09-23").plusDays(d), 25.0, 10.0, null, 30, 20.0, 40.0, 270, 0.0, 10, null, null, null, "clear"));
        }
        Forecast f = new Forecast("open-meteo", "best_match", "CC BY 4.0", fetched, 40.0, "Australia/Adelaide", hourly.get(48), hourly, daily);
        Map<String, Object> v = Forecasts.view(f, ADELAIDE, 3.2, now);
        List<Map<String, Object>> hours = (List<Map<String, Object>>) v.get("hourly");
        assertThat(hours).hasSize(Forecasts.HOURS).hasSize(72);
        // Every field the Hub and IncidentWatch read off a forecast is there (contract/consumers.json).
        assertThat(au.gully.ConsumerContract.missing(v, "forecast")).isEmpty();
        assertThat(hours.getFirst()).containsEntry("at", "2026-09-23T02:00:00Z").containsEntry("temperatureC", 21.0);
        assertThat(hours.getLast()).containsEntry("at", "2026-09-26T01:00:00Z");
        // Everything each hour holds goes with it (W-44).
        assertThat(hours.getFirst()).containsEntry("apparentTemperatureC", 18.0).containsEntry("dewPointC", 5.0).containsEntry("pressureMslHpa", 1012.0)
                .containsEntry("visibilityKm", 24.0).containsEntry("uvIndex", 4.0).containsEntry("daytime", true);
        List<Map<String, Object>> days = (List<Map<String, Object>>) v.get("daily");
        assertThat(days).extracting(d -> d.get("date"))
                .containsExactly("2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26", "2026-09-27", "2026-09-28", "2026-09-29");
        assertThat(days.getFirst()).containsKeys("maxApparentTemperatureC", "uvIndexMax", "sunrise", "sunset");
        assertThat(v).containsEntry("ageMinutes", 100L).containsEntry("stale", false);
        assertThat((Map<String, Object>) v.get("station")).containsEntry("id", "023000").containsEntry("km", 3.2);
        // With a drought and a curing figure (W-22, W-24), every hour carries the forest and grass indices, every day its worst.
        double[] dry = new double[20];
        au.gully.record.Drought d = new au.gully.record.Drought(LocalDate.parse("2025-09-23"), LocalDate.parse("2026-09-22"), 365, true, 500, 120, 8, "HIGH", dry, LocalDate.parse("2026-09-23"));
        au.gully.cfs.Curing.Entry cured = new au.gully.cfs.Curing.Entry("Adelaide Metropolitan", 80, 4.5, LocalDate.parse("2026-09-20"), null, null, null);
        Map<String, Object> fire = Forecasts.view(f, ADELAIDE, 3.2, now,
                new Forecasts.FireInputs(d, ADELAIDE, "Adelaide Metropolitan", cured, au.gully.fuel.Fuel.Kind.GRASS));
        List<Map<String, Object>> fh = (List<Map<String, Object>>) fire.get("hourly");
        assertThat(fh.getFirst()).containsKey("ffdi").containsKey("gfdi").containsKey("fbi").containsKey("forestFbi").containsKey("forestRating");
        // Every hour carries the place's own index too (W-44): here grass, so the grass FBI.
        assertThat(fh).allSatisfy(h -> assertThat(h.get("pointFbi")).isNotNull().isEqualTo(h.get("fbi")));
        List<Map<String, Object>> fd = (List<Map<String, Object>>) fire.get("daily");
        Map<String, Object> today = (Map<String, Object>) fd.getFirst().get("fire");
        assertThat(today).containsKeys("ffdiMax", "gfdiMax", "fbiMax", "afdrsRating", "forestFbiMax", "forestRating");
        // And every day to the seventh its worst hour's indices, from the seven days of hours held.
        assertThat(fd).hasSize(7).allSatisfy(day -> assertThat((Map<String, Object>) day.get("fire")).isNotNull()
                .extracting("ffdiMax", "fbiMax", "forestFbiMax", "pointFbiMax").doesNotContainNull());
        assertThat(fire).containsKey("windChanges");
        assertThat((Map<String, Object>) fire.get("fireFrom")).containsEntry("station", "023000").containsEntry("district", "Adelaide Metropolitan");
        // Three hours on, it is old: an ask fetches it again.
        assertThat(Forecasts.view(f, ADELAIDE, 3.2, fetched.plus(Forecasts.LIFE))).containsEntry("stale", true);
    }
}
