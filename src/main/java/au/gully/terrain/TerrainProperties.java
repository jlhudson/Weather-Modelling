package au.gully.terrain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * Where the two rasters are mounted (docs/06 items 17 and 19). Both optional: without a file the
 * hexagon simply carries no slope, no land use, and the upstream model's own elevation.
 *
 * @param elevationFile a GeoTIFF of ground height in metres, such as Geoscience Australia's 9-second DEM
 * @param landCoverFile a GeoTIFF of land-cover or land-use class codes, such as ABARES' catchment
 *                      scale land use; the class mapping is {@code <file>.classes.properties} beside it
 */
@ConfigurationProperties(prefix = "gully.terrain")
public record TerrainProperties(String elevationFile, String landCoverFile) {

    public Path elevation() {
        return path(elevationFile);
    }

    public Path landCover() {
        return path(landCoverFile);
    }

    private static Path path(String s) {
        return s == null || s.isBlank() ? null : Path.of(s.trim());
    }
}
