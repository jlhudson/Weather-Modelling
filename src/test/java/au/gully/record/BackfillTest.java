package au.gully.record;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackfillTest {

    @Test
    void onlyTheMissingDaysAreAskedForInRunsAFortnightApart() {
        // A gap three hundred days back, a week of scattered days ten days apart, and the last four days.
        LocalDate today = LocalDate.parse("2026-09-23");
        List<LocalDate> days = new ArrayList<>();
        days.add(today.minusDays(300));
        days.add(today.minusDays(299));
        days.add(today.minusDays(60));
        days.add(today.minusDays(50));
        for (int d = 4; d >= 1; d--) {
            days.add(today.minusDays(d));
        }
        List<LocalDate[]> runs = Backfill.runs(days);
        // Three fetches, not one of three hundred days: the two ten days apart share one, costing no more.
        assertThat(runs).hasSize(3);
        assertThat(runs.get(0)).containsExactly(today.minusDays(300), today.minusDays(299));
        assertThat(runs.get(1)).containsExactly(today.minusDays(60), today.minusDays(50));
        assertThat(runs.get(2)).containsExactly(today.minusDays(4), today.minusDays(1));
        assertThat(Backfill.runs(List.of())).isEmpty();
    }
}
