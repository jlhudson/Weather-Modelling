package au.gully.fire;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * McArthur Mk5 grassland, Noble, Bary and Gill (1980), checked against a value worked by hand from the
 * published equations rather than recalled. Moved here from The Hub with the meter (docs/06 item 4).
 */
class GrassFireDangerTest {

    /**
     * The curing that gives moisture {@code m} at this temperature and humidity: the equation solved for C.
     */
    private static double curingFor(double m, double t, double h) {
        double rest = (97.7 + 4.06 * h) / (t + 6) - 0.00854 * h - 30;
        return 3000.0 / (m - rest);
    }

    @Test
    void moistureWorkedByHand() {
        // T 35, H 20, C 90:
        //   (97.7 + 4.06 x 20) / (35 + 6) = 178.9 / 41 = 4.3634
        //   - 0.00854 x 20 = -0.1708 ; + 3000 / 90 = 33.333 ; - 30
        //   = 4.3634 - 0.1708 + 33.333 - 30 = 7.526
        assertThat(GrassFireDanger.moisture(35, 20, 90)).isCloseTo(7.526, offset(0.005));
    }

    @Test
    void indexWorkedByHandOnTheDryBranch() {
        // M = 7.526 (above), W 4.5, V 30:
        //   F = 3.35 x 4.5 x exp(-0.0897 x 7.526 + 0.0403 x 30)
        //     = 15.075 x exp(-0.6751 + 1.209) = 15.075 x exp(0.5339) = 15.075 x 1.7056 = 25.71
        assertThat(GrassFireDanger.gfdi(35, 20, 30, 90, 4.5)).isCloseTo(25.71, offset(0.05));
        assertThat(GrassFireDanger.rating(25.71)).isEqualTo("VERY HIGH");
    }

    @Test
    void theTwoBranchesMeetAtTheBreak() {
        // At M = 18.8 the two forms must agree, or the meter would jump as the grass dries by a hair.
        double w = 4.5;
        double v = 20;
        double dry = 3.35 * w * Math.exp(-0.0897 * 18.8 + 0.0403 * v);
        double damp = 0.299 * w * Math.exp(-1.686 + 0.0403 * v) * (30 - 18.8);
        // 6.2506 both ways: the published constants were chosen so the two forms join without a step.
        assertThat(damp / dry).isCloseTo(1.0, offset(0.001));
        // And the meter itself walks across the break without a jump: 18.79 and 18.81 per cent moisture
        // answer within a hair of each other. Reached by nudging the curing, which is what moves M.
        double justDry = GrassFireDanger.gfdi(20, 40, v, curingFor(18.79, 20, 40), w);
        double justDamp = GrassFireDanger.gfdi(20, 40, v, curingFor(18.81, 20, 40), w);
        assertThat(justDamp / justDry).isCloseTo(1.0, offset(0.01));
    }

    @Test
    void wetGrassDoesNotBurnAndGreenGrassBarely() {
        // Cool, humid, half cured: moisture well past 30 per cent.
        assertThat(GrassFireDanger.gfdi(12, 90, 5, 50, 4.5)).isZero();
        // The same weather at 100 per cent curing is still a tiny index, not zero.
        assertThat(GrassFireDanger.gfdi(35, 20, 30, 100, 4.5)).isGreaterThan(GrassFireDanger.gfdi(35, 20, 30, 60, 4.5));
    }

    @Test
    void fuelLoadScalesTheIndexLinearly() {
        double at45 = GrassFireDanger.gfdi(35, 20, 30, 90, 4.5);
        double at9 = GrassFireDanger.gfdi(35, 20, 30, 90, 9.0);
        assertThat(at9 / at45).isCloseTo(2.0, offset(1e-9));
    }

    @Test
    void grassRunsToCatastrophicAt150NotAt100() {
        assertThat(GrassFireDanger.rating(11.9)).isEqualTo("LOW-MODERATE");
        assertThat(GrassFireDanger.rating(12)).isEqualTo("HIGH");
        assertThat(GrassFireDanger.rating(49.9)).isEqualTo("VERY HIGH");
        assertThat(GrassFireDanger.rating(99)).isEqualTo("SEVERE");
        assertThat(GrassFireDanger.rating(100)).isEqualTo("EXTREME");
        assertThat(GrassFireDanger.rating(150)).isEqualTo("CATASTROPHIC");
    }

    @Test
    void spreadIsThirteenPerCentOfTheIndexDoublingEveryTenDegreesUphill() {
        assertThat(GrassFireDanger.spreadKmh(50)).isCloseTo(6.5, offset(1e-9));
        assertThat(GrassFireDanger.slopeFactor(10)).isCloseTo(2.0, offset(0.01));
        assertThat(GrassFireDanger.slopeFactor(-10)).isCloseTo(0.5, offset(0.01));
        assertThat(GrassFireDanger.slopeFactor(0)).isEqualTo(1.0);
        // Clamped: a cliff is not a fire behaviour input.
        assertThat(GrassFireDanger.slopeFactor(60)).isEqualTo(GrassFireDanger.slopeFactor(20));
    }

    @Test
    void aFireTravelsWithTheWind() {
        assertThat(GrassFireDanger.spreadDirectionDeg(0)).isEqualTo(180);
        assertThat(GrassFireDanger.spreadDirectionDeg(315)).isEqualTo(135);
        assertThat(GrassFireDanger.spreadDirectionDeg(180)).isEqualTo(0);
    }

    @Test
    void aMissingInputIsNoIndexNotAnAssumedOne() {
        assertThat(GrassFireDanger.of(null, 20, 30.0, 90.0, 4.5)).isNull();
        assertThat(GrassFireDanger.of(35.0, 20, 30.0, null, 4.5)).isNull();
        assertThat(GrassFireDanger.of(35.0, 20, 30.0, 0.0, 4.5)).isNull();
        assertThat(GrassFireDanger.of(35.0, 20, 30.0, 90.0, 4.5)).isCloseTo(25.7, offset(0.05));
    }

}
