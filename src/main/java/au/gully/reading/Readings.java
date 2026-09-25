package au.gully.reading;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationReader;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.StationsFeed;
import au.gully.fire.FireDanger;
import au.gully.fire.Kbdi;
import au.gully.platform.Status;
import au.gully.platform.UpstreamException;
import au.gully.reach.Geo;
import au.gully.upstreams.Forecast;
import au.gully.reach.Probe;
import au.gully.reach.Reach;
import au.gully.reach.ReachRule;
import au.gully.reach.Reaches;
import au.gully.reach.Terrain;
import au.gully.reach.TerrainStore;
import au.gully.reach.TerrainTiles;
import au.gully.record.Backfill;
import au.gully.record.Drought;
import au.gully.record.Droughts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The reading at a point (W-8): the weather now and the drought, blended from the stations whose
 * reach contains it, and the forest fire danger index from the blend.
 * <p>
 * The Bureau's stations answer where they reach. Where none of them can say what the weather is -
 * none reaches, or the ones that do carry no temperature - a point of our own answers ({@link Points}):
 * one already dropped whose reach contains the place, or a new one dropped here. A station lacking a
 * value stays out of that value's blend, and every value names the stations it came from.
 * <p>
 * A <em>forced</em> ask (W-13) goes to the upstreams first, whatever the timers say: the Bureau's
 * file is read now, the days missing from each station in reach are filled, and a point of ours has
 * its current fetched again however young it is. The reading then says what the ask brought.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Readings {

    private final StationRegistry stations;
    private final StationsFeed feed;
    private final TerrainStore terrain;
    private final ReachRule rule;
    private final TerrainTiles tiles;
    private final Droughts droughts;
    private final Points points;
    private final StationReader reader;
    private final Backfill backfill;
    private final Forecasts forecasts;
    private final au.gully.cfs.FireBan fireBan;
    private final au.gully.cfs.Curing curing;
    private final au.gully.bureau.Warnings warnings;
    private final au.gully.record.Record record;
    private final Rivers rivers;
    private final Snapshots snapshots;
    private final au.gully.fuel.LandCover landCover;
    private final GeometryFactory geometry = new GeometryFactory();

    /**
     * One station in reach, with what the blend needs of it; {@code model} where its now is the model's -
     * a point of ours, or a Bureau station whose file has gone quiet (W-20).
     */
    record Member(Station station, Terrain terrain, Reach reach, double km, int bearingIndex, double costKm, double weight,
                  Double heightM, Observation latest, boolean fresh, boolean model, Optional<Drought> drought) {
    }

    /**
     * The reading now, kept for a reference when one is named (W-27): at most once every three hours a reference.
     */
    public Map<String, Object> at(double lat, double lon, Instant now, boolean force, String ref) {
        Map<String, Object> out = at(lat, lon, now, force);
        if (ref != null && !ref.isBlank()) {
            out.put("ref", ref.trim());
            out.put("kept", snapshots.keep(ref, lat, lon, now, out));
        }
        return out;
    }

    /**
     * The reading as it was at a past moment (W-27): the one kept for the reference nearest it, within three hours;
     * else rebuilt from the stations' own record - each station in reach today, its reading nearest the moment within
     * the hour while the raw readings are kept (three days), its six-hour window after that - blended as a reading now
     * is, with the drought of that day. {@code history} says which, and what the values are.
     */
    public Map<String, Object> past(double lat, double lon, Instant at, String ref, Instant now) {
        Map<String, Object> history = new LinkedHashMap<>();
        history.put("requested", at.toString());
        history.put("ref", ref);
        Optional<Map<String, Object>> kept = snapshots.near(ref, at);
        if (kept.isPresent()) {
            Map<String, Object> out = new LinkedHashMap<>(kept.get());
            history.put("answeredFrom", "kept");
            history.put("keptAt", out.remove("keptAt"));
            out.put("history", history);
            return out;
        }
        ReachRule.Rule r = rule.current();
        org.locationtech.jts.geom.Point here = geometry.createPoint(new Coordinate(lon, lat));
        Double height = height(lat, lon);
        boolean raw = Duration.between(at, now).compareTo(au.gully.bureau.StationRegistry.KEEP_READINGS) < 0;
        List<Member> members = new ArrayList<>();
        for (Station s : stations.bureau()) {
            Terrain t = terrain.get(s.id()).orElse(null);
            if (t == null) {
                continue;
            }
            Reach reach = Reach.of(t, r);
            if (!Probe.polygon(reach).contains(here)) {
                continue;
            }
            Observation o = raw ? nearest(s.id(), at) : window(s.id(), at);
            if (o == null) {
                continue;
            }
            double km = Geo.distanceKm(lat, lon, s.lat(), s.lon());
            int b = Probe.bearingIndex(Geo.bearingDeg(s.lat(), s.lon(), lat, lon));
            double cost = cost(t, b, km, r);
            // The drought of that day, with the rain since 9 am the station had at that moment.
            Optional<Drought> dry = Drought.of(record.days(s.id()), au.gully.record.Record.dayOf(at, au.gully.record.Record.zoneOf(s)), o.rainSince9amMm());
            members.add(new Member(s, t, reach, km, b, cost, Blend.weight(cost), t.elevationM(), o, true, false, dry));
        }
        members.sort(Comparator.comparingDouble(Member::costKm));
        history.put("answeredFrom", members.isEmpty() ? null : raw ? "readings" : "windows");
        history.put("basis", members.isEmpty() ? "no station in reach held a reading within the hour, or a window, for that moment"
                : raw ? "each station's reading nearest the moment, within the hour"
                : "each station's six-hour window around the moment: its mean temperature, its least humidity and its mean wind, gust its strongest");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", at.toString());
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("lat", lat);
        point.put("lon", lon);
        point.put("heightM", height);
        point.put("water", height != null && height <= 0);
        out.put("point", point);
        out.put("from", members.isEmpty() ? "nothing" : "stations");
        out.put("rule", Reaches.rule(r));
        Map<String, Object> current = current(members, height, at);
        Map<String, Object> drought = drought(members);
        out.put("current", current);
        out.put("drought", drought);
        out.put("fire", fire(current, drought));
        List<Map<String, Object>> listed = new ArrayList<>();
        for (Member m : members) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", m.station().id());
            s.put("name", m.station().name());
            s.put("km", Math.round(m.km() * 10) / 10.0);
            s.put("weight", m.weight());
            s.put("at", m.latest().at() == null ? null : m.latest().at().toString());
            listed.add(s);
        }
        out.put("stations", listed);
        out.put("history", history);
        return out;
    }

    /**
     * A station's raw reading nearest a moment, within the hour either side.
     */
    private Observation nearest(String stationId, Instant at) {
        return stations.readings(stationId, at.minus(Duration.ofHours(1)), at.plus(Duration.ofHours(1))).stream()
                .min(Comparator.comparingLong(o -> Math.abs(Duration.between(o.at(), at).toSeconds()))).orElse(null);
    }

    /**
     * A station's six-hour window around a moment, as a reading: its mean temperature, its least humidity (the driest of
     * it, the fire-weather case), its mean wind and its strongest gust, its rain since 9 am at its last reading.
     */
    private Observation window(String stationId, Instant at) {
        return record.windowAt(stationId, at).map(w -> new Observation(stationId, au.gully.storage.Db.instant(w.get("at")),
                au.gully.storage.Db.dbl(w.get("temp_mean_c")), null, null, au.gully.storage.Db.integer(w.get("rh_min_pct")),
                au.gully.storage.Db.dbl(w.get("wind_mean_kmh")), null, null, au.gully.storage.Db.dbl(w.get("gust_max_kmh")), null,
                au.gully.storage.Db.dbl(w.get("rain_since_9am_mm")), au.gully.storage.Db.dbl(w.get("rain_24h_mm")),
                au.gully.storage.Db.dbl(w.get("temp_max_c")), au.gully.storage.Db.dbl(w.get("temp_min_c")), null, null, null, null)).orElse(null);
    }

    /**
     * The reading at a point; forced, the upstreams are asked first, and {@code grabbed} says what came.
     */
    public Map<String, Object> at(double lat, double lon, Instant now, boolean force) {
        ReachRule.Rule r = rule.current();
        Map<String, Object> grabbed = force ? new LinkedHashMap<>() : null;
        if (force) {
            grabbed.put("bureauDownloaded", reader.read());
        }
        org.locationtech.jts.geom.Point here = geometry.createPoint(new Coordinate(lon, lat));
        Double height = height(lat, lon);

        // The Bureau's stations whose reach contains the point.
        List<Member> members = new ArrayList<>();
        for (Station s : stations.bureau()) {
            member(s, lat, lon, height, r, here, now).ifPresent(members::add);
        }
        // A member with no drought to give has its missing days fetched now (W-14), rested six hours between tries;
        // forced, every member has every missing day fetched. Then the members again, since a drought may have moved.
        int days = 0;
        for (Member m : members) {
            if (force || m.drought().isEmpty()) {
                Backfill.Range want = backfill.wants(m.station(), now, force);
                if (want != null) {
                    days += backfill.fill(m.station(), want, now);
                }
            }
        }
        if (force) {
            grabbed.put("daysFilled", days);
        }
        if (days > 0) {
            members.clear();
            for (Station s : stations.bureau()) {
                member(s, lat, lon, height, r, here, now).ifPresent(members::add);
            }
        }
        String from = "stations";
        // None can say what the weather is: a point of ours, found or dropped.
        if (members.stream().noneMatch(m -> m.fresh() && m.latest().temperatureC() != null)) {
            Station found = points.within(lat, lon).orElse(null);
            if (found != null) {
                Points.Used used = points.use(found, now, force);
                if (force) {
                    grabbed.put("currentFetched", used.currentFetched());
                    grabbed.put("daysFilled", (int) grabbed.get("daysFilled") + used.daysFilled());
                }
                from = "point";
            } else {
                from = "new point";
            }
            Station p = found != null ? found : points.drop(lat, lon, height, now);
            if (force && found == null) {
                grabbed.put("currentFetched", points.currentLives(p, now));
            }
            Optional<Member> m = member(p, lat, lon, height, r, here, now);
            members.add(m.isPresent() ? m.get() : bare(p, lat, lon, height, now));
        }
        members.sort(Comparator.comparingDouble(Member::costKm));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now.toString());
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("lat", lat);
        point.put("lon", lon);
        point.put("heightM", height);
        point.put("water", height != null && height <= 0);
        out.put("point", point);
        out.put("from", from);
        if (force) {
            out.put("grabbed", grabbed);
        }
        out.put("rule", Reaches.rule(r));
        Map<String, Object> current = current(members, height, now);
        Map<String, Object> drought = drought(members);
        out.put("current", current);
        out.put("drought", drought);
        // The fire ban district and what the CFS has published for it (W-23); the district's curing, and the grass indices on the blend (W-24).
        Map<String, Object> ban = fireBan.at(lat, lon, now).orElse(null);
        String district = ban == null ? null : (String) ban.get("district");
        au.gully.cfs.Curing.Entry cured = district == null ? null : curing.of(district).orElse(null);
        Map<String, Object> fire = fire(current, drought);
        if (district != null) {
            Double rh = (Double) current.get("humidityPct");
            fire.put("grass", au.gully.cfs.Grass.block(district, cured, (Double) current.get("temperatureC"), rh == null ? null : (int) Math.round(rh),
                    (Double) current.get("windSpeedKmh"), now.atZone(java.time.ZoneId.of("Australia/Adelaide")).toLocalDate()));
        }
        // The place's own AFDRS rating (W-38): the index of the fuel its land cover says it carries.
        au.gully.fuel.LandCover.Cover cover = landCover.at(lat, lon).orElse(null);
        out.put("afdrs", au.gully.fuel.PointRating.block(cover, forestOf(fire), grassOf(fire)));
        out.put("fire", fire);
        out.put("fireBan", ban);
        // The Bureau's warnings in force here (W-25): by the public district of the nearest Bureau station in reach, or the
        // nearest at all, and by the fire weather district; the rest of the state's listed apart, never dropped.
        List<String> aacs = new ArrayList<>();
        members.stream().filter(m -> !m.station().isPoint() && m.station().district() != null).min(Comparator.comparingDouble(Member::km)).ifPresent(m -> aacs.add(m.station().district()));
        if (aacs.isEmpty()) {
            stations.bureau().stream().filter(s -> s.district() != null)
                    .min(Comparator.comparingDouble(s -> Geo.distanceKm(lat, lon, s.lat(), s.lon()))).ifPresent(s -> aacs.add(s.district()));
        }
        if (ban != null && ban.get("aac") != null) {
            aacs.add((String) ban.get("aac"));
        }
        out.put("warnings", warningsView(warnings.at(aacs, now), aacs, warnings.readAt()));
        // The forecast (W-20): the nearest station in reach's, or the point of ours'; fetched when older than three hours.
        Member nearest = members.stream().min(Comparator.comparingDouble(Member::km)).orElse(null);
        // Forced, it is fetched again - unless this ask already did (a point's current, a quiet station's now).
        boolean again = force && nearest != null && forecasts.held(nearest.station().id()).map(f -> f.fetchedAt() == null || f.fetchedAt().isBefore(now)).orElse(true);
        Forecast forecast = nearest == null ? null : forecasts.of(nearest.station(), now, again).orElse(null);
        // The outlook is carried forward from a drought: the forecast station's own, or the nearest in reach that holds one.
        Member dry = nearest != null && nearest.drought().isPresent() ? nearest
                : members.stream().filter(m -> m.drought().isPresent()).findFirst().orElse(null);
        out.put("forecast", forecast == null ? null : Forecasts.view(forecast, nearest.station(), nearest.km(), now,
                new Forecasts.FireInputs(dry == null ? null : dry.drought().get(), dry == null ? null : dry.station(), district, cured, cover == null ? null : cover.fuel())));
        // Flood weather (W-26): the rain down at the forecast station, the rain coming, and the river at the point.
        if (nearest != null) {
            java.time.ZoneId zone = au.gully.record.Record.zoneOf(nearest.station());
            out.put("flood", Flood.block(nearest.station(), record.days(nearest.station().id()), record.rainSoFar(nearest.station(), now),
                    au.gully.record.Record.dayOf(now, zone), forecast, now, rivers.at(lat, lon, now, now.atZone(zone).toLocalDate()).orElse(null)));
        }
        out.put("modelNow", members.stream().filter(m -> m.model() && !m.station().isPoint()).map(m -> m.station().id()).toList());
        List<Map<String, Object>> listed = new ArrayList<>();
        for (Member m : members) {
            listed.add(station(m, lat, lon, height, now));
        }
        out.put("stations", listed);
        return out;
    }

    /**
     * The warnings as a reading carries them: in force here in full, elsewhere in the state by title.
     */
    static Map<String, Object> warningsView(au.gully.bureau.Warnings.Split split, List<String> aacs, Instant readAt) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("areas", aacs);
        w.put("here", split.here().stream().map(au.gully.bureau.Warnings::view).toList());
        w.put("elsewhere", split.elsewhere().stream().map(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.id());
            m.put("kind", x.kind());
            m.put("title", x.title());
            m.put("until", x.until() == null ? null : x.until().toString());
            return m;
        }).toList());
        w.put("readAt", readAt == null ? null : readAt.toString());
        return w;
    }

    private Double height(double lat, double lon) {
        try {
            return tiles.elevations(List.of(new double[]{lat, lon})).elevations().getFirst();
        } catch (UpstreamException | RuntimeException e) {
            log.debug("reading {},{}: no height ({})", lat, lon, e.getMessage());
            return null;
        }
    }

    /**
     * A station as a member of the blend at a point, if its reach contains the point.
     */
    private Optional<Member> member(Station s, double lat, double lon, Double height, ReachRule.Rule r, org.locationtech.jts.geom.Point here, Instant now) {
        Terrain t = terrain.get(s.id()).orElse(null);
        if (t == null) {
            return Optional.empty();
        }
        Reach reach = Reach.of(t, r);
        if (!Probe.polygon(reach).contains(here)) {
            return Optional.empty();
        }
        double km = Geo.distanceKm(lat, lon, s.lat(), s.lon());
        int b = Probe.bearingIndex(Geo.bearingDeg(s.lat(), s.lon(), lat, lon));
        double cost = cost(t, b, km, r);
        Observation o = stations.latest(s.id()).orElse(null);
        boolean fresh = Status.isFresh(o, now);
        boolean model = s.isPoint();
        // The Bureau's file gone quiet for this station (W-20): the model's now at it stands in, fetched if none is young enough.
        if (!fresh && !s.isPoint()) {
            Observation m = forecasts.modelNow(s, now, false).orElse(null);
            if (m != null) {
                o = m;
                fresh = true;
                model = true;
            }
        }
        return Optional.of(new Member(s, t, reach, km, b, cost, Blend.weight(cost), t.elevationM(), o, fresh, model, droughts.of(s, now)));
    }

    /**
     * A point of ours whose terrain is not there yet: a member at its distance, so what it has is used.
     */
    private Member bare(Station p, double lat, double lon, Double height, Instant now) {
        double km = Geo.distanceKm(lat, lon, p.lat(), p.lon());
        Observation o = stations.latest(p.id()).orElse(null);
        boolean fresh = Status.isFresh(o, now);
        return new Member(p, null, null, km, 0, km, Blend.weight(km), p.heightM(), o, fresh, true, droughts.of(p, now));
    }

    /**
     * The cost from a station to a point along the ray towards it: the reach's own arithmetic
     * (W-16), the distance plus the sustained climb and descent crossed on the way.
     */
    static double cost(Terrain t, int bearing, double km, ReachRule.Rule r) {
        int steps = (int) Math.min(Terrain.STEPS, Math.round(km / Terrain.STEP_KM));
        return Math.round(Reach.costKm(km, Reach.crossed(t, bearing, steps), r) * 100) / 100.0;
    }

    // ---------------------------------------------------------------- the blends

    private Map<String, Object> current(List<Member> members, Double height, Instant now) {
        List<Member> fresh = members.stream().filter(Member::fresh).toList();
        Map<String, Object> c = new LinkedHashMap<>();
        Map<String, List<String>> from = new LinkedHashMap<>();
        value(c, from, "temperatureC", fresh, m -> Blend.toHeight(m.latest().temperatureC(), m.heightM(), height, Blend.LAPSE_C_PER_KM), 1);
        value(c, from, "apparentTemperatureC", fresh, m -> Blend.toHeight(m.latest().apparentTemperatureC(), m.heightM(), height, Blend.LAPSE_C_PER_KM), 1);
        value(c, from, "dewPointC", fresh, m -> Blend.toHeight(m.latest().dewPointC(), m.heightM(), height, Blend.DEW_POINT_LAPSE_C_PER_KM), 1);
        value(c, from, "humidityPct", fresh, m -> m.latest().humidityPct() == null ? null : m.latest().humidityPct().doubleValue(), 0);
        value(c, from, "windSpeedKmh", fresh, m -> m.latest().windSpeedKmh(), 0);
        value(c, from, "windGustKmh", fresh, m -> m.latest().windGustKmh(), 0);
        List<Blend.Part> dirs = parts(fresh, m -> m.latest().windDirectionDeg() == null ? null : m.latest().windDirectionDeg().doubleValue());
        c.put("windDirectionDeg", Blend.meanBearing(dirs));
        from.put("windDirectionDeg", dirs.stream().map(Blend.Part::id).toList());
        value(c, from, "pressureMslHpa", fresh, m -> m.latest().pressureMslHpa(), 1);
        value(c, from, "rainSince9amMm", fresh, m -> m.latest().rainSince9amMm(), 1);
        value(c, from, "rain24hMm", fresh, m -> m.latest().rain24hMm(), 1);
        value(c, from, "maxTemperatureC", fresh, m -> Blend.toHeight(m.latest().maxTemperatureC(), m.heightM(), height, Blend.LAPSE_C_PER_KM), 1);
        Instant newest = fresh.stream().map(m -> m.latest().at()).max(Comparator.naturalOrder()).orElse(null);
        c.put("at", newest == null ? null : newest.toString());
        c.put("ageMinutes", newest == null ? null : Duration.between(newest, now).toMinutes());
        c.put("from", from);
        return c;
    }

    private static void value(Map<String, Object> c, Map<String, List<String>> from, String name, List<Member> members,
                              Function<Member, Double> get, int decimals) {
        List<Blend.Part> parts = parts(members, get);
        Double v = Blend.mean(parts);
        double f = Math.pow(10, decimals);
        c.put(name, v == null ? null : Math.round(v * f) / f);
        from.put(name, parts.stream().map(Blend.Part::id).toList());
    }

    private static List<Blend.Part> parts(List<Member> members, Function<Member, Double> get) {
        List<Blend.Part> parts = new ArrayList<>();
        for (Member m : members) {
            Double v = get.apply(m);
            if (v != null) {
                parts.add(new Blend.Part(m.station().id(), m.weight(), v));
            }
        }
        return parts;
    }

    private Map<String, Object> drought(List<Member> members) {
        List<Member> held = members.stream().filter(m -> m.drought().isPresent()).toList();
        Map<String, Object> d = new LinkedHashMap<>();
        List<Blend.Part> kbdi = parts(held, m -> m.drought().get().kbdiMm());
        List<Blend.Part> df = parts(held, m -> m.drought().get().droughtFactor());
        Double k = Blend.mean(kbdi), f = Blend.mean(df);
        d.put("kbdiMm", k == null ? null : Math.round(k * 10) / 10.0);
        d.put("band", k == null ? null : Kbdi.band(k));
        d.put("droughtFactor", f == null ? null : Math.round(f * 10) / 10.0);
        d.put("complete", !held.isEmpty() && held.stream().allMatch(m -> m.drought().get().complete()));
        d.put("from", kbdi.stream().map(Blend.Part::id).toList());
        d.put("computedFor", held.isEmpty() ? null : held.getFirst().drought().get().computedFor().toString());
        return d;
    }

    /**
     * The forest block (W-33): the AFDRS dry forest model's figures, and what they rest on.
     */
    static Map<String, Object> forest(au.gully.fire.DryForest.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fbi", r.fbi());
        m.put("rating", r.rating());
        m.put("rateOfSpreadKmh", Math.round(r.rateOfSpreadMh() / 10) / 100.0);
        m.put("flameHeightM", r.flameHeightM());
        m.put("intensityKwm", r.intensityKwm());
        m.put("moisturePct", r.moisturePct());
        m.put("model", "AFDRS dry forest (Cheney et al. 2012), as the AFDRS Fire Behaviour Index Technical Guide - Forest v2024.6.0 gives it");
        m.put("fuel", au.gully.fire.DryForest.Fuel.PROVISIONAL.basis());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> forestOf(Map<String, Object> fire) {
        return fire.get("forest") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> grassOf(Map<String, Object> fire) {
        return fire.get("grass") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static Map<String, Object> fire(Map<String, Object> current, Map<String, Object> drought) {
        Map<String, Object> f = new LinkedHashMap<>();
        Double ffdi = FireDanger.of((Double) current.get("temperatureC"), (Double) current.get("humidityPct"),
                (Double) current.get("windSpeedKmh"), (Double) drought.get("droughtFactor"));
        f.put("ffdi", ffdi);
        f.put("ffdiRating", ffdi == null ? null : FireDanger.rating(ffdi));
        f.put("inputs", Map.of("temperatureC", current.get("temperatureC") != null, "humidityPct", current.get("humidityPct") != null,
                "windSpeedKmh", current.get("windSpeedKmh") != null, "droughtFactor", drought.get("droughtFactor") != null));
        // The AFDRS dry forest index on the same weather (W-33), at the local time the weather is for.
        Instant at = current.get("at") == null ? Instant.now() : Instant.parse((String) current.get("at"));
        au.gully.fire.DryForest.Result r = au.gully.fire.DryForest.of((Double) current.get("temperatureC"), (Double) current.get("humidityPct"),
                (Double) current.get("windSpeedKmh"), (Double) drought.get("droughtFactor"), java.time.LocalDateTime.ofInstant(at, java.time.ZoneId.of("Australia/Adelaide")),
                au.gully.fire.DryForest.Fuel.PROVISIONAL);
        f.put("forest", r == null ? null : forest(r));
        return f;
    }

    /**
     * One member as the reading lists it: what the probe says of a station, and its part in the blend.
     */
    private Map<String, Object> station(Member m, double lat, double lon, Double height, Instant now) {
        Map<String, Object> s = new LinkedHashMap<>(feed.properties(m.station(), now));
        s.put("lat", m.station().lat());
        s.put("lon", m.station().lon());
        s.put("km", Math.round(m.km() * 10) / 10.0);
        s.put("bearingDeg", (int) Math.round(Geo.bearingDeg(lat, lon, m.station().lat(), m.station().lon())));
        s.put("costKm", m.costKm());
        s.put("weight", m.weight());
        s.put("elevationM", m.heightM());
        s.put("aboveM", height == null || m.heightM() == null ? null : Math.round(m.heightM() - height));
        s.put("rayKm", m.reach() == null ? null : m.reach().km()[m.bearingIndex()]);
        s.put("margin", m.reach() == null ? null : Math.round((m.reach().km()[m.bearingIndex()] - m.km()) * 10) / 10.0);
        s.put("gives", gives(m));
        return s;
    }

    private static List<String> gives(Member m) {
        List<String> out = new ArrayList<>();
        Observation o = m.latest();
        if (m.fresh() && o != null) {
            if (o.temperatureC() != null) out.add("temperature");
            if (o.humidityPct() != null) out.add("humidity");
            if (o.windSpeedKmh() != null) out.add("wind");
            if (o.rainSince9amMm() != null) out.add("rain");
        }
        if (m.drought().isPresent()) out.add("drought");
        return out;
    }
}
