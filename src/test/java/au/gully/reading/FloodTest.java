package au.gully.reading;

import au.gully.record.Record;
import au.gully.upstreams.Conditions;
import au.gully.upstreams.Forecast;
import au.gully.upstreams.OpenMeteo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class FloodTest {

    static final LocalDate TODAY = LocalDate.parse("2026-09-25");

    @Test
    void theRainDownIsTodaySoFarAndTheBureauDaysBeforeIt() {
        NavigableMap<LocalDate, Record.Day> days = new TreeMap<>();
        days.put(TODAY.minusDays(1), new Record.Day(TODAY.minusDays(1), 30.0, 15.0, "bureau"));
        days.put(TODAY.minusDays(2), new Record.Day(TODAY.minusDays(2), 20.0, 15.0, "bureau"));
        days.put(TODAY.minusDays(3), new Record.Day(TODAY.minusDays(3), 50.0, 15.0, "bureau"));
        days.put(TODAY.minusDays(10), new Record.Day(TODAY.minusDays(10), 100.0, 15.0, "archive"));
        // Today so far 4; three days is today and the two before: 4 + 30 + 20.
        assertThat(Flood.before(days, TODAY, 2, 4.0)).isEqualTo(54.0);
        assertThat(Flood.before(days, TODAY, 6, 4.0)).isEqualTo(104.0);
        assertThat(Flood.before(days, TODAY, 29, 4.0)).isEqualTo(204.0);
        // A record with none of those days says nothing, rather than a dry zero.
        assertThat(Flood.before(new TreeMap<>(), TODAY, 2, null)).isNull();
    }

    @Test
    void theRainComingIsTheForecastsHoursAhead() {
        Instant now = Instant.parse("2026-09-25T02:10:00Z");
        List<Conditions> hours = new ArrayList<>();
        for (int h = -3; h < 80; h++) {
            hours.add(Conditions.at(Instant.parse("2026-09-25T02:00:00Z").plusSeconds(3600L * h)).precipitation(h < 0 ? 9.0 : 1.0).precipitationProbability(h == 30 ? 90 : 20).build());
        }
        Forecast f = new Forecast("open-meteo", "best_match", "", now, 30.0, "Australia/Adelaide", hours.get(3), hours, List.of());
        // The hours after now: 03:00 to 02:00 next day is twenty-four; the past hours are not coming.
        assertThat(Flood.ahead(f, now, java.time.Duration.ofHours(24))).isEqualTo(24.0);
        assertThat(Flood.ahead(f, now, java.time.Duration.ofHours(72))).isEqualTo(72.0);
        Map<String, Object> b = Flood.block(null, new TreeMap<>(), 2.0, TODAY, f, now, null);
        assertThat((Map<String, Object>) b.get("coming")).containsEntry("maxChancePct", 90);
        assertThat(b).containsEntry("threeDaysEachSideMm", 74.0);
    }

    @Test
    void theRiverIsItsFlowAgainstItsOwnMeanAndWhereItIsGoing() {
        List<OpenMeteo.DischargeRow> rows = new ArrayList<>();
        for (int d = -10; d < 0; d++) {
            rows.add(new OpenMeteo.DischargeRow(TODAY.plusDays(d), 100.0));
        }
        rows.add(new OpenMeteo.DischargeRow(TODAY, 150.0));
        rows.add(new OpenMeteo.DischargeRow(TODAY.plusDays(1), 180.0));
        rows.add(new OpenMeteo.DischargeRow(TODAY.plusDays(2), 240.0));
        rows.add(new OpenMeteo.DischargeRow(TODAY.plusDays(3), 210.0));
        Map<String, Object> r = Rivers.summary(rows, TODAY);
        assertThat(r).containsEntry("cumecs", 150.0).containsEntry("meanCumecs", 100.0).containsEntry("meanOverDays", 10)
                .containsEntry("ratioToMean", 1.5).containsEntry("trend", "rising").containsEntry("peakCumecs", 240.0).containsEntry("peakOn", "2026-09-27");
    }

    @Test
    void aPointIsSnappedToTheCentreOfItsGlofasCell() {
        // Renmark: the cell centred on -34.175, 140.775, as Open-Meteo answers it.
        assertThat(Rivers.snap(-34.1800)).isEqualTo(-34.175);
        assertThat(Rivers.snap(140.7500)).isEqualTo(140.775);
    }
}
