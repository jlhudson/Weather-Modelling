package au.gully.cfs;

import au.gully.fire.CsiroGrassland;
import au.gully.fire.GrassFireDanger;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GrassTest {

    static final LocalDate TODAY = LocalDate.parse("2026-12-10");

    @Test
    void theGrassBlockIsBothModelsOnTheDistrictsCuring() {
        Curing.Entry e = new Curing.Entry("Mid North", 90, 4.5, LocalDate.parse("2026-12-07"), "CFS map", "operator", Instant.now());
        Map<String, Object> g = Grass.block("Mid North", e, 35.0, 12, 40.0, TODAY);
        assertThat(g).containsEntry("curingPct", 90).containsEntry("fuelLoadTHa", 4.5).containsEntry("curingOld", false);
        double byHand = Math.round(GrassFireDanger.gfdi(35, 12, 40, 90, 4.5) * 10) / 10.0;
        assertThat(g).containsEntry("gfdi", byHand).containsEntry("gfdiRating", GrassFireDanger.rating(byHand));
        CsiroGrassland.Result r = CsiroGrassland.of(35.0, 12, 40.0, 90.0, 4.5, null);
        assertThat(g).containsEntry("fbi", r.fbi()).containsEntry("afdrsRating", r.rating()).containsEntry("condition", "grazed");
        // A figure three weeks old is still used, and said to be old.
        Curing.Entry stale = new Curing.Entry("Mid North", 90, 4.5, LocalDate.parse("2026-11-15"), null, null, null);
        assertThat(Grass.block("Mid North", stale, 35.0, 12, 40.0, TODAY)).containsEntry("curingOld", true).containsKey("gfdi");
    }

    @Test
    void withoutACuringFigureThereIsNoGrassIndexAndItSaysWhy() {
        Map<String, Object> g = Grass.block("Riverland", null, 35.0, 12, 40.0, TODAY);
        assertThat(g).doesNotContainKey("gfdi").doesNotContainKey("fbi");
        assertThat((String) g.get("note")).contains("no curing figure").contains("Riverland");
    }
}
