package au.gully.reach;

import au.gully.platform.UpstreamException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the sea is (W-19): how far a station is from it, which its reach grows with. The coast is
 * found once, in coarse elevation tiles - zoom {@link #ZOOM}, about a kilometre a pixel, over South
 * Australia and the Southern Ocean below it, {@value #TILES_ACROSS} tiles a side - as the water
 * joined to the ocean: every pixel at or below sea level reached from the bottom edge, which is all
 * sea, without crossing land. So the gulfs are sea and Lake Eyre and the salt lakes, though they read
 * below zero or near it, are not. The coastline is the sea's pixels that touch land, and a station's
 * distance from the sea is the distance to the nearest of them.
 * <p>
 * Held in memory, found on the first sampling after the start; a failure is tried again after
 * {@link #RETRY_AFTER}, and until then every sampling fails with it, so a station is sampled whole or not at all.
 */
@Slf4j
@Component
public class Coast {

    public static final int ZOOM = 7;
    /**
     * The tiles, left and top: 126.6°E to 149.1°E, 24.5°S to 43°S at zoom 7 - the state, and the ocean below it all the way along.
     */
    static final int X0 = 109, Y0 = 73, TILES_ACROSS = 8;
    static final Duration RETRY_AFTER = Duration.ofMinutes(5);

    private final TerrainTiles tiles;
    /** The coastline, {@code {lat, lon}} in degrees; null until found. */
    private volatile double[][] coastline;
    private volatile Instant failedAt;
    private volatile String failure;

    public Coast(TerrainTiles tiles) {
        this.tiles = tiles;
    }

    /**
     * How far a place is from the sea, in kilometres, to the tenth.
     *
     * @throws UpstreamException while the coast cannot be found
     */
    public double inlandKm(double lat, double lon) throws UpstreamException {
        double[][] c = coastline();
        if (c.length == 0) {
            throw new UpstreamException("the coast: no sea in the tiles");
        }
        double best = Double.MAX_VALUE;
        for (double[] p : c) {
            // Equirectangular: a few hundred metres off at a thousand kilometres, and the nearest is what matters.
            double dx = Math.toRadians(p[1] - lon) * Math.cos(Math.toRadians((p[0] + lat) / 2)), dy = Math.toRadians(p[0] - lat);
            best = Math.min(best, dx * dx + dy * dy);
        }
        return Math.round(Math.sqrt(best) * Geo.EARTH_RADIUS_KM * 10) / 10.0;
    }

    private synchronized double[][] coastline() throws UpstreamException {
        if (coastline != null) {
            return coastline;
        }
        if (failedAt != null && Instant.now().isBefore(failedAt.plus(RETRY_AFTER))) {
            throw new UpstreamException("the coast: " + failure + " (tried again after " + failedAt.plus(RETRY_AFTER) + ")");
        }
        int side = TILES_ACROSS * TerrainTiles.SIZE;
        float[] heights = new float[side * side];
        try {
            for (int ty = 0; ty < TILES_ACROSS; ty++) {
                for (int tx = 0; tx < TILES_ACROSS; tx++) {
                    float[] t = tiles.tile(ZOOM, X0 + tx, Y0 + ty);
                    for (int j = 0; j < TerrainTiles.SIZE; j++) {
                        System.arraycopy(t, j * TerrainTiles.SIZE, heights, (ty * TerrainTiles.SIZE + j) * side + tx * TerrainTiles.SIZE, TerrainTiles.SIZE);
                    }
                }
            }
        } catch (UpstreamException e) {
            failedAt = Instant.now();
            failure = e.getMessage();
            throw e;
        }
        List<int[]> edge = coastline(heights, side);
        double[][] out = new double[edge.size()][];
        for (int i = 0; i < out.length; i++) {
            out[i] = latLon(edge.get(i)[0], edge.get(i)[1]);
        }
        coastline = out;
        failedAt = null;
        log.info("the coast found: {} pixels of coastline in {} tiles at zoom {}", out.length, TILES_ACROSS * TILES_ACROSS, ZOOM);
        return out;
    }

    /**
     * The coastline in a square of heights: the pixels at or below sea level joined to the bottom edge
     * without crossing land, that touch land. {@code {column, row}} each.
     */
    static List<int[]> coastline(float[] heights, int side) {
        boolean[] sea = new boolean[heights.length];
        int[] stack = new int[heights.length];
        int top = 0;
        for (int i = 0; i < side; i++) {
            int p = (side - 1) * side + i;
            if (water(heights[p])) {
                sea[p] = true;
                stack[top++] = p;
            }
        }
        while (top > 0) {
            int p = stack[--top], x = p % side, y = p / side;
            int[] next = {x > 0 ? p - 1 : -1, x < side - 1 ? p + 1 : -1, y > 0 ? p - side : -1, y < side - 1 ? p + side : -1};
            for (int q : next) {
                if (q >= 0 && !sea[q] && water(heights[q])) {
                    sea[q] = true;
                    stack[top++] = q;
                }
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int p = 0; p < sea.length; p++) {
            if (!sea[p]) {
                continue;
            }
            int x = p % side, y = p / side;
            if ((x > 0 && !sea[p - 1]) || (x < side - 1 && !sea[p + 1]) || (y > 0 && !sea[p - side]) || (y < side - 1 && !sea[p + side])) {
                out.add(new int[]{x, y});
            }
        }
        return out;
    }

    private static boolean water(float h) {
        return !Float.isNaN(h) && h <= 0;
    }

    /**
     * A pixel's centre in the square, as {@code {lat, lon}}.
     */
    static double[] latLon(int column, int row) {
        double n = Math.pow(2, ZOOM);
        double x = X0 + (column + .5) / TerrainTiles.SIZE, y = Y0 + (row + .5) / TerrainTiles.SIZE;
        double lon = x / n * 360 - 180;
        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))));
        return new double[]{lat, lon};
    }
}
