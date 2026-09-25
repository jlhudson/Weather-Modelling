package au.gully.fire;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DryForestTest {

    static final LocalDateTime MIDNIGHT_JANUARY = LocalDateTime.parse("2026-01-15T00:00:00");

    @Test
    void theOfficialCodesOwnRowsForMoistureAndSpread() {
        // AFDRS 2024.6.0 output (tests/datasets/dry_forest_small.csv): 10 km/h, 10 % humidity, midnight in January, no fuel yet.
        DryForest.Fuel burnt = new DryForest.Fuel(3, 1, 20, 2, 10, 10, 2, 2, 2, 4.5, 0.3, 3, 0, "just burnt");
        double mc10 = DryForest.moisture(10, 10, MIDNIGHT_JANUARY);
        assertThat(mc10).isCloseTo(4.577, within(1e-9));
        assertThat(DryForest.rateOfSpread(10, mc10, DryForest.availability(10), burnt)).isCloseTo(56.6486, within(0.01));
        double mc25 = DryForest.moisture(25, 10, MIDNIGHT_JANUARY);
        assertThat(mc25).isCloseTo(3.8525, within(1e-9));
        assertThat(DryForest.rateOfSpread(10, mc25, DryForest.availability(10), burnt)).isCloseTo(69.2911, within(0.01));
    }

    @Test
    void theIndexIsTheIntensityBetweenThePublishedAnchorsRoundedDown() {
        // The same file's intensities and the index the AFDRS code gave each.
        assertThat(DryForest.fbi(916.760)).isEqualTo(12);
        assertThat(DryForest.fbi(2903.875)).isEqualTo(19);
        assertThat(DryForest.fbi(121.307)).isEqualTo(6);
        // The anchors themselves, and the last segment carried on.
        assertThat(DryForest.fbi(4000)).isEqualTo(24);
        assertThat(DryForest.fbi(30000)).isEqualTo(100);
        assertThat(DryForest.fbi(120000)).isEqualTo(250);
        assertThat(DryForest.fbi(0)).isZero();
    }

    @Test
    void theMoistureEquationFollowsTheHourAndTheSeason() {
        // A sunny summer afternoon, the same hour in winter, and the night.
        assertThat(DryForest.moisture(35, 10, LocalDateTime.parse("2026-01-15T14:00"))).isCloseTo(2.76 + 1.24 - 0.6545, within(1e-9));
        assertThat(DryForest.moisture(35, 10, LocalDateTime.parse("2026-07-15T14:00"))).isCloseTo(3.60 + 1.69 - 1.575, within(1e-9));
        assertThat(DryForest.moisture(35, 10, LocalDateTime.parse("2026-01-15T21:00"))).isCloseTo(3.08 + 1.98 - 1.6905, within(1e-9));
        assertThat(DryForest.moistureFactor(25)).isEqualTo(0.05);
        assertThat(DryForest.moistureFactor(2)).isEqualTo(DryForest.moistureFactor(4));
    }

    @Test
    void aBadSummerAfternoonInLongUnburntForest() {
        // Computed from the guide's equations on the provisional fuel, not an official figure: 35 °C, 10 %, 40 km/h,
        // drought factor 10, a sunny afternoon - Extreme. The accumulation is unrounded and the canopy held at its steady state,
        // as the official code (fdrs_calcs 2024.6.0) computes them.
        DryForest.Result r = DryForest.of(35.0, 10.0, 40.0, 10.0, LocalDateTime.parse("2026-01-15T15:00"), DryForest.Fuel.PROVISIONAL);
        assertThat(r.moisturePct()).isCloseTo(3.35, within(0.01));
        assertThat(r.rateOfSpreadMh()).isCloseTo(1504.7, within(0.1));
        assertThat(r.flameHeightM()).isCloseTo(14.73, within(0.05));
        assertThat(r.fbi()).isEqualTo(56);
        assertThat(r.rating()).isEqualTo("Extreme");
        // Still air: the spread is the moisture's alone, whatever the fuel.
        assertThat(DryForest.of(20.0, 50.0, 3.0, 5.0, LocalDateTime.parse("2026-04-15T10:00"), DryForest.Fuel.PROVISIONAL).rating()).isEqualTo("No Rating");
        assertThat(DryForest.of(null, 50.0, 3.0, 5.0, LocalDateTime.parse("2026-04-15T10:00"), DryForest.Fuel.PROVISIONAL)).isNull();
    }
}
