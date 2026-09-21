package au.gully.reach;

import au.gully.platform.Fetched;
import au.gully.upstreams.Ledger;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TerrainTilesTest {

    /**
     * A tile whose every pixel encodes {@code height}, in the terrarium encoding.
     */
    static byte[] tile(double height) throws Exception {
        BufferedImage img = new BufferedImage(TerrainTiles.SIZE, TerrainTiles.SIZE, BufferedImage.TYPE_INT_RGB);
        double v = height + 32768;
        int r = (int) (v / 256), g = (int) (v % 256), b = (int) Math.round((v - Math.floor(v)) * 256) & 255;
        for (int j = 0; j < TerrainTiles.SIZE; j++) {
            for (int i = 0; i < TerrainTiles.SIZE; i++) {
                img.setRGB(i, j, (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * A fetcher that answers every tile from one image and counts the fetches.
     */
    static TerrainTiles.TileFetch fetcher(byte[] png, List<String> urls) {
        return uri -> {
            urls.add(uri.toString());
            return new Fetched(200, png, "image/png", null, null);
        };
    }

    static Ledger ledger(List<String> rows) {
        return new Ledger(null) {
            @Override
            public void record(String upstream, double units, boolean ok, Duration latency, String detail) {
                rows.add(upstream + " " + (ok ? "ok" : "failed") + " " + detail);
            }
        };
    }

    @Test
    void theTerrariumEncodingDecodesToMetresAndTilesAreFetchedOnceEach() throws Exception {
        List<String> urls = new ArrayList<>(), rows = new ArrayList<>();
        TerrainTiles tiles = new TerrainTiles(fetcher(tile(597.5), urls), ledger(rows));
        // Adelaide's 2,401 points: a dozen or two tiles at zoom 10 (a disc, not a box), each fetched once.
        List<double[]> points = List.of(Terrain.points(-34.9257, 138.5832));
        TerrainTiles.Sampled s = tiles.elevations(points);
        assertThat(s.elevations()).hasSize(Terrain.POINTS);
        assertThat(s.elevations().getFirst()).isCloseTo(597.5, within(0.01));
        assertThat(s.tilesFetched()).isBetween(12, 30);
        assertThat(urls).hasSize(s.tilesFetched()).allMatch(u -> u.startsWith(TerrainTiles.BASE + TerrainTiles.ZOOM + "/"));
        assertThat(urls.stream().distinct().count()).as("no tile twice").isEqualTo(urls.size());
        assertThat(rows).hasSize(s.tilesFetched()).allMatch(r -> r.startsWith("terrain-tiles ok tile 10/"));
        // The next station beside it finds its tiles in the cache.
        TerrainTiles.Sampled again = tiles.elevations(List.of(Terrain.points(-34.95, 138.52)));
        assertThat(again.tilesFetched()).isLessThan(s.tilesFetched());
    }

    @Test
    void theSeaAndLandBelowSeaLevelReadAtOrBelowZero() throws Exception {
        List<String> urls = new ArrayList<>();
        TerrainTiles tiles = new TerrainTiles(fetcher(tile(-18.25), urls), ledger(new ArrayList<>()));
        assertThat(tiles.elevations(List.of(new double[]{-34.8, 138.3})).elevations().getFirst()).isCloseTo(-18.25, within(0.01));
    }

    @Test
    void theSamplerKeepsAStationWholeOrNotAtAll() throws Exception {
        List<String> urls = new ArrayList<>();
        TerrainTiles.TileFetch failing = uri -> { throw new au.gully.platform.UpstreamException("HTTP 503 from s3"); };
        TerrainTiles tiles = new TerrainTiles(failing, ledger(new ArrayList<>()));
        TerrainStore store = new TerrainStore(null) {
            @Override
            public void put(Terrain t) {
                throw new AssertionError("nothing should be kept");
            }
        };
        au.gully.bureau.StationRegistry registry = new au.gully.bureau.StationRegistry(null);
        TerrainSampler sampler = new TerrainSampler(registry, store, tiles);
        au.gully.bureau.Station s = new au.gully.bureau.Station("023000", "94648", "ADELAIDE", -34.9257, 138.5832, 29.32, "Australia/Adelaide", "SA_PW001", "sa");
        assertThat(sampler.sample(s)).isEmpty();
        assertThat(sampler.lastFailure()).contains("503");
        assertThat(sampler.lastFailedAt()).isAfter(Instant.now().minusSeconds(5));
    }
}
