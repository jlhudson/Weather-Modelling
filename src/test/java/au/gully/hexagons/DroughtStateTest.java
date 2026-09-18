package au.gully.hexagons;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state as written to a row reads back - including a row written before the drought areas
 * existed (W-11), which has no {@code area} and no {@code areaCells}: Jackson 3 refuses a null for
 * a primitive, so the count is nullable, and this is the test that keeps it so.
 */
class DroughtStateTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void aStateWrittenBeforeTheAreasReadsBack() {
        String before = "{\"kbdiMm\":7.1,\"meanAnnualRainfallMm\":612.0,\"spunUpFrom\":\"2025-09-18\",\"computedFor\":\"2026-09-17\","
                + "\"days\":365,\"recentRainMm\":[0.0,2.4],\"from\":\"archive\"}";
        DroughtState s = MAPPER.readValue(before, DroughtState.class);
        assertThat(s.kbdiMm()).isEqualTo(7.1);
        assertThat(s.area()).isNull();
        assertThat(s.areaCells()).isNull();
        assertThat(s.index().areaHexagons()).isNull();
        DroughtState adopted = s.forArea("46_-280", 7);
        assertThat(adopted.area()).isEqualTo("46_-280");
        assertThat(adopted.areaCells()).isEqualTo(7);
        assertThat(adopted.step(LocalDate.of(2026, 9, 18), 0.0, 25.0, 5.0).area()).isEqualTo("46_-280");
    }

    @Test
    void aStateWrittenNowReadsBackWhole() {
        DroughtState s = new DroughtState(42.0, 550.0, LocalDate.of(2025, 9, 18), LocalDate.of(2026, 9, 18), 366,
                Collections.nCopies(20, 0.0), "stations+archive", "46_-280", 7);
        DroughtState back = MAPPER.readValue(MAPPER.writeValueAsString(s), DroughtState.class);
        assertThat(back).isEqualTo(s);
    }
}
