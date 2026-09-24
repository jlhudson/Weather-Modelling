package au.gully.cfs;

import au.gully.bureau.StationsFeed;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A place's fire ban district and what the CFS has published for it (W-23): the district from the shapes,
 * the rating, fire behaviour index and total fire ban for today and the days ahead from the ratings feed.
 * Where the feed has nothing for today - out of season - the answer says so, with the last day it did
 * publish, rather than carrying that day as today's.
 */
@Service
public class FireBan {

    private final FireDistricts districts;
    private final FireRatings ratings;

    public FireBan(FireDistricts districts, FireRatings ratings, StationsFeed feed) {
        this.districts = districts;
        this.ratings = ratings;
        // Every station wears its district on the map and in the API, and today's published rating where there is one.
        feed.decorate((s, p) -> {
            Optional<String> d = districts.of(s.lat(), s.lon());
            p.put("fireBanDistrict", d.map(this::name).orElse(null));
            LocalDate today = LocalDate.now(FireRatings.ADELAIDE);
            Optional<FireRatings.RatingDay> r = d.flatMap(ratings::of).flatMap(x -> x.on(today));
            p.put("fireDangerRating", r.map(FireRatings.RatingDay::rating).orElse(null));
            p.put("totalFireBan", r.map(FireRatings.RatingDay::totalFireBan).orElse(null));
        });
    }

    /**
     * The district and its published days for a place, or empty outside South Australia's districts.
     */
    public Optional<Map<String, Object>> at(double lat, double lon, Instant now) {
        return districts.of(lat, lon).map(raw -> view(name(raw), ratings.of(raw).orElse(null), now));
    }

    /**
     * A district's name as the ratings feed writes it (the shapes file shouts it), or in title case.
     */
    String name(String raw) {
        return ratings.of(raw).map(FireRatings.DistrictRating::district).orElseGet(() -> titleCase(raw));
    }

    static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.trim().toLowerCase().split("\s+")) {
            b.append(b.length() == 0 ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    static Map<String, Object> view(String name, FireRatings.DistrictRating r, Instant now) {
        LocalDate today = now.atZone(FireRatings.ADELAIDE).toLocalDate();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("district", name);
        out.put("number", r == null ? null : r.number());
        out.put("aac", r == null ? null : r.aac());
        Optional<FireRatings.RatingDay> t = r == null ? Optional.empty() : r.on(today);
        out.put("today", t.map(FireBan::day).orElse(null));
        List<Map<String, Object>> ahead = new ArrayList<>();
        if (r != null) {
            r.days().stream().filter(d -> d.date() != null && !d.date().isBefore(today)).forEach(d -> ahead.add(day(d)));
        }
        out.put("days", ahead);
        out.put("current", t.isPresent());
        Optional<FireRatings.RatingDay> last = r == null ? Optional.empty() : r.latest();
        out.put("lastPublished", last.map(d -> d.date().toString()).orElse(null));
        out.put("note", r == null ? "the CFS ratings could not be read"
                : t.isPresent() ? null : "the CFS has published no rating for today" + last.map(d -> "; the last was for " + d.date()).orElse("") + " (out of the fire danger season)");
        out.put("source", "CFS, South Australia Fire Danger Ratings");
        return out;
    }

    private static Map<String, Object> day(FireRatings.RatingDay d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.date().toString());
        m.put("rating", d.rating());
        m.put("fbi", d.fbi());
        m.put("totalFireBan", d.totalFireBan());
        return m;
    }

    private static List<List<Double>> ring(org.locationtech.jts.geom.Coordinate[] coords) {
        List<List<Double>> out = new ArrayList<>();
        for (org.locationtech.jts.geom.Coordinate c : coords) {
            out.add(List.of(Math.round(c.x * 1e4) / 1e4, Math.round(c.y * 1e4) / 1e4));
        }
        return out;
    }

    /**
     * What the CFS published for each district today, in a line, by the district's name in capitals: the curing page.
     */
    public Map<String, String> today(Instant now) {
        LocalDate today = now.atZone(FireRatings.ADELAIDE).toLocalDate();
        Map<String, String> out = new LinkedHashMap<>();
        ratings.ensure(now).values().forEach(r -> r.on(today).ifPresent(d -> out.put(r.district().toUpperCase(),
                d.rating() + (d.fbi() == null ? "" : " (FBI " + d.fbi() + ")") + (d.totalFireBan() ? " 00b7 TOTAL FIRE BAN" : ""))));
        return out;
    }

    /**
     * Every district's shape with today's published rating, for the map's layer: GeoJSON polygons, holes kept.
     */
    public Map<String, Object> geojson(Instant now) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (FireDistricts.District d : districts.ensure(now)) {
            Map<String, Object> props = view(name(d.name()), ratings.of(d.name()).orElse(null), now);
            List<List<List<List<Double>>>> polygons = new ArrayList<>();
            for (org.locationtech.jts.geom.Polygon p : d.polygons()) {
                List<List<List<Double>>> rings = new ArrayList<>();
                rings.add(ring(p.getExteriorRing().getCoordinates()));
                for (int i = 0; i < p.getNumInteriorRing(); i++) {
                    rings.add(ring(p.getInteriorRingN(i).getCoordinates()));
                }
                polygons.add(rings);
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("geometry", Map.of("type", "MultiPolygon", "coordinates", polygons));
            f.put("properties", props);
            features.add(f);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "FeatureCollection");
        out.put("readAt", districts.readAt() == null ? null : districts.readAt().toString());
        out.put("ratingsReadAt", ratings.readAt() == null ? null : ratings.readAt().toString());
        out.put("features", features);
        return out;
    }
}
