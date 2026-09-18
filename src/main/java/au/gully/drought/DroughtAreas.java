package au.gully.drought;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.hexagons.Cell;
import au.gully.hexagons.DroughtState;
import au.gully.hexagons.Grid;
import au.gully.platform.Json;
import au.gully.science.Kbdi;
import au.gully.storage.Db;
import au.gully.upstreams.OpenMeteo;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drought over an area of hexagons, stepped forward daily (docs/06 item 7, W-11). Drought is a
 * property of a district, not a 15 km cell, so the plane is tiled into fixed areas of
 * {@link #RADIUS} — a hexagon and its ring, seven cells about 45 km across — and one state serves
 * every hexagon in the area: the first hexagon asked about in an area spins it up, the rest share
 * it, and it is stepped once a day for all of them. The areas never move and never overlap
 * ({@link Grid#areaCentre}), so which hexagon was asked about first changes nothing.
 * <p>
 * The inputs are the Bureau stations inside the area — the day's rain to 9 am and the day's
 * maximum, which the station ledger has for every day the service has been running — and, for the
 * days they do not cover, Open-Meteo's archive at the area's centre: one archive fetch per area,
 * about six allowance units, once. After a year of the ledger the archive is never asked again.
 * <p>
 * The state is held here and in {@code drought_area}; each hexagon carries a copy of its area's
 * state on its own row, so a reading is answered from the hexagon and the row says what it was
 * answered from.
 */
@Slf4j
@Service
public class DroughtAreas {

    /**
     * The area's radius in cells: 1 is the hexagon and its ring, seven cells, about 45 km across.
     * 2 would be nineteen cells about 75 km across, one archive fetch for all of them, at the cost
     * of the rain gradient across the Mount Lofty Ranges being one figure.
     */
    public static final int RADIUS = 1;

    /**
     * How far back the integration runs. A year is enough for the assumed starting deficit — field
     * capacity — to have washed out.
     */
    public static final int SPIN_UP_DAYS = 365;

    /**
     * How far behind real time the reanalysis archive runs; the forecast endpoint's past days close the gap.
     */
    static final int ARCHIVE_LAG_DAYS = 5;

    /**
     * When an area has no station in it, the nearest within this reach stands in.
     */
    static final double STATION_REACH_KM = 75;

    /**
     * Used for the equation's mean annual rainfall only when the window is too short to derive it.
     */
    static final double DEFAULT_ANNUAL_RAIN_MM = 550;

    /**
     * When the rain day has closed: the 9 am total is in the station files by then.
     */
    static final LocalTime DAY_CLOSES = LocalTime.of(9, 10);

    /**
     * How long after a spin-up that produced nothing before it is tried again for the area.
     */
    static final java.time.Duration RETRY_AFTER = java.time.Duration.ofMinutes(15);

    private final Grid grid;
    private final StationRegistry stations;
    private final Upstreams upstreams;
    private final JdbcClient db;
    private final Json json;
    private final Map<String, Area> areas = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> attempts = new ConcurrentHashMap<>();

    public DroughtAreas(Grid grid, StationRegistry stations, Upstreams upstreams, JdbcClient db, Json json) {
        this.grid = grid;
        this.stations = stations;
        this.upstreams = upstreams;
        this.db = db;
        this.json = json;
    }

    /**
     * One area: its centre cell, the zone its days are cut on, and the state.
     */
    public record Area(Cell centre, String zone, DroughtState state) {
    }

    // ---------------------------------------------------------------- the areas

    /**
     * The centre of the area a cell belongs to.
     */
    public Cell centreOf(Cell cell) {
        return grid.areaCentre(cell, RADIUS);
    }

    /**
     * How many cells an area has: seven for a hexagon at radius 1.
     */
    public int cellsPerArea() {
        return grid.area(grid.cell(0, 0), RADIUS).size();
    }

    public int size() {
        return areas.size();
    }

    public Collection<Area> all() {
        return List.copyOf(areas.values());
    }

    /**
     * The state of the area a cell belongs to, as held: no fetching, no stepping.
     */
    public Optional<DroughtState> held(Cell cell) {
        Area a = areas.get(centreOf(cell).id());
        return a == null ? Optional.empty() : Optional.of(a.state());
    }

    /**
     * The state of the area a cell belongs to, spun up if the area has none and stepped to yesterday
     * if it is behind. One spin-up per area, whatever asks concurrently. Empty when nothing could
     * supply enough days.
     */
    public Optional<DroughtState> stateFor(Cell cell, ZoneId zone, LocalDate today) {
        Cell centre = centreOf(cell);
        synchronized (locks.computeIfAbsent(centre.id(), k -> new Object())) {
            Area held = areas.get(centre.id());
            LocalDate yesterday = today.minusDays(1);
            if (held == null) {
                // A spin-up that could not be fed - the archive out of allowance, say - is tried again
                // after a while, not on every ask.
                Instant last = attempts.get(centre.id());
                if (last != null && Instant.now().isBefore(last.plus(RETRY_AFTER))) {
                    return Optional.empty();
                }
                attempts.put(centre.id(), Instant.now());
                Optional<DroughtState> fresh = spinUp(centre, today);
                fresh.ifPresent(s -> save(new Area(centre, zone.getId(), s)));
                return fresh;
            }
            if (held.state().computedFor().isBefore(yesterday)) {
                // The same restraint for a step that cannot advance yet: the day's inputs arrive on their own clock.
                Instant last = attempts.get(centre.id());
                if (last != null && Instant.now().isBefore(last.plus(RETRY_AFTER))) {
                    return Optional.of(held.state());
                }
                attempts.put(centre.id(), Instant.now());
                DroughtState next = stepTo(centre, held.state(), yesterday);
                if (!next.computedFor().equals(held.state().computedFor())) {
                    save(new Area(centre, held.zone(), next));
                    return Optional.of(next);
                }
            }
            return Optional.of(held.state());
        }
    }

    /**
     * The daily step (docs/06 item 7): every area whose last complete day is behind the calendar is
     * stepped forward, exactly once per area per day, after 9:10 am in the area's own zone. Runs on a
     * short timer and does nothing before then.
     *
     * @return the ids of the areas stepped
     */
    public Set<String> stepAll(Instant now) {
        Set<String> stepped = new LinkedHashSet<>();
        for (Area a : List.copyOf(areas.values())) {
            ZonedDateTime local = now.atZone(zoneOf(a));
            if (local.toLocalTime().isBefore(DAY_CLOSES)) {
                continue;
            }
            LocalDate yesterday = local.toLocalDate().minusDays(1);
            if (!a.state().computedFor().isBefore(yesterday)) {
                continue;
            }
            synchronized (locks.computeIfAbsent(a.centre().id(), k -> new Object())) {
                try {
                    DroughtState next = stepTo(a.centre(), a.state(), yesterday);
                    if (!next.computedFor().equals(a.state().computedFor())) {
                        save(new Area(a.centre(), a.zone(), next));
                        stepped.add(a.centre().id());
                    }
                } catch (RuntimeException e) {
                    log.warn("drought step for area {} failed: {}", a.centre().id(), e.getMessage());
                }
            }
        }
        return stepped;
    }

    /**
     * A state computed before the areas existed — per hexagon, at the hexagon's own point — taken as
     * the state of the hexagon's area when the area has none: the point is within a cell of the
     * centre, and the spin-up is not worth spending again.
     *
     * @return whether it was taken
     */
    public boolean adopt(Cell cell, String zone, DroughtState legacy) {
        Cell centre = centreOf(cell);
        synchronized (locks.computeIfAbsent(centre.id(), k -> new Object())) {
            if (areas.containsKey(centre.id()) || legacy == null || legacy.computedFor() == null) {
                return false;
            }
            save(new Area(centre, zone, legacy.forArea(centre.id(), cellsPerArea())));
            return true;
        }
    }

    // ---------------------------------------------------------------- the table

    public void rehydrate() {
        areas.clear();
        db.sql("select id, zone, state from drought_area").query().listOfRows().forEach(row -> {
            try {
                Cell centre = grid.parse((String) row.get("id"));
                DroughtState state = json.read(row.get("state").toString(), DroughtState.class);
                areas.put(centre.id(), new Area(centre, (String) row.get("zone"), state));
            } catch (RuntimeException e) {
                log.debug("drought area {} could not be read back: {}", row.get("id"), e.getMessage());
            }
        });
        log.info("drought areas rehydrated: {} ({} cells each)", areas.size(), cellsPerArea());
    }

    private void save(Area a) {
        areas.put(a.centre().id(), a);
        db.sql("""
                insert into drought_area (id, lat, lon, radius, zone, computed_for, state, updated_at)
                values (:id, :lat, :lon, :radius, :zone, :for, :state::jsonb, :at)
                on conflict (id) do update set zone = excluded.zone, computed_for = excluded.computed_for,
                  state = excluded.state, updated_at = excluded.updated_at""")
                .param("id", a.centre().id()).param("lat", a.centre().lat()).param("lon", a.centre().lon())
                .param("radius", RADIUS).param("zone", a.zone()).param("for", a.state().computedFor())
                .param("state", json.write(a.state())).param("at", Db.ts(Instant.now())).update();
    }

    private static ZoneId zoneOf(Area a) {
        try {
            return a.zone() == null ? ZoneId.of("Australia/Adelaide") : ZoneId.of(a.zone());
        } catch (RuntimeException e) {
            return ZoneId.of("Australia/Adelaide");
        }
    }

    // ---------------------------------------------------------------- the maths

    /**
     * A year of daily rain and maximum temperature for the area, from the stations where they have
     * it and the archive at the centre for the rest, integrated into a deficit. Empty when neither
     * could supply enough days.
     */
    Optional<DroughtState> spinUp(Cell centre, LocalDate today) {
        LocalDate from = today.minusDays(SPIN_UP_DAYS);
        LocalDate to = today.minusDays(1);
        List<Station> area = stationsFor(centre);
        SortedMap<LocalDate, Input> days = new TreeMap<>();
        stations.daily(area, from, to).forEach((d, in) -> {
            if (in.rainMm() != null && in.maxTemperatureC() != null) {
                days.put(d, new Input(in.rainMm(), in.maxTemperatureC()));
            }
        });
        int fromStations = days.size();
        fillGaps(centre, days, from, to, today);
        if (days.size() < Kbdi.WINDOW_DAYS) {
            log.warn("drought spin-up for area {}: only {} days available", centre.id(), days.size());
            return Optional.empty();
        }
        List<LocalDate> dates = new ArrayList<>(days.keySet());
        double[] rain = new double[dates.size()];
        double[] maxTemp = new double[dates.size()];
        double totalRain = 0;
        for (int i = 0; i < dates.size(); i++) {
            Input in = days.get(dates.get(i));
            rain[i] = in.rainMm();
            maxTemp[i] = in.maxTemperatureC();
            totalRain += rain[i];
        }
        double annual = dates.size() >= 300 ? totalRain * 365.0 / dates.size() : DEFAULT_ANNUAL_RAIN_MM;
        double[] series = Kbdi.series(rain, maxTemp, annual, 0.0);
        List<Double> window = new ArrayList<>();
        for (int i = Math.max(0, rain.length - Kbdi.WINDOW_DAYS); i < rain.length; i++) {
            window.add(rain[i]);
        }
        String source = fromStations == days.size() ? "stations" : fromStations == 0 ? "archive" : "stations+archive";
        DroughtState state = new DroughtState(series[series.length - 1], annual, dates.getFirst(), dates.getLast(),
                dates.size(), window, source, centre.id(), cellsPerArea());
        log.info("drought area {} ({} cells): KBDI {} mm, DF {}, from {} days ({} from {} stations)", centre.id(), cellsPerArea(),
                Math.round(state.kbdiMm()), au.gully.science.Numbers.round1(state.droughtFactor()), dates.size(),
                fromStations, area.size());
        return Optional.of(state);
    }

    /**
     * The state stepped forward through every complete day up to {@code upTo}, from the stations,
     * with the recent-days call at the centre filling any day they missed. Stops at the first day
     * nothing can supply, and says how far it got.
     */
    DroughtState stepTo(Cell centre, DroughtState state, LocalDate upTo) {
        LocalDate from = state.computedFor().plusDays(1);
        if (from.isAfter(upTo)) {
            return state;
        }
        List<Station> area = stationsFor(centre);
        SortedMap<LocalDate, Input> days = new TreeMap<>();
        stations.daily(area, from, upTo).forEach((d, in) -> {
            if (in.rainMm() != null && in.maxTemperatureC() != null) {
                days.put(d, new Input(in.rainMm(), in.maxTemperatureC()));
            }
        });
        boolean gap = false;
        for (LocalDate d = from; !d.isAfter(upTo); d = d.plusDays(1)) {
            if (!days.containsKey(d)) {
                gap = true;
                break;
            }
        }
        if (gap) {
            int pastDays = (int) java.time.temporal.ChronoUnit.DAYS.between(from, upTo) + 2;
            upstreams.recentDays(centre.lat(), centre.lon(), Math.min(92, pastDays)).ifPresent(rows -> {
                for (OpenMeteo.DailyRow r : rows) {
                    if (!days.containsKey(r.date()) && r.rainMm() != null && r.maxTemperatureC() != null) {
                        days.put(r.date(), new Input(r.rainMm(), r.maxTemperatureC()));
                    }
                }
            });
        }
        DroughtState current = state.forArea(centre.id(), cellsPerArea());
        double interceptionLeft = interceptionLeft(state.recentRainMm());
        for (LocalDate d = from; !d.isAfter(upTo); d = d.plusDays(1)) {
            Input in = days.get(d);
            if (in == null) {
                break;
            }
            current = current.step(d, in.rainMm(), in.maxTemperatureC(), interceptionLeft);
            interceptionLeft = in.rainMm() > 0 ? Math.max(0, interceptionLeft - Math.min(in.rainMm(), interceptionLeft)) : Kbdi.INTERCEPTION_MM;
        }
        return current;
    }

    /**
     * How much canopy interception is left in the current rain event, from the window: a dry
     * yesterday restores the full allowance, a run of wet days has used some of it.
     */
    static double interceptionLeft(List<Double> recentRainMm) {
        double eventRain = 0;
        for (int i = recentRainMm.size() - 1; i >= 0; i--) {
            Double r = recentRainMm.get(i);
            if (r == null || r <= 0) {
                break;
            }
            eventRain += r;
        }
        return Math.max(0, Kbdi.INTERCEPTION_MM - Math.min(Kbdi.INTERCEPTION_MM, eventRain));
    }

    /**
     * The days the stations did not cover, from the archive (older than its lag) and the recent-days
     * call (newer), each fetched at the area's centre and only when there is something missing in
     * its range.
     */
    private void fillGaps(Cell centre, SortedMap<LocalDate, Input> days, LocalDate from, LocalDate to, LocalDate today) {
        LocalDate archiveEnd = today.minusDays(ARCHIVE_LAG_DAYS);
        boolean missingOld = false, missingRecent = false;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (days.containsKey(d)) {
                continue;
            }
            if (d.isBefore(archiveEnd)) {
                missingOld = true;
            } else {
                missingRecent = true;
            }
        }
        if (missingOld) {
            upstreams.archive(centre.lat(), centre.lon(), from, archiveEnd).ifPresent(rows -> put(days, rows));
        }
        if (missingRecent) {
            upstreams.recentDays(centre.lat(), centre.lon(), ARCHIVE_LAG_DAYS + 2).ifPresent(rows -> put(days, rows));
        }
        days.headMap(from).clear();
        days.tailMap(to.plusDays(1)).clear();
    }

    private static void put(SortedMap<LocalDate, Input> days, List<OpenMeteo.DailyRow> rows) {
        for (OpenMeteo.DailyRow r : rows) {
            if (r.date() != null && !days.containsKey(r.date()) && r.rainMm() != null && r.maxTemperatureC() != null) {
                days.put(r.date(), new Input(r.rainMm(), r.maxTemperatureC()));
            }
        }
    }

    /**
     * The stations of an area: those inside its cells, else the nearest within reach of its centre.
     */
    List<Station> stationsFor(Cell centre) {
        List<Station> inside = stations.inCells(grid, grid.area(centre, RADIUS));
        if (!inside.isEmpty()) {
            return inside;
        }
        return stations.within(centre.lat(), centre.lon(), STATION_REACH_KM).stream()
                .limit(1).map(StationRegistry.Nearest::station).toList();
    }

    private record Input(double rainMm, double maxTemperatureC) {
    }
}
