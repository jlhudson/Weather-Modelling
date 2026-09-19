package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A wind change in a station's last readings (W-16): the swing graded by degrees, the speed change by
 * km/h, the two together by the higher; a calm not compared; the widest change in the window kept.
 */
class WindShiftTest {

    private static final Instant T = Instant.parse("2026-09-19T05:00:00Z");

    private static Observation at(int minutesAgo, Double kmh, Integer deg) {
        return new Observation("023000", T.minusSeconds(minutesAgo * 60L), 24.0, null, null, 30, kmh, deg, null, null, 1015.0, 0.0, null, null, null, null, null, null, null);
    }

    @Test
    void theSouthWesterlyBehindANortherlyIsASharpChange() {
        // Newest first: a 35 km/h northerly an hour ago backing to a 30 km/h south-westerly now.
        List<Observation> recent = List.of(at(0, 30.0, 225), at(10, 28.0, 240), at(20, 20.0, 300), at(30, 25.0, 350), at(40, 32.0, 355), at(50, 35.0, 0));
        WindShift w = WindShift.of(recent).orElseThrow();
        assertThat(w.grade()).isEqualTo("sharp");
        assertThat(w.swingGrade()).isEqualTo("sharp");
        assertThat(w.swingDeg()).isEqualTo(135);
        assertThat(w.fromDeg()).isEqualTo(0);
        assertThat(w.toDeg()).isEqualTo(225);
        assertThat(w.overMinutes()).isEqualTo(50);
        assertThat(w.speedGrade()).isNull();
        assertThat(w.deltaKmh()).isEqualTo(-5.0);
        assertThat(w.describe()).isEqualTo("sharp change: 0° → 225° (135°), over 50 min");
    }

    @Test
    void aFresheningWithoutASwingIsGradedOnSpeed() {
        List<Observation> recent = List.of(at(0, 38.0, 320), at(10, 30.0, 315), at(20, 22.0, 318), at(30, 15.0, 322));
        WindShift w = WindShift.of(recent).orElseThrow();
        assertThat(w.grade()).isEqualTo("marked");
        assertThat(w.swingGrade()).isNull();
        assertThat(w.speedGrade()).isEqualTo("marked");
        assertThat(w.deltaKmh()).isEqualTo(23.0);
        assertThat(w.describe()).isEqualTo("marked change: 15 → 38 km/h (+23), over 30 min");
    }

    @Test
    void bothTogetherTakeTheHigherGrade() {
        List<Observation> recent = List.of(at(0, 45.0, 270), at(20, 12.0, 230));
        WindShift w = WindShift.of(recent).orElseThrow();
        assertThat(w.swingGrade()).isEqualTo("slight");
        assertThat(w.speedGrade()).isEqualTo("sharp");
        assertThat(w.grade()).isEqualTo("sharp");
        assertThat(w.describe()).isEqualTo("sharp change: 230° → 270° (40°), 12 → 45 km/h (+33), over 20 min");
    }

    @Test
    void aVaneInACalmIsNotAChangeAndASteadyWindIsNothing() {
        // 5 km/h swinging 150°: the vane is wandering, not the wind.
        assertThat(WindShift.of(List.of(at(0, 5.0, 100), at(10, 4.0, 250)))).isEmpty();
        // 20 km/h holding within 20° and 5 km/h: nothing to say.
        assertThat(WindShift.of(List.of(at(0, 20.0, 100), at(10, 22.0, 110), at(20, 18.0, 95)))).isEmpty();
        // One reading is no window.
        assertThat(WindShift.of(List.of(at(0, 20.0, 100)))).isEmpty();
        // The short way round: 350° to 20° is 30°, slight.
        assertThat(WindShift.angle(350, 20)).isEqualTo(30);
        assertThat(WindShift.of(List.of(at(0, 20.0, 20), at(10, 20.0, 350))).orElseThrow().grade()).isEqualTo("slight");
    }
}
