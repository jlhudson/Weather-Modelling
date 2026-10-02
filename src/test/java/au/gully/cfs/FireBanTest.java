package au.gully.cfs;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FireBanTest {

    static byte[] fixture(String name) throws Exception {
        try (InputStream in = FireBanTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void aPlaceIsInItsDistrictHolesAndAll() throws Exception {
        // The CFS's own file as it was on 25 September 2026: fifteen districts in Web Mercator.
        List<FireDistricts.District> d = new FireDistricts(null, null, props()).parse(fixture("cfs-fire-ban-districts.json"));
        assertThat(d).hasSize(15);
        assertThat(FireDistricts.within(d, -34.9257, 138.5832)).as("West Terrace").map(String::toUpperCase).contains("ADELAIDE METROPOLITAN");
        assertThat(FireDistricts.within(d, -34.9800, 138.7100)).as("Mount Lofty").map(String::toUpperCase).contains("MOUNT LOFTY RANGES");
        assertThat(FireDistricts.within(d, -34.1800, 140.7500)).as("Renmark").map(String::toUpperCase).contains("RIVERLAND");
        assertThat(FireDistricts.within(d, -29.0100, 134.7500)).as("Coober Pedy").map(String::toUpperCase).contains("NORTH WEST PASTORAL");
        assertThat(FireDistricts.within(d, -35.7700, 137.2100)).as("Kingscote").map(String::toUpperCase).contains("KANGAROO ISLAND");
        assertThat(FireDistricts.within(d, -36.0000, 132.0000)).as("the Bight").isEmpty();
    }

    @Test
    void aDayBeforeTodayIsNeverTakenForToday() throws Exception {
        // The feed as it stood out of season: every district "No Rating", published for 1 May 2026.
        Map<String, FireRatings.DistrictRating> r = new FireRatings(null, null, null, props()).parse(fixture("cfs-fire-danger-ratings.json"));
        assertThat(r).hasSize(15);
        FireRatings.DistrictRating lofty = r.get(FireRatings.key("Mount Lofty Ranges"));
        assertThat(lofty.aac()).isEqualTo("SA_FW015");
        assertThat(lofty.latest()).get().extracting(FireRatings.RatingDay::date).isEqualTo(LocalDate.parse("2026-05-04"));
        Map<String, Object> v = FireBan.view("Mount Lofty Ranges", lofty, Instant.parse("2026-09-25T02:00:00Z"));
        assertThat(v).containsEntry("current", false).containsEntry("today", null).containsEntry("lastPublished", "2026-05-04");
        assertThat((List<?>) v.get("days")).isEmpty();
        assertThat((String) v.get("note")).contains("no rating for today");
        // On a day it did publish, that day is today's.
        Map<String, Object> may = FireBan.view("Mount Lofty Ranges", lofty, Instant.parse("2026-05-01T02:00:00Z"));
        assertThat(may).containsEntry("current", true);
        assertThat((Map<String, Object>) may.get("today")).containsEntry("rating", "No Rating").containsEntry("totalFireBan", false);
    }

    /**
     * The CFS map viewer's file (W-45): GeoJSON, the layer's names under {@code properties}, {@code fbi0}
     * a string and {@code tfb0} spelt {@code No}. Kangaroo Island's feature as it stood out of season,
     * the geometry left out - the test the Hub kept for this file until 28 September 2026.
     */
    @Test
    void theViewersGeoJsonReadsAsTheLayerDid() {
        String file = "{\"type\":\"FeatureCollection\",\"features\":[{\"id\":\"feature_01\",\"type\":\"Feature\",\"properties\":{\"objectid\":2,"
                + "\"firebandistrict\":\"Kangaroo Island\",\"firedangerrating\":\"No Rating\",\"issuingauthority\":\"Australian Bureau of Meteorology\",\"aac\":\"SA_FW003\","
                + "\"ratingdate\":\"01/05/2026\",\"firedangerrating_0\":\"No Rating\",\"fbi0\":\"0\",\"tfb0\":\"No\",\"ratingdate_0\":\"1/5/2026\","
                + "\"sttmloc0\":\"2026-05-01T00:00:00+09:30\",\"endtmloc0\":\"2026-05-02T00:00:00+09:30\",\"sttmutc0\":\"2026-04-30T14:30:00Z\",\"endtmutc0\":\"2026-05-01T14:30:00Z\","
                + "\"distnumb\":\"3\",\"tfb\":\"No\",\"tfb_override\":null,\"areaaff1\":\"Kangaroo Island\",\"areaaff2\":\"\"},\"geometry\":null}]}";
        FireRatings.DistrictRating ki = new FireRatings(null, null, null, props()).parse(file.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .get(FireRatings.key("Kangaroo Island"));
        assertThat(ki).isNotNull();
        assertThat(ki.number()).isEqualTo(3);
        assertThat(ki.aac()).isEqualTo("SA_FW003");
        assertThat(ki.days()).singleElement().satisfies(d -> {
            assertThat(d.date()).isEqualTo(LocalDate.parse("2026-05-01"));
            assertThat(d.rating()).isEqualTo("No Rating");
            assertThat(d.fbi()).isZero();
            assertThat(d.totalFireBan()).isFalse();
        });
    }

    /**
     * The Bureau's South Australian fire weather districts (IDM00007, W-50) are the CFS's fire ban districts: the code the
     * CFS's ratings give each district names the Bureau's shape of that name, and the two shapes are the same ground but
     * for the water - the Bureau's leave Lake Alexandrina, Lake Albert and the Coorong to a marine zone of their own
     * ({@code SA_MW017}, Murray Lakes), which the CFS's Murraylands and Upper South East take in - and the Bureau's coast
     * drawn to half a kilometre.
     */
    @Test
    void theBureausFireWeatherDistrictsAreTheCfssFireBanDistricts() throws Exception {
        List<FireDistricts.District> cfs = new FireDistricts(null, null, props()).parse(fixture("cfs-fire-ban-districts.json"));
        Map<String, FireRatings.DistrictRating> ratings = new FireRatings(null, null, null, props()).parse(fixture("cfs-fire-danger-ratings.json"));
        au.gully.bureau.WarningAreas bureau = new au.gully.bureau.WarningAreas();
        assertThat(ratings.values()).hasSize(15).allSatisfy(r -> {
            au.gully.bureau.WarningAreas.Shape shape = bureau.of(r.aac()).orElseThrow();
            assertThat(shape.name()).isEqualTo(r.district());
            FireDistricts.District d = cfs.stream().filter(x -> x.name().equalsIgnoreCase(r.district())).findFirst().orElseThrow();
            // The CFS's own rings cross themselves here and there, so they are mended before they are compared.
            org.locationtech.jts.geom.Geometry theirs = org.locationtech.jts.geom.util.GeometryFixer.fix(new org.locationtech.jts.geom.GeometryFactory()
                    .createMultiPolygon(d.polygons().toArray(org.locationtech.jts.geom.Polygon[]::new)));
            double shared = shape.geometry().intersection(theirs).getArea(), either = shape.geometry().union(theirs).getArea();
            assertThat(shared / either).as(r.district()).isGreaterThan(0.95);
        });
        // What the CFS's Murraylands holds and the Bureau's does not is the lakes.
        org.locationtech.jts.geom.Geometry murraylands = org.locationtech.jts.geom.util.GeometryFixer.fix(new org.locationtech.jts.geom.GeometryFactory()
                .createMultiPolygon(cfs.stream().filter(x -> x.name().equalsIgnoreCase("Murraylands")).findFirst().orElseThrow().polygons()
                        .toArray(org.locationtech.jts.geom.Polygon[]::new))).difference(bureau.of("SA_FW007").orElseThrow().geometry());
        assertThat(murraylands.intersection(bureau.of("SA_MW017").orElseThrow().geometry()).getArea() / murraylands.getArea()).isGreaterThan(0.9);
    }

    static au.gully.platform.GullyProperties props() {
        return new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("gully.enabled", "false")))
                .bindOrCreate("gully", au.gully.platform.GullyProperties.class);
    }
}
