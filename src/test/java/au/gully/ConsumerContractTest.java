package au.gully;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer contract's own reader: a missing key and a renamed list element are caught; a null
 * value and an empty list are not a breach.
 */
class ConsumerContractTest {

    @Test
    void aMissingOrRenamedFieldIsNamedAndANullIsNot() {
        Map<String, Object> reach = Map.of("features", List.of(Map.of("properties", Map.of("name", "ADELAIDE", "reachKm", 35.0))));
        assertThat(ConsumerContract.missing(reach, "reach.geojson")).containsExactly("reach.geojson: features[].properties.areaKm2");

        java.util.HashMap<String, Object> props = new java.util.HashMap<>(Map.of("name", "ADELAIDE", "reachKm", 35.0));
        props.put("areaKm2", null);
        assertThat(ConsumerContract.missing(Map.of("features", List.of(Map.of("properties", props))), "reach.geojson")).isEmpty();
        assertThat(ConsumerContract.missing(Map.of("features", List.of()), "reach.geojson")).isEmpty();
    }
}
