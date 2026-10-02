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
 * weather districts ({@code SA_FW…}), river basins for a flood - and the hazard's own times. Read when asked and held
 * {@link #LIFE}, never on a clock (W-15); each listing by conditional GET, each product once for as long as its listing
 * names it at the same time. An item linking to a page rather than a product - the marine wind summary, a warning to
 * sheep graziers - is a warning of its own with no areas (W-48). A place is under its own state's warnings, and the rest
 * of that state's are listed beside.
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
     * A listing item names its product: {@code .../products/IDS21037.shtml}.
     */
    private static final Pattern PRODUCT = Pattern.compile("/(ID[A-Z]\\d{5})\\.shtml");
    /**
     * Or links to a page, not a product - {@code .../sa/warnings/sheep.shtml} - and carries no areas (W-48).
     */
    private static final Pattern PAGE = Pattern.compile("/([\\w-]+)\\.shtml$");
    /**
     * The Bureau's time stamp at the head of a listed title: {@code 02/04:44 CST }.
     */
    private static final Pattern STAMP = Pattern.compile("^\\d{2}/\\d{2}:\\d{2} [A-Z]{3,4} ");

    private final HttpFetcher http;
    private final Ledger ledger;
    private final boolean enabled;
    private final Map<String, Listing> listings = new LinkedHashMap<>();

    /**
     * One state's listing as last read: the products it named, and the warnings among them.
     */
    private static final class Listing {
        private final String state;
        private final URI uri;
        private final Map<String, Warning> products = new ConcurrentHashMap<>();
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
     * @param areas every area the warning covers: its code, name and type; none for a page
     * @param from  when the hazard begins, or the issue time
     * @param until when it ends, or the product's expiry; null for a page, in force while listed
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
                return l.current;
            }
            l.triedAt = now;
            long started = System.nanoTime();
            try {
                Fetched f = http.getIfChanged(l.uri);
                if (!f.notModified()) {
                    List<Warning> fresh = new ArrayList<>();
                    for (Item item : parseListing(l.state, f.body())) {
                        if (item.productId() == null) {
                            fresh.add(page(item));
                            continue;
                        }
                        Warning w = l.products.get(item.productId());
                        if (w == null || !Objects.equals(w.listedAt(), item.publishedAt())) {
                            byte[] xml = http.get(URI.create("https://reg.bom.gov.au/fwo/" + item.productId() + ".xml")).body();
                            w = parseProduct(xml, item);
                            if (w == null) {
                                continue;
                            }
                            l.products.put(item.productId(), w);
                        }
                        fresh.add(w);
                    }
                    Set<String> listed = new HashSet<>(fresh.stream().map(Warning::id).toList());
                    l.products.keySet().removeIf(id -> !listed.contains(id));
                    l.current = List.copyOf(fresh);
                    ledger.record(ID, 0, true, Duration.ofNanos(System.nanoTime() - started), "warnings " + l.state + ", " + fresh.size() + " in force");
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
            return l.current;
        }
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
     */
    static Warning parseProduct(byte[] xml, Item item) throws XMLStreamException {
        XMLStreamReader r = reader(xml);
        String id = null, productType = null, title = null, phenomena = null, headline = null, hazardType = null, severity = null, textType = null;
        Instant issued = null, expiry = null, from = null, until = null;
        Map<String, Area> areas = new LinkedHashMap<>();
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
                            if (hazardType == null) {
                                hazardType = r.getAttributeValue(null, "type");
                                severity = r.getAttributeValue(null, "severity");
                                from = StationFile.instant(r.getAttributeValue(null, "start-time-utc"));
                                until = StationFile.instant(r.getAttributeValue(null, "end-time-utc"));
                            }
                        }
                        case "area" -> {
                            String aac = r.getAttributeValue(null, "aac");
                            String type = r.getAttributeValue(null, "type");
                            // Every area the warning names, but the state or region it is filed under.
                            if (aac != null && !"region".equals(type) && !"state".equals(type)) {
                                areas.putIfAbsent(aac, new Area(aac, r.getAttributeValue(null, "description"), type));
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
                }
            }
        } finally {
            r.close();
        }
        if (id == null || !"W".equals(productType) || areas.isEmpty()) {
            return null;
        }
        String t = title == null ? item.title() : title;
        return new Warning(id, item.state(), t, headline, phenomena, kind(hazardType, t), hazardType, severity, new ArrayList<>(areas.values()),
                issued, from == null ? issued : from, until == null ? expiry : until, item.link(), item.publishedAt());
    }

    /**
     * An item linking to a page as a warning of its own (W-48): named by its state and page, titled as listed without
     * the Bureau's time stamp, issued when listed, covering no area, in force for as long as the listing names it.
     */
    static Warning page(Item item) {
        Matcher m = PAGE.matcher(item.link());
        String name = m.find() ? m.group(1) : item.link();
        String title = STAMP.matcher(item.title()).replaceFirst("");
        return new Warning(item.state() + ":" + name, item.state(), title, null, null, kind(null, title), null, null, List.of(),
                item.publishedAt(), item.publishedAt(), null, item.link(), item.publishedAt());
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
     * What is wrong, state by state, or null while every listing reads.
     */
    public String failure() {
        List<String> out = listings.values().stream().filter(l -> l.failure != null).map(l -> l.state + ": " + l.failure).toList();
        return out.isEmpty() ? null : String.join("; ", out);
    }
}
