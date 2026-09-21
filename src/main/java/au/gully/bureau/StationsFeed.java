package au.gully.bureau;

import au.gully.platform.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The stations as GeoJSON: every station in the register as a point, with its latest values and how
 * old they are. One shape for the console map and the API, so what the map draws is what a caller
 * gets.
 */
@Component
@RequiredArgsConstructor
public class StationsFeed {

    /**
     * How many of the last readings the wind's mean is taken over.
     */
    public static final int WIND_MEAN_OVER = 5;

    private final StationRegistry stations;
    private final List<java.util.function.BiConsumer<Station, Map<String, Object>>> decorators = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Who adds to every station's properties: the drought, the reach.
     */
    public void decorate(java.util.function.BiConsumer<Station, Map<String, Object>> decorator) {
        decorators.add(decorator);
    }

    public Map<String, Object> geojson() {
        Instant now = Instant.now();
        List<Map<String, Object>> features = new ArrayList<>();
        int fresh = 0;
        for (Station s : stations.all()) {
            Map<String, Object> p = properties(s, now);
            if (Boolean.TRUE.equals(p.get("fresh"))) {
                fresh++;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "Feature");
            f.put("id", s.id());
            f.put("geometry", Map.of("type", "Point", "coordinates", List.of(s.lon(), s.lat())));
            f.put("properties", p);
            features.add(f);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "FeatureCollection");
        out.put("at", now.toString());
        out.put("stations", features.stream().filter(f -> !Station.POINT.equals(((Map<?, ?>) f.get("properties")).get("kind"))).count());
        out.put("points", features.stream().filter(f -> Station.POINT.equals(((Map<?, ?>) f.get("properties")).get("kind"))).count());
        out.put("fresh", fresh);
        out.put("updatedAt", stations.lastUpdateAt() == null ? null : stations.lastUpdateAt().toString());
        out.put("features", features);
        return out;
    }

    /**
     * One station's properties: what it is, and what it last said.
     */
    public Map<String, Object> properties(Station s, Instant now) {
        Observation o = stations.latest(s.id()).orElse(null);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", s.id());
        p.put("wmoId", s.wmoId());
        p.put("name", s.name());
        p.put("kind", s.kind());
        p.put("state", s.state());
        p.put("district", s.district());
        p.put("zone", s.zone());
        p.put("heightM", s.heightM());
        p.put("at", o == null || o.at() == null ? null : o.at().toString());
        Long age = o == null || o.at() == null ? null : Duration.between(o.at(), now).toMinutes();
        p.put("ageMinutes", age);
        p.put("fresh", age != null && age < Status.STALE.toMinutes());
        p.put("temperatureC", o == null ? null : o.temperatureC());
        p.put("apparentTemperatureC", o == null ? null : o.apparentTemperatureC());
        p.put("dewPointC", o == null ? null : o.dewPointC());
        p.put("humidityPct", o == null ? null : o.humidityPct());
        p.put("windSpeedKmh", o == null ? null : o.windSpeedKmh());
        p.put("windDirectionDeg", o == null ? null : o.windDirectionDeg());
        p.put("windDirection", o == null ? null : o.windDirection());
        p.put("windGustKmh", o == null ? null : o.windGustKmh());
        p.put("pressureMslHpa", o == null ? null : o.pressureMslHpa());
        p.put("rainSince9amMm", o == null ? null : o.rainSince9amMm());
        p.put("rain24hMm", o == null ? null : o.rain24hMm());
        p.put("maxTemperatureC", o == null ? null : o.maxTemperatureC());
        p.put("minTemperatureC", o == null ? null : o.minTemperatureC());
        p.put("cloud", o == null ? null : o.cloud());
        p.put("cloudOktas", o == null ? null : o.cloudOktas());
        p.put("visibilityKm", o == null ? null : o.visibilityKm());
        p.put("deltaTC", o == null ? null : o.deltaTC());
        // The wind as a trend (W-9): the mean of the last readings, the latest included, speed as a mean and
        // direction as a vector, so a swing shows against what it has mostly been.
        WindMean w = WindMean.of(stations.recent(s.id()), WIND_MEAN_OVER);
        p.put("windMeanKmh", w == null ? null : w.kmh());
        p.put("windMeanDeg", w == null ? null : w.deg());
        p.put("windMeanGustKmh", w == null ? null : w.gustKmh());
        p.put("windMeanOver", w == null ? null : w.readings());
        p.put("windMeanMinutes", w == null ? null : w.minutes());
        for (java.util.function.BiConsumer<Station, Map<String, Object>> d : decorators) {
            d.accept(s, p);
        }
        return p;
    }

    /**
     * Everything held for one station: its properties and its last readings, newest first.
     */
    public Optional<Map<String, Object>> detail(String id) {
        return stations.station(id).map(s -> {
            Map<String, Object> out = new LinkedHashMap<>(properties(s, Instant.now()));
            out.put("lat", s.lat());
            out.put("lon", s.lon());
            List<Map<String, Object>> recent = new ArrayList<>();
            for (Observation r : stations.recent(s.id())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", r.at() == null ? null : r.at().toString());
                m.put("temperatureC", r.temperatureC());
                m.put("humidityPct", r.humidityPct());
                m.put("windSpeedKmh", r.windSpeedKmh());
                m.put("windDirectionDeg", r.windDirectionDeg());
                m.put("windGustKmh", r.windGustKmh());
                m.put("rainSince9amMm", r.rainSince9amMm());
                recent.add(m);
            }
            out.put("recent", recent);
            return out;
        });
    }
}
