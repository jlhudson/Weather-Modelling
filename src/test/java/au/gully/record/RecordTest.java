package au.gully.record;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.upstreams.OpenMeteo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RecordTest {

    static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");
    static final Station ADELAIDE_STATION = new Station("023000", "94648", "ADELAIDE", -34.9257, 138.5832, 29.32, "Australia/Adelaide", "SA_PW001", "sa");

    /**
     * A record that keeps its days in memory and remembers what it would have written.
     */
    static final class InMemory extends Record {
        final List<String> writes = new ArrayList<>();

        InMemory() {
            super(null, null);
        }

        @Override
        public void put(String stationId, Day day, boolean replace) {
            writes.add("day " + day.day() + " " + day.rainMm() + " " + day.maxTempC() + " " + day.source() + (replace ? "" : " (keep)"));
            super.putInMemory(stationId, day, replace);
        }
    }

    static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(ADELAIDE).toInstant();
    }

    static Observation ob(String local, double temp, Double rainSince9, Double rain24, Double publishedMax) {
        return new Observation("023000", at(local), temp, null, null, 50, 10.0, 180, "S", 15.0, 1015.0, rainSince9, rain24, publishedMax, null, null, null, null, null);
    }

    @Test
    void theBoundariesAreThreeNineFifteenAndTwentyOneLocalStrictlyAfter() {
        assertThat(Record.boundaryAfter(at("2026-09-21T08:50"), ADELAIDE)).isEqualTo(at("2026-09-21T09:00"));
        assertThat(Record.boundaryAfter(at("2026-09-21T09:00"), ADELAIDE)).as("the 9 am reading opens the new window").isEqualTo(at("2026-09-21T15:00"));
        assertThat(Record.boundaryAfter(at("2026-09-21T23:30"), ADELAIDE)).isEqualTo(at("2026-09-22T03:00"));
        assertThat(Record.dayOf(at("2026-09-21T08:59"), ADELAIDE)).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(Record.dayOf(at("2026-09-21T09:00"), ADELAIDE)).isEqualTo(LocalDate.of(2026, 9, 21));
    }

    @Test
    void aDayFoldsIntoFourWindowsAndTheNineAmReadingClosesIt() {
        // A day of readings on the 20th from 9 am, every three hours, warmest at 3 pm; then the 21st's 9 am and 9:10 readings.
        double[] temps = {14, 20, 26, 22, 17, 13, 11, 10};
        String[] times = {"2026-09-20T09:00", "2026-09-20T12:00", "2026-09-20T15:00", "2026-09-20T18:00", "2026-09-20T21:00", "2026-09-21T00:00", "2026-09-21T03:00", "2026-09-21T06:00"};
        List<Observation> readings = new ArrayList<>();
        for (int i = 0; i < temps.length; i++) {
            readings.add(ob(times[i], temps[i], 0.4 * i, 1.2, 26.0));
        }
        // The 9 am reading on the 21st: the total to 9 am is the 20th's rain, and it opens the next day, not this one.
        readings.add(ob("2026-09-21T09:00", 12, 0.0, 3.6, 12.0));
        readings.add(ob("2026-09-21T09:10", 13, 0.0, 3.6, 13.0));
        Record.Folded f = Record.fold(LocalDate.of(2026, 9, 20), ADELAIDE, readings);
        assertThat(f.windows()).hasSize(4);
        assertThat(f.windows().getFirst().at()).isEqualTo(at("2026-09-20T15:00"));
        assertThat(f.windows().getFirst().readings()).isEqualTo(2);
        assertThat(f.windows().getFirst().tMax()).isEqualTo(20.0);
        assertThat(f.windows().get(1).tMax()).as("3 pm to 9 pm: 26 and 22").isEqualTo(26.0);
        Record.Hour6 last = f.windows().getLast();
        assertThat(last.at()).isEqualTo(at("2026-09-21T09:00"));
        assertThat(last.readings()).as("3 am and 6 am; the 9 am reading is the next day's").isEqualTo(2);
        assertThat(last.tMax()).isEqualTo(11.0);
        assertThat(last.publishedMax()).isEqualTo(26.0);
        // The day: the 9 am reading's total, the windows' maximum.
        assertThat(f.day()).isPresent();
        assertThat(f.day().get()).isEqualTo(new Record.Day(LocalDate.of(2026, 9, 20), 3.6, 26.0, Record.SOURCE_BUREAU));
        // Folded again from the same readings, the same answer: the fold is idempotent.
        assertThat(Record.fold(LocalDate.of(2026, 9, 20), ADELAIDE, readings)).isEqualTo(f);
        // Readings of another day are not this day's.
        assertThat(Record.fold(LocalDate.of(2026, 9, 19), ADELAIDE, readings).windows()).isEmpty();
    }

    @Test
    void aNineAmReadingWithoutATotalLeavesTheDayForTheArchiveWhichNeverReplacesTheStationsOwn() {
        // No total at 9 am: the windows are written, the day is not.
        Record.Folded f = Record.fold(LocalDate.of(2026, 9, 20), ADELAIDE, List.of(ob("2026-09-20T15:00", 20, 0.0, null, null), ob("2026-09-21T09:00", 12, 0.0, null, null)));
        assertThat(f.windows()).hasSize(1);
        assertThat(f.day()).isEmpty();
        // A total but no reading inside the day: nothing to take a maximum from, so the archive has the day.
        assertThat(Record.fold(LocalDate.of(2026, 9, 20), ADELAIDE, List.of(ob("2026-09-21T09:00", 12, 0.0, 3.6, null))).day()).isEmpty();
        InMemory r = new InMemory();
        assertThat(r.days("023000")).isEmpty();
        assertThat(r.missing("023000", LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 20))).hasSize(3);
        // The archive fills the 18th to the 20th; the station's own 19th, arriving later, is not replaced by a later fill.
        int added = r.fill("023000", List.of(new OpenMeteo.DailyRow(LocalDate.of(2026, 9, 18), 1.0, 21.0),
                new OpenMeteo.DailyRow(LocalDate.of(2026, 9, 19), 2.0, 22.0), new OpenMeteo.DailyRow(LocalDate.of(2026, 9, 20), 3.0, 23.0)));
        assertThat(added).isEqualTo(3);
        assertThat(r.missing("023000", LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 20))).isEmpty();
        r.put("023000", new Record.Day(LocalDate.of(2026, 9, 19), 9.0, 30.0, Record.SOURCE_BUREAU), true);
        assertThat(r.fill("023000", List.of(new OpenMeteo.DailyRow(LocalDate.of(2026, 9, 19), 2.0, 22.0)))).isZero();
        assertThat(r.days("023000").get(LocalDate.of(2026, 9, 19)).rainMm()).isEqualTo(9.0);
        assertThat(r.days("023000").get(LocalDate.of(2026, 9, 19)).source()).isEqualTo("bureau");
    }

    @Test
    void theDroughtIntegratesAYearAndSaysWhenItIsShort() {
        InMemory r = new InMemory();
        LocalDate today = LocalDate.of(2026, 9, 21);
        // Nineteen days: not enough to speak of.
        for (int i = 1; i <= 19; i++) {
            r.put("023000", new Record.Day(today.minusDays(i), 0.0, 25.0, Record.SOURCE_ARCHIVE), false);
        }
        assertThat(Drought.of(r.days("023000"), today, 0.0)).isEmpty();
        // A year: an Adelaide-shaped year, 550 mm mostly in winter, summer maxima of 30.
        InMemory y = new InMemory();
        for (int i = 1; i <= 365; i++) {
            LocalDate d = today.minusDays(i);
            boolean winter = d.getMonthValue() >= 5 && d.getMonthValue() <= 9;
            double rain = winter ? (d.getDayOfMonth() % 3 == 0 ? 9.0 : 0.0) : (d.getDayOfMonth() % 10 == 0 ? 4.0 : 0.0);
            double max = winter ? 16.0 : 30.0;
            y.put("023000", new Record.Day(d, rain, max, Record.SOURCE_ARCHIVE), false);
        }
        Drought dr = Drought.of(y.days("023000"), today, 0.0).orElseThrow();
        assertThat(dr.days()).isEqualTo(365);
        assertThat(dr.complete()).isTrue();
        assertThat(dr.meanAnnualRainMm()).isCloseTo(510, within(60.0));
        // Late September after a wet winter: the deficit is low and the factor with it.
        assertThat(dr.kbdiMm()).isBetween(0.0, 60.0);
        assertThat(dr.droughtFactor()).isBetween(0.0, 8.0);
        assertThat(dr.recentRainMm()).hasSize(20);
        assertThat(dr.computedFor()).isEqualTo(today);
        assertThat(dr.band()).isIn("SATURATED", "MOIST", "DRYING");
        // Three hundred days: held, but not complete.
        InMemory p = new InMemory();
        for (int i = 1; i <= 300; i++) {
            p.put("023000", new Record.Day(today.minusDays(i), 0.0, 30.0, Record.SOURCE_ARCHIVE), false);
        }
        Drought partial = Drought.of(p.days("023000"), today, 0.0).orElseThrow();
        assertThat(partial.complete()).isFalse();
        assertThat(partial.days()).isEqualTo(300);
        assertThat(partial.kbdiMm()).as("three hundred dry days at thirty degrees, with no rain to speak of").isGreaterThan(150);
        assertThat(partial.droughtFactor()).isGreaterThan(8);
    }
}
