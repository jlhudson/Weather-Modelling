package au.gully.terrain;

import au.gully.hexagons.Cell;
import au.gully.hexagons.Grid;
import au.gully.science.LandUse;
import au.gully.science.LandUse.LandClass;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Digital Earth Australia's land cover for a hexagon (W-15): the level-4 codes to the seven classes,
 * and the shares of the Murray Bridge hexagon counted from a raster the service fetched for it - a
 * 64 × 64 LZW GeoTIFF as the WCS answers, kept here so the count is a fact, not a live call.
 */
class DeaLandCoverTest {

    @Test
    void theLevelFourCodesReduceToTheSevenClasses() {
        // Cultivated: crops are crop, orchards and plantations are trees, vines are scrub.
        assertThat(DeaLandCover.classOf(3)).isEqualTo(LandClass.CROPLAND);
        assertThat(DeaLandCover.classOf(17)).isEqualTo(LandClass.CROPLAND);
        assertThat(DeaLandCover.classOf(9)).isEqualTo(LandClass.FOREST);
        assertThat(DeaLandCover.classOf(13)).isEqualTo(LandClass.SCRUB);
        // Natural terrestrial: woody closed and open is forest, sparse is scrub, herbaceous is grass.
        assertThat(DeaLandCover.classOf(27)).isEqualTo(LandClass.FOREST);
        assertThat(DeaLandCover.classOf(29)).isEqualTo(LandClass.FOREST);
        assertThat(DeaLandCover.classOf(30)).isEqualTo(LandClass.SCRUB);
        assertThat(DeaLandCover.classOf(34)).isEqualTo(LandClass.GRASSLAND);
        assertThat(DeaLandCover.classOf(36)).isEqualTo(LandClass.GRASSLAND);
        // Aquatic: a paperbark swamp is forest, a reed bed grassland, rice is crop.
        assertThat(DeaLandCover.classOf(64)).isEqualTo(LandClass.FOREST);
        assertThat(DeaLandCover.classOf(79)).isEqualTo(LandClass.GRASSLAND);
        assertThat(DeaLandCover.classOf(40)).isEqualTo(LandClass.CROPLAND);
        // The surfaces and the water.
        assertThat(DeaLandCover.classOf(93)).isEqualTo(LandClass.BUILT_UP);
        assertThat(DeaLandCover.classOf(96)).isEqualTo(LandClass.BARE);
        assertThat(DeaLandCover.classOf(101)).isEqualTo(LandClass.WATER);
        assertThat(DeaLandCover.classOf(104)).isEqualTo(LandClass.WATER);
        assertThat(DeaLandCover.classOf(150)).isEqualTo(LandClass.UNKNOWN);
    }

    @Test
    void theMurrayBridgeHexagonIsMostlyCroppedWithTheRiverThroughIt() throws IOException {
        byte[] tiff;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream("/terrain/dea-landcover-2025-murray-bridge.tif"))) {
            tiff = in.readAllBytes();
        }
        DeaLandCover.Cover cover = new DeaLandCover.Cover(tiff, 2025);
        assertThat(cover.source()).isEqualTo("dea-landcover-2025");
        Cell centre = Grid.DEFAULT.cell(0, 0);
        LandUse use = DeaLandCover.landUse(cover, Grid.DEFAULT, centre).orElseThrow();
        assertThat(use.source()).isEqualTo("dea-landcover-2025");
        assertThat(use.dominant()).isEqualTo(LandClass.CROPLAND);
        assertThat(use.share(LandClass.CROPLAND)).isGreaterThan(40);
        assertThat(use.share(LandClass.GRASSLAND)).isGreaterThan(10);
        assertThat(use.share(LandClass.WATER)).isBetween(1, 10);
        assertThat(use.share(LandClass.BUILT_UP)).isBetween(1, 10);
        assertThat(use.leads()).isEqualTo("grass");
        assertThat(use.burnablePct()).isGreaterThan(80);
        int total = use.percent().values().stream().mapToInt(Integer::intValue).sum();
        assertThat(total).isBetween(98, 102);
        // The class at a point: walking the box finds the river and the town, each a class of its own.
        java.util.Set<LandClass> seen = new java.util.HashSet<>();
        for (double lat = -35.23; lat < -35.03; lat += 0.005) {
            for (double lon = 139.14; lon < 139.39; lon += 0.005) {
                DeaLandCover.classAt(tiff, lat, lon).ifPresent(seen::add);
            }
        }
        assertThat(seen).contains(LandClass.WATER, LandClass.BUILT_UP, LandClass.CROPLAND, LandClass.GRASSLAND);
        // And well outside the raster there is nothing to say.
        assertThat(DeaLandCover.classAt(tiff, -36.5, 139.30)).isEmpty();
    }
}
