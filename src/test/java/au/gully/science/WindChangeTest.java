package au.gully.science;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static au.gully.science.WindChange.WIND_CHANGE_MIN_SPEED_KMH;
import static au.gully.science.WindChange.WIND_CHANGE_TURN_DEG;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wind-change rule (docs/24 G6), pinned at its edges: the turn threshold, the speed floor, the
 * short way round the compass, the start of the series and the end of the window. A rule a crew acts
 * on has to be exactly the rule the Javadoc states, so the boundaries are asserted on both sides.
 */
class WindChangeTest {

    private static final Instant T0 = Instant.parse("2026-09-07T02:00:00Z");

    private static Conditions hour(int h, Integer fromDeg, Double speedKmh) {
        return Conditions.at(T0.plus(Duration.ofHours(h))).windDirection(fromDeg).wind(speedKmh)
                .gust(speedKmh == null ? null : speedKmh + 10).build();
    }

    /** {@code n} hourly rows from {@code T0}, the direction and speed of each taken from the arrays. */
    private static List<Conditions> series(int[] directions, double[] speeds) {
        List<Conditions> out = new ArrayList<>();
        for (int h = 0; h < directions.length; h++) {
            out.add(hour(h, directions[h], speeds[h]));
        }
        return out;
    }

    private static int[] fill(int n, int value) {
        int[] out = new int[n];
        Arrays.fill(out, value);
        return out;
    }

    private static double[] fill(int n, double value) {
        double[] out = new double[n];
        Arrays.fill(out, value);
        return out;
    }

    @Test
    void theFirstQualifyingHourIsTheAnswerNotTheBiggestTurn() {
        // North-westerly for six hours, south-westerly from hour six, southerly from hour twelve.
        int[] dir = fill(16, 315);
        Arrays.fill(dir, 6, 12, 225);
        Arrays.fill(dir, 12, 16, 180);
        double[] speed = fill(16, 25.0);
        Arrays.fill(speed, 6, 16, 30.0);

        WindChange change = WindChange.find(series(dir, speed), 48);

        assertThat(change).isNotNull();
        assertThat(change.at()).isEqualTo(T0.plus(Duration.ofHours(6)));
        assertThat(change.fromDeg()).as("the direction three hours earlier").isEqualTo(315);
        assertThat(change.toDeg()).isEqualTo(225);
        assertThat(change.speedKmh()).isEqualTo(30.0);
        assertThat(change.gustKmh()).isEqualTo(40.0);
    }

    @Test
    void aTurnOfExactlyTheThresholdIsNotAChangeAndOneDegreeMoreIs() {
        int[] onThreshold = fill(8, 0);
        Arrays.fill(onThreshold, 3, 8, WIND_CHANGE_TURN_DEG);
        assertThat(WindChange.find(series(onThreshold, fill(8, 20.0)), 48)).isNull();

        int[] pastIt = fill(8, 0);
        Arrays.fill(pastIt, 3, 8, WIND_CHANGE_TURN_DEG + 1);
        WindChange change = WindChange.find(series(pastIt, fill(8, 20.0)), 48);
        assertThat(change).isNotNull();
        assertThat(change.at()).isEqualTo(T0.plus(Duration.ofHours(3)));
    }

    @Test
    void aTurnBelowTheSpeedFloorIsWeatherNotAChange() {
        // A ninety-degree swing at 14.9 km/h is a calm afternoon wandering; at the floor it counts.
        int[] dir = fill(8, 315);
        Arrays.fill(dir, 3, 8, 225);
        double[] tooLight = fill(8, WIND_CHANGE_MIN_SPEED_KMH - 0.1);
        assertThat(WindChange.find(series(dir, tooLight), 48)).isNull();

        double[] reachesTheFloorAtFive = tooLight.clone();
        reachesTheFloorAtFive[5] = WIND_CHANGE_MIN_SPEED_KMH;
        WindChange change = WindChange.find(series(dir, reachesTheFloorAtFive), 48);
        assertThat(change).isNotNull();
        assertThat(change.at()).as("the first hour that is both turned and strong enough").isEqualTo(T0.plus(Duration.ofHours(5)));
        assertThat(change.fromDeg()).as("hour two, three hours before hour five").isEqualTo(315);
        assertThat(change.speedKmh()).isEqualTo(15.0);
    }

    /**
     * 350 to 20 is a turn of thirty degrees, not three hundred and thirty. Measured the naive way every
     * northerly that wobbles across true north would be a wind change.
     */
    @Test
    void theTurnIsMeasuredTheShortWayRoundTheCompass() {
        assertThat(WindChange.turn(350, 20)).isEqualTo(30);
        assertThat(WindChange.turn(20, 350)).isEqualTo(30);
        assertThat(WindChange.turn(10, 350)).isEqualTo(20);
        assertThat(WindChange.turn(0, 180)).isEqualTo(180);
        assertThat(WindChange.turn(90, 270)).isEqualTo(180);
        assertThat(WindChange.turn(0, 0)).isZero();
        assertThat(WindChange.turn(359, 0)).isEqualTo(1);

        int[] wobble = fill(8, 350);
        Arrays.fill(wobble, 3, 8, 20);
        assertThat(WindChange.find(series(wobble, fill(8, 25.0)), 48)).as("thirty degrees across north").isNull();

        int[] swing = fill(8, 350);
        Arrays.fill(swing, 3, 8, 40);
        WindChange change = WindChange.find(series(swing, fill(8, 25.0)), 48);
        assertThat(change).as("fifty degrees across north").isNotNull();
        assertThat(change.fromDeg()).isEqualTo(350);
        assertThat(change.toDeg()).isEqualTo(40);
    }

    /**
     * A front due in two hours has no row three hours before it. The start of the series stands in,
     * because a change that big over a shorter window is a change over three hours as well, and a rule
     * that could never report the next two hours would miss the one change that matters most.
     */
    @Test
    void aFrontInTheFirstHoursIsReadAgainstTheStartOfTheSeries() {
        WindChange change = WindChange.find(series(new int[]{315, 315, 225, 225}, fill(4, 25.0)), 48);
        assertThat(change).isNotNull();
        assertThat(change.at()).isEqualTo(T0.plus(Duration.ofHours(2)));
        assertThat(change.fromDeg()).isEqualTo(315);
    }

    @Test
    void nothingBeyondTheWindowCounts() {
        int[] dir = fill(60, 315);
        Arrays.fill(dir, 48, 60, 225);
        List<Conditions> series = series(dir, fill(60, 25.0));
        assertThat(WindChange.find(series, 48)).as("hour 48 is the first outside a 48-hour window").isNull();
        WindChange change = WindChange.find(series, 49);
        assertThat(change).isNotNull();
        assertThat(change.at()).isEqualTo(T0.plus(Duration.ofHours(48)));
    }

    /**
     * Where a series steps six-hourly the row three hours back does not exist. The hour is skipped
     * rather than compared with the row six hours back, which would measure a different thing.
     */
    @Test
    void aGapInTheSeriesIsSkippedRatherThanComparedAcross() {
        List<Conditions> gapped = List.of(hour(0, 315, 25.0), hour(1, 315, 25.0), hour(2, 315, 25.0), hour(8, 225, 30.0), hour(14, 225, 30.0));
        assertThat(WindChange.find(gapped, 48)).isNull();
    }

    @Test
    void rowsWithoutADirectionOrASpeedAreIgnoredAndAnEmptySeriesHasNoChange() {
        assertThat(WindChange.find(null, 48)).isNull();
        assertThat(WindChange.find(List.of(), 48)).isNull();
        List<Conditions> blind = List.of(hour(0, 315, 25.0), hour(1, null, 25.0), hour(2, 315, null), hour(3, 225, 30.0));
        WindChange change = WindChange.find(blind, 48);
        assertThat(change).isNotNull();
        assertThat(change.at()).isEqualTo(T0.plus(Duration.ofHours(3)));
        assertThat(change.fromDeg()).isEqualTo(315);
    }
}
