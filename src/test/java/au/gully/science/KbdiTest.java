package au.gully.science;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The soil moisture deficit and the drought factor derived from it.
 * <p>
 * These are checked by their properties rather than against a single published worked example, because
 * KBDI is an integral over a year and there is no hand-computable answer for one. The properties are
 * strong enough to catch a wrong constant: a year with no rain must reach the dry end, a year of daily
 * rain must stay at the wet end, and rain must be intercepted per event rather than per day.
 */
class KbdiTest {

    private static final double ADELAIDE_ANNUAL_RAIN_MM = 550;

    private static double[] filled(int n, double value) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, value);
        return out;
    }

    @Test
    void aYearWithoutRainReachesTheDryEnd() {
        double[] rain = new double[365];
        double[] temp = filled(365, 30);
        double[] series = Kbdi.series(rain, temp, ADELAIDE_ANNUAL_RAIN_MM, 0);
        assertThat(series[364]).isGreaterThan(190).isLessThanOrEqualTo(Kbdi.FIELD_CAPACITY_MM);
        assertThat(Kbdi.band(series[364])).isEqualTo("SEVERE");
    }

    @Test
    void aYearOfDailyRainStaysAtFieldCapacity() {
        double[] rain = filled(365, 10);
        double[] temp = filled(365, 20);
        double[] series = Kbdi.series(rain, temp, ADELAIDE_ANNUAL_RAIN_MM, 0);
        assertThat(series[364]).isLessThan(5);
        assertThat(Kbdi.band(series[364])).isEqualTo("SATURATED");
    }

    @Test
    void aWetSeasonWashesOutTheStartingAssumption() {
        // The spin-up has to begin somewhere, and it begins at field capacity. What makes that starting
        // point stop mattering is a wet season, not merely the passage of time - so this is asserted
        // against a mild wet winter and a hot dry summer, which is the year South Australia has.
        //
        // Note the rain is delivered as three-day events rather than as frequent single wet days. That
        // is not decoration: canopy interception takes its allowance once per event, so 24 mm over
        // three days reaches the soil while the same 24 mm spread over eight separate days does not.
        double[] rain = new double[365];
        double[] temp = new double[365];
        for (int day = 0; day < 365; day++) {
            boolean winter = day < 180;
            temp[day] = winter ? 15 : 32;
            if (winter && day % 12 < 3) {
                rain[day] = 8.0;
            }
        }
        double fromWet = Kbdi.series(rain, temp, ADELAIDE_ANNUAL_RAIN_MM, 0)[364];
        double fromDry = Kbdi.series(rain, temp, ADELAIDE_ANNUAL_RAIN_MM, Kbdi.FIELD_CAPACITY_MM)[364];
        assertThat(fromWet).isCloseTo(fromDry, Offset.offset(1.0));
        // And by the end of that dry summer the deficit is real, not an artefact of where it started.
        assertThat(fromWet).isGreaterThan(120);
    }

    @Test
    void withoutRainAtAllTheTwoStartingPointsDoNotConverge() {
        // The other half of the same fact, and the reason the answer reports how deep its window went.
        // A rainless year leaves the wet start about twenty millimetres below the dry one, because the
        // approach to the ceiling is asymptotic. Both land in the driest bands, but they are not equal.
        double[] rain = new double[365];
        double[] temp = filled(365, 25);
        double fromWet = Kbdi.series(rain, temp, ADELAIDE_ANNUAL_RAIN_MM, 0)[364];
        double fromDry = Kbdi.series(rain, temp, ADELAIDE_ANNUAL_RAIN_MM, Kbdi.FIELD_CAPACITY_MM)[364];
        assertThat(fromWet).isLessThan(fromDry);
        assertThat(Kbdi.band(fromWet)).isIn("VERY DRY", "SEVERE");
    }

    @Test
    void theDeficitIsClampedAtBothEnds() {
        assertThat(Kbdi.step(0, 40, 50, ADELAIDE_ANNUAL_RAIN_MM)).isGreaterThanOrEqualTo(0);
        assertThat(Kbdi.step(Kbdi.FIELD_CAPACITY_MM, 45, 0, ADELAIDE_ANNUAL_RAIN_MM))
                .isLessThanOrEqualTo(Kbdi.FIELD_CAPACITY_MM);
    }

    @Test
    void rainIsInterceptedPerEventNotPerDay() {
        // Six millimetres over two consecutive days gets past the canopy. The same six split by a dry
        // day does not, because the allowance resets. Implementing this per day is the classic error.
        double[] temp = filled(3, 25);
        double consecutive = Kbdi.series(new double[]{3, 3, 0}, temp, ADELAIDE_ANNUAL_RAIN_MM, 100)[2];
        double split = Kbdi.series(new double[]{3, 0, 3}, temp, ADELAIDE_ANNUAL_RAIN_MM, 100)[2];
        assertThat(consecutive).isLessThan(split);
    }

    @Test
    void aHotDayDriesFasterThanAMildOne() {
        assertThat(Kbdi.step(100, 40, 0, ADELAIDE_ANNUAL_RAIN_MM))
                .isGreaterThan(Kbdi.step(100, 15, 0, ADELAIDE_ANNUAL_RAIN_MM));
    }

    @Test
    void aWetterClimateDriesFasterNotSlower() {
        // Counter-intuitive, and correct. Mean annual rainfall enters the Keetch-Byram equation as a
        // stand-in for vegetation density, and denser vegetation transpires more water, so a wetter
        // climate loses soil moisture faster per day than an arid one at the same temperature.
        // Reading that term as "wetter means slower" inverts the index everywhere it is used.
        assertThat(Kbdi.step(100, 30, 0, 1500)).isGreaterThan(Kbdi.step(100, 30, 0, 300));
    }

    @Test
    void theDroughtFactorIsZeroOnTheDayOfASoakingAndRecovers() {
        double[] today = new double[20];
        today[19] = 30;
        assertThat(Kbdi.droughtFactor(150, today)).isZero();

        double[] tenDaysAgo = new double[20];
        tenDaysAgo[9] = 30;
        assertThat(Kbdi.droughtFactor(150, tenDaysAgo)).isGreaterThan(5);
    }

    @Test
    void aBigRecentEventSuppressesTheFactorFarMoreThanASmallOne() {
        double[] heavy = new double[20];
        heavy[17] = 100;
        double[] light = new double[20];
        light[17] = 3;
        assertThat(Kbdi.droughtFactor(150, heavy)).isLessThan(Kbdi.droughtFactor(150, light));
    }

    @Test
    void rainBelowTheEventThresholdDoesNotCount() {
        double[] drizzle = new double[20];
        drizzle[18] = 1.5;
        assertThat(Kbdi.droughtFactor(150, drizzle)).isEqualTo(Kbdi.droughtFactor(150, new double[20]));
    }

    @Test
    void theFactorIsBoundedAndPeaksAtTheDryEnd() {
        double[] dry = new double[20];
        assertThat(Kbdi.droughtFactor(Kbdi.FIELD_CAPACITY_MM, dry)).isEqualTo(10.0);
        assertThat(Kbdi.droughtFactor(0, dry)).isBetween(0.0, 10.0);
    }

    @Test
    void wetGroundCannotProduceADryFactorHoweverLongSinceItRained() {
        // Finkele's ceiling. Without it, twenty dry days over saturated ground reads the same as twenty
        // dry days over a drought, which is the whole reason the deficit is integrated at all.
        double[] dry = new double[20];
        assertThat(Kbdi.droughtFactor(5, dry)).isLessThan(Kbdi.droughtFactor(150, dry));
        assertThat(Kbdi.limit(5)).isLessThan(1.0);
    }

    @Test
    void theBandsCoverTheWholeRange() {
        assertThat(Kbdi.band(0)).isEqualTo("SATURATED");
        assertThat(Kbdi.band(40)).isEqualTo("MOIST");
        assertThat(Kbdi.band(75)).isEqualTo("DRYING");
        assertThat(Kbdi.band(120)).isEqualTo("DRY");
        assertThat(Kbdi.band(170)).isEqualTo("VERY DRY");
        assertThat(Kbdi.band(200)).isEqualTo("SEVERE");
    }
}
