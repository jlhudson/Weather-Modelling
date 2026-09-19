package au.gully.hexagons;

import au.gully.bureau.Observation;
import au.gully.bureau.StationRegistry;
import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The drift ledger (W-12): the latest {@link Drift} per hexagon, held in memory for the reading and
 * the map, and every comparison written to {@code forecast_drift} so the accuracy of the forecasts
 * over a day, a week, a season can be asked for — per hexagon, per station, per upstream. A
 * comparison is made whenever a hexagon with a station and a forecast is looked at, and written only
 * when the station has a newer observation than the last one written, so a hexagon asked about
 * every minute still writes one row per ten-minute station file.
 */
@Slf4j
@Service
public class Drifts {

    private final StationRegistry stations;
    private final JdbcClient db;
    private final Map<String, Drift> latest = new ConcurrentHashMap<>();

    public Drifts(StationRegistry stations, JdbcClient db) {
        this.stations = stations;
        this.db = db;
    }

    /**
     * The station against the forecast, for a hexagon with both and a fresh observation; recorded
     * when the observation is new. Empty otherwise.
     */
    public Optional<Drift> check(Hexagon h, Instant now) {
        if (h.stationId() == null || h.forecast() == null) {
            return Optional.empty();
        }
        Optional<Observation> o = stations.latest(h.stationId());
        if (o.isEmpty() || o.get().at() == null || Duration.between(o.get().at(), now).compareTo(FirePictures.STATION_STALE) >= 0) {
            return Optional.empty();
        }
        ZoneId zone = h.zone() == null ? ZoneId.of("Australia/Adelaide") : ZoneId.of(h.zone());
        Optional<Drift> drift = Drift.of(o.get(), h.forecast(), zone);
        drift.ifPresent(d -> {
            Drift held = latest.get(h.id());
            // The same observation against the same forecast is the same comparison; a new observation,
            // or a new forecast against the same observation, is worth a row.
            boolean fresh = held == null || d.at().isAfter(held.at())
                    || (h.forecast().fetchedAt() != null && held.at().equals(d.at()) && !Objects.equals(held.upstream(), d.upstream()));
            if (fresh || held == null || held.score() != d.score()) {
                latest.put(h.id(), d);
            }
            if (fresh) {
                record(h.id(), d);
            }
        });
        return drift;
    }

    public Optional<Drift> latest(String hexagonId) {
        return Optional.ofNullable(latest.get(hexagonId));
    }

    public int size() {
        return latest.size();
    }

    private void record(String hexagonId, Drift d) {
        db.sql("""
                insert into forecast_drift (hexagon_id, at, station_id, upstream, temperature_c, humidity_pct, wind_kmh, rain_mm, score, worst, drifted, created_at)
                values (:h, :at, :s, :u, :t, :rh, :w, :rain, :score, :worst, :drifted, :now)""")
                .param("h", hexagonId).param("at", Db.ts(d.at())).param("s", d.stationId()).param("u", d.upstream())
                .param("t", d.temperatureC()).param("rh", d.humidityPct()).param("w", d.windKmh()).param("rain", d.rainMm())
                .param("score", d.score()).param("worst", d.worst()).param("drifted", d.drifted()).param("now", Db.ts(Instant.now()))
                .update();
    }

    /**
     * The latest comparison per hexagon, back into memory at start.
     */
    public void rehydrate() {
        latest.clear();
        db.sql("select distinct on (hexagon_id) hexagon_id, at, station_id, upstream, temperature_c, humidity_pct, wind_kmh, rain_mm, score, worst, drifted"
                        + " from forecast_drift order by hexagon_id, at desc")
                .query().listOfRows().forEach(row -> latest.put((String) row.get("hexagon_id"), drift(row)));
        log.info("forecast drift rehydrated: {} hexagons with a comparison", latest.size());
    }

    // ---------------------------------------------------------------- reads for the API and the console

    /**
     * Per hexagon over a window: how many comparisons, the mean and the worst score, how many
     * forecasts were thrown out, and the latest comparison. The map colours by this.
     */
    public List<Map<String, Object>> summary(Duration window) {
        Instant since = Instant.now().minus(window);
        List<Map<String, Object>> out = new ArrayList<>();
        db.sql("""
                select hexagon_id, count(*) as n, avg(score) as mean_score, max(score) as max_score,
                  count(*) filter (where drifted) as thrown_out, max(at) as last_at,
                  avg(abs(temperature_c)) as mean_temperature_c, avg(abs(humidity_pct)) as mean_humidity_pct,
                  avg(abs(wind_kmh)) as mean_wind_kmh, avg(abs(rain_mm)) as mean_rain_mm
                from forecast_drift where at >= :since group by hexagon_id order by mean_score desc""")
                .param("since", Db.ts(since)).query().listOfRows().forEach(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    String id = (String) row.get("hexagon_id");
                    m.put("hexagon", id);
                    m.put("comparisons", ((Number) row.get("n")).longValue());
                    m.put("meanScore", round2(Db.dbl(row.get("mean_score"))));
                    m.put("maxScore", round2(Db.dbl(row.get("max_score"))));
                    m.put("thrownOut", ((Number) row.get("thrown_out")).longValue());
                    m.put("lastAt", Db.instant(row.get("last_at")));
                    m.put("meanTemperatureC", round2(Db.dbl(row.get("mean_temperature_c"))));
                    m.put("meanHumidityPct", round2(Db.dbl(row.get("mean_humidity_pct"))));
                    m.put("meanWindKmh", round2(Db.dbl(row.get("mean_wind_kmh"))));
                    m.put("meanRainMm", round2(Db.dbl(row.get("mean_rain_mm"))));
                    m.put("latest", latest.get(id));
                    out.add(m);
                });
        return out;
    }

    /**
     * The mean score per hexagon over a window, for the map: null where there is none.
     */
    public Map<String, Double> meanScores(Duration window) {
        Map<String, Double> out = new HashMap<>();
        db.sql("select hexagon_id, avg(score) as mean_score from forecast_drift where at >= :since group by hexagon_id")
                .param("since", Db.ts(Instant.now().minus(window))).query().listOfRows()
                .forEach(row -> out.put((String) row.get("hexagon_id"), round2(Db.dbl(row.get("mean_score")))));
        return out;
    }

    /**
     * The comparisons, newest first.
     */
    public List<Drift> recent(String hexagonId, int limit) {
        String where = hexagonId == null ? "" : " where hexagon_id = :h";
        var q = db.sql("select hexagon_id, at, station_id, upstream, temperature_c, humidity_pct, wind_kmh, rain_mm, score, worst, drifted"
                + " from forecast_drift" + where + " order by at desc limit :n").param("n", Math.max(1, Math.min(limit, 5000)));
        if (hexagonId != null) {
            q = q.param("h", hexagonId);
        }
        return q.query().listOfRows().stream().map(Drifts::drift).toList();
    }

    private static Drift drift(Map<String, Object> row) {
        return new Drift(Db.instant(row.get("at")), (String) row.get("station_id"), (String) row.get("upstream"),
                Db.dbl(row.get("temperature_c")), Db.integer(row.get("humidity_pct")), Db.dbl(row.get("wind_kmh")), Db.dbl(row.get("rain_mm")),
                Db.dbl(row.get("score")) == null ? 0 : Db.dbl(row.get("score")), (String) row.get("worst"), Boolean.TRUE.equals(row.get("drifted")));
    }

    private static Double round2(Double v) {
        return v == null ? null : Math.round(v * 100) / 100.0;
    }
}
