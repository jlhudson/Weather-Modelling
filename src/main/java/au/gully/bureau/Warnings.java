package au.gully.bureau;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.UpstreamException;
import au.gully.upstreams.Ledger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Bureau's warnings for South Australia (W-25) and Tasmania (W-47): each state's listing, and the product each item
 * points at, which carries the areas the warning covers - public districts ({@code SA_PW…}, {@code TAS_PW…}), fire
 * weather districts ({@code SA_FW…}), coastal waters ({@code SA_MW…}), river basins for a flood - and the hazard's own
 * times; an area the product cancels is not covered. Read when asked and held {@link #LIFE}, never on a clock (W-15);
 * each listing by conditional GET, each product once for as long as its listing names it at the same time. An item
 * linking to a page rather than a product - the marine wind summary, a warning to sheep graziers - is a warning of its
 * own (W-48), whose areas are its product's: the page names it (W-49). A place is under its own state's warnings, and
 * the rest of that state's are listed beside.
 */
@Slf4j
@Component
public class Warnings {

    public static final String ID = "bureau-warnings";
    /**
     * Each state's listing, by the Bureau's lower-case state code.
     */
    public static final Map<String, String> LISTINGS = Map.of(
            "sa", "https://reg.bom.gov.au/fwo/IDZ00057.warnings_sa.xml",
            "tas", "https://reg.bom.gov.au/fwo/IDZ00058.warnings_tas.xml");
    public static final Duration LIFE = Duration.ofMinutes(10);
    /**
     * How long a listing may go unread before what it last said is stale: a page's warning, in force only while listed, is
     * then dropped rather than held through the outage, and the answer says so.
     */
    public static final Duration STALE = Duration.ofMinutes(30);

    /**
     * A listing item names its product: {@code .../products/IDS21037.shtml}.
     */
    private static final Pattern PRODUCT = Pattern.compile("/(ID[A-Z]\\d{5})\\.shtml");
    /**
     * Or links to a page, not a product - {@code .../sa/warnings/sheep.shtml} (W-48).
     */
    private static final Pattern PAGE = Pattern.compile("/([\\w-]+)\\.shtml$");
    /**
     * The product a page shows, named at its head: {@code <p class="p-id">IDS20201</p>} (W-49).
     */
    private static final Pattern PAGE_PRODUCT = Pattern.compile("class=\"p-id\"[^>]*>\\s*(ID[A-Z]\\d{5})\\s*<");
    /**
     * The Bureau's time stamp at the head of a listed title: {@code 02/04:44 CST }.
     */
    private static final Pattern STAMP = Pattern.compile("^\\d{2}/\\d{2}:\\d{2} [A-Z]{3,4} ");
    /**
     * The forecast districts a listed title names, after its last "for": {@code … for Mount Lofty Ranges, Kangaroo Island
     * and Murraylands forecast districts}.
     */
    private static final Pattern DISTRICTS_NAMED = Pattern.compile(".*\\bfor (?:the )?(.+?) forecast districts?\\W*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /**
     * Where the Bureau keeps each product's XML.
     */
    static final String PRODUCTS = "https://reg.bom.gov.au/fwo/";
    /**
     * The public forecast districts by name, numbered as the Bureau numbers them in its public weather forecast districts
     * (IDM00001) and names them in its products' areas: what a title's districts are matched against (W-49).
     */
    static final Map<String, Map<String, Area>> PUBLIC_DISTRICTS = Map.of(
            "sa", districts("SA_PW", "Adelaide Metropolitan", "Yorke Peninsula", "Kangaroo Island", "Upper South East", "Lower South East",
                    "Riverland", "Murraylands", "Mid North", "Flinders", "West Coast", "Eastern Eyre Peninsula", "Lower Eyre Peninsula",
                    "North West Pastoral", "North East Pastoral", "Mount Lofty Ranges"),
            "tas", districts("TAS_PW", "Furneaux Islands", "North East", "East Coast", "Central North", "Midlands", "South East",
                    "Upper Derwent Valley", "Central Plateau", "Western", "North West Coast", "King Island"));

    private final HttpFetcher http;
    private final Ledger ledger;
    private final boolean enabled;
    private final Map<String, Listing> listings = new LinkedHashMap<>();

    /**
     * One state's listing as last read: each product and page it named, as read for its listing time, and the warnings among them.
     */
    private static final class Listing {
        private final String state;
        private final URI uri;
        private final Map<String, Warning> held = new ConcurrentHashMap<>();
        private volatile List<Warning> current = List.of();
        private volatile Instant readAt;
        private volatile Instant triedAt;
        private volatile String failure;

        Listing(String state, String url) {
            this.state = state;
            this.uri = URI.create(url);
        }
    }

    public Warnings(HttpFetcher http, Ledger ledger, au.gully.platform.GullyProperties properties) {
        this.http = http;
        this.ledger = ledger;
        this.enabled = properties.enabled();
        StationReader.STATES.stream().filter(LISTINGS::containsKey).forEach(s -> listings.put(s, new Listing(s, LISTINGS.get(s))));
    }

    /**
     * One warning.
     *
     * @param id    the product's identifier, or for a page the state and the page: {@code sa:sheep}
     * @param state the Bureau's lower-case code for the state whose listing names it
     * @param kind  {@code fire weather}, {@code severe weather}, {@code flood}, {@code severe thunderstorm},
     *              {@code marine wind}, {@code sheep graziers} or {@code other}: from the hazard's code, else the title
     * @param areas every area the warning covers, its code, name and type: none that its product cancels; for a page, its
     *              product's, else the forecast districts its title names
     * @param from  when the hazard begins, or the issue time
     * @param until when it ends, or the product's expiry; null for a page whose product was not read, in force while listed
     */
    public record Warning(String id, String state, String title, String headline, String phenomena, String kind, String hazardType, String severity,
                          List<Area> areas, Instant issued, Instant from, Instant until, String link, Instant listedAt) {

        public boolean covers(Collection<String> aacs) {
            return areas.stream().anyMatch(a -> aacs.contains(a.aac()));
        }
    }

    public record Area(String aac, String name, String type) {
    }

    /**
     * One item of a state's listing.
     *
     * @param productId the product it names, or null where it links to a page
     */
    public record Item(String state, String productId, String title, String link, Instant publishedAt) {
    }

    /**
     * What reads a file for the warnings: the service's fetcher, or a test's fixtures.
     */
    interface Fetch {
        byte[] get(URI uri) throws UpstreamException;
    }

    /**
     * Every state's warnings in force, each listing read now when older than {@link #LIFE}.
     */
    public List<Warning> ensure(Instant now) {
        List<Warning> out = new ArrayList<>();
        for (Listing l : listings.values()) {
            out.addAll(ensure(l, now));
        }
        return out;
    }

    private List<Warning> ensure(Listing l, Instant now) {
        synchronized (l) {
            boolean due = l.readAt == null || Duration.between(l.readAt, now).compareTo(LIFE) >= 0;
            boolean resting = l.triedAt != null && l.failure != null && Duration.between(l.triedAt, now).compareTo(Duration.ofMinutes(5)) < 0;
            if (!enabled || !due || resting) {
                return listed(l, now);
            }
            l.triedAt = now;
            long started = System.nanoTime();
            try {
                Fetched f = http.getIfChanged(l.uri);
                if (!f.notModified()) {
                    List<Warning> fresh = new ArrayList<>();
                    int unread = 0;
                    for (Item item : parseListing(l.state, f.body())) {
                        if (item.productId() == null) {
                            Warning w = l.held.get(pageId(item));
                            if (w == null || !Objects.equals(w.listedAt(), item.publishedAt())) {
                                try {
                                    w = page(item, uri -> http.get(uri).body());
                                    l.held.put(w.id(), w);
                                } catch (UpstreamException | XMLStreamException | RuntimeException e) {
                                    // Unread, the page stands on its title's districts until the listing is read whole again.
                                    w = page(item);
                                    unread++;
                                    log.warn("bureau warnings {}: the page {} could not be read: {}", l.state, item.link(), e.getMessage());
                                }
                            }
                            fresh.add(w);
                            continue;
                        }
                        Warning w = l.held.get(item.productId());
                        if (w == null || !Objects.equals(w.listedAt(), item.publishedAt())) {
                            Warning read;
                            try {
                                read = parseProduct(http.get(URI.create(PRODUCTS + item.productId() + ".xml")).body(), item);
                            } catch (UpstreamException | XMLStreamException | RuntimeException e) {
                                // Unread, the product stands as last read, or is left out, until the listing is read whole again;
                                // the rest of the listing is taken in.
                                unread++;
                                log.warn("bureau warnings {}: the product {} could not be read: {}", l.state, item.productId(), e.getMessage());
                                if (w != null) {
                                    fresh.add(w);
                                }
                                continue;
                            }
                            if (read == null) {
                                continue;
                            }
                            w = read;
                            l.held.put(item.productId(), w);
                        }
                        fresh.add(w);
                    }
                    Set<String> listed = new HashSet<>(fresh.stream().map(Warning::id).toList());
                    l.held.keySet().removeIf(id -> !listed.contains(id));
                    l.current = List.copyOf(fresh);
                    if (unread > 0) {
                        http.forget(l.uri);
                    }
                    ledger.record(ID, 0, true, Duration.ofNanos(System.nanoTime() - started), "warnings " + l.state + ", " + fresh.size() + " in force"
                            + (unread > 0 ? ", " + unread + " unread" : ""));
                }
                l.readAt = now;
                l.failure = null;
            } catch (UpstreamException | XMLStreamException | RuntimeException e) {
                l.failure = e.getMessage();
                // Not taken in: heard again whole next time rather than "unchanged".
                http.forget(l.uri);
                ledger.record(ID, 0, false, Duration.ofNanos(System.nanoTime() - started), "warnings " + l.state + ": " + e.getMessage());
                log.warn("bureau warnings {}: {}", l.state, e.getMessage());
            }
            return listed(l, now);
        }
    }

    /**
     * A listing's warnings as last read, but its pages' when it has not been read for {@link #STALE}: a page is in force
     * only while listed, and an unread listing no longer says it is.
     */
    private static List<Warning> listed(Listing l, Instant now) {
        if (!stale(l, now)) {
            return l.current;
        }
        return l.current.stream().filter(w -> w.until() != null).toList();
    }

    private static boolean stale(Listing l, Instant now) {
        return l.readAt == null || Duration.between(l.readAt, now).compareTo(STALE) >= 0;
    }

    /**
     * A state's warnings in force covering any of a place's areas, and the rest of that state's; none for a state whose
     * listing is not read.
     */
    public Split at(String state, Collection<String> aacs, Instant now) {
        List<Warning> here = new ArrayList<>(), elsewhere = new ArrayList<>();
        Listing l = listings.get(state);
        for (Warning w : l == null ? List.<Warning>of() : ensure(l, now)) {
            if (w.until() != null && w.until().isBefore(now)) {
                continue;
            }
            (w.covers(aacs) ? here : elsewhere).add(w);
        }
        return new Split(here, elsewhere);
    }

    public record Split(List<Warning> here, List<Warning> elsewhere) {
    }

    public static Map<String, Object> view(Warning w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id());
        m.put("state", w.state());
        m.put("kind", w.kind());
        m.put("title", w.title());
        m.put("headline", w.headline());
        m.put("phenomena", w.phenomena());
        m.put("severity", w.severity());
        m.put("issued", w.issued() == null ? null : w.issued().toString());
        m.put("from", w.from() == null ? null : w.from().toString());
        m.put("until", w.until() == null ? null : w.until().toString());
        m.put("areas", w.areas().stream().map(a -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("aac", a.aac());
            x.put("name", a.name());
            x.put("type", a.type());
            return x;
        }).toList());
        m.put("link", w.link());
        return m;
    }

    // ---------------------------------------------------------------- the files

    static List<Item> parseListing(String state, byte[] rss) throws XMLStreamException {
        XMLStreamReader r = reader(rss);
        List<Item> out = new ArrayList<>();
        String title = null, link = null, pubDate = null;
        boolean inItem = false;
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if (name.equals("item")) {
                        inItem = true;
                        title = link = pubDate = null;
                    } else if (inItem) {
                        switch (name) {
                            case "title" -> title = r.getElementText();
                            case "link" -> link = r.getElementText();
                            case "pubDate" -> pubDate = r.getElementText();
                            default -> {
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("item")) {
                    inItem = false;
                    if (link == null) {
                        continue;
                    }
                    Matcher m = PRODUCT.matcher(link);
                    if (m.find()) {
                        out.add(new Item(state, m.group(1), squash(title), link.trim(), rfc1123(pubDate)));
                    } else if (PAGE.matcher(link.trim()).find()) {
                        out.add(new Item(state, null, squash(title), link.trim(), rfc1123(pubDate)));
                    }
                }
            }
        } finally {
            r.close();
        }
        return out;
    }

    /**
     * One product as a warning; null when it is not a warning ({@code product-type} other than {@code W}) or names no area.
     * An area the product cancels - its phase, or its hazard's, {@code CAN} - is not one it covers (W-49): a warning to
     * sheep graziers renewed for three districts and cancelled for two covers the three, and a product that cancels every
     * area it names covers none. The hazard's kind, severity and times are the first one not cancelled.
     */
    static Warning parseProduct(byte[] xml, Item item) throws XMLStreamException {
        XMLStreamReader r = reader(xml);
        String id = null, productType = null, title = null, phenomena = null, headline = null, hazardType = null, severity = null, textType = null;
        Instant issued = null, expiry = null, from = null, until = null;
        Map<String, Area> areas = new LinkedHashMap<>();
        Set<String> cancelled = new HashSet<>();
        boolean inCancelledHazard = false;
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    switch (name) {
                        case "identifier" -> id = r.getElementText().trim();
                        case "product-type" -> productType = r.getElementText().trim();
                        case "issue-time-utc" -> issued = StationFile.instant(r.getElementText());
                        case "expiry-time" -> expiry = StationFile.instant(r.getElementText());
                        case "text" -> textType = r.getAttributeValue(null, "type");
                        case "p" -> {
                            String text = squash(r.getElementText());
                            if (!text.isEmpty() && "warning_title".equals(textType) && title == null) {
                                title = text;
                            } else if (!text.isEmpty() && "warning_phenomena_summary".equals(textType) && phenomena == null) {
                                phenomena = text;
                            }
                        }
                        case "hazard" -> {
                            inCancelledHazard = "CAN".equals(r.getAttributeValue(null, "phase")) || "CAN".equals(r.getAttributeValue(null, "severity"));
                            if (hazardType == null && !inCancelledHazard) {
                                hazardType = r.getAttributeValue(null, "type");
                                severity = r.getAttributeValue(null, "severity");
                                from = StationFile.instant(r.getAttributeValue(null, "start-time-utc"));
                                until = StationFile.instant(r.getAttributeValue(null, "end-time-utc"));
                            }
                        }
                        case "area" -> {
                            String aac = r.getAttributeValue(null, "aac");
                            String type = r.getAttributeValue(null, "type");
                            // Every area the warning names, but the state or region it is filed under, and those it cancels.
                            if (aac != null && !"region".equals(type) && !"state".equals(type)) {
                                if (inCancelledHazard || "CAN".equals(r.getAttributeValue(null, "phase"))) {
                                    cancelled.add(aac);
                                } else {
                                    areas.putIfAbsent(aac, new Area(aac, r.getAttributeValue(null, "description"), type));
                                }
                            }
                        }
                        default -> {
                        }
                    }
                    if (name.equals("text") && "warning_headline".equals(textType) && headline == null) {
                        String text = squash(r.getElementText());
                        if (!text.isEmpty()) {
                            headline = text;
                        }
                        textType = null;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("text")) {
                    textType = null;
                } else if (event == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("hazard")) {
                    inCancelledHazard = false;
                }
            }
        } finally {
            r.close();
        }
        if (id == null || !"W".equals(productType) || areas.isEmpty() && cancelled.isEmpty()) {
            return null;
        }
        String t = title == null ? item.title() : title;
        return new Warning(id, item.state(), t, headline, phenomena, kind(hazardType, t), hazardType, severity, new ArrayList<>(areas.values()),
                issued, from == null ? issued : from, until == null ? expiry : until, item.link(), item.publishedAt());
    }

    /**
     * An item linking to a page, as a warning of its own (W-48) covering the areas of the product the page shows (W-49):
     * the marine wind summary's coastal waters, a warning to sheep graziers' districts, with the product's hazard and
     * times, under the page's name, title and link. The page names its product at its head; the product's XML is where any
     * product's is. A page off the Bureau's site is not read, and one that names no product, or whose product is not a
     * warning, is {@link #page(Item) as its title says}. Throws where the page or its product cannot be read.
     */
    static Warning page(Item item, Fetch fetch) throws UpstreamException, XMLStreamException {
        Warning listed = page(item);
        URI uri = onTheBureau(item.link());
        if (uri == null) {
            return listed;
        }
        String productId = productOf(fetch.get(uri));
        if (productId == null) {
            return listed;
        }
        Warning product = parseProduct(fetch.get(URI.create(PRODUCTS + productId + ".xml")), item);
        if (product == null) {
            return listed;
        }
        return new Warning(listed.id(), listed.state(), listed.title(), product.headline(), product.phenomena(),
                kind(product.hazardType(), listed.title()), product.hazardType(), product.severity(), product.areas(),
                product.issued(), product.from(), product.until(), item.link(), item.publishedAt());
    }

    /**
     * An item linking to a page, as its listing says it (W-48): named by its state and page, titled as listed without the
     * Bureau's time stamp, issued when listed, in force for as long as the listing names it, covering the forecast
     * districts its title names, if it names any.
     */
    static Warning page(Item item) {
        String title = STAMP.matcher(item.title()).replaceFirst("");
        return new Warning(pageId(item), item.state(), title, null, null, kind(null, title), null, null, districtsNamed(item.state(), title),
                item.publishedAt(), item.publishedAt(), null, item.link(), item.publishedAt());
    }

    /**
     * A page's warning's name: its state and its page, {@code sa:sheep}.
     */
    static String pageId(Item item) {
        Matcher m = PAGE.matcher(item.link());
        return item.state() + ":" + (m.find() ? m.group(1) : item.link());
    }

    /**
     * The product a page shows, or null where it names none.
     */
    static String productOf(byte[] html) {
        Matcher m = PAGE_PRODUCT.matcher(new String(html, java.nio.charset.StandardCharsets.ISO_8859_1));
        return m.find() ? m.group(1) : null;
    }

    /**
     * A link the listing gives, read from the Bureau's own host as the listing is; null for a link elsewhere.
     */
    static URI onTheBureau(String link) {
        try {
            URI u = URI.create(link.trim());
            String host = u.getHost();
            if (host == null || !(host.equals("reg.bom.gov.au") || host.equals("www.bom.gov.au") || host.equals("bom.gov.au")) || u.getRawPath() == null) {
                return null;
            }
            return URI.create("https://reg.bom.gov.au" + u.getRawPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The public forecast districts a title names (W-49), by the Bureau's names for its state's districts: {@code Warning to
     * Sheep Graziers for Mount Lofty Ranges, Kangaroo Island and Murraylands forecast districts} is {@code SA_PW015},
     * {@code SA_PW003} and {@code SA_PW007}. "Parts of" a district is the district, as the Bureau's own products list it;
     * a name that is not one of the state's districts is left out, never guessed.
     */
    static List<Area> districtsNamed(String state, String title) {
        Map<String, Area> known = PUBLIC_DISTRICTS.get(state);
        Matcher m = DISTRICTS_NAMED.matcher(squash(title));
        if (known == null || !m.matches()) {
            return List.of();
        }
        Map<String, Area> out = new LinkedHashMap<>();
        for (String part : m.group(1).split(",|\\s+and\\s+")) {
            Area a = known.get(part.trim().replaceFirst("(?i)^(parts of |the )+", "").trim().toLowerCase(java.util.Locale.ROOT));
            if (a != null) {
                out.putIfAbsent(a.aac(), a);
            }
        }
        return List.copyOf(out.values());
    }

    private static Map<String, Area> districts(String prefix, String... names) {
        Map<String, Area> out = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            String aac = prefix + String.format("%03d", i + 1);
            out.put(names[i].toLowerCase(java.util.Locale.ROOT), new Area(aac, names[i], "public-district"));
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * What kind of warning, in words: from the hazard's code where it says, else the title.
     */
    static String kind(String hazardType, String title) {
        String t = title == null ? "" : title.toLowerCase();
        if ("FWW".equals(hazardType) || t.contains("fire weather")) {
            return "fire weather";
        }
        if (t.contains("flood") || (hazardType != null && hazardType.startsWith("FL"))) {
            return "flood";
        }
        if (t.contains("thunderstorm") || "STW".equals(hazardType)) {
            return "severe thunderstorm";
        }
        if ("SWW".equals(hazardType) || t.contains("severe weather")) {
            return "severe weather";
        }
        if (t.contains("marine wind")) {
            return "marine wind";
        }
        if (t.contains("sheep graziers")) {
            return "sheep graziers";
        }
        return "other";
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    static Instant rfc1123(String s) {
        if (s == null) {
            return null;
        }
        try {
            return ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Every state's warnings as last read, without reading.
     */
    public List<Warning> current() {
        return listings.values().stream().flatMap(l -> l.current.stream()).toList();
    }

    /**
     * When the listing read longest ago was read: every state's is at least as fresh; null until each has been read.
     */
    public Instant readAt() {
        Instant oldest = null;
        for (Listing l : listings.values()) {
            if (l.readAt == null) {
                return null;
            }
            oldest = oldest == null || l.readAt.isBefore(oldest) ? l.readAt : oldest;
        }
        return oldest;
    }

    /**
     * When a state's listing was last read, or null.
     */
    public Instant readAt(String state) {
        Listing l = listings.get(state);
        return l == null ? null : l.readAt;
    }

    /**
     * Whether any state's listing has gone unread for {@link #STALE}, or was never read.
     */
    public boolean stale(Instant now) {
        return listings.values().stream().anyMatch(l -> stale(l, now));
    }

    /**
     * What is wrong, state by state, or null while every listing reads.
     */
    public String failure() {
        List<String> out = listings.values().stream().filter(l -> l.failure != null).map(l -> l.state + ": " + l.failure).toList();
        return out.isEmpty() ? null : String.join("; ", out);
    }
}
