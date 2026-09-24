package au.gully.fire;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AFDRS grassland model against the published constants (AFDRS Fire Behaviour Index Technical
 * Guide — Grassland, v2024.6.0, §1.5) and the FBI table (AFDRS Technical User Guide, Table 7), with
 * one example worked by hand in the comments so a wrong constant is caught by arithmetic a reader can
 * repeat.
 */
class CsiroGrasslandTest {

    /**
     * 30 °C, 20 % RH, 30 km/h, fully cured, natural grass at 4.5 t/ha:
     * <pre>
     *   MC   = 9.58 - 0.205·30 + 0.138·20 = 6.19 %
     *   φM   = exp(-0.108·6.19)          = 0.5125
     *   φC   = 1.036 / (1 + 103.989 e^(-0.0996·80)) = 0.9998
     *   ROS  = 1000 (1.4 + 0.838·25^0.844) φM φC = 1000 · 14.08 · 0.5125 · 0.9998 = 7,214 m/h = 2.00 m/s
     *   I    = 18600 · 0.45 · 2.004 = 16,770 kW/m
     *   FBI  = 24 + (16770 - 9000) / (17500 - 9000) · 26 = 47.8 → 47, "High"
     * </pre>
     */
    @Test
    void theWorkedExampleReproduces() {
        assertThat(CsiroGrassland.moisturePct(30, 20)).isCloseTo(6.19, Offset.offset(0.01));
        assertThat(CsiroGrassland.moistureCoefficient(6.19, 30)).isCloseTo(0.5125, Offset.offset(0.001));
        assertThat(CsiroGrassland.curingCoefficient(100)).isCloseTo(0.9998, Offset.offset(0.001));
        double ros = CsiroGrassland.rateOfSpreadMh(CsiroGrassland.Condition.NATURAL, 30, 6.19, 100);
        assertThat(ros).isCloseTo(7_214, Offset.offset(30.0));
        double intensity = CsiroGrassland.intensityKwm(ros, 4.5);
        assertThat(intensity).isCloseTo(16_770, Offset.offset(80.0));
        assertThat(CsiroGrassland.fbi(intensity)).isCloseTo(47.8, Offset.offset(0.3));

        CsiroGrassland.Result r = CsiroGrassland.of(30.0, 20, 30.0, 100.0, 4.5, CsiroGrassland.Condition.NATURAL);
        assertThat(r.fbi()).isEqualTo(47);
        assertThat(r.rating()).isEqualTo("High");
        assertThat(r.rateOfSpreadKmh()).isCloseTo(7.21, Offset.offset(0.05));
    }

    @Test
    void theFbiTableHasRoundNumbersAtItsBoundaries() {
        assertThat(CsiroGrassland.fbi(0)).isEqualTo(0);
        assertThat(CsiroGrassland.fbi(100)).isEqualTo(6);
        assertThat(CsiroGrassland.fbi(3_000)).isEqualTo(12);
        assertThat(CsiroGrassland.fbi(9_000)).isEqualTo(24);
        assertThat(CsiroGrassland.fbi(17_500)).isEqualTo(50);
        assertThat(CsiroGrassland.fbi(25_000)).isEqualTo(100);
        // Kilmore East, Black Saturday: 90,000 kW/m anchors 200.
        assertThat(CsiroGrassland.fbi(90_000)).isEqualTo(200);
        assertThat(CsiroGrassland.fbi(1_550)).isCloseTo(9.0, Offset.offset(0.01));
    }

    @Test
    void theRatingsFollowThePublishedThresholdsAfterFlooring() {
        assertThat(CsiroGrassland.afdrs(11.9)).isEqualTo("No Rating");
        assertThat(CsiroGrassland.afdrs(12)).isEqualTo("Moderate");
        assertThat(CsiroGrassland.afdrs(23.999)).isEqualTo("Moderate");
        assertThat(CsiroGrassland.afdrs(24)).isEqualTo("High");
        assertThat(CsiroGrassland.afdrs(50)).isEqualTo("Extreme");
        assertThat(CsiroGrassland.afdrs(100)).isEqualTo("Catastrophic");
        assertThat(CsiroGrassland.afdrs(250)).isEqualTo("Catastrophic");
    }

    @Test
    void grazedAndEatenOutSpreadSlowerThanNaturalAndEatenOutIsHalfGrazedInWind() {
        double natural = CsiroGrassland.rateOfSpreadMh(CsiroGrassland.Condition.NATURAL, 30, 6, 100);
        double grazed = CsiroGrassland.rateOfSpreadMh(CsiroGrassland.Condition.GRAZED, 30, 6, 100);
        double eaten = CsiroGrassland.rateOfSpreadMh(CsiroGrassland.Condition.EATEN_OUT, 30, 6, 100);
        assertThat(grazed).isLessThan(natural);
        // 0.357 against 0.715 / 2 = 0.3575: the published constants round the half.
        assertThat(eaten).isCloseTo(grazed / 2, Offset.offset(grazed / 2 * 0.005));
    }

    @Test
    void theWindRelationIsLinearBelowFiveAndAPowerAbove() {
        double at4 = CsiroGrassland.rateOfSpreadMh(CsiroGrassland.Condition.NATURAL, 4, 6, 100);
        double at5 = CsiroGrassland.rateOfSpreadMh(CsiroGrassland.Condition.NATURAL, 5, 6, 100);
        double at6 = CsiroGrassland.rateOfSpreadMh(CsiroGrassland.Condition.NATURAL, 6, 6, 100);
        assertThat(at5).isGreaterThan(at4);
        assertThat(at6).isGreaterThan(at5);
        // At 5 km/h the linear form gives 0.054 + 0.269·5 = 1.399 and the power form 1.4: the two meet.
        assertThat(1000 * (0.054 + 0.269 * 5)).isCloseTo(1000 * 1.4, Offset.offset(2.0));
    }

    @Test
    void wetGrassAndGreenGrassDoNotCarryFire() {
        assertThat(CsiroGrassland.moistureCoefficient(21, 20)).isZero();
        assertThat(CsiroGrassland.curingCoefficient(19)).isZero();
        assertThat(CsiroGrassland.moisturePct(10, 95)).as("floored at 5").isGreaterThanOrEqualTo(5);
        assertThat(CsiroGrassland.moisturePct(45, 5)).isEqualTo(5.0);
    }

    @Test
    void theMoistureCoefficientChangesSlopeWithTheWindAboveTwelvePerCent() {
        assertThat(CsiroGrassland.moistureCoefficient(15, 5)).isCloseTo(0.684 - 0.0342 * 15, Offset.offset(1e-9));
        assertThat(CsiroGrassland.moistureCoefficient(15, 20)).isCloseTo(0.547 - 0.0228 * 15, Offset.offset(1e-9));
    }

    @Test
    void conditionIsInferredFromTheLoadAsAfdrsDoes() {
        assertThat(CsiroGrassland.Condition.fromLoad(6)).isEqualTo(CsiroGrassland.Condition.NATURAL);
        assertThat(CsiroGrassland.Condition.fromLoad(4.5)).isEqualTo(CsiroGrassland.Condition.GRAZED);
        assertThat(CsiroGrassland.Condition.fromLoad(2)).isEqualTo(CsiroGrassland.Condition.EATEN_OUT);
        assertThat(CsiroGrassland.Condition.parse("eaten-out")).isEqualTo(CsiroGrassland.Condition.EATEN_OUT);
        assertThat(CsiroGrassland.Condition.parse(null)).isEqualTo(CsiroGrassland.Condition.GRAZED);
    }

    @Test
    void aMissingInputProducesNoResult() {
        assertThat(CsiroGrassland.of(null, 20, 30.0, 100.0, 4.5, null)).isNull();
        assertThat(CsiroGrassland.of(30.0, 20, 30.0, null, 4.5, null)).isNull();
    }

    @Test
    void flameHeightGrowsWithSpreadAndIsTallerInNaturalGrass() {
        assertThat(CsiroGrassland.flameHeightM(CsiroGrassland.Condition.NATURAL, 7_200))
                .isGreaterThan(CsiroGrassland.flameHeightM(CsiroGrassland.Condition.GRAZED, 7_200))
                .isGreaterThan(CsiroGrassland.flameHeightM(CsiroGrassland.Condition.NATURAL, 1_000));
    }
}
