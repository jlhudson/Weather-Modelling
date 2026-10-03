package au.gully.bureau;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Duration;
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
    @SuppressWarnings("unchecked")
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

        assertThat(marine.areas()).as("its title names no district").isEmpty();

        Warnings.Warning sheep = Warnings.page(items.get(2));
        assertThat(sheep.id()).isEqualTo("sa:sheep");
        assertThat(sheep.title()).isEqualTo("Warning to Sheep Graziers for Mount Lofty Ranges, Kangaroo Island, Murraylands, Upper South East and Lower South East forecast districts");
        assertThat(sheep.kind()).isEqualTo("sheep graziers");
        // As listed, it covers the districts its title names (W-49); issued when listed, in force for as long as the listing names it.
        assertThat(sheep.areas()).extracting(Warnings.Area::aac).containsExactly("SA_PW015", "SA_PW003", "SA_PW007", "SA_PW004", "SA_PW005");
        assertThat(sheep.covers(List.of("SA_PW015", "SA_FW015"))).isTrue();
        assertThat(sheep.covers(List.of("SA_PW012", "SA_FW012"))).isFalse();
        assertThat(sheep.issued()).isEqualTo(Instant.parse("2026-10-01T19:14:12Z")).isEqualTo(sheep.from()).isEqualTo(sheep.listedAt());
        assertThat(sheep.until()).isNull();
        assertThat(sheep.headline()).isNull();
        assertThat(sheep.hazardType()).isNull();

        Map<String, Object> v = Warnings.view(sheep);
        assertThat(v).containsEntry("id", "sa:sheep").containsEntry("state", "sa").containsEntry("kind", "sheep graziers")
                .containsEntry("until", null).containsEntry("link", "http://reg.bom.gov.au/sa/warnings/sheep.shtml");
        assertThat((List<Map<String, Object>>) v.get("areas")).first().isEqualTo(Map.of("aac", "SA_PW015", "name", "Mount Lofty Ranges", "type", "public-district"));
    }

    @Test
    void aPageNamesTheProductItShows() throws Exception {
        // The Bureau's two South Australian pages as they stood on 2 October 2026.
        assertThat(Warnings.productOf(fixture("sa-warnings-marine-wind.shtml"))).isEqualTo("IDS20201");
        assertThat(Warnings.productOf(fixture("sa-warnings-sheep.shtml"))).isEqualTo("IDS20242");
        assertThat(Warnings.productOf("<html><p>No warnings.</p></html>".getBytes())).isNull();
        // Read from the Bureau's own host, as the listing is; a link elsewhere is not read.
        assertThat(Warnings.onTheBureau("http://reg.bom.gov.au/sa/warnings/sheep.shtml")).hasToString("https://reg.bom.gov.au/sa/warnings/sheep.shtml");
        assertThat(Warnings.onTheBureau("http://www.bom.gov.au/tas/warnings/marinewind.shtml")).hasToString("https://reg.bom.gov.au/tas/warnings/marinewind.shtml");
        assertThat(Warnings.onTheBureau("https://example.com/sa/warnings/sheep.shtml")).isNull();
    }

    @Test
    void theMarineWindSummaryCoversItsCoastalWaters() throws Exception {
        Warnings.Item sa = new Warnings.Item("sa", null, "02/15:30 CST Marine Wind Warning Summary for South Australia", "http://reg.bom.gov.au/sa/warnings/marine-wind.shtml", Instant.parse("2026-10-02T06:00:11Z"));
        Warnings.Warning w = Warnings.parseProduct(fixture("IDS20201.xml"), sa);
        assertThat(w.areas()).containsExactly(new Warnings.Area("SA_MW009", "Upper South East Coast: Murray Mouth to Cape Jaffa", "coast"),
                new Warnings.Area("SA_MW010", "Lower South East Coast: Cape Jaffa to SA-VIC Border", "coast"));
        assertThat(w.hazardType()).isEqualTo("MWW");
        assertThat(w.severity()).isEqualTo("STR");
        assertThat(w.kind()).isEqualTo("marine wind");
        assertThat(w.until()).isEqualTo(Instant.parse("2026-10-03T14:30:00Z"));
        // Tasmania's names a day's waters and the next day's: the warning covers both.
        Warnings.Item tas = new Warnings.Item("tas", null, "02/16:05 EST Marine Wind Warning Summary for Tasmania", "http://reg.bom.gov.au/tas/warnings/marinewind.shtml", Instant.parse("2026-10-02T06:05:12Z"));
        assertThat(Warnings.parseProduct(fixture("IDT20100.xml"), tas).areas()).extracting(Warnings.Area::aac)
                .containsExactly("TAS_MW001", "TAS_MW002", "TAS_MW009", "TAS_MW003", "TAS_MW004");
    }

    @Test
    void anAreaTheProductCancelsIsNotCovered() throws Exception {
        // Renewed for three districts, cancelled for Mount Lofty Ranges and Kangaroo Island, 2 October 2026 at 5:29 pm.
        Warnings.Warning w = Warnings.parseProduct(fixture("IDS20242.xml"), SHEEP);
        assertThat(w.areas()).extracting(Warnings.Area::aac).containsExactly("SA_PW007", "SA_PW004", "SA_PW005");
        assertThat(w.areas()).allMatch(a -> "public-district".equals(a.type()));
        assertThat(w.covers(List.of("SA_PW015", "SA_FW015"))).as("Mount Lofty Ranges, cancelled").isFalse();
        assertThat(w.covers(List.of("SA_PW007"))).as("Murraylands").isTrue();
        assertThat(w.hazardType()).isEqualTo("SHW");
        assertThat(w.severity()).isEqualTo("STD");
        assertThat(w.from()).isEqualTo(Instant.parse("2026-10-02T07:58:00Z"));
        assertThat(w.until()).isEqualTo(Instant.parse("2026-10-03T14:30:00Z"));
        // A product cancelling every area it names covers none, and is still the warning the listing names.
        String all = new String(fixture("IDS20242.xml")).replace("phase=\"REN\" type=\"SHW\" severity=\"STD\"", "phase=\"CAN\" type=\"SHW\" severity=\"CAN\"")
                .replace("phase=\"REN\" description", "phase=\"CAN\" description");
        Warnings.Warning cancelled = Warnings.parseProduct(all.getBytes(), SHEEP);
        assertThat(cancelled).isNotNull();
        assertThat(cancelled.areas()).isEmpty();
        assertThat(cancelled.severity()).isNull();
    }

    /**
     * The listing's sheep graziers item as it stood at 5:29 pm on 2 October 2026.
     */
    static final Warnings.Item SHEEP = new Warnings.Item("sa", null,
            "02/17:29 CST Warning to Sheep Graziers for Murraylands, Upper South East and Lower South East forecast districts",
            "http://reg.bom.gov.au/sa/warnings/sheep.shtml", Instant.parse("2026-10-02T07:59:15Z"));

    @Test
    void aPageIsAWarningCoveringItsProductsAreas() throws Exception {
        Map<String, byte[]> files = Map.of(
                "https://reg.bom.gov.au/sa/warnings/sheep.shtml", fixture("sa-warnings-sheep.shtml"),
                "https://reg.bom.gov.au/fwo/IDS20242.xml", fixture("IDS20242.xml"));
        Warnings.Warning w = Warnings.page(SHEEP, uri -> {
            byte[] b = files.get(uri.toString());
            if (b == null) {
                throw new au.gully.platform.UpstreamException("HTTP 404 from " + uri, 404);
            }
            return b;
        });
        // The page's name, title and link; the product's areas, hazard and times.
        assertThat(w.id()).isEqualTo("sa:sheep");
        assertThat(w.title()).isEqualTo("Warning to Sheep Graziers for Murraylands, Upper South East and Lower South East forecast districts");
        assertThat(w.link()).isEqualTo("http://reg.bom.gov.au/sa/warnings/sheep.shtml");
        assertThat(w.kind()).isEqualTo("sheep graziers");
        assertThat(w.areas()).extracting(Warnings.Area::aac).containsExactly("SA_PW007", "SA_PW004", "SA_PW005");
        assertThat(w.issued()).isEqualTo(Instant.parse("2026-10-02T07:59:07Z"));
        assertThat(w.until()).isEqualTo(Instant.parse("2026-10-03T14:30:00Z"));
        assertThat(w.listedAt()).isEqualTo(SHEEP.publishedAt());
        assertThat(w.covers(List.of("SA_PW004"))).isTrue();
        Map<String, Object> v = Warnings.view(w);
        assertThat(v).containsEntry("id", "sa:sheep").containsEntry("kind", "sheep graziers").containsEntry("severity", "STD");
        assertThat((List<?>) v.get("areas")).hasSize(3);

        // A page that names no product is the warning its title says.
        Warnings.Warning bare = Warnings.page(SHEEP, uri -> "<html></html>".getBytes());
        assertThat(bare.areas()).extracting(Warnings.Area::aac).containsExactly("SA_PW007", "SA_PW004", "SA_PW005");
        assertThat(bare.until()).isNull();
    }

    @Test
    void aTitlesDistrictsAreTheBureausOwnNoneGuessed() throws Exception {
        // The Tasmanian listing's own title for IDT21037 names the nine districts the product lists, "parts of" and all.
        List<Warnings.Item> tas = Warnings.parseListing("tas", fixture("IDZ00058.warnings_tas.xml"));
        List<Warnings.Area> named = Warnings.districtsNamed("tas", tas.get(1).title());
        Warnings.Warning product = Warnings.parseProduct(fixture("IDT21037.xml"), tas.get(1));
        assertThat(named).containsExactlyInAnyOrderElementsOf(product.areas());

        assertThat(Warnings.districtsNamed("sa", "Warning to Sheep Graziers for Mount Lofty Ranges, Kangaroo Island, Murraylands, Upper South East and Lower South East forecast districts"))
                .extracting(Warnings.Area::aac).containsExactly("SA_PW015", "SA_PW003", "SA_PW007", "SA_PW004", "SA_PW005");
        assertThat(Warnings.districtsNamed("sa", "Warning to Sheep Graziers for the Murraylands forecast district."))
                .containsExactly(new Warnings.Area("SA_PW007", "Murraylands", "public-district"));
        // A name that is not a district of the state is left out; a title naming no districts names none.
        assertThat(Warnings.districtsNamed("sa", "Warning to Sheep Graziers for Mount Lofty Ranges and Narnia forecast districts"))
                .extracting(Warnings.Area::aac).containsExactly("SA_PW015");
        assertThat(Warnings.districtsNamed("tas", "Warning to Sheep Graziers for Mount Lofty Ranges forecast districts")).isEmpty();
        assertThat(Warnings.districtsNamed("sa", "Marine Wind Warning Summary for South Australia")).isEmpty();
    }

    /**
     * A fetcher over files, counting what was asked: the listings, pages and products as the Bureau served them.
     */
    static final class Files extends au.gully.platform.HttpFetcher {
        final Map<String, byte[]> files = new java.util.HashMap<>();
        final List<String> asked = new java.util.ArrayList<>();
        final List<String> forgotten = new java.util.ArrayList<>();

        Files() {
            super(org.springframework.web.client.RestClient.builder(), org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.detect(),
                    org.springframework.boot.http.client.HttpClientSettings.defaults(), PROPS);
        }

        @Override
        public au.gully.platform.Fetched get(java.net.URI uri) throws au.gully.platform.UpstreamException {
            asked.add(uri.toString());
            byte[] b = files.get(uri.toString());
            if (b == null) {
                throw new au.gully.platform.UpstreamException("HTTP 404 from " + uri, 404);
            }
            return new au.gully.platform.Fetched(200, b, null, null, null);
        }

        @Override
        public au.gully.platform.Fetched getIfChanged(java.net.URI uri) throws au.gully.platform.UpstreamException {
            return get(uri);
        }

        @Override
        public void forget(java.net.URI uri) {
            forgotten.add(uri.toString());
        }
    }

    static final au.gully.platform.GullyProperties PROPS = new au.gully.platform.GullyProperties(true, "test", "Australia/Adelaide", null, null, null);

    static byte[] listing(String sheepPublished) {
        return ("<rss version=\"2.0\"><channel>"
                + "<item><title>02/15:30 CST Marine Wind Warning Summary for South Australia</title><link>http://reg.bom.gov.au/sa/warnings/marine-wind.shtml</link><pubDate>Fri, 02 Oct 2026 06:00:11 GMT</pubDate></item>"
                + "<item><title>02/17:29 CST Warning to Sheep Graziers\nfor Murraylands, Upper South East and Lower South East forecast districts</title><link>http://reg.bom.gov.au/sa/warnings/sheep.shtml</link><pubDate>"
                + sheepPublished + "</pubDate></item></channel></rss>").getBytes();
    }

    @Test
    void aPageIsReadOnceForItsListingTimeAndAgainWhenItFails() throws Exception {
        Files http = new Files();
        http.files.put(Warnings.LISTINGS.get("sa"), listing("Fri, 02 Oct 2026 07:59:15 GMT"));
        http.files.put(Warnings.LISTINGS.get("tas"), "<rss version=\"2.0\"><channel></channel></rss>".getBytes());
        http.files.put("https://reg.bom.gov.au/sa/warnings/marine-wind.shtml", fixture("sa-warnings-marine-wind.shtml"));
        http.files.put("https://reg.bom.gov.au/fwo/IDS20201.xml", fixture("IDS20201.xml"));
        http.files.put("https://reg.bom.gov.au/sa/warnings/sheep.shtml", fixture("sa-warnings-sheep.shtml"));
        http.files.put("https://reg.bom.gov.au/fwo/IDS20242.xml", fixture("IDS20242.xml"));
        au.gully.upstreams.Ledger ledger = new au.gully.upstreams.Ledger(null) {
            @Override
            public void record(String upstream, double units, boolean ok, java.time.Duration latency, String detail) {
            }
        };
        Warnings warnings = new Warnings(http, ledger, PROPS);
        Instant now = Instant.parse("2026-10-02T08:50:00Z");

        List<Warnings.Warning> first = warnings.ensure(now);
        assertThat(first).extracting(Warnings.Warning::id).containsExactly("sa:marine-wind", "sa:sheep");
        assertThat(first.get(0).areas()).extracting(Warnings.Area::aac).containsExactly("SA_MW009", "SA_MW010");
        assertThat(first.get(1).areas()).extracting(Warnings.Area::aac).containsExactly("SA_PW007", "SA_PW004", "SA_PW005");
        assertThat(http.asked).filteredOn(u -> u.contains("/sa/warnings/") || u.contains("/fwo/IDS2")).hasSize(4);
        // A place in the Murraylands is under it; one in the Adelaide Hills, where it was cancelled, is not.
        assertThat(warnings.at("sa", List.of("SA_PW007", "SA_FW007"), now).here()).extracting(Warnings.Warning::id).containsExactly("sa:sheep");
        assertThat(warnings.at("sa", List.of("SA_PW015", "SA_FW015"), now).here()).isEmpty();

        // The listing at the same times: nothing read again but the listing.
        http.asked.clear();
        warnings.ensure(now.plus(Warnings.LIFE));
        assertThat(http.asked).containsExactlyInAnyOrder(Warnings.LISTINGS.get("sa"), Warnings.LISTINGS.get("tas"));

        // The sheep graziers' warning issued again, its page down: it stands on its title, and the listing is forgotten so the next read tries again.
        http.files.put(Warnings.LISTINGS.get("sa"), listing("Fri, 02 Oct 2026 13:30:00 GMT"));
        http.files.remove("https://reg.bom.gov.au/sa/warnings/sheep.shtml");
        http.asked.clear();
        List<Warnings.Warning> down = warnings.ensure(now.plus(Warnings.LIFE.multipliedBy(2)));
        assertThat(http.asked).doesNotContain("https://reg.bom.gov.au/sa/warnings/marine-wind.shtml").contains("https://reg.bom.gov.au/sa/warnings/sheep.shtml");
        assertThat(down.get(1).areas()).extracting(Warnings.Area::aac).containsExactly("SA_PW007", "SA_PW004", "SA_PW005");
        assertThat(down.get(1).until()).isNull();
        assertThat(http.forgotten).containsExactly(Warnings.LISTINGS.get("sa"));
        assertThat(warnings.failure()).isNull();

        // Back up: read on the next read.
        http.files.put("https://reg.bom.gov.au/sa/warnings/sheep.shtml", fixture("sa-warnings-sheep.shtml"));
        http.asked.clear();
        List<Warnings.Warning> up = warnings.ensure(now.plus(Warnings.LIFE.multipliedBy(3)));
        assertThat(http.asked).contains("https://reg.bom.gov.au/sa/warnings/sheep.shtml", "https://reg.bom.gov.au/fwo/IDS20242.xml");
        assertThat(up.get(1).until()).isEqualTo(Instant.parse("2026-10-03T14:30:00Z"));
    }

    static Warnings reading(Files http) {
        au.gully.upstreams.Ledger ledger = new au.gully.upstreams.Ledger(null) {
            @Override
            public void record(String upstream, double units, boolean ok, java.time.Duration latency, String detail) {
            }
        };
        return new Warnings(http, ledger, PROPS);
    }

    static byte[] tasListing(String... products) {
        StringBuilder rss = new StringBuilder("<rss version=\"2.0\"><channel>");
        for (String p : products) {
            rss.append("<item><title>18/22:18 EST Severe Weather Warning</title><link>http://reg.bom.gov.au/products/").append(p)
                    .append(".shtml</link><pubDate>Fri, 18 Sep 2026 12:18:39 GMT</pubDate></item>");
        }
        return rss.append("</channel></rss>").toString().getBytes();
    }

    @Test
    void aProductThatCannotBeReadIsLeftOutAndTheRestOfTheListingTakenIn() throws Exception {
        Files http = new Files();
        http.files.put(Warnings.LISTINGS.get("sa"), "<rss version=\"2.0\"><channel></channel></rss>".getBytes());
        http.files.put(Warnings.LISTINGS.get("tas"), tasListing("IDT99999", "IDT21037"));
        http.files.put("https://reg.bom.gov.au/fwo/IDT21037.xml", fixture("IDT21037.xml"));
        Warnings warnings = reading(http);
        Instant now = Instant.parse("2026-09-18T13:00:00Z");

        // IDT99999's XML is a 404: it is left out, the other taken in, and the listing forgotten so the next read tries again.
        assertThat(warnings.ensure(now)).extracting(Warnings.Warning::id).containsExactly("IDT21037");
        assertThat(warnings.failure()).isNull();
        assertThat(warnings.readAt("tas")).isEqualTo(now);
        assertThat(http.forgotten).containsExactly(Warnings.LISTINGS.get("tas"));

        // Listed again at a new time, its product now down: it stands as last read.
        http.files.put(Warnings.LISTINGS.get("tas"), new String(tasListing("IDT21037")).replace("12:18:39", "12:48:39").getBytes());
        http.files.remove("https://reg.bom.gov.au/fwo/IDT21037.xml");
        assertThat(warnings.ensure(now.plus(Warnings.LIFE))).extracting(Warnings.Warning::id).containsExactly("IDT21037");
        assertThat(warnings.failure()).isNull();
    }

    @Test
    void anUnreadListingSaysSoAndItsPagesAreDroppedOnceStale() throws Exception {
        Files http = new Files();
        http.files.put(Warnings.LISTINGS.get("sa"), listing("Fri, 02 Oct 2026 07:59:15 GMT"));
        http.files.put(Warnings.LISTINGS.get("tas"), "<rss version=\"2.0\"><channel></channel></rss>".getBytes());
        Warnings warnings = reading(http);
        Instant now = Instant.parse("2026-10-02T08:50:00Z");
        assertThat(warnings.stale(now)).as("never read").isTrue();

        // Its pages unread, both stand on their titles, in force while listed.
        assertThat(warnings.ensure(now)).extracting(Warnings.Warning::id).containsExactly("sa:marine-wind", "sa:sheep");
        assertThat(warnings.failure()).isNull();
        assertThat(warnings.stale(now)).isFalse();

        // The listing down: the failure is said, and what it last said is held a while.
        http.files.remove(Warnings.LISTINGS.get("sa"));
        assertThat(warnings.ensure(now.plus(Warnings.LIFE))).hasSize(2);
        assertThat(warnings.failure()).startsWith("sa: ");
        assertThat(warnings.stale(now.plus(Warnings.LIFE))).isFalse();

        // Unread for STALE, a page's warning is no longer in force: the listing no longer says it is.
        Instant later = now.plus(Warnings.STALE);
        assertThat(warnings.ensure(later)).isEmpty();
        assertThat(warnings.at("sa", List.of("SA_PW007"), later).here()).isEmpty();
        assertThat(warnings.stale(later)).isTrue();
        assertThat(warnings.readAt()).isEqualTo(now);

        // Back up: read again, and fresh.
        http.files.put(Warnings.LISTINGS.get("sa"), listing("Fri, 02 Oct 2026 07:59:15 GMT"));
        Instant back = later.plus(Duration.ofMinutes(5));
        assertThat(warnings.ensure(back)).hasSize(2);
        assertThat(warnings.failure()).isNull();
        assertThat(warnings.stale(back)).isFalse();
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
