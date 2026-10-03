package au.gully.api;

import au.gully.ConsumerContract;
import au.gully.bureau.WarningAreas;
import au.gully.bureau.Warnings;
import au.gully.platform.GullyProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StationsControllerTest {

    @Test
    void warningsNeverReadSayItRatherThanNoWarnings() {
        // Not enabled, the listings are never read: as on a cold start whose first read failed.
        Warnings warnings = new Warnings(null, null, new GullyProperties(false, "test", "Australia/Adelaide", null, null, null, null));
        StationsController c = new StationsController(null, null, null, null, null, null, warnings, new WarningAreas());

        Map<String, Object> list = c.warnings();
        assertThat(ConsumerContract.missing(list, "warnings")).isEmpty();
        assertThat(list).containsEntry("readAt", null).containsEntry("failure", null).containsEntry("stale", true);

        Map<String, Object> shapes = c.warningShapes();
        assertThat(ConsumerContract.missing(shapes, "warnings.geojson")).isEmpty();
        assertThat(shapes).containsEntry("readAt", null).containsEntry("failure", null).containsEntry("stale", true);
    }
}
