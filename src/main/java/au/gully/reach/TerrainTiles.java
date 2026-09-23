package au.gully.reach;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.UpstreamException;
import au.gully.upstreams.Ledger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The digital elevation model the terrain is sampled from: the Terrain Tiles on AWS's open data
 * registry (Mapzen's, from SRTM, GMTED2010 and others; bathymetry over the sea), as 256-pixel PNG
 * tiles in the "terrarium" encoding — {@code height = R × 256 + G + B ÷ 256 − 32768}. Public, no
 * key, no published limit; every tile fetched is a row in the ledger under {@link #ID} so the
 * Upstreams page can say how many. Zoom {@link #ZOOM} is about 125 m a pixel at Adelaide's
 * latitude, finer than the kilometre a ray steps; a station's 150-kilometre disc (W-19) is a hundred-odd
 * tiles, and neighbouring stations share them through a cache. The coast is found in coarser ones
 * ({@link #tile}, {@link Coast}).
 * <p>
 * The sea reads at or below zero: the tiles carry bathymetry, so open water is negative and a
 * shoreline pixel is zero. Land below sea level (Lake Eyre, minus fifteen) reads negative too and
 * is taken as water, which for a reach is the right answer: a salt lake is not the station's ground.
 */
@Slf4j
@Component
public class TerrainTiles {

    public static final String ID = "terrain-tiles";
    public static final int ZOOM = 10;
    public static final String BASE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/";
    public static final String ATTRIBUTION = "Terrain Tiles (AWS Open Data; Mapzen, from SRTM, GMTED2010, ETOPO1 and others)";
    static final int SIZE = 256;
    /**
     * A 150-km disc (W-19) is a hundred-odd tiles; neighbouring stations share most of them. A tile is 256 KB decoded.
     */
    static final int CACHE_TILES = 128;

    private final TileFetch http;
    private final Ledger ledger;
    /** Off with {@code gully.enabled}: nothing is fetched, and a height is simply not known. */
    private final boolean enabled;
    private final Map<String, float[]> cache = new LinkedHashMap<>(CACHE_TILES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE_TILES;
        }
    };

    /**
     * The tiles held, dropped: the next sampling fetches every tile it needs again.
     */
    public void clearCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    @Autowired
    public TerrainTiles(HttpFetcher http, Ledger ledger, au.gully.platform.GullyProperties properties) {
        this(http::get, ledger, properties.enabled());
    }

    TerrainTiles(TileFetch http, Ledger ledger) {
        this(http, ledger, true);
    }

    TerrainTiles(TileFetch http, Ledger ledger, boolean enabled) {
        this.http = http;
        this.ledger = ledger;
        this.enabled = enabled;
    }

    /**
     * One GET of a tile: the fetcher in production, a stand-in under test.
     */
    @FunctionalInterface
    interface TileFetch {
        Fetched get(URI uri) throws UpstreamException;
    }

    /**
     * The height at each point, metres, in order; NaN where a tile carries nothing at the point. The
     * tiles are fetched as they are met and kept for the next station.
     *
     * @return the heights, and how many tiles were fetched for them
     * @throws UpstreamException when a tile cannot be fetched or decoded: the whole sampling is abandoned
     */
    public Sampled elevations(List<double[]> points) throws UpstreamException {
        if (!enabled) {
            throw new UpstreamException(ID + ": gully.enabled is false");
        }
        List<Double> out = new ArrayList<>(points.size());
        int fetched = 0;
        for (double[] p : points) {
            double n = Math.pow(2, ZOOM);
            double xt = (p[1] + 180) / 360 * n;
            double latR = Math.toRadians(p[0]);
            double yt = (1 - Math.log(Math.tan(latR) + 1 / Math.cos(latR)) / Math.PI) / 2 * n;
            int x = (int) Math.floor(xt), y = (int) Math.floor(yt);
            int px = Math.min(SIZE - 1, (int) ((xt - x) * SIZE)), py = Math.min(SIZE - 1, (int) ((yt - y) * SIZE));
            String key = x + "/" + y;
            float[] tile;
            synchronized (cache) {
                tile = cache.get(key);
            }
            if (tile == null) {
                tile = fetch(ZOOM, x, y);
                fetched++;
                synchronized (cache) {
                    cache.put(key, tile);
                }
            }
            float v = tile[py * SIZE + px];
            out.add(Float.isNaN(v) ? null : (double) v);
        }
        return new Sampled(out, fetched);
    }

    /**
     * One tile at any zoom, decoded to heights, row-major, {@link #SIZE} a side: what the coast is found in ({@link Coast}).
     * Not kept.
     */
    public float[] tile(int zoom, int x, int y) throws UpstreamException {
        if (!enabled) {
            throw new UpstreamException(ID + ": gully.enabled is false");
        }
        return fetch(zoom, x, y);
    }

    private float[] fetch(int zoom, int x, int y) throws UpstreamException {
        String url = BASE + zoom + "/" + x + "/" + y + ".png";
        long started = System.nanoTime();
        try {
            Fetched f = http.get(URI.create(url));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(f.body()));
            if (img == null || img.getWidth() != SIZE || img.getHeight() != SIZE) {
                throw new UpstreamException(ID + ": " + url + " is not a " + SIZE + "-pixel tile");
            }
            float[] out = new float[SIZE * SIZE];
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    int rgb = img.getRGB(i, j);
                    int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                    out[j * SIZE + i] = (float) (r * 256 + g + b / 256.0 - 32768);
                }
            }
            ledger.record(ID, 1, true, Duration.ofNanos(System.nanoTime() - started), "tile " + zoom + "/" + x + "/" + y + " " + f.body().length + " bytes");
            return out;
        } catch (IOException | RuntimeException e) {
            ledger.record(ID, 1, false, Duration.ofNanos(System.nanoTime() - started), "tile " + zoom + "/" + x + "/" + y + ": " + e.getMessage());
            throw new UpstreamException(ID + ": " + url + ": " + e.getMessage());
        } catch (UpstreamException e) {
            ledger.record(ID, 1, false, Duration.ofNanos(System.nanoTime() - started), "tile " + zoom + "/" + x + "/" + y + ": " + e.getMessage());
            throw e;
        }
    }

    /**
     * The heights sampled, and how many tiles had to be fetched for them.
     */
    public record Sampled(List<Double> elevations, int tilesFetched) {
    }
}
