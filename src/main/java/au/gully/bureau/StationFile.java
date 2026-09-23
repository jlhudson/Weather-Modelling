package au.gully.bureau;

import lombok.experimental.UtilityClass;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Bureau's per-state observation product ({@code IDx60920.xml}): every automatic weather station
 * in the state with its latest values, refreshed every ten minutes. Read with the JDK's streaming
 * parser, station by station, taking the first period (the latest) of each.
 * <p>
 * The seven products, one per state, and the state each file speaks for. The ACT's stations are in
 * the New South Wales file.
 */
@UtilityClass
public class StationFile {

    public static final Map<String, String> PRODUCTS = Map.of(
            "nsw", "IDN60920", "vic", "IDV60920", "qld", "IDQ60920", "sa", "IDS60920",
            "wa", "IDW60920", "tas", "IDT60920", "nt", "IDD60920");

    public static final String BASE = "https://reg.bom.gov.au/fwo/";

    /**
     * @param state lower-case state code, e.g. {@code sa}
     */
    public static String url(String state) {
        String product = PRODUCTS.get(state);
        if (product == null) {
            throw new IllegalArgumentException("no Bureau observation product for " + state);
        }
        return BASE + product + ".xml";
    }

    /**
     * Every station in the file with its latest observation.
     */
    public static List<StationReading> parse(byte[] xml, String state) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
        List<StationReading> out = new ArrayList<>();
        Station station = null;
        Builder values = null;
        boolean inFirstPeriod = false;
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = r.getLocalName();
                    if (name.equals("station")) {
                        station = station(r, state);
                        values = new Builder();
                        inFirstPeriod = false;
                    } else if (station != null) {
                        if (name.equals("period")) {
                            inFirstPeriod = "0".equals(r.getAttributeValue(null, "index"));
                            if (inFirstPeriod) {
                                values.at = instant(r.getAttributeValue(null, "time-utc"));
                            }
                        } else if (name.equals("element") && inFirstPeriod) {
                            String type = r.getAttributeValue(null, "type");
                            String text = r.getElementText();
                            values.put(type, text);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("station") && station != null) {
                        out.add(new StationReading(station, values.build(station.id())));
                        station = null;
                        values = null;
                    }
                }
            }
        } finally {
            r.close();
        }
        return out;
    }

    private static Station station(XMLStreamReader r, String state) {
        String bomId = r.getAttributeValue(null, "bom-id");
        String wmo = r.getAttributeValue(null, "wmo-id");
        String name = r.getAttributeValue(null, "stn-name");
        double lat = Double.parseDouble(r.getAttributeValue(null, "lat"));
        double lon = Double.parseDouble(r.getAttributeValue(null, "lon"));
        Double height = dbl(r.getAttributeValue(null, "stn-height"));
        // Some stations come tagged UTC; every station in the state keeps the state's clock, or its day would turn at 6:30 pm.
        String tz = r.getAttributeValue(null, "tz");
        if (tz == null || !tz.startsWith("Australia/")) {
            tz = "Australia/Adelaide";
        }
        String district = r.getAttributeValue(null, "forecast-district-id");
        return new Station(bomId == null ? wmo : bomId, wmo, name, lat, lon, height, tz, district, state);
    }

    static Instant instant(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso.trim()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static Double dbl(String s) {
        if (s == null || s.isBlank() || s.equals("-")) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer integer(String s) {
        Double d = dbl(s);
        return d == null ? null : (int) Math.round(d);
    }

    /**
     * Collects the elements of one period. Whichever order they arrive in, and whichever are absent.
     */
    private static final class Builder {
        Instant at;
        Double temperature, apparent, dewPoint, wind, gust, pressure, rain, rain24, maxTemp, minTemp, visibility, deltaT;
        Integer humidity, windDir, oktas;
        String windDirection, cloud;

        void put(String type, String text) {
            if (type == null) {
                return;
            }
            switch (type) {
                case "air_temperature" -> temperature = dbl(text);
                case "apparent_temp" -> apparent = dbl(text);
                case "dew_point" -> dewPoint = dbl(text);
                case "rel-humidity" -> humidity = integer(text);
                case "wind_spd_kmh" -> wind = dbl(text);
                case "wind_dir_deg" -> windDir = integer(text);
                case "wind_dir" -> windDirection = text == null ? null : text.trim();
                case "gust_kmh" -> gust = dbl(text);
                case "msl_pres" -> pressure = dbl(text);
                case "rainfall" -> rain = dbl(text);
                case "rainfall_24hr" -> rain24 = dbl(text);
                case "maximum_air_temperature" -> maxTemp = dbl(text);
                case "minimum_air_temperature" -> minTemp = dbl(text);
                case "vis_km" -> visibility = dbl(text);
                case "cloud" -> cloud = text == null || text.isBlank() ? null : text.trim();
                case "cloud_oktas" -> oktas = integer(text);
                case "delta_t" -> deltaT = dbl(text);
                default -> {
                    // knots, QNH and the rest: published for pilots, not needed here
                }
            }
        }

        Observation build(String stationId) {
            return new Observation(stationId, at, temperature, apparent, dewPoint, humidity, wind, windDir,
                    windDirection, gust, pressure, rain, rain24, maxTemp, minTemp, visibility, cloud, oktas, deltaT);
        }
    }

    /**
     * A station and what it last reported.
     */
    public record StationReading(Station station, Observation observation) {
    }
}
