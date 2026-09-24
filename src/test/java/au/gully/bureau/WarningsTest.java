package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WarningsTest {

    static byte[] fixture(String name) throws Exception {
        try (InputStream in = WarningsTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void aWarningCarriesEveryAreaItCoversAndItsHazardsTimes() throws Exception {
        // A severe weather warning for Tasmania as the Bureau published it, 18 September 2026.
        Warnings.Item item = new Warnings.Item("IDT21037", "Severe Weather Warning", "http://reg.bom.gov.au/tas/warnings/IDT21037.shtml", Instant.parse("2026-09-18T12:20:00Z"));
        Warnings.Warning w = Warnings.parseProduct(fixture("IDT21037.xml"), item);
        assertThat(w).isNotNull();
        assertThat(w.id()).isEqualTo("IDT21037");
        assertThat(w.kind()).isEqualTo("severe weather");
        assertThat(w.hazardType()).isEqualTo("SWW");
        // The nine public districts it names - not the region it is filed under.
        assertThat(w.areas()).extracting(Warnings.Area::aac).contains("TAS_PW010", "TAS_PW004", "TAS_PW009").doesNotContain("TAS_FA001");
        assertThat(w.areas()).allMatch(a -> "public-district".equals(a.type()));
        assertThat(w.until()).isEqualTo(Instant.parse("2026-09-18T21:00:00Z"));
        assertThat(w.covers(List.of("TAS_PW004"))).isTrue();
        assertThat(w.covers(List.of("SA_PW001", "SA_FW015"))).isFalse();
    }

    @Test
    void theListingNamesItsProductsAndLeavesThePagesOut() throws Exception {
        List<Warnings.Item> items = Warnings.parseListing(fixture("IDZ00058.warnings_tas.xml"));
        assertThat(items).isNotEmpty().allMatch(i -> i.productId().startsWith("IDT"));
        assertThat(items).allMatch(i -> i.publishedAt() != null);
    }

    @Test
    void theKindIsTheHazardsCodeElseTheTitle() {
        assertThat(Warnings.kind("FWW", "Fire Weather Warning")).isEqualTo("fire weather");
        assertThat(Warnings.kind(null, "Flood Watch for the Onkaparinga River")).isEqualTo("flood");
        assertThat(Warnings.kind(null, "Severe Thunderstorm Warning")).isEqualTo("severe thunderstorm");
        assertThat(Warnings.kind("SWW", "Severe Weather Warning")).isEqualTo("severe weather");
        assertThat(Warnings.kind(null, "Sheep Graziers Warning")).isEqualTo("other");
    }
}
