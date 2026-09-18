package au.gully.cfs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * The CFS feeds: the ratings as read live on 18 September 2026 (two districts of the fifteen), and
 * the district shapes as a synthetic square in Web Mercator.
 */
class CfsTest {

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = CfsTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void theRatingsFeedGivesFiveDaysPerDistrictWithItsIndexAndBan() throws Exception {
        Ratings ratings = new Ratings(null);
        Map<String, Ratings.DistrictRating> parsed = ratings.parse(fixture("cfs-ratings-two.json"), Instant.parse("2026-09-18T14:00:00Z"));
        assertThat(parsed).containsOnlyKeys("MOUNT LOFTY RANGES", "KANGAROO ISLAND");
        Ratings.DistrictRating mlr = parsed.get("MOUNT LOFTY RANGES");
        assertThat(mlr.district()).isEqualTo("Mount Lofty Ranges");
        assertThat(mlr.number()).isEqualTo(2);
        assertThat(mlr.aac()).isEqualTo("SA_FW015");
        assertThat(mlr.days()).hasSize(5);
        Ratings.RatingDay day0 = mlr.days().getFirst();
        assertThat(day0.rating()).isEqualTo("No Rating");
        assertThat(day0.fbi()).isEqualTo(0);
        assertThat(day0.totalFireBan()).isFalse();
        assertThat(day0.date()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(day0.from()).isEqualTo(Instant.parse("2026-04-30T14:30:00Z"));
        // No day covers September, so "today" is day 0 — the feed's own convention when the season is over.
        assertThat(mlr.at(Instant.parse("2026-09-18T14:00:00Z"))).contains(day0);
        assertThat(Ratings.normalise("  Mount   Lofty Ranges ")).isEqualTo("MOUNT LOFTY RANGES");
    }

    @Test
    void mercatorMetresBecomeDegrees() {
        double[] ll = Districts.fromMercator(15_428_000, -4_170_000);
        assertThat(ll[1]).isCloseTo(138.59, offset(0.05));
        assertThat(ll[0]).isCloseTo(-35.045, offset(0.01));
    }

    @Test
    void aPointInsideADistrictShapeIsNamedAndOutsideIsNot() {
        // A square around Adelaide, in Web Mercator, as the published file would carry it.
        String json = "{\"features\":[{\"attributes\":{\"firebandistrict\":\"Adelaide Metropolitan\"},"
                + "\"geometry\":{\"rings\":[[[15400000,-4200000],[15460000,-4200000],[15460000,-4140000],[15400000,-4140000],[15400000,-4200000]]]}}]}";
        Districts districts = new Districts(null);
        List<Districts.District> parsed = districts.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(parsed).hasSize(1);
        assertThat(parsed.getFirst().name()).isEqualTo("ADELAIDE METROPOLITAN");
        // Inside: the centre of the square. Outside: well to the north.
        double[] inside = Districts.fromMercator(15_430_000, -4_170_000);
        double[] outside = Districts.fromMercator(15_430_000, -3_000_000);
        assertThat(parsed.getFirst().shapes().getFirst().covers(new org.locationtech.jts.geom.GeometryFactory()
                .createPoint(new org.locationtech.jts.geom.Coordinate(inside[1], inside[0])))).isTrue();
        assertThat(parsed.getFirst().shapes().getFirst().covers(new org.locationtech.jts.geom.GeometryFactory()
                .createPoint(new org.locationtech.jts.geom.Coordinate(outside[1], outside[0])))).isFalse();
    }
}
