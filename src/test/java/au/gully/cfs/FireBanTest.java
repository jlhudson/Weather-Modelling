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
        Map<String, FireRatings.DistrictRating> r = new FireRatings(null, null, props()).parse(fixture("cfs-fire-danger-ratings.json"));
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

    static au.gully.platform.GullyProperties props() {
        return new org.springframework.boot.context.properties.bind.Binder(new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(Map.of("gully.enabled", "false")))
                .bindOrCreate("gully", au.gully.platform.GullyProperties.class);
    }
}
