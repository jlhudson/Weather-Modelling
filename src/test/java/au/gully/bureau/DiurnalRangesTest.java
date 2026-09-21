package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fold (W-25): six-hourly windows into Bureau days, the day's high against the morning's low.
 */
class DiurnalRangesTest {

    private static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");

    private static Instant local(LocalDate d, int hour, int minute) {
        return LocalDateTime.of(d, LocalTime.of(hour, minute)).atZone(ADELAIDE).toInstant();
    }

    /**
     * A station's date as the ledger writes it: rows at 00:50, 06:50, 12:50 and 18:50 local, each the
     * six hours before it. The dawn low sits in the 00:50–06:50 window; the 06:50–12:50 window crosses
     * 9 am and still starts near the low; the afternoon high is in the 12:50–18:50 one.
     */
    private static void date(List<DiurnalRanges.Row> rows, LocalDate d, double low, double high) {
        rows.add(new DiurnalRanges.Row(local(d, 0, 50), low + 3, low + 2, low + 5, 36));
        rows.add(new DiurnalRanges.Row(local(d, 6, 50), low + 1, low, low + 2, 36));
        rows.add(new DiurnalRanges.Row(local(d, 12, 50), high - 2, low + 1, high - 1, 36));
        rows.add(new DiurnalRanges.Row(local(d, 18, 50), high - 4, high - 5, high, 36));
    }

    @Test
    void theDaysHighAgainstThatMorningsLowAndTheMeanOverCompleteDays() {
        List<DiurnalRanges.Row> rows = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 9, 15);
        date(rows, d, 8.0, 20.0);
        date(rows, d.plusDays(1), 10.0, 24.0);
        date(rows, d.plusDays(2), 6.0, 18.0);
        date(rows, d.plusDays(3), 9.0, 21.0);
        // Today, the 19th, at 3 pm: the night's and the morning's rows are in, the afternoon's is not.
        LocalDate today = d.plusDays(4);
        rows.add(new DiurnalRanges.Row(local(today, 0, 50), 12.0, 11.0, 14.0, 36));
        rows.add(new DiurnalRanges.Row(local(today, 6, 50), 7.5, 7.0, 9.0, 36));
        rows.add(new DiurnalRanges.Row(local(today, 12, 50), 16.0, 8.0, 17.0, 36));
        DiurnalRanges.Diurnal out = DiurnalRanges.fold(rows, ADELAIDE, local(today, 15, 0));

        // The 18th: the 18:50 row's 21.0 against the dawn of the 18th, the 06:50 row's 9.0 - the
        // 06:50–12:50 window's 10.0 crosses 9 am, so its low belongs to the 24 hours to 9 am too, and
        // the 12:50 row of the 17th (its low 7.0, before 9 am on the 17th) belongs to the 17th's morning.
        assertThat(out.day()).isNotNull();
        assertThat(out.day().date()).isEqualTo(d.plusDays(3));
        assertThat(out.day().highC()).isEqualTo(21.0);
        assertThat(out.day().lowC()).isEqualTo(9.0);
        assertThat(out.day().rangeC()).isEqualTo(12.0);
        assertThat(out.day().complete()).isTrue();

        // Today so far: the 12:50 row's 17.0 against this morning's 7.0 - never complete.
        assertThat(out.today().date()).isEqualTo(today);
        assertThat(out.today().highC()).isEqualTo(17.0);
        assertThat(out.today().lowC()).isEqualTo(7.0);
        assertThat(out.today().rangeC()).isEqualTo(10.0);
        assertThat(out.today().complete()).isFalse();

        // The week: the 15th to the 18th are complete - 12, 14, 12 and 12 - the 15th because the
        // three windows of its morning are enough for the 24 hours before it.
        assertThat(out.week().of()).isEqualTo(7);
        assertThat(out.week().days()).isEqualTo(4);
        assertThat(out.week().meanRangeC()).isEqualTo(12.5);
        assertThat(out.month().of()).isEqualTo(30);
        assertThat(out.month().days()).isEqualTo(4);
        assertThat(out.month().meanRangeC()).isEqualTo(12.5);
    }

    @Test
    void aDayShortOfWindowsIsNotCompleteAndBareRowsOnlyStandIn() {
        List<DiurnalRanges.Row> rows = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 9, 15);
        date(rows, d, 8.0, 20.0);
        // The 16th after a restart: two rows carry a window, two are bare spot readings.
        rows.add(new DiurnalRanges.Row(local(d.plusDays(1), 0, 50), 11.0, null, null, null));
        rows.add(new DiurnalRanges.Row(local(d.plusDays(1), 6, 50), 10.0, 10.0, 12.0, 30));
        rows.add(new DiurnalRanges.Row(local(d.plusDays(1), 12, 50), 22.0, null, null, null));
        rows.add(new DiurnalRanges.Row(local(d.plusDays(1), 18, 50), 20.0, 19.0, 24.0, 30));
        date(rows, d.plusDays(2), 6.0, 18.0);
        DiurnalRanges.Diurnal out = DiurnalRanges.fold(rows, ADELAIDE, local(d.plusDays(3), 15, 0));
        // The 16th's morning drew on two windows, so the 16th is not complete; the 17th's afternoon on
        // two, so nor is the 17th. The 15th is the last complete day, and the week's only one.
        assertThat(out.day().date()).isEqualTo(d);
        assertThat(out.day().rangeC()).isEqualTo(12.0);
        assertThat(out.week().days()).isEqualTo(1);
        assertThat(out.week().meanRangeC()).isEqualTo(12.0);
        // Today has no row yet: the morning's low stands, the high does not.
        assertThat(out.today().highC()).isNull();
        assertThat(out.today().lowC()).isEqualTo(13.0);
        assertThat(out.today().rangeC()).isNull();
    }

    @Test
    void nothingWithoutRows() {
        assertThat(DiurnalRanges.fold(List.of(), ADELAIDE, Instant.now())).isNull();
    }

    @Test
    void theBureauDayStartsAtNine() {
        LocalDate d = LocalDate.of(2026, 9, 21);
        assertThat(DiurnalRanges.bureauDay(local(d, 8, 59).atZone(ADELAIDE))).isEqualTo(d.minusDays(1));
        assertThat(DiurnalRanges.bureauDay(local(d, 9, 0).atZone(ADELAIDE))).isEqualTo(d);
    }
}
