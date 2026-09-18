package au.gully.bureau;

import lombok.experimental.UtilityClass;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Bureau's warnings, as two kinds of file on the same server as the stations (docs/06 item 5):
 * a per-state RSS listing the current warnings, refreshed every ten minutes, and the product XML each
 * item points at, which carries the districts the warning covers and the hazard's own times.
 */
@UtilityClass
public class WarningFiles {

    /**
     * The seven listings, read live 18 September 2026. The numbering is the Bureau's and is not in
     * state order.
     */
    public static final Map<String, String> FEEDS = Map.of(
            "nsw", "IDZ00054.warnings_nsw", "nt", "IDZ00055.warnings_nt", "qld", "IDZ00056.warnings_qld",
            "sa", "IDZ00057.warnings_sa", "tas", "IDZ00058.warnings_tas", "vic", "IDZ00059.warnings_vic",
            "wa", "IDZ00060.warnings_wa");

    public static final String BASE = "https://reg.bom.gov.au/fwo/";

    /**
     * A product page link names the product: {@code .../products/IDT21037.shtml}. The marine wind
     * summaries link to a page, not a product, and carry no districts to join on, so they are skipped.
     */
    private static final Pattern PRODUCT = Pattern.compile("/(ID[A-Z]\\d{5})\\.shtml");

    public static String feedUrl(String state) {
        String feed = FEEDS.get(state);
        if (feed == null) {
            throw new IllegalArgumentException("no Bureau warnings feed for " + state);
        }
        return BASE + feed + ".xml";
    }

    public static String productUrl(String productId) {
        return BASE + productId + ".xml";
    }

    /**
     * The items of one state's listing that point at a product.
     */
    public static List<Item> parseFeed(byte[] rss) throws XMLStreamException {
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
                    Matcher m = link == null ? null : PRODUCT.matcher(link);
                    if (m != null && m.find()) {
                        out.add(new Item(m.group(1), squash(title), link.trim(), rfc1123(pubDate)));
                    }
                }
            }
        } finally {
            r.close();
        }
        return out;
    }

    /**
     * One product XML as a warning. Null when the file is not a warning product (the listing can name
     * a forecast) or names no district.
     */
    public static Warning parseProduct(byte[] xml, String state, String link) throws XMLStreamException {
        XMLStreamReader r = reader(xml);
        String id = null, productType = null, title = null, phenomena = null, headline = null;
        String hazardType = null, severity = null;
        Instant issued = null, expiry = null, hazardFrom = null, hazardUntil = null;
        Set<String> districts = new LinkedHashSet<>();
        String textType = null;
        boolean inAreaList = false;
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
                            if (text.isEmpty()) {
                                break;
                            }
                            if ("warning_title".equals(textType) && title == null) {
                                title = text;
                            } else if ("warning_phenomena_summary".equals(textType) && phenomena == null) {
                                phenomena = text;
                            }
                        }
                        case "hazard" -> {
                            if (hazardType == null) {
                                hazardType = r.getAttributeValue(null, "type");
                                severity = r.getAttributeValue(null, "severity");
                                hazardFrom = StationFile.instant(r.getAttributeValue(null, "start-time-utc"));
                                hazardUntil = StationFile.instant(r.getAttributeValue(null, "end-time-utc"));
                            }
                        }
                        case "area-list" -> inAreaList = true;
                        case "area" -> {
                            String aac = r.getAttributeValue(null, "aac");
                            String type = r.getAttributeValue(null, "type");
                            if (inAreaList && aac != null && ("public-district".equals(type) || aac.contains("_PW"))) {
                                districts.add(aac);
                            }
                        }
                        default -> {
                        }
                    }
                    // A warning_headline is a text element with its own content and no <p>.
                    if (name.equals("text") && "warning_headline".equals(textType) && headline == null) {
                        String text = squash(r.getElementText());
                        if (!text.isEmpty()) {
                            headline = text;
                        }
                        textType = null;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = r.getLocalName();
                    if (name.equals("area-list")) {
                        inAreaList = false;
                    } else if (name.equals("text")) {
                        textType = null;
                    }
                }
            }
        } finally {
            r.close();
        }
        if (id == null || !"W".equals(productType) || districts.isEmpty()) {
            return null;
        }
        return new Warning(id, state, title, phenomena, headline, hazardType, severity, new ArrayList<>(districts),
                issued, hazardFrom, hazardUntil == null ? expiry : hazardUntil, link);
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
     * One listed warning: which product, and when the listing says it was published.
     */
    public record Item(String productId, String title, String link, Instant publishedAt) {
    }
}
