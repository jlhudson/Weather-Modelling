package au.gully.hexagons;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state as written to a row reads back — including a row written while the drought was shared
 * across areas (19 September 2026, since reversed), which carries an {@code area} and an
 * {@code areaCells} the record no longer has: unknown fields are ignored, and this keeps it so.
 */
class DroughtStateTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void aRowWrittenWithTheOldAreaFieldsReadsBack() {
        String before = "{\"kbdiMm\":7.1,\"meanAnnualRainfallMm\":612.0,\"spunUpFrom\":\"2025-09-18\",\"computedFor\":\"2026-09-17\","
                + "\"days\":365,\"recentRainMm\":[0.0,2.4],\"from\":\"archive\",\"area\":\"46_-280\",\"areaCells\":7}";
        DroughtState s = MAPPER.readValue(before, DroughtState.class);
        assertThat(s.kbdiMm()).isEqualTo(7.1);
        assertThat(s.days()).isEqualTo(365);
        assertThat(s.step(LocalDate.of(2026, 9, 18), 0.0, 25.0, 5.0).computedFor()).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    @Test
    void aStateWrittenNowReadsBackWhole() {
        DroughtState s = new DroughtState(42.0, 550.0, LocalDate.of(2025, 9, 18), LocalDate.of(2026, 9, 18), 366,
                Collections.nCopies(20, 0.0), "stations+archive");
        DroughtState back = MAPPER.readValue(MAPPER.writeValueAsString(s), DroughtState.class);
        assertThat(back).isEqualTo(s);
        assertThat(MAPPER.writeValueAsString(s.index())).doesNotContain("area");
    }
}
