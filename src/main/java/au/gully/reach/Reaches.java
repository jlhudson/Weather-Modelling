package au.gully.reach;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every station's reach under a rule, as GeoJSON: the rule in force for the map and the API, or
 * any rule the sliders ask about for the preview. Drawn on demand from the terrain in memory —
 * eighty stations at two and a half thousand samples each is a moment's arithmetic — so nothing
 * is stored but the terrain and the rule.
 */
@Service
@RequiredArgsConstructor
public class Reaches {

    private final StationRegistry stations;
    private final TerrainStore terrain;
    private final ReachRule rule;

    /**
     * Every station with terrain, its reach under the rule, as a FeatureCollection of polygons.
     */
    public Map<String, Object> geojson(ReachRule.Rule r) {
        List<Map<String, Object>> features = new ArrayList<>();
        for (Station s : stations.all()) {
            terrain.get(s.id()).ifPresent(t -> features.add(feature(s, Reach.of(t, r), t)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "FeatureCollection");
        out.put("rule", rule(r));
        out.put("inForce", r.equals(rule.current()));
        out.put("stations", stations.size());
        out.put("sampled", features.size());
        out.put("features", features);
        return out;
    }

    public Map<String, Object> geojson() {
        return geojson(rule.current());
    }

    public static Map<String, Object> rule(ReachRule.Rule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reachKm", r.reachKm());
        m.put("kmPer100m", r.kmPer100m());
        m.put("inlandPct", r.inlandPct());
        m.put("descentShare", r.descentShare());
        return m;
    }

    private static Map<String, Object> feature(Station s, Reach r, Terrain t) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", s.id());
        p.put("name", s.name());
        p.put("heightM", s.heightM());
        p.put("elevationM", t.elevationM());
        p.putAll(summary(r));
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", "Feature");
        f.put("id", s.id());
        f.put("geometry", Map.of("type", "Polygon", "coordinates", List.of(r.coordinates())));
        f.put("properties", p);
        return f;
    }

    /**
     * The figures of one reach: its area and how far it goes, and why its rays stopped.
     */
    public static Map<String, Object> summary(Reach r) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("areaKm2", r.areaKm2());
        p.put("minKm", r.minKm());
        p.put("meanKm", r.meanKm());
        p.put("maxKm", r.maxKm());
        p.put("reachKm", r.reachKm());
        p.put("inlandKm", r.inlandKm());
        p.put("waterKm", r.waterKm());
        p.put("island", r.island());
        p.put("waterRays", r.waterRays());
        Map<String, Object> cuts = new LinkedHashMap<>();
        r.cuts().forEach((k, v) -> cuts.put(k.name().toLowerCase(), v));
        p.put("cut", cuts);
        return p;
    }

    /**
     * What one station's drawer says of its terrain and its reach under the rule in force.
     */
    public Map<String, Object> detail(String stationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Terrain t = terrain.get(stationId).orElse(null);
        Map<String, Object> tm = new LinkedHashMap<>();
        tm.put("sampled", t != null);
        tm.put("sampledAt", t == null ? null : t.sampledAt().toString());
        tm.put("elevationM", t == null ? null : t.elevationM());
        tm.put("tiles", t == null ? null : t.calls());
        tm.put("inlandKm", t == null ? null : t.inlandKm());
        tm.put("points", Terrain.POINTS);
        tm.put("source", TerrainTiles.ATTRIBUTION);
        out.put("terrain", tm);
        if (t != null) {
            Reach r = Reach.of(t, rule.current());
            Map<String, Object> rm = new LinkedHashMap<>(summary(r));
            rm.put("rule", rule(rule.current()));
            List<Map<String, Object>> rays = new ArrayList<>();
            for (int b = 0; b < Terrain.BEARINGS; b++) {
                Map<String, Object> ray = new LinkedHashMap<>();
                ray.put("bearing", Terrain.bearingDeg(b));
                ray.put("km", r.km()[b]);
                ray.put("cut", r.cut()[b].name().toLowerCase());
                rays.add(ray);
            }
            rm.put("rays", rays);
            out.put("reach", rm);
        } else {
            out.put("reach", null);
        }
        return out;
    }
}
