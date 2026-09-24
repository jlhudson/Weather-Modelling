package au.gully.reading;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationsFeed;
import au.gully.fire.Outlook;
import au.gully.record.Drought;
import au.gully.platform.Json;
import au.gully.platform.Status;
import au.gully.record.Record;
import au.gully.storage.Db;
import au.gully.upstreams.Conditions;
import au.gully.upstreams.DayOutlook;
import au.gully.upstreams.Forecast;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The forecasts (W-20): the model's latest answer for a station - a Bureau station or a point of ours -
 * held a station at a time, in {@code station_forecast} and in memory. Nothing fetches on a clock: an
 * ask that finds a station's forecast missing or older than {@link #LIFE} fetches it again. What an ask
 * is shown is the next {@link #HOURS} hours and {@link #DAYS} days.
 * <p>
 * The same answer carries the model's now. Where a station's own file has gone quiet - its latest
 * reading older than {@link Status#STALE}, the Bureau down - an ask in its reach fetches the model's now
 * for it, good for {@link #NOW_LIFE}; the reading blends it and the map draws it, labelled the model's,
 * and it is never written into the station's readings or its history.
 */
@Slf4j
@Service
public class Forecasts {

    /**
     * How long a forecast stands before an ask fetches it again.
     */
    public static final Duration LIFE = Duration.ofHours(3);
    /**
     * How long the model's now stands in for a quiet station's: an hour, as a point of ours' current does.
     */
    public static final Duration NOW_LIFE = Duration.ofHours(1);
    /**
     * How long a row is kept after it was fetched.
     */
    public static final Duration KEEP = Duration.ofDays(1);
    public static final int HOURS = 12;
    public static final int DAYS = 3;

    private final JdbcClient db;
    private final Upstreams upstreams;
    private final Json json;
    private final Map<String, Forecast> held = new ConcurrentHashMap<>();

    public Forecasts(JdbcClient db, Upstreams upstreams, Json json, StationsFeed feed) {
        this.db = db;
        this.upstreams = upstreams;
        this.json = json;
        // A Bureau station gone quiet wears the model's now on the map, when an ask has fetched one.
        feed.decorate((s, p) -> {
            p.put("from", s.isPoint() ? "model" : "bureau");
            if (s.isPoint() || Boolean.TRUE.equals(p.get("fresh"))) {
                return;
            }
            heldNow(s, Instant.now()).ifPresent(o -> {
                p.put("from", "model");
                p.put("bureauAt", p.get("at"));
                p.put("at", o.at().toString());
                p.put("ageMinutes", Duration.between(o.at(), Instant.now()).toMinutes());
                p.put("fresh", true);
                p.put("temperatureC", o.temperatureC());
                p.put("apparentTemperatureC", o.apparentTemperatureC());
                p.put("dewPointC", o.dewPointC());
                p.put("humidityPct", o.humidityPct());
                p.put("windSpeedKmh", o.windSpeedKmh());
                p.put("windDirectionDeg", o.windDirectionDeg());
                p.put("windDirection", null);
                p.put("windGustKmh", o.windGustKmh());
                p.put("pressureMslHpa", o.pressureMslHpa());
                p.put("rainSince9amMm", o.rainSince9amMm());
                p.put("rain24hMm", o.rain24hMm());
                p.put("maxTemperatureC", o.maxTemperatureC());
                p.put("minTemperatureC", null);
                p.put("cloud", o.cloud());
                p.put("cloudOktas", null);
                p.put("visibilityKm", o.visibilityKm());
                p.put("deltaTC", null);
            });
        });
    }

    /**
     * The forecasts kept, back into memory.
     */
    public void rehydrate() {
        held.clear();
        int[] bad = {0};
        db.sql("select station_id, body::text as body from station_forecast").query().listOfRows().forEach(row -> {
            try {
                held.put((String) row.get("station_id"), json.read((String) row.get("body"), Forecast.class));
            } catch (RuntimeException e) {
                bad[0]++;
            }
        });
        log.info("forecasts rehydrated: {}{}", held.size(), bad[0] == 0 ? "" : " (" + bad[0] + " unreadable, fetched again when asked)");
    }

    /**
     * A station's forecast as held, fetched again when missing or older than {@link #LIFE}; forced, fetched
     * again whatever its age. Where the upstreams cannot answer, what is held, however old.
     */
    public Optional<Forecast> of(Station s, Instant now, boolean force) {
        return get(s, now, LIFE, force);
    }

    /**
     * The model's now at a station: the held forecast's, fetched again when older than {@link #NOW_LIFE};
     * empty when no upstream can give one that young.
     */
    public Optional<Observation> modelNow(Station s, Instant now, boolean force) {
        return get(s, now, NOW_LIFE, force).map(f -> PointCurrent.of(s.id(), f, Record.zoneOf(s))).filter(o -> young(o, now));
    }

    /**
     * The model's now at a station if one is held young enough, without asking for it.
     */
    public Optional<Observation> heldNow(Station s, Instant now) {
        Forecast f = held.get(s.id());
        return f == null ? Optional.empty() : Optional.ofNullable(PointCurrent.of(s.id(), f, Record.zoneOf(s))).filter(o -> young(o, now));
    }

    public Optional<Forecast> held(String stationId) {
        return Optional.ofNullable(stationId == null ? null : held.get(stationId));
    }

    private static boolean young(Observation o, Instant now) {
        return Status.isFresh(o, now);
    }

    private Optional<Forecast> get(Station s, Instant now, Duration life, boolean force) {
        Forecast f = held.get(s.id());
        if (!force && f != null && f.fetchedAt() != null && Duration.between(f.fetchedAt(), now).compareTo(life) < 0) {
            return Optional.of(f);
        }
        try {
            Forecast fetched = upstreams.fetch(s.lat(), s.lon());
            put(s.id(), fetched);
            log.info("forecast {} ({}): fetched from {}", s.id(), s.name(), fetched.upstream());
            return Optional.of(fetched);
        } catch (Upstreams.NoUpstream e) {
            log.warn("forecast {}: none fetched ({}){}", s.id(), e.getMessage(), f == null ? "" : "; the one from " + f.fetchedAt() + " stands");
            return Optional.ofNullable(f);
        }
    }

    /**
     * A forecast fetched elsewhere, kept as the station's.
     */
    public void put(String stationId, Forecast f) {
        db.sql("""
                insert into station_forecast (station_id, fetched_at, upstream, body) values (:id, :at, :upstream, cast(:body as jsonb))
                on conflict (station_id) do update set fetched_at = excluded.fetched_at, upstream = excluded.upstream, body = excluded.body""")
                .param("id", stationId).param("at", Db.ts(f.fetchedAt())).param("upstream", f.upstream()).param("body", json.write(f)).update();
        held.put(stationId, f);
    }

    public void forget(String stationId) {
        db.sql("delete from station_forecast where station_id = :id").param("id", stationId).update();
        held.remove(stationId);
    }

    /**
     * The rows older than {@link #KEEP}, gone.
     */
    /**
     * A day's fire block: its worst hour and the values of that hour, and the drought it was drawn with.
     */
    static Map<String, Object> fire(Outlook.Day d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ffdiMax", d.ffdiMax());
        m.put("ffdiRating", d.rating());
        m.put("peakAt", d.peakAt() == null ? null : d.peakAt().toString());
        m.put("temperatureC", d.temperatureC());
        m.put("humidityPct", d.humidityPct());
        m.put("windSpeedKmh", d.windSpeedKmh());
        m.put("droughtFactor", d.droughtFactor());
        m.put("kbdiMm", d.kbdiMm());
        return m;
    }

    public int prune(Instant now) {
        Instant before = now.minus(KEEP);
        held.values().removeIf(f -> f.fetchedAt() == null || f.fetchedAt().isBefore(before));
        return db.sql("delete from station_forecast where fetched_at < :before").param("before", Db.ts(before)).update();
    }

    /**
     * A forecast as an ask is shown it: whose it is, how old, the next {@link #HOURS} hours from the one
     * now running, and {@link #DAYS} days from today.
     */
    public static Map<String, Object> view(Forecast f, Station from, Double km, Instant now) {
        return view(f, from, km, now, null, null);
    }

    /**
     * The same, with the fire outlook (W-22) drawn from a drought: every hour's forest index, and each day's worst
     * hour. Without a drought, no index - never one drawn from a guessed factor.
     *
     * @param drought     the drought the outlook is carried forward from, or null
     * @param droughtFrom the station that drought is, named in the answer
     */
    public static Map<String, Object> view(Forecast f, Station from, Double km, Instant now, Drought drought, Station droughtFrom) {
        ZoneId zone = Record.zoneOf(from);
        List<Outlook.Hour> fireHours = drought == null ? List.of()
                : Outlook.hours(f.hourly(), drought.kbdiMm(), drought.meanAnnualRainMm(), drought.recentRainMm(), now, zone, Record.DAY_TURNS_AT);
        Map<Instant, Outlook.Hour> fireAt = new LinkedHashMap<>();
        fireHours.forEach(h -> fireAt.put(h.at(), h));
        Map<LocalDate, Outlook.Day> fireDay = new LinkedHashMap<>();
        Outlook.days(fireHours, f.hourly(), zone).forEach(d -> fireDay.put(d.date(), d));
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("id", from.id());
        who.put("name", from.name());
        who.put("kind", from.kind());
        who.put("km", km == null ? null : Math.round(km * 10) / 10.0);
        out.put("station", who);
        out.put("fetchedAt", f.fetchedAt() == null ? null : f.fetchedAt().toString());
        out.put("ageMinutes", f.fetchedAt() == null ? null : Duration.between(f.fetchedAt(), now).toMinutes());
        out.put("stale", f.fetchedAt() == null || Duration.between(f.fetchedAt(), now).compareTo(LIFE) >= 0);
        out.put("upstream", f.upstream());
        out.put("model", f.model());
        out.put("attribution", f.attribution());
        List<Map<String, Object>> hours = new ArrayList<>();
        for (Conditions c : f.hourly()) {
            // From the hour now running: the series' hours are local, and Adelaide's fall on the half hour in UTC.
            if (c.at() != null && c.at().plus(Duration.ofHours(1)).isAfter(now) && hours.size() < HOURS) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("at", c.at().toString());
                h.put("temperatureC", c.temperatureC());
                h.put("humidityPct", c.humidityPct());
                h.put("windSpeedKmh", c.windSpeedKmh());
                h.put("windDirectionDeg", c.windDirectionDeg());
                h.put("windGustKmh", c.windGustKmh());
                h.put("precipitationMm", c.precipitationMm());
                h.put("precipitationProbabilityPct", c.precipitationProbabilityPct());
                h.put("cloudCoverPct", c.cloudCoverPct());
                h.put("condition", c.condition());
                Outlook.Hour fh = fireAt.get(c.at());
                h.put("ffdi", fh == null ? null : fh.ffdi());
                h.put("ffdiRating", fh == null ? null : fh.rating());
                h.put("droughtFactor", fh == null ? null : fh.droughtFactor());
                hours.add(h);
            }
        }
        out.put("hourly", hours);
        LocalDate today = now.atZone(zone).toLocalDate();
        List<Map<String, Object>> days = new ArrayList<>();
        for (DayOutlook d : f.daily()) {
            if (d.date() != null && !d.date().isBefore(today) && days.size() < DAYS) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", d.date().toString());
                m.put("maxTemperatureC", d.maxTemperatureC());
                m.put("minTemperatureC", d.minTemperatureC());
                m.put("minHumidityPct", d.minHumidityPct());
                m.put("maxWindKmh", d.maxWindKmh());
                m.put("maxGustKmh", d.maxGustKmh());
                m.put("windDirectionDeg", d.dominantWindDirectionDeg());
                m.put("precipitationMm", d.precipitationMm());
                m.put("precipitationProbabilityPct", d.precipitationProbabilityPct());
                m.put("condition", d.condition());
                Outlook.Day fd = fireDay.get(d.date());
                m.put("fire", fd == null ? null : fire(fd));
                days.add(m);
            }
        }
        out.put("daily", days);
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("station", droughtFrom == null ? null : droughtFrom.id());
        basis.put("kbdiMm", drought == null ? null : drought.kbdiMm());
        basis.put("droughtFactor", drought == null ? null : drought.droughtFactor());
        basis.put("computedFor", drought == null ? null : drought.computedFor().toString());
        out.put("fireFrom", basis);
        return out;
    }
}
