package au.gully.api;

import au.gully.bureau.DiurnalRanges;
import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.bureau.Warning;
import au.gully.hexagons.*;
import au.gully.science.*;
import au.gully.upstreams.Forecast;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static au.gully.science.Numbers.round1;

/**
 * The one translation layer between what a hexagon holds and what the API serves (docs/06 item 13).
 * Nothing here fetches, computes an index or touches the database: it reads the hexagon, the station
 * register and the ground's record and lays them out in the contract's shape.
 */
@Service
@RequiredArgsConstructor
public class Readings {

    private final HexagonStore store;
    private final StationRegistry stations;
    private final FirePictures pictures;
    private final DiurnalRanges diurnal;

    /**
     * The reading now: the ask, then the assembly.
     */
    public Reading now(double lat, double lon, boolean withForecast, String ref) {
        return of(store.ask(lat, lon, withForecast, ref), new Reading.Point(lat, lon), withForecast);
    }

    /**
     * The reading from what a hexagon holds, without asking: the console and the per-hexagon read,
     * which must never trigger a fetch.
     */
    public Reading of(Hexagon h, Reading.Point point, boolean withForecast) {
        Instant now = Instant.now();
        Optional<FirePictures.Now> current = pictures.now(h, now);
        if (current.isEmpty()) {
            return unavailable(point, h, "no upstream answered and the hexagon holds no reading: " + reason(h));
        }
        FirePictures.Now c = current.get();
        Forecast f = h.forecast();
        FirePicture fire = h.fire();
        DroughtIndex drought = h.drought() == null ? null : h.drought().index();
        ZoneId zone = store.zoneOf(h);
        boolean stale = f != null && !c.observed() && store.life().expired(f, now);
        return new Reading(Reading.SCHEMA, true, null, point, hexagon(h, point, now),
                f == null ? null : new Reading.Source(f.upstream(), f.model(), f.attribution(), f.fetchedAt(),
                        store.life().expiresAt(f), store.life().forecast().toString(), stale),
                c.at(), c.conditions(), c.from(), station(h), nearby(c.nearby()), fire(fire), flood(h, f, drought, zone), drought,
                warnings(fire), withForecast && f != null ? forecast(f, fire, h, zone) : null, drift(h), null, Reading.DISCLAIMER);
    }

    /**
     * The reading as it was, from the ground's record (W-19): the hexagon's station's six-hourly
     * ledger row nearest the time asked for, or the model's row where the station was not there.
     * The conditions only: no fire picture is kept for a moment, and the forecast is never history.
     */
    public Reading at(double lat, double lon, Instant at, History history) {
        Reading.Point point = new Reading.Point(lat, lon);
        Optional<Hexagon> held = store.at(lat, lon);
        if (held.isEmpty()) {
            return unavailable(point, null, "no history: nothing has been asked about this hexagon");
        }
        Hexagon h = held.get();
        Optional<History.Then> then = history.then(h, at);
        if (then.isEmpty()) {
            return unavailable(point, h, h.stationId() == null
                    ? "no history: no station within reach, and the model did not stand in for this hexagon within three hours of that time"
                    : "no history: the station's ledger has no row within three hours of that time");
        }
        History.Then s = then.get();
        Instant now = Instant.now();
        return new Reading(Reading.SCHEMA, true, null, point, hexagon(h, point, now),
                s.upstream() == null ? null : new Reading.Source(s.upstream(), null, null, s.recordedAt(), null, null, false),
                s.at(), s.conditions(), s.from(), station(h), null, null, null, null, List.of(), null, null,
                new Reading.HistoryBlock(s.at(), s.recordedAt(), null, at), Reading.DISCLAIMER);
    }

    /**
     * The reading cut to "now" (W-20): what the ground says at the point and the fire picture drawn
     * from it - the conditions, where they came from, the station and the neighbours, the indices,
     * the warnings, the station's word on the forecast - and nothing of the days ahead, the drought
     * or the flood picture. The same blocks, in the same shape, so one contract serves both.
     */
    public static Reading nowView(Reading r) {
        return new Reading(r.schema(), r.available(), r.unavailable(), r.point(), r.hexagon(), r.source(), r.at(), r.current(),
                r.currentFrom(), r.station(), r.nearby(), r.fire(), null, null, r.warnings(), null, r.drift(), r.history(), r.disclaimer());
    }

    public Reading unavailable(Reading.Point point, Hexagon h, String why) {
        return new Reading(Reading.SCHEMA, false, why, point, h == null ? null : hexagon(h, point, Instant.now()), null, null,
                null, null, h == null ? null : station(h), null, null, null, null, List.of(), null, null, null, Reading.DISCLAIMER);
    }

    private static String reason(Hexagon h) {
        return h.hasStation() ? "its station has not reported" : "every upstream is out of allowance, paused or failing";
    }

    // ---------------------------------------------------------------- the blocks

    Reading.HexagonBlock hexagon(Hexagon h, Reading.Point point, Instant now) {
        Grid grid = store.grid();
        Forecast f = h.forecast();
        LandUse land = h.landUse();
        // The class at the point itself, off the hexagon's raster; the shares are the hexagon's.
        String at = land == null || point == null ? null
                : store.landClassAt(h, point.lat(), point.lon()).map(LandUse.LandClass::key).orElse(null);
        return new Reading.HexagonBlock(h.id(), round(h.cell().lat()), round(h.cell().lon()), grid.cellKm(),
                h.elevationM(), h.elevationFrom(), h.slopeDeg(), h.zone(), h.fireBanDistrict(), h.bureauDistrict(),
                land == null ? null : new Reading.LandUseBlock(at, land.byKey(), land.leads(), land.burnablePct(), land.source()),
                h.stationId(), h.kind(), h.activatedAt(), f == null ? null : f.fetchedAt(), store.life().expiresAt(f));
    }

    static Reading.NearbyBlock nearby(Interpolation.Result r) {
        if (r == null) {
            return null;
        }
        return new Reading.NearbyBlock(r.ring(), r.stations().stream().map(u -> new Reading.NearbyStation(u.id(), u.name(), u.distanceKm(), u.heightM(), u.weight())).toList(),
                r.elevationM(), r.elevationApplied(), Interpolation.LAPSE_TEMPERATURE_C_PER_KM, Interpolation.LAPSE_DEW_POINT_C_PER_KM);
    }

    /**
     * The station's word on the forecast (W-12), or null where the hexagon has no station or no comparison yet.
     */
    Reading.DriftBlock drift(Hexagon h) {
        return store.drift(h.id()).map(d -> new Reading.DriftBlock(d.at(), d.stationId(), d.upstream(), d.temperatureC(), d.humidityPct(),
                d.windKmh(), d.rainMm(), d.score(), d.worst(), d.drifted())).orElse(null);
    }

    Reading.StationBlock station(Hexagon h) {
        String id = h.nearestStationId() == null ? h.stationId() : h.nearestStationId();
        Optional<Station> s = stations.station(id);
        if (s.isEmpty()) {
            return null;
        }
        Station st = s.get();
        Observation o = stations.latest(id).orElse(null);
        // Inside means inside: a station within reach of the edge is the hexagon's, and says how far off it is.
        boolean inside = store.grid().cellOf(st.lat(), st.lon()).id().equals(h.id());
        double km = round1(Grid.planarMetres(h.cell().lat(), h.cell().lon(), st.lat(), st.lon()) / 1000);
        return new Reading.StationBlock(st.id(), st.name(), st.lat(), st.lon(), st.heightM(),
                inside ? 0.0 : km, inside, o == null ? null : o.at(),
                o == null ? null : o.temperatureC(), o == null ? null : o.apparentTemperatureC(),
                o == null ? null : o.dewPointC(), o == null ? null : o.humidityPct(), o == null ? null : o.windSpeedKmh(),
                o == null ? null : o.windDirectionDeg(), o == null ? null : o.windDirection(), o == null ? null : o.windGustKmh(),
                o == null ? null : o.pressureMslHpa(), o == null ? null : o.rainSince9amMm(), o == null ? null : o.rain24hMm(),
                o == null ? null : o.maxTemperatureC(), o == null ? null : o.minTemperatureC(),
                o == null ? null : o.visibilityKm(), o == null ? null : o.cloud(),
                stations.windShift(id).map(w -> new Reading.WindShiftBlock(w.at(), w.grade(), w.swingGrade(), w.speedGrade(), w.fromDeg(), w.toDeg(),
                        w.swingDeg(), w.fromKmh(), w.toKmh(), w.deltaKmh(), w.overMinutes(), w.describe())).orElse(null),
                stations.recent(id).stream().map(r -> new Reading.RecentReading(r.at(), r.temperatureC(), r.humidityPct(), r.windSpeedKmh(),
                        r.windDirectionDeg(), r.windGustKmh(), r.rainSince9amMm())).toList(),
                diurnal.of(st).map(Readings::diurnal).orElse(null));
    }

    /**
     * The station's diurnal range as the reading carries it (W-25).
     */
    static Reading.DiurnalBlock diurnal(DiurnalRanges.Diurnal d) {
        return new Reading.DiurnalBlock(day(d.day()), day(d.today()), period(d.week()), period(d.month()));
    }

    private static Reading.DiurnalDay day(DiurnalRanges.Day d) {
        return d == null ? null : new Reading.DiurnalDay(d.date(), d.highC(), d.lowC(), d.rangeC(), d.complete());
    }

    private static Reading.DiurnalPeriod period(DiurnalRanges.Period p) {
        return p == null ? null : new Reading.DiurnalPeriod(p.meanRangeC(), p.days(), p.of());
    }

    static Reading.FireBlock fire(FirePicture p) {
        if (p == null) {
            return null;
        }
        FirePicture.Grass g = p.grass();
        FirePicture.Official o = p.official();
        FirePicture.Wind w = p.wind();
        return new Reading.FireBlock(p.ffdi(), p.ffdiRating(), p.peakFfdi(), p.droughtFactor(), p.kbdiMm(), p.kbdiBand(),
                p.leads(), p.appliesToPct(),
                g == null ? null : new Reading.GrassBlock(g.curingPct(), g.curingEnteredOn(), g.fuelLoadTHa(), g.condition(),
                        g.gfdi(), g.gfdiRating(), g.spreadKmh(), g.moisturePct(), g.rateOfSpreadKmh(), g.intensityKwm(),
                        g.flameHeightM(), g.fbi(), g.afdrsRating()),
                o == null ? null : new Reading.OfficialBlock(o.district(), o.rating(), o.fbi(), o.totalFireBan(), o.date(),
                        o.from(), o.to(), o.days().stream().map(d -> new Reading.OfficialDay(d.day(), d.date(), d.rating(),
                                d.fbi(), d.totalFireBan(), d.from(), d.to())).toList(), o.readAt()),
                w == null ? null : new Reading.WindBlock(w.speedKmh(), w.directionDeg(), w.gustKmh(), w.band(), w.change()),
                p.fireWeatherWarning(), p.vapourPressureDeficitKpa(), p.soilMoistureSurface(), p.soilMoistureRootZone(),
                p.boundaryLayerHeightM(), p.windSpeed80mKmh(), p.windDirection80mDeg(), p.capeJkg(), p.liftedIndex());
    }

    static List<Reading.WarningBlock> warnings(FirePicture p) {
        if (p == null) {
            return List.of();
        }
        List<Reading.WarningBlock> out = new ArrayList<>();
        for (Warning w : p.warnings()) {
            out.add(new Reading.WarningBlock(w.id(), w.title(), w.phenomena(), w.headline(), w.hazard(), w.severity(),
                    w.issuedAt(), w.from(), w.until(), w.link()));
        }
        return out;
    }

    /**
     * What has fallen, what is coming, how full the ground is, and what the river is doing. Each half
     * degrades on its own: without a drought state the antecedent totals are null and the forecast
     * rain is still real; without a river cell the discharge is null and everything else stands.
     */
    static FloodWeather flood(Hexagon h, Forecast f, DroughtIndex drought, ZoneId zone) {
        if (f == null && drought == null && h.river() == null) {
            return null;
        }
        Conditions current = f == null ? null : f.current();
        List<DayOutlook> daily = f == null ? List.of() : f.daily();
        Double next24 = rainOverNextHours(f, 24);
        Double next48 = add(next24, daily.size() > 1 ? daily.get(1).precipitationMm() : null);
        Double next72 = add(next48, daily.size() > 2 ? daily.get(2).precipitationMm() : null);

        RiverState.River river = h.river() == null || !h.river().hasRiver() ? null : h.river().river(LocalDate.now(zone));
        Map<LocalDate, Double> dischargeByDate = new LinkedHashMap<>();
        if (river != null) {
            river.ahead().forEach(r -> dischargeByDate.put(r.date(), r.cumecs()));
        }
        List<FloodOutlook> outlook = new ArrayList<>();
        for (DayOutlook d : daily) {
            Double discharge = dischargeByDate.get(d.date());
            Double ratio = discharge == null || river == null || river.meanCumecs() == null || river.meanCumecs() == 0
                    ? null : round1(discharge / river.meanCumecs());
            outlook.add(new FloodOutlook(d.date(), d.precipitationMm(), d.precipitationProbabilityPct(), discharge, ratio));
        }
        return new FloodWeather(
                drought == null ? null : drought.rainOverLast(1),
                drought == null ? null : drought.rainOverLast(2),
                drought == null ? null : drought.rainOverLast(3),
                drought == null ? null : drought.rainOverLast(7),
                rainOverNextHours(f, 6), rainOverNextHours(f, 12), next24, next48, next72,
                maxRainProbability(f),
                current == null ? null : current.soilMoistureSurface(),
                current == null ? null : current.soilMoistureRootZone(),
                river == null ? null : river.currentCumecs(),
                river == null ? null : river.meanCumecs(),
                river == null ? null : river.ratioToMean(),
                river == null ? null : river.trend(),
                outlook);
    }

    /**
     * The days ahead and the hours ahead, each day whole.
     */
    static Reading.ForecastBlock forecast(Forecast f, FirePicture fire, Hexagon h, ZoneId zone) {
        Map<LocalDate, FireOutlook> fireDays = new HashMap<>();
        if (fire != null) {
            fire.outlook().forEach(o -> fireDays.put(o.date(), o));
        }
        FloodWeather flood = flood(h, f, h.drought() == null ? null : h.drought().index(), zone);
        Map<LocalDate, FloodOutlook> floodDays = new HashMap<>();
        if (flood != null) {
            flood.outlook().forEach(o -> floodDays.put(o.date(), o));
        }
        List<Reading.Day> days = new ArrayList<>();
        for (DayOutlook d : f.daily()) {
            FireOutlook fo = fireDays.get(d.date());
            FloodOutlook fl = floodDays.get(d.date());
            days.add(new Reading.Day(d.date(), d.maxTemperatureC(), d.minTemperatureC(), d.maxApparentTemperatureC(),
                    d.minHumidityPct(), d.maxWindKmh(), d.maxGustKmh(), d.dominantWindDirectionDeg(), d.precipitationMm(),
                    d.precipitationProbabilityPct(), d.uvIndexMax(), d.sunrise(), d.sunset(), d.condition(),
                    fo == null ? null : new Reading.DayFire(fo.ffdi(), fo.ffdiRating(), fo.gfdi(), fo.gfdiRating(), fo.fbi(),
                            fo.afdrsRating(), fo.kbdiMm(), fo.droughtFactor(), fo.maxTemperatureC(), fo.minHumidityPct(),
                            fo.maxWindKmh(), fo.maxGustKmh(), fo.rainMm()),
                    fl == null ? null : new Reading.DayFlood(fl.rainMm(), fl.rainProbabilityPct(), fl.riverDischargeCumecs(),
                            fl.dischargeRatioToMean())));
        }
        List<Reading.Hour> hours = new ArrayList<>();
        Instant floor = Instant.now().minus(Duration.ofHours(1));
        for (Conditions c : f.hourly()) {
            if (c.at() == null || c.at().isBefore(floor)) {
                continue;
            }
            hours.add(new Reading.Hour(c.at(), c.temperatureC(), c.humidityPct(), c.windSpeedKmh(), c.windDirectionDeg(),
                    c.windGustKmh(), c.precipitationMm(), c.precipitationProbabilityPct(), c.condition(),
                    hourFire(c, fire, zone)));
        }
        return new Reading.ForecastBlock(days, hours, fire == null || fire.wind() == null ? null : fire.wind().change());
    }

    /**
     * The indices for one forecast hour, with the drought factor of the day the hour falls on — the
     * projected one — or the current factor where the outlook does not reach.
     */
    private static Reading.HourFire hourFire(Conditions c, FirePicture fire, ZoneId zone) {
        FirePictures.HourIndices h = FirePictures.atHour(c, fire, zone);
        return h == null ? null : new Reading.HourFire(h.ffdi(), h.ffdiRating(), h.gfdi(), h.gfdiRating(), h.fbi(), h.afdrsRating(), h.droughtFactor());
    }

    private static Double rainOverNextHours(Forecast f, int hours) {
        if (f == null) {
            return null;
        }
        Instant now = Instant.now();
        Instant until = now.plus(Duration.ofHours(hours));
        double total = 0;
        boolean any = false;
        for (Conditions c : f.hourly()) {
            if (c.at() == null || c.at().isBefore(now) || c.at().isAfter(until) || c.precipitationMm() == null) {
                continue;
            }
            total += c.precipitationMm();
            any = true;
        }
        return any ? round1(total) : null;
    }

    private static Integer maxRainProbability(Forecast f) {
        if (f == null) {
            return null;
        }
        Integer max = null;
        for (Conditions c : f.hourly()) {
            if (c.precipitationProbabilityPct() != null && (max == null || c.precipitationProbabilityPct() > max)) {
                max = c.precipitationProbabilityPct();
            }
        }
        for (DayOutlook d : f.daily()) {
            if (d.precipitationProbabilityPct() != null && (max == null || d.precipitationProbabilityPct() > max)) {
                max = d.precipitationProbabilityPct();
            }
        }
        return max;
    }

    private static Double add(Double a, Double b) {
        if (a == null && b == null) {
            return null;
        }
        return round1((a == null ? 0 : a) + (b == null ? 0 : b));
    }

    private static double round(double degrees) {
        return Math.round(degrees * 100_000.0) / 100_000.0;
    }
}
