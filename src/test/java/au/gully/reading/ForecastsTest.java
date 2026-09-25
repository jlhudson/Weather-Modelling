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
    void anAskIsShownTwelveHoursFromTheOneRunningAndThreeDaysFromToday() {
        // Fetched at 01:00 UTC; asked at 02:40 UTC (1:10 pm in Adelaide). The series runs from two days back to three ahead.
        Instant fetched = Instant.parse("2026-09-23T01:00:00Z"), now = Instant.parse("2026-09-23T02:40:00Z");
        List<Conditions> hourly = new ArrayList<>();
        for (int h = -48; h < 72; h++) {
            hourly.add(Conditions.at(fetched.plus(Duration.ofHours(h))).temperature(20.0 + h).build());
        }
        List<DayOutlook> daily = new ArrayList<>();
        for (int d = -1; d < 7; d++) {
            daily.add(new DayOutlook(LocalDate.parse("2026-09-23").plusDays(d), 25.0, 10.0, null, 30, 20.0, 40.0, 270, 0.0, 10, null, null, null, "clear"));
        }
        Forecast f = new Forecast("open-meteo", "best_match", "CC BY 4.0", fetched, 40.0, "Australia/Adelaide", hourly.get(48), hourly, daily);
        Map<String, Object> v = Forecasts.view(f, ADELAIDE, 3.2, now);
        List<Map<String, Object>> hours = (List<Map<String, Object>>) v.get("hourly");
        assertThat(hours).hasSize(Forecasts.HOURS);
        assertThat(hours.getFirst()).containsEntry("at", "2026-09-23T02:00:00Z").containsEntry("temperatureC", 21.0);
        assertThat(hours.getLast()).containsEntry("at", "2026-09-23T13:00:00Z");
        List<Map<String, Object>> days = (List<Map<String, Object>>) v.get("daily");
        assertThat(days).extracting(d -> d.get("date")).containsExactly("2026-09-23", "2026-09-24", "2026-09-25");
        assertThat(v).containsEntry("ageMinutes", 100L).containsEntry("stale", false);
        assertThat((Map<String, Object>) v.get("station")).containsEntry("id", "023000").containsEntry("km", 3.2);
        // With a drought and a curing figure (W-22, W-24), every hour carries the forest and grass indices, every day its worst.
        double[] dry = new double[20];
        au.gully.record.Drought d = new au.gully.record.Drought(LocalDate.parse("2025-09-23"), LocalDate.parse("2026-09-22"), 365, true, 500, 120, 8, "HIGH", dry, LocalDate.parse("2026-09-23"));
        au.gully.cfs.Curing.Entry cured = new au.gully.cfs.Curing.Entry("Adelaide Metropolitan", 80, 4.5, LocalDate.parse("2026-09-20"), null, null, null);
        Map<String, Object> fire = Forecasts.view(f, ADELAIDE, 3.2, now, new Forecasts.FireInputs(d, ADELAIDE, "Adelaide Metropolitan", cured));
        List<Map<String, Object>> fh = (List<Map<String, Object>>) fire.get("hourly");
        assertThat(fh.getFirst()).containsKey("ffdi").containsKey("gfdi").containsKey("fbi").containsKey("forestFbi").containsKey("forestRating");
        Map<String, Object> today = (Map<String, Object>) ((List<Map<String, Object>>) fire.get("daily")).getFirst().get("fire");
        assertThat(today).containsKeys("ffdiMax", "gfdiMax", "fbiMax", "afdrsRating", "forestFbiMax", "forestRating");
        assertThat((Map<String, Object>) fire.get("fireFrom")).containsEntry("station", "023000").containsEntry("district", "Adelaide Metropolitan");
        // Three hours on, it is old: an ask fetches it again.
        assertThat(Forecasts.view(f, ADELAIDE, 3.2, fetched.plus(Forecasts.LIFE))).containsEntry("stale", true);
    }
}
