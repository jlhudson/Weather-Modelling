package au.gully.fire;

import au.gully.upstreams.Conditions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

class WindChangeTest {

    static final Instant NOW = Instant.parse("2026-12-10T00:10:00Z");

    static List<Conditions> hours(IntFunction<Conditions.Builder> hour) {
        List<Conditions> out = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            out.add(hour.apply(i).build());
        }
        return out;
    }

    static Instant at(int i) {
        return Instant.parse("2026-12-10T00:00:00Z").plusSeconds(3600L * i);
    }

    @Test
    void aHotNortherlySwingingSouthWesterlyIsACoolChangeAtItsHour() {
        // North-west at 35 km/h and 40 °C until hour 9; from hour 10 south-west at 45 km/h, gusting 70, and 28 °C.
        List<Conditions> h = hours(i -> i < 10
                ? Conditions.at(at(i)).windDirection(315).wind(35.0).gust(50.0).temperature(40.0)
                : Conditions.at(at(i)).windDirection(225).wind(45.0).gust(70.0).temperature(28.0));
        List<WindChange.Change> changes = WindChange.find(h, NOW);
        assertThat(changes).hasSize(1);
        WindChange.Change c = changes.getFirst();
        assertThat(c.at()).isEqualTo(at(10));
        assertThat(c.fromDeg()).isEqualTo(315);
        assertThat(c.toDeg()).isEqualTo(225);
        assertThat(c.swingDeg()).isEqualTo(90);
        assertThat(c.speedAfterKmh()).isEqualTo(45.0);
        assertThat(c.gustAfterKmh()).isEqualTo(70.0);
        assertThat(c.coolsC()).isEqualTo(12.0);
        assertThat(c.cool()).isTrue();
        assertThat(c.hoursAway()).isEqualTo(9.8);
    }

    @Test
    void aSteadyWindAndACalmOneChangeNothing() {
        assertThat(WindChange.find(hours(i -> Conditions.at(at(i)).windDirection(200 + (i % 3) * 5).wind(25.0).temperature(20.0)), NOW)).isEmpty();
        // A swing in near calm is no change: the air after barely moves.
        assertThat(WindChange.find(hours(i -> Conditions.at(at(i)).windDirection(i < 10 ? 0 : 180).wind(5.0).temperature(20.0)), NOW)).isEmpty();
    }

    @Test
    void aSwingThatWarmsIsAChangeButNotACoolOne() {
        // South-westerly backing north-easterly and warming: a change to watch, not the cool change.
        List<Conditions> h = hours(i -> i < 20
                ? Conditions.at(at(i)).windDirection(225).wind(20.0).temperature(22.0)
                : Conditions.at(at(i)).windDirection(45).wind(30.0).temperature(26.0));
        List<WindChange.Change> changes = WindChange.find(h, NOW);
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().cool()).isFalse();
        assertThat(changes.getFirst().coolsC()).isEqualTo(-4.0);
    }

    @Test
    void onlyTheNextTwoDaysAreSearched() {
        // A change at hour 55 is beyond forty-eight hours from now.
        List<Conditions> h = hours(i -> Conditions.at(at(i)).windDirection(i < 55 ? 315 : 225).wind(35.0).temperature(i < 55 ? 35.0 : 25.0));
        assertThat(WindChange.find(h, NOW)).isEmpty();
    }
}
