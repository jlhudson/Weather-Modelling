package au.weather.core;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The McArthur arithmetic, checked against a value computed by hand from the published equation, and
 * the forecast projection built on top of it. This is the only number in the weather feature a person
 * reads and acts on, so it is the one worth pinning.
 */
class FireDangerTest {

    @Test
    void ffdiMatchesTheNobleEquationWorkedByHand() {
        // 35 C, 20 % RH, 30 km/h, drought factor 10:
        // 2.0 exp(-0.450 + 0.987 ln 10 - 0.0345(20) + 0.0338(35) + 0.0234(30)) = 2.0 exp(3.0177) = 40.9
        assertThat(FireDanger.ffdi(35, 20, 30, 10)).isCloseTo(40.9, Offset.offset(0.2));
    }

    @Test
    void ffdiCrossesIntoCatastrophicOnAFireWeatherDay() {
        double index = FireDanger.ffdi(40, 10, 50, 10);
        assertThat(index).isGreaterThan(100);
        assertThat(FireDanger.rating(index)).isEqualTo("CATASTROPHIC");
    }

    @Test
    void aMildWetMorningIsLowToModerate() {
        // The conditions Open-Meteo actually returned over Adelaide on 5 September 2026.
        double index = FireDanger.ffdi(14.7, 62, 31.8, 8);
        assertThat(index).isLessThan(12);
        assertThat(FireDanger.rating(index)).isEqualTo("LOW-MODERATE");
    }

    @Test
    void ratingBandsAreTheClassicSix() {
        assertThat(FireDanger.rating(5)).isEqualTo("LOW-MODERATE");
        assertThat(FireDanger.rating(12)).isEqualTo("HIGH");
        assertThat(FireDanger.rating(25)).isEqualTo("VERY HIGH");
        assertThat(FireDanger.rating(50)).isEqualTo("SEVERE");
        assertThat(FireDanger.rating(75)).isEqualTo("EXTREME");
        assertThat(FireDanger.rating(100)).isEqualTo("CATASTROPHIC");
    }

    @Test
    void ffdiRisesWithHeatAndWindAndFallsWithHumidity() {
        double base = FireDanger.ffdi(30, 30, 20, 8);
        assertThat(FireDanger.ffdi(35, 30, 20, 8)).isGreaterThan(base);
        assertThat(FireDanger.ffdi(30, 30, 40, 8)).isGreaterThan(base);
        assertThat(FireDanger.ffdi(30, 60, 20, 8)).isLessThan(base);
    }

    @Test
    void ffdiRisesWithTheDroughtFactor() {
        // The point of the whole spin-up: this input moves the answer by a factor of five across its range.
        assertThat(FireDanger.ffdi(35, 20, 30, 10)).isGreaterThan(4 * FireDanger.ffdi(35, 20, 30, 2));
    }

    @Test
    void aDroughtFactorOfZeroDoesNotProduceNegativeInfinity() {
        assertThat(FireDanger.ffdi(35, 20, 30, 0)).isFinite().isPositive();
    }

    @Test
    void noIndexIsProducedWhenAnInputIsMissing() {
        Conditions withoutHumidity = Conditions.at(Instant.now()).temperature(35.0).wind(30.0).build();
        assertThat(withoutHumidity.fireInputsPresent()).isFalse();
        assertThat(FireDanger.of(withoutHumidity, 8)).isNull();

        Conditions complete = Conditions.at(Instant.now()).temperature(35.0).humidity(20).wind(30.0).build();
        assertThat(complete.fireInputsPresent()).isTrue();
        assertThat(FireDanger.of(complete, 8)).isPositive();
    }

    @Test
    void theOutlookCarriesTheDeficitForwardThroughForecastRain() {
        LocalDate day = LocalDate.of(2026, 9, 5);
        List<FireDanger.FireDay> dry = List.of(
                new FireDanger.FireDay(day, 38.0, 12, 40.0, 60.0, 0.0),
                new FireDanger.FireDay(day.plusDays(1), 39.0, 10, 45.0, 70.0, 0.0),
                new FireDanger.FireDay(day.plusDays(2), 40.0, 9, 50.0, 75.0, 0.0));
        List<FireDanger.FireDay> wet = List.of(
                new FireDanger.FireDay(day, 38.0, 12, 40.0, 60.0, 0.0),
                new FireDanger.FireDay(day.plusDays(1), 39.0, 10, 45.0, 70.0, 40.0),
                new FireDanger.FireDay(day.plusDays(2), 40.0, 9, 50.0, 75.0, 0.0));

        List<Double> history = new java.util.ArrayList<>(java.util.Collections.nCopies(20, 0.0));
        List<FireOutlook> dryOutlook = FireDanger.outlook(dry, 150, 550, history);
        List<FireOutlook> wetOutlook = FireDanger.outlook(wet, 150, 550, history);

        assertThat(dryOutlook).hasSize(3);
        // Forty millimetres on day two has to show up on day three, or the projection is decorative.
        assertThat(wetOutlook.get(2).kbdiMm()).isLessThan(dryOutlook.get(2).kbdiMm());
        assertThat(wetOutlook.get(2).droughtFactor()).isLessThan(dryOutlook.get(2).droughtFactor());
        assertThat(wetOutlook.get(2).ffdi()).isLessThan(dryOutlook.get(2).ffdi());
    }

    @Test
    void aDryHotForecastRatesSevereOrWorse() {
        LocalDate day = LocalDate.of(2026, 1, 20);
        List<FireOutlook> outlook = FireDanger.outlook(
                List.of(new FireDanger.FireDay(day, 42.0, 8, 45.0, 70.0, 0.0)),
                190, 550, new java.util.ArrayList<>(java.util.Collections.nCopies(20, 0.0)));
        assertThat(outlook).hasSize(1);
        assertThat(outlook.getFirst().ffdi()).isGreaterThan(75);
        assertThat(outlook.getFirst().ffdiRating()).isIn("EXTREME", "CATASTROPHIC");
        assertThat(outlook.getFirst().droughtFactor()).isGreaterThan(9);
    }

    @Test
    void anOutlookDayMissingAnInputCarriesNoIndexButStillCarriesTheDeficit() {
        LocalDate day = LocalDate.of(2026, 9, 5);
        List<FireOutlook> outlook = FireDanger.outlook(
                List.of(new FireDanger.FireDay(day, 30.0, null, 20.0, null, 0.0)),
                100, 550, new java.util.ArrayList<>(java.util.Collections.nCopies(20, 0.0)));
        assertThat(outlook.getFirst().ffdi()).isNull();
        assertThat(outlook.getFirst().ffdiRating()).isNull();
        assertThat(outlook.getFirst().kbdiMm()).isNotNull();
    }
}
