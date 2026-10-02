package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Warnings.Item item = new Warnings.Item("tas", "IDT21037", "Severe Weather Warning", "http://reg.bom.gov.au/tas/warnings/IDT21037.shtml", Instant.parse("2026-09-18T12:20:00Z"));
        Warnings.Warning w = Warnings.parseProduct(fixture("IDT21037.xml"), item);
        assertThat(w).isNotNull();
        assertThat(w.id()).isEqualTo("IDT21037");
        assertThat(w.state()).isEqualTo("tas");
        assertThat(Warnings.view(w)).containsEntry("state", "tas");
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
    void theListingNamesItsProductsAndItsPages() throws Exception {
        List<Warnings.Item> items = Warnings.parseListing("tas", fixture("IDZ00058.warnings_tas.xml"));
        assertThat(items).extracting(Warnings.Item::productId).containsExactly(null, "IDT21037");
        assertThat(items).allMatch(i -> "tas".equals(i.state()) && i.publishedAt() != null);
    }

    @Test
    void aPageTheListingLinksToIsAWarningOfItsOwn() throws Exception {
        // South Australia's listing on 2 October 2026 held two items, both pages, and the service said no warnings (W-48).
        List<Warnings.Item> items = Warnings.parseListing("sa", fixture("IDZ00057.warnings_sa.xml"));
        assertThat(items).hasSize(3).allMatch(i -> "sa".equals(i.state()));
        assertThat(items).extracting(Warnings.Item::productId).containsExactly(null, "IDS21037", null);

        Warnings.Item product = items.get(1);
        assertThat(product.link()).isEqualTo("http://reg.bom.gov.au/products/IDS21037.shtml");
        assertThat(product.title()).startsWith("02/04:58 CST Severe Weather Warning");

        Warnings.Warning marine = Warnings.page(items.get(0));
        assertThat(marine.id()).isEqualTo("sa:marine-wind");
        assertThat(marine.state()).isEqualTo("sa");
        assertThat(marine.title()).isEqualTo("Marine Wind Warning Summary for South Australia");
        assertThat(marine.kind()).isEqualTo("marine wind");
        assertThat(marine.link()).isEqualTo("http://reg.bom.gov.au/sa/warnings/marine-wind.shtml");

        Warnings.Warning sheep = Warnings.page(items.get(2));
        assertThat(sheep.id()).isEqualTo("sa:sheep");
        assertThat(sheep.title()).isEqualTo("Warning to Sheep Graziers for Mount Lofty Ranges, Kangaroo Island, Murraylands, Upper South East and Lower South East forecast districts");
        assertThat(sheep.kind()).isEqualTo("sheep graziers");
        // No areas, so never here; issued when listed, in force for as long as the listing names it.
        assertThat(sheep.areas()).isEmpty();
        assertThat(sheep.covers(List.of("SA_PW012", "SA_FW008"))).isFalse();
        assertThat(sheep.issued()).isEqualTo(Instant.parse("2026-10-01T19:14:12Z")).isEqualTo(sheep.from()).isEqualTo(sheep.listedAt());
        assertThat(sheep.until()).isNull();
        assertThat(sheep.headline()).isNull();
        assertThat(sheep.hazardType()).isNull();

        Map<String, Object> v = Warnings.view(sheep);
        assertThat(v).containsEntry("id", "sa:sheep").containsEntry("state", "sa").containsEntry("kind", "sheep graziers")
                .containsEntry("until", null).containsEntry("areas", List.of()).containsEntry("link", "http://reg.bom.gov.au/sa/warnings/sheep.shtml");
    }

    @Test
    void theKindIsTheHazardsCodeElseTheTitle() {
        assertThat(Warnings.kind("FWW", "Fire Weather Warning")).isEqualTo("fire weather");
        assertThat(Warnings.kind(null, "Flood Watch for the Onkaparinga River")).isEqualTo("flood");
        assertThat(Warnings.kind(null, "Severe Thunderstorm Warning")).isEqualTo("severe thunderstorm");
        assertThat(Warnings.kind("SWW", "Severe Weather Warning")).isEqualTo("severe weather");
        assertThat(Warnings.kind(null, "Marine Wind Warning Summary for Tasmania")).isEqualTo("marine wind");
        assertThat(Warnings.kind(null, "Warning to Sheep Graziers for Mount Lofty Ranges")).isEqualTo("sheep graziers");
        assertThat(Warnings.kind(null, "Surf Warning Summary")).isEqualTo("other");
    }
}
