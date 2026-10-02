package au.gully.bureau;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class WarningAreasTest {

    static final WarningAreas AREAS = new WarningAreas();
    static final GeometryFactory GEOMETRY = new GeometryFactory();

    static List<String> holding(double lat, double lon) {
        var p = GEOMETRY.createPoint(new Coordinate(lon, lat));
        return AREAS.all().values().stream().filter(s -> s.geometry().covers(p)).map(WarningAreas.Shape::aac).sorted().toList();
    }

    static Warnings.Warning warning(String id, String kind, List<Warnings.Area> areas) {
        Instant at = Instant.parse("2026-10-02T07:59:07Z");
        return new Warnings.Warning(id, "sa", "A warning", null, null, kind, null, null, areas, at, at, null, "http://reg.bom.gov.au/products/" + id + ".shtml", at);
    }

    @Test
    void everyDistrictAndZoneOfBothStatesHasItsShape() {
        Map<String, Long> counts = AREAS.all().keySet().stream().collect(Collectors.groupingBy(a -> a.substring(0, a.length() - 3), Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("SA_PW", 15L, "SA_FW", 15L, "SA_MW", 12L, "TAS_PW", 11L, "TAS_FW", 11L, "TAS_MW", 15L));
        assertThat(AREAS.all().values()).allSatisfy(s -> {
            assertThat(s.geometry().isValid()).as(s.aac()).isTrue();
            assertThat(s.geometry().isEmpty()).as(s.aac()).isFalse();
            assertThat(s.state()).isEqualTo(s.aac().startsWith("SA_") ? "sa" : "tas");
        });
        assertThat(AREAS.of("SA_PW015")).get().satisfies(s -> {
            assertThat(s.name()).isEqualTo("Mount Lofty Ranges");
            assertThat(s.type()).isEqualTo("forecast district");
        });
        assertThat(AREAS.of("SA_FW015")).get().extracting(WarningAreas.Shape::type).isEqualTo("fire weather district");
        assertThat(AREAS.of("SA_MW009")).get().extracting(WarningAreas.Shape::name, WarningAreas.Shape::type).containsExactly("Upper South East Coast", "coastal waters");
        assertThat(AREAS.of("SA_MW011")).get().extracting(WarningAreas.Shape::type).isEqualTo("local waters");
        // The names a listed title is matched against (W-49) are the shapes' own.
        for (Map<String, Warnings.Area> state : Warnings.PUBLIC_DISTRICTS.values()) {
            assertThat(state.values()).allSatisfy(a -> assertThat(AREAS.of(a.aac())).get().extracting(WarningAreas.Shape::name).isEqualTo(a.name()));
        }
    }

    @Test
    void aPlaceIsInTheDistrictsTheBureauFilesItUnder() {
        // The stations' own forecast districts in the Bureau's files, and the fire weather district of the same name.
        assertThat(holding(-34.9257, 138.5832)).as("West Terrace").containsExactly("SA_FW001", "SA_PW001");
        assertThat(holding(-33.6501, 134.8880)).as("Elliston").containsExactly("SA_FW010", "SA_PW010");
        assertThat(holding(-42.8897, 147.3278)).as("Hobart").containsExactly("TAS_FW006", "TAS_PW006");
        assertThat(holding(-35.1200, 139.2700)).as("Murray Bridge").containsExactly("SA_FW007", "SA_PW007");
        assertThat(holding(-34.9800, 138.7100)).as("Mount Lofty").containsExactly("SA_FW015", "SA_PW015");
        assertThat(holding(-35.7700, 137.2100)).as("Kingscote").containsExactly("SA_FW003", "SA_PW003");
        // The gulf off Adelaide is Gulf St Vincent, no district; the Bight beyond every zone is nothing.
        assertThat(holding(-34.9000, 138.4000)).as("off Glenelg").containsExactly("SA_MW006");
        assertThat(holding(-38.0000, 132.0000)).as("the Bight").isEmpty();
    }

    @Test
    void aWarningsShapeIsTheUnionOfItsAreasShapes() {
        // The sheep graziers' warning of 2 October 2026: three districts side by side, one shape, no seams.
        Warnings.Warning sheep = warning("sa:sheep", "sheep graziers", List.of(
                new Warnings.Area("SA_PW007", "Murraylands", "public-district"),
                new Warnings.Area("SA_PW004", "Upper South East", "public-district"),
                new Warnings.Area("SA_PW005", "Lower South East", "public-district")));
        Geometry g = AREAS.shape(sheep);
        double sum = List.of("SA_PW007", "SA_PW004", "SA_PW005").stream().mapToDouble(a -> AREAS.of(a).get().geometry().getArea()).sum();
        assertThat(g.getArea()).isCloseTo(sum, within(sum * 1e-6));
        for (double[] p : new double[][]{{-35.12, 139.27}, {-36.98, 140.74}, {-37.83, 140.78}}) {
            assertThat(g.covers(GEOMETRY.createPoint(new Coordinate(p[1], p[0])))).as("%s", p).isTrue();
        }
        assertThat(g.covers(GEOMETRY.createPoint(new Coordinate(138.5832, -34.9257)))).as("Adelaide").isFalse();
        Map<String, Object> geometry = AREAS.geometry(sheep);
        assertThat(geometry).containsEntry("type", "Polygon");
        @SuppressWarnings("unchecked")
        List<List<List<Double>>> rings = (List<List<List<Double>>>) geometry.get("coordinates");
        assertThat(rings).hasSize(1);
        assertThat(rings.getFirst().getFirst()).isEqualTo(rings.getFirst().getLast());

        // A fire weather warning and a marine wind warning take their own kinds of area.
        assertThat(AREAS.shape(warning("IDS21000", "fire weather", List.of(new Warnings.Area("SA_FW015", "Mount Lofty Ranges", "fire-district"))))
                .equalsExact(AREAS.of("SA_FW015").get().geometry())).isTrue();
        assertThat(AREAS.geometry(warning("sa:marine-wind", "marine wind", List.of(
                new Warnings.Area("SA_MW009", "Upper South East Coast: Murray Mouth to Cape Jaffa", "coast"),
                new Warnings.Area("SA_MW010", "Lower South East Coast: Cape Jaffa to SA-VIC Border", "coast"))))).isNotNull();
    }

    @Test
    void aWarningWhoseAreasHaveNoShapeHasNone() {
        // A flood warning filed by river basin, and a page whose title names no district: listed, never drawn.
        assertThat(AREAS.geometry(warning("IDS20364", "flood", List.of(new Warnings.Area("SA_RC003", "Onkaparinga River", "river-basin"))))).isNull();
        assertThat(AREAS.geometry(warning("sa:marine-wind", "marine wind", List.of()))).isNull();
        // A basin beside a district adds nothing to the district's shape.
        assertThat(AREAS.shape(warning("IDS20999", "flood", List.of(new Warnings.Area("SA_RC003", "Onkaparinga River", "river-basin"),
                new Warnings.Area("SA_PW015", "Mount Lofty Ranges", "public-district")))).equalsExact(AREAS.of("SA_PW015").get().geometry())).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyWarningIsAFeatureWithItsViewAsProperties() throws Exception {
        // The listing as it stood on 2 October 2026: the marine wind summary as listed, its page unread; the sheep
        // graziers' warning covering the five districts its title names (W-49); and a flood warning by river basin.
        List<Warnings.Item> items = Warnings.parseListing("sa", WarningsTest.fixture("IDZ00057.warnings_sa.xml"));
        Warnings.Warning marine = Warnings.page(items.get(0)), sheep = Warnings.page(items.get(2));
        Warnings.Warning flood = warning("IDS20364", "flood", List.of(new Warnings.Area("SA_RC003", "Onkaparinga River", "river-basin")));
        Instant readAt = Instant.parse("2026-10-02T08:47:02Z");

        Map<String, Object> fc = AREAS.geojson(List.of(marine, sheep, flood), readAt);
        assertThat(fc).containsOnlyKeys("type", "features", "readAt").containsEntry("type", "FeatureCollection").containsEntry("readAt", "2026-10-02T08:47:02Z");
        List<Map<String, Object>> features = (List<Map<String, Object>>) fc.get("features");
        assertThat(features).extracting(f -> f.get("id")).containsExactly("sa:marine-wind", "sa:sheep", "IDS20364");
        assertThat(features).allSatisfy(f -> assertThat(f).containsOnlyKeys("type", "id", "properties", "geometry").containsEntry("type", "Feature"));
        assertThat(features.get(1).get("properties")).isEqualTo(Warnings.view(sheep));
        assertThat(features.get(0).get("geometry")).isNull();
        assertThat(features.get(2).get("geometry")).isNull();
        // Kangaroo Island is across the water from the other four: two pieces.
        Map<String, Object> shape = (Map<String, Object>) features.get(1).get("geometry");
        assertThat(shape).containsEntry("type", "MultiPolygon");
        assertThat((List<?>) shape.get("coordinates")).hasSize(2);

        // Light enough to send on every ask: five districts in a few tens of kilobytes.
        String json = JsonMapper.builder().build().writeValueAsString(fc);
        assertThat(json.length()).isBetween(2_000, 60_000);
        assertThat(AREAS.geojson(List.of(), null)).containsEntry("features", List.of()).containsEntry("readAt", null);
    }
}
