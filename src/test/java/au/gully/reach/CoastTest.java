package au.gully.reach;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CoastTest {

    @Test
    void theSeaIsTheWaterJoinedToTheOceanAndALakeIsNot() {
        // Ten by ten: the ocean along the bottom two rows, a gulf a column wide running up from it, and a lake inland
        // that reads below sea level (Lake Eyre) but touches neither.
        int side = 10;
        float[] h = new float[side * side];
        Arrays.fill(h, 50);
        for (int x = 0; x < side; x++) {
            h[8 * side + x] = -40;
            h[9 * side + x] = -40;
        }
        for (int y = 3; y < 8; y++) {
            h[y * side + 5] = -5;
        }
        h[2 * side + 1] = -15;
        h[2 * side + 2] = -15;
        List<int[]> c = Coast.coastline(h, side);
        // The gulf touches land on both sides all the way up; the ocean's top row touches land; the bottom row does not.
        assertThat(c).anySatisfy(p -> assertThat(p).containsExactly(5, 3));
        assertThat(c).anySatisfy(p -> assertThat(p).containsExactly(0, 8));
        assertThat(c).noneSatisfy(p -> assertThat(p[1]).isEqualTo(9));
        // The lake is not the sea.
        assertThat(c).noneSatisfy(p -> assertThat(p[1]).isEqualTo(2));
        // Ten along the ocean's edge but the one under the gulf, and the gulf's five.
        assertThat(c).hasSize(9 + 5);
    }

    @Test
    void aPixelIsPlacedAtItsCentre() {
        // The first pixel of the square is just inside its north-west corner: 126.56°E, about 24.5°S at zoom 7.
        double[] nw = Coast.latLon(0, 0);
        assertThat(nw[1]).isCloseTo(126.57, within(0.02));
        assertThat(nw[0]).isCloseTo(-24.53, within(0.05));
    }
}
