package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WindMeanTest {

    static Observation ob(int minutesAgo, Double kmh, Integer deg, Double gust) {
        return new Observation("x", Instant.parse("2026-09-22T00:00:00Z").minusSeconds(minutesAgo * 60L), null, null, null, null, kmh, deg, null, gust,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void theMeanIsOverTheNewestFiveAndTheDirectionIsAVector() {
        // Newest first, as the register keeps them: six readings, the sixth ignored.
        List<Observation> six = List.of(ob(0, 20.0, 350, 30.0), ob(10, 10.0, 10, null), ob(20, 15.0, 0, 20.0), ob(30, 15.0, 0, 25.0), ob(40, 20.0, 10, 35.0), ob(50, 90.0, 180, 99.0));
        WindMean w = WindMean.of(six, 5);
        assertThat(w.readings()).isEqualTo(5);
        assertThat(w.kmh()).isEqualTo(16.0);
        assertThat(w.deg()).as("350 and 10 round to north").isIn(0, 358, 359, 360, 1, 2);
        assertThat(w.gustKmh()).isEqualTo(27.5);
        assertThat(w.minutes()).isEqualTo(40);
    }

    @Test
    void readingsWithoutASpeedAreSkippedAndNoneMeansNoMean() {
        assertThat(WindMean.of(List.of(ob(0, null, 90, null), ob(10, null, 90, null)), 5)).isNull();
        WindMean w = WindMean.of(List.of(ob(0, null, 90, null), ob(10, 12.0, 90, null)), 5);
        assertThat(w.readings()).isEqualTo(1);
        assertThat(w.deg()).isEqualTo(90);
        // Calm readings carry no direction into the vector.
        WindMean calm = WindMean.of(List.of(ob(0, 0.0, 45, null), ob(10, 0.0, 200, null)), 5);
        assertThat(calm.kmh()).isEqualTo(0.0);
        assertThat(calm.deg()).isNull();
    }
}
