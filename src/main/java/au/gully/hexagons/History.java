package au.gully.hexagons;

import au.gully.science.Conditions;
import au.gully.storage.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.*;

/**
 * What the weather <em>was</em> (docs/06 item 2, reshaped by W-19): the ground's record, never the
 * hexagon's. A station's readings are consolidated every six hours into the ledger
 * ({@code station_sample}, the station register writes it) and kept five years; a hexagon whose
 * "now" was the model - no station within reach reporting, no neighbours - has the series the
 * model gave at each fetch kept in {@code model_now}, so what stood in is not lost; and the daily
 * rain and maximum the drought was stepped with are kept per hexagon in {@code drought_day} - the
 * one thing kept per hexagon, because rain is a place's, not a station's.
 * <p>
 * A reading as it was is answered from these: the hexagon's station's ledger row nearest the
 * moment, or the model's row where the station was not there. A row stands for {@link #STANDS_FOR}
 * either side of its moment; beyond that there is no answer. No fire picture is kept: the indices
 * of a moment are the conditions and the drought of that day, and both are here to recompute from.
 */
@Slf4j
@Repository
public class History {

    /**
     * How long the ground's record is kept: five years, then pruned by the hourly sweep.
     */
    public static final Period KEEP = Period.ofYears(5);

    /**
     * How far either side of its moment a row answers for: half the ledger's cadence, so a
     * six-hourly record is continuous and a lone fetch is a point.
     */
    public static final Duration STANDS_FOR = Duration.ofHours(3);

    private final JdbcClient db;

    public History(JdbcClient db) {
        this.db = db;
    }

    /**
     * One moment as the record has it.
     *
     * @param at         the time the values describe
     * @param recordedAt when the row was written: the ledger's moment, or the fetch
     * @param from       {@code station} or {@code model}
     * @param stationId  the station whose ledger answered, or null for the model
     * @param upstream   the model's upstream, or null for a station
     */
    public record Then(Instant at, Instant recordedAt, String from, String stationId, Conditions conditions, String upstream) {
    }

    // ---------------------------------------------------------------- writes

    /**
     * The model's "now" for a hexagon on a fetch when nothing on the ground answered: the series
     * read at the moment. A second fetch at the same moment is ignored.
     */
    public void modelNow(Hexagon h, Conditions c, Instant fetchedAt, String upstream, String model) {
        if (c == null || c.at() == null) {
            return;
        }
        db.sql("""
                insert into model_now (hexagon_id, at, fetched_at, upstream, model, temperature_c, apparent_temperature_c, dew_point_c,
                  humidity_pct, wind_speed_kmh, wind_direction_deg, wind_gust_kmh, precipitation_mm, pressure_msl_hpa, cloud_cover_pct, condition)
                values (:h, :at, :fetched, :upstream, :model, :t, :app, :dew, :rh, :w, :dir, :g, :rain, :p, :cloud, :cond)
                on conflict (hexagon_id, at) do nothing""")
                .param("h", h.id()).param("at", Db.ts(c.at())).param("fetched", Db.ts(fetchedAt)).param("upstream", upstream).param("model", model)
                .param("t", c.temperatureC()).param("app", c.apparentTemperatureC()).param("dew", c.dewPointC()).param("rh", c.humidityPct())
                .param("w", c.windSpeedKmh()).param("dir", c.windDirectionDeg()).param("g", c.windGustKmh()).param("rain", c.precipitationMm())
                .param("p", c.pressureMslHpa()).param("cloud", c.cloudCoverPct()).param("cond", c.condition()).update();
    }

    /**
     * The rows older than {@link #KEEP}, gone: the model's and the ledger's.
     */
    public int prune(Instant now) {
        // Years are a calendar's, not an instant's: the cutoff is worked out on the calendar and read back.
        java.time.ZonedDateTime cutoffDay = now.atZone(java.time.ZoneOffset.UTC).minus(KEEP);
        Instant cutoff = cutoffDay.toInstant();
        int n = db.sql("delete from model_now where at < :cutoff").param("cutoff", Db.ts(cutoff)).update();
        n += db.sql("delete from station_sample where at < :cutoff").param("cutoff", Db.ts(cutoff)).update();
        n += db.sql("delete from drought_day where day < :day").param("day", cutoffDay.toLocalDate()).update();
        n += db.sql("delete from archive_day where day < :day").param("day", cutoffDay.toLocalDate()).update();
        return n;
    }

    // ---------------------------------------------------------------- reads

    /**
     * What was "now" for a hexagon at a moment: its station's ledger row nearest the moment, else the
     * model's row nearest it, either within {@link #STANDS_FOR}; empty when neither has one.
     */
    public Optional<Then> then(Hexagon h, Instant at) {
        Instant from = at.minus(STANDS_FOR), to = at.plus(STANDS_FOR);
        if (h.stationId() != null) {
            Optional<Then> station = db.sql("select * from station_sample where station_id = :s and at >= :from and at <= :to"
                            + " order by abs(extract(epoch from (at - :at))) limit 1")
                    .param("s", h.stationId()).param("from", Db.ts(from)).param("to", Db.ts(to)).param("at", Db.ts(at))
                    .query().listOfRows().stream().findFirst().map(History::fromLedger);
            if (station.isPresent()) {
                return station;
            }
        }
        return db.sql("select * from model_now where hexagon_id = :h and at >= :from and at <= :to"
                        + " order by abs(extract(epoch from (at - :at))) limit 1")
                .param("h", h.id()).param("from", Db.ts(from)).param("to", Db.ts(to)).param("at", Db.ts(at))
                .query().listOfRows().stream().findFirst().map(History::fromModel);
    }

    /**
     * What was "now" for every hexagon at a moment, for the map's timeline: one ledger query and one
     * model query over the window, the nearest row per station and per hexagon, each hexagon taking
     * its station's, or the model's where it has no station.
     */
    public Map<String, Then> allAt(Collection<Hexagon> hexagons, Instant at) {
        Instant from = at.minus(STANDS_FOR), to = at.plus(STANDS_FOR);
        Map<String, Then> byStation = new HashMap<>();
        db.sql("select distinct on (station_id) * from station_sample where at >= :from and at <= :to"
                        + " order by station_id, abs(extract(epoch from (at - :at)))")
                .param("from", Db.ts(from)).param("to", Db.ts(to)).param("at", Db.ts(at))
                .query().listOfRows().forEach(row -> byStation.put((String) row.get("station_id"), fromLedger(row)));
        Map<String, Then> byModel = new HashMap<>();
        db.sql("select distinct on (hexagon_id) * from model_now where at >= :from and at <= :to"
                        + " order by hexagon_id, abs(extract(epoch from (at - :at)))")
                .param("from", Db.ts(from)).param("to", Db.ts(to)).param("at", Db.ts(at))
                .query().listOfRows().forEach(row -> byModel.put((String) row.get("hexagon_id"), fromModel(row)));
        Map<String, Then> out = new HashMap<>();
        for (Hexagon h : hexagons) {
            Then t = h.stationId() == null ? null : byStation.get(h.stationId());
            if (t == null) {
                t = byModel.get(h.id());
            }
            if (t != null) {
                out.put(h.id(), t);
            }
        }
        return out;
    }

    /**
     * A hexagon's record, newest first: its station's ledger where it has one, else the model's rows.
     */
    public List<Then> of(Hexagon h, int limit) {
        int n = Math.max(1, Math.min(limit, 1000));
        if (h.stationId() != null) {
            return db.sql("select * from station_sample where station_id = :s order by at desc limit :n")
                    .param("s", h.stationId()).param("n", n).query().listOfRows().stream().map(History::fromLedger).toList();
        }
        return db.sql("select * from model_now where hexagon_id = :h order by at desc limit :n")
                .param("h", h.id()).param("n", n).query().listOfRows().stream().map(History::fromModel).toList();
    }

    public long modelRows() {
        Long n = db.sql("select count(*) from model_now").query(Long.class).single();
        return n == null ? 0 : n;
    }

    public long droughtDays() {
        Long n = db.sql("select count(*) from drought_day").query(Long.class).single();
        return n == null ? 0 : n;
    }

    /**
     * A table's rows written on a UTC day, as maps, for the nightly export.
     */
    public List<Map<String, Object>> rowsOn(String table, String column, Instant from, Instant to) {
        if (!Set.of("station_sample", "model_now", "drought_day", "archive_day").contains(table)) {
            throw new IllegalArgumentException("not a history table: " + table);
        }
        return db.sql("select * from " + table + " where " + column + " >= :from and " + column + " < :to order by " + column)
                .param("from", Db.ts(from)).param("to", Db.ts(to)).query().listOfRows();
    }

    private static Then fromLedger(Map<String, Object> row) {
        Instant at = Db.instant(row.get("at"));
        Conditions c = Conditions.at(at)
                .temperature(Db.dbl(row.get("temperature_c")))
                .humidity(Db.integer(row.get("humidity_pct")))
                .wind(Db.dbl(row.get("wind_speed_kmh")))
                .windDirection(Db.integer(row.get("wind_direction_deg")))
                .gust(Db.dbl(row.get("wind_gust_kmh")))
                .precipitation(Db.dbl(row.get("rain_since_9am_mm")))
                .pressure(Db.dbl(row.get("pressure_hpa")))
                .build();
        return new Then(at, at, "station", (String) row.get("station_id"), c, null);
    }

    private static Then fromModel(Map<String, Object> row) {
        Instant at = Db.instant(row.get("at"));
        Conditions c = Conditions.at(at)
                .temperature(Db.dbl(row.get("temperature_c")))
                .apparent(Db.dbl(row.get("apparent_temperature_c")))
                .dewPoint(Db.dbl(row.get("dew_point_c")))
                .humidity(Db.integer(row.get("humidity_pct")))
                .wind(Db.dbl(row.get("wind_speed_kmh")))
                .windDirection(Db.integer(row.get("wind_direction_deg")))
                .gust(Db.dbl(row.get("wind_gust_kmh")))
                .precipitation(Db.dbl(row.get("precipitation_mm")))
                .pressure(Db.dbl(row.get("pressure_msl_hpa")))
                .cloud(Db.integer(row.get("cloud_cover_pct")))
                .condition((String) row.get("condition"))
                .build();
        return new Then(at, Db.instant(row.get("fetched_at")), "model", null, c, (String) row.get("upstream"));
    }
}
