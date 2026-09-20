package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mean of the readings before the latest against the latest: a vector mean of direction so the
 * wrap at north does not put the average behind the station, plain means of speed and gust, a calm
 * left out of the direction, and nothing with fewer than two readings.
 */
class WindTrendTest {

    private static final Instant T = Instant.parse("2026-09-19T05:00:00Z");

    private static Observation at(int minutesAgo, Double kmh, Integer deg, Double gust) {
        return new Observation("023000", T.minusSeconds(minutesAgo * 60L), 24.0, null, null, 30, kmh, deg, null, gust, 1015.0, 0.0, null, null, null, null, null, null, null);
    }

    @Test
    void theMeanOfTheFiveBeforeAgainstTheLatest() {
        // A steady 25 km/h northerly for fifty minutes, then a 30 km/h south-westerly.
        List<Observation> recent = List.of(at(0, 30.0, 225, 45.0), at(10, 24.0, 355, 35.0), at(20, 26.0, 0, 38.0), at(30, 25.0, 5, 36.0), at(40, 24.0, 350, 34.0), at(50, 26.0, 10, 37.0));
        WindTrend t = WindTrend.of(recent).orElseThrow();
        assertThat(t.readings()).isEqualTo(5);
        assertThat(t.overMinutes()).isEqualTo(50);
        assertThat(t.meanDeg()).isEqualTo(0);
        assertThat(t.meanKmh()).isEqualTo(25.0);
        assertThat(t.meanGust()).isEqualTo(36.0);
        assertThat(t.latestDeg()).isEqualTo(225);
        assertThat(t.latestKmh()).isEqualTo(30.0);
        assertThat(t.latestGust()).isEqualTo(45.0);
        assertThat(t.swingDeg()).isEqualTo(135);
        assertThat(t.deltaKmh()).isEqualTo(5.0);
        assertThat(t.at()).isEqualTo(T);
    }

    @Test
    void theMeanDirectionIsAVectorMeanAcrossNorth() {
        // 350° and 10° average to north, not to 180°.
        WindTrend t = WindTrend.of(List.of(at(0, 20.0, 90, null), at(10, 20.0, 350, null), at(20, 20.0, 10, null))).orElseThrow();
        assertThat(t.meanDeg()).isEqualTo(0);
        assertThat(t.swingDeg()).isEqualTo(90);
    }

    @Test
    void aCalmReadingCountsForSpeedButNotDirection() {
        WindTrend t = WindTrend.of(List.of(at(0, 20.0, 180, null), at(10, 3.0, 45, null), at(20, 20.0, 180, null))).orElseThrow();
        assertThat(t.readings()).isEqualTo(2);
        assertThat(t.meanDeg()).isEqualTo(180);
        assertThat(t.meanKmh()).isEqualTo(11.5);
        assertThat(t.swingDeg()).isEqualTo(0);
    }

    @Test
    void aCalmLatestHasNoSwing() {
        WindTrend t = WindTrend.of(List.of(at(0, 4.0, 90, null), at(10, 20.0, 180, null))).orElseThrow();
        assertThat(t.meanDeg()).isEqualTo(180);
        assertThat(t.swingDeg()).isNull();
        assertThat(t.deltaKmh()).isEqualTo(-16.0);
    }

    @Test
    void oneReadingIsNoTrend() {
        assertThat(WindTrend.of(List.of(at(0, 20.0, 90, null)))).isEmpty();
        assertThat(WindTrend.of(List.of())).isEmpty();
        assertThat(WindTrend.of(null)).isEmpty();
    }

    @Test
    void readingsWithoutADirectionLeaveTheMeanDirectionNull() {
        WindTrend t = WindTrend.of(List.of(at(0, 20.0, 90, null), at(10, 18.0, null, null), at(20, 22.0, null, null))).orElseThrow();
        assertThat(t.meanDeg()).isNull();
        assertThat(t.meanKmh()).isEqualTo(20.0);
        assertThat(t.swingDeg()).isNull();
        assertThat(t.deltaKmh()).isEqualTo(0.0);
    }
}
