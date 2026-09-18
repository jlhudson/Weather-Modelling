package au.gully.hexagons;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.cfs.Districts;
import au.gully.drought.DroughtAreas;
import au.gully.drought.Rivers;
import au.gully.platform.GullyProperties;
import au.gully.terrain.Terrain;
import au.gully.upstreams.Forecast;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one in-memory cache that holds everything we know about every hexagon (docs/06 items 0, 1 and
 * 14), rebuilt from the database when the service starts, so the database is not touched to answer
 * a request.
 * <p>
 * The rules, and they are short:
 * <ul>
 *   <li>A point is answered by the hexagon it falls in. A hexagon nobody has asked about does not
 *       exist; the first ask creates it, works out what it is made of, and fetches its reading.</li>
 *   <li>A reading is kept until the upstream says it is stale. Served close to expiry, it is
 *       refreshed in the background so the next ask is already fresh. Several asks arriving at once
 *       for a hexagon with nothing fetch it once.</li>
 *   <li>A hexagon with a Bureau station in it is always alive: its "now" is the station's, free,
 *       every ten minutes, and the upstream is called for its forecast only, when a forecast is asked for.</li>
 *   <li>The Hub sweeps its open incidents every few minutes, so their hexagons stay warm by being
 *       asked about and go cold on their own when the incident closes.</li>
 *   <li>History is written only when an ask says an incident is present.</li>
 * </ul>
 */
@Slf4j
@Service
public class HexagonStore {

    private final Grid grid;
    private final HexagonRepository repository;
    private final Terrain terrain;
    private final StationRegistry stations;
    private final Districts districts;
    private final Upstreams upstreams;
    private final FirePictures pictures;
    private final History history;
    private final DroughtAreas droughtAreas;
    private final Rivers rivers;
    private final GullyProperties properties;

    private final Map<String, Hexagon> hexagons = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Hexagon>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService refreshes = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("refresh-", 0).factory());
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong fetched = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();

    public HexagonStore(Grid grid, HexagonRepository repository, Terrain terrain, StationRegistry stations, Districts districts,
                        Upstreams upstreams, FirePictures pictures, History history, DroughtAreas droughtAreas, Rivers rivers,
                        GullyProperties properties) {
        this.grid = grid;
        this.repository = repository;
        this.terrain = terrain;
        this.stations = stations;
        this.districts = districts;
        this.upstreams = upstreams;
        this.pictures = pictures;
        this.history = history;
        this.droughtAreas = droughtAreas;
        this.rivers = rivers;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- the ask

    /**
     * The hexagon for a point, with a reading as fresh as the upstream allows.
     *
     * @param wantForecast whether the series is wanted; a station hexagon is not fetched without it
     * @param incident     the incident's id when one is present, which is what writes history; null otherwise
     * @return the hexagon as it stands after the ask; its reading may be stale, and says so, when no
     * upstream could answer
     */
    public Hexagon ask(double lat, double lon, boolean wantForecast, String incident) {
        Instant now = Instant.now();
        Cell cell = grid.cellOf(lat, lon);
        boolean created = !hexagons.containsKey(cell.id());
        Hexagon h = hexagons.computeIfAbsent(cell.id(), k -> create(cell, now));
        boolean firstAsk = h.activatedAt() == null;
        h = replace(cell.id(), old -> old.asked(now));
        if (firstAsk || created) {
            repository.saveActivity(h);
            h = ensureRiver(h, lat, lon, now);
        }
        // Every ask, not only the first: cheap when the area is current, and the way a hexagon picks up an
        // area spun up or adopted since it was last asked about.
        h = ensureDrought(h, now);

        boolean stationFresh = pictures.now(h, now).map(n -> "station".equals(n.from())).orElse(false);
        Forecast f = h.forecast();
        boolean need;
        Instant nextExpiry;
        if (f == null) {
            need = wantForecast || !stationFresh;
            nextExpiry = null;
        } else if (stationFresh) {
            need = wantForecast && f.forecastExpired(now);
            nextExpiry = f.forecastExpiresAt();
        } else {
            need = f.currentExpired(now) || (wantForecast && f.forecastExpired(now));
            nextExpiry = f.currentExpiresAt();
        }
        if (need) {
            h = fetchNow(h);
        } else if (nextExpiry != null && Duration.between(now, nextExpiry).compareTo(properties.refreshAhead()) < 0) {
            refreshInBackground(h.id());
        }
        if (h.fire() == null) {
            h = recompute(h.id(), now);
        }
        served.incrementAndGet();
        if (h.forecast() != null && !stationFresh && h.forecast().currentExpired(now)) {
            stale.incrementAndGet();
        }
        if (incident != null && !incident.isBlank()) {
            Optional<FirePictures.Now> current = pictures.now(h, now);
            if (current.isPresent() && history.snapshot(h, current.get(), incident.trim(), now)) {
                h = replace(h.id(), old -> old.snapshotted(now));
                repository.saveActivity(h);
            }
        }
        return h;
    }

    /**
     * The whole of what a hexagon is made of, worked out once: its elevation and slope from the
     * terrain file, its land use from the land-cover file, its fire ban district by point-in-polygon,
     * its station and its nearest station from the register.
     */
    private Hexagon create(Cell cell, Instant now) {
        Optional<Double> elevation = terrain.elevation(cell.lat(), cell.lon());
        Optional<Station> inside = stations.inCell(grid, cell);
        Optional<StationRegistry.Nearest> nearest = stations.nearest(cell.lat(), cell.lon());
        Double elev = elevation.orElse(null);
        String elevFrom = elevation.isPresent() ? "terrain" : null;
        if (elev == null && inside.isPresent() && inside.get().heightM() != null) {
            elev = inside.get().heightM();
            elevFrom = "station";
        }
        String zone = nearest.map(n -> n.station().zone()).orElse(properties.zone());
        Hexagon h = new Hexagon(cell, zone, elev, elevFrom,
                terrain.meanSlopeDeg(grid, cell).orElse(null),
                terrain.landUse(grid, cell, cell.lat(), cell.lon()).orElse(null),
                districts.districtOf(cell.lat(), cell.lon()).orElse(null),
                nearest.map(n -> n.station().district()).orElse(null),
                inside.map(Station::id).orElse(null),
                nearest.map(n -> n.station().id()).orElse(null),
                nearest.map(n -> au.gully.science.Numbers.round1(n.distanceKm())).orElse(null),
                null, null, null, null, now, null, null, null, 0, 0);
        repository.insert(h);
        version.incrementAndGet();
        log.debug("hexagon {} created at {},{}: {} station, district {}", cell.id(), cell.lat(), cell.lon(),
                inside.isPresent() ? "with" : "no", h.fireBanDistrict());
        return h;
    }

    private Hexagon fetchNow(Hexagon h) {
        CompletableFuture<Hexagon> f = inFlight.computeIfAbsent(h.id(), id -> CompletableFuture.supplyAsync(() -> fetch(id), refreshes)
                .whenComplete((r, e) -> inFlight.remove(id)));
        try {
            return f.join();
        } catch (CompletionException e) {
            log.debug("fetch for {} failed: {}", h.id(), e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return hexagons.getOrDefault(h.id(), h);
        }
    }

    private void refreshInBackground(String id) {
        inFlight.computeIfAbsent(id, k -> CompletableFuture.supplyAsync(() -> fetch(k), refreshes)
                .whenComplete((r, e) -> inFlight.remove(k)));
    }

    /**
     * One upstream fetch for one hexagon, on whichever thread; the hexagon replaced whole with the
     * answer, its row written, its picture recomputed.
     */
    private Hexagon fetch(String id) {
        Hexagon before = hexagons.get(id);
        if (before == null) {
            return null;
        }
        Instant now = Instant.now();
        try {
            Forecast forecast = upstreams.fetch(before.cell());
            Hexagon after = replace(id, old -> old.withForecast(forecast, now));
            repository.saveForecast(after);
            fetched.incrementAndGet();
            return recompute(id, now);
        } catch (Upstreams.NoUpstream e) {
            log.debug("no upstream for {}: {}", id, e.getMessage());
            return before;
        }
    }

    // ---------------------------------------------------------------- what changes the picture

    /**
     * The picture recomputed from what the hexagon holds now.
     */
    public Hexagon recompute(String id, Instant now) {
        return replace(id, old -> old.withFire(pictures.compute(old, now)));
    }

    /**
     * Every hexagon that has a picture, or could have one, recomputed: called when the stations, the
     * warnings, the ratings or the curing figures change.
     */
    public int recomputeAll() {
        Instant now = Instant.now();
        int n = 0;
        for (String id : hexagons.keySet()) {
            Hexagon h = hexagons.get(id);
            if (h != null && (h.active() || h.hasStation())) {
                recompute(id, now);
                n++;
            }
        }
        return n;
    }

    /**
     * The station register changed: every hexagon re-finds its station and its nearest, and every
     * station gets a hexagon of its own so the map can show it and it can answer "now" for free.
     */
    public int stationsChanged() {
        Instant now = Instant.now();
        int changed = 0;
        for (Station s : stations.all()) {
            Cell cell = grid.cellOf(s.lat(), s.lon());
            if (!hexagons.containsKey(cell.id())) {
                hexagons.computeIfAbsent(cell.id(), k -> create(cell, now));
                changed++;
            }
        }
        for (String id : hexagons.keySet()) {
            Hexagon h = hexagons.get(id);
            if (h == null) {
                continue;
            }
            Optional<Station> inside = stations.inCell(grid, h.cell());
            Optional<StationRegistry.Nearest> nearest = stations.nearest(h.cell().lat(), h.cell().lon());
            String insideId = inside.map(Station::id).orElse(null);
            String nearestId = nearest.map(n -> n.station().id()).orElse(null);
            if (!Objects.equals(insideId, h.stationId()) || !Objects.equals(nearestId, h.nearestStationId())) {
                Hexagon after = replace(id, old -> old.withStations(insideId, nearestId,
                        nearest.map(n -> au.gully.science.Numbers.round1(n.distanceKm())).orElse(null),
                        nearest.map(n -> n.station().district()).orElse(null)));
                repository.saveStations(after);
                changed++;
            }
        }
        recomputeAll();
        return changed;
    }

    /**
     * The district shapes were read: every hexagon whose centre now falls in a district it did not
     * carry (or carried differently) is re-joined. Needed on a cold start, where the station poll
     * creates the station hexagons before the shapes are read; harmless daily after that.
     */
    public int districtsChanged() {
        int changed = 0;
        for (String id : hexagons.keySet()) {
            Hexagon h = hexagons.get(id);
            if (h == null) {
                continue;
            }
            String district = districts.districtOf(h.cell().lat(), h.cell().lon()).orElse(null);
            if (!Objects.equals(district, h.fireBanDistrict())) {
                repository.saveDistrict(replace(id, old -> old.withDistrict(district)));
                changed++;
            }
        }
        if (changed > 0) {
            log.info("districts: {} hexagons joined to a district", changed);
            recomputeAll();
        }
        return changed;
    }

    // ---------------------------------------------------------------- drought and rivers

    /**
     * The hexagon carries a copy of its area's state (W-11): the area is spun up once, by whichever
     * hexagon in it is asked about first, and stepped once a day for all of them; the copy is
     * refreshed here whenever the area has moved on.
     */
    private Hexagon ensureDrought(Hexagon h, Instant now) {
        ZoneId zone = zoneOf(h);
        DroughtState state;
        try {
            state = droughtAreas.stateFor(h.cell(), zone, LocalDate.now(zone)).orElse(null);
        } catch (RuntimeException e) {
            log.warn("drought for {} not computed: {}", h.id(), e.getMessage());
            return h;
        }
        if (state == null || sameDrought(h.drought(), state)) {
            return h;
        }
        Hexagon after = replace(h.id(), old -> old.withDrought(state));
        repository.saveDrought(after);
        return after;
    }

    private static boolean sameDrought(DroughtState held, DroughtState area) {
        return held != null && held.computedFor() != null && held.computedFor().equals(area.computedFor())
                && Objects.equals(held.area(), area.area());
    }

    public int droughtAreaCount() {
        return droughtAreas.size();
    }

    private Hexagon ensureRiver(Hexagon h, double lat, double lon, Instant now) {
        if (!properties.sources().rivers()) {
            return h;
        }
        LocalDate today = LocalDate.now(zoneOf(h));
        if (h.river() != null && !h.river().computedFor().isBefore(today)) {
            return h;
        }
        Optional<RiverState> river = rivers.at(lat, lon, today);
        if (river.isEmpty()) {
            return h;
        }
        Hexagon after = replace(h.id(), old -> old.withRiver(river.get()));
        repository.saveRiver(after);
        return after;
    }

    /**
     * The daily step (docs/06 item 7): every area whose last complete day is behind the calendar is
     * stepped forward from the station ledger, exactly once per area per day, after 9:10 am in the
     * area's zone when the rain day has closed; then every hexagon carrying a copy that the area
     * has moved past takes the new state and has its picture recomputed. Runs on a short timer.
     *
     * @return how many areas were stepped
     */
    public int stepDrought() {
        Instant now = Instant.now();
        Set<String> stepped = droughtAreas.stepAll(now);
        int copied = 0;
        for (String id : hexagons.keySet()) {
            Hexagon h = hexagons.get(id);
            if (h == null || h.drought() == null) {
                continue;
            }
            DroughtState area = droughtAreas.held(h.cell()).orElse(null);
            if (area == null || sameDrought(h.drought(), area)) {
                continue;
            }
            Hexagon after = replace(id, old -> old.withDrought(area));
            repository.saveDrought(after);
            recompute(id, now);
            copied++;
        }
        if (!stepped.isEmpty() || copied > 0) {
            log.info("drought: {} areas stepped, {} hexagons took their area's state", stepped.size(), copied);
        }
        return stepped.size();
    }

    /**
     * Rivers refreshed once a day for the active hexagons.
     */
    public int refreshRivers() {
        if (!properties.sources().rivers()) {
            return 0;
        }
        Instant now = Instant.now();
        int refreshed = 0;
        for (String id : hexagons.keySet()) {
            Hexagon h = hexagons.get(id);
            if (h == null || !h.active() || h.river() == null || h.lastAskedAt() == null
                    || Duration.between(h.lastAskedAt(), now).compareTo(properties.coldAfter()) > 0) {
                continue;
            }
            LocalDate today = LocalDate.now(zoneOf(h));
            if (!h.river().computedFor().isBefore(today)) {
                continue;
            }
            Optional<RiverState> river = rivers.at(h.river().lat(), h.river().lon(), today);
            if (river.isPresent()) {
                Hexagon after = replace(id, old -> old.withRiver(river.get()));
                repository.saveRiver(after);
                refreshed++;
            }
        }
        return refreshed;
    }

    /**
     * Forecasts nobody has asked about for {@code gully.cold-after} are dropped from memory and from
     * the row; the hexagon itself stays, with what it is made of.
     */
    public int sweep() {
        Instant now = Instant.now();
        int cooled = 0;
        for (String id : hexagons.keySet()) {
            Hexagon h = hexagons.get(id);
            if (h == null || h.forecast() == null || h.lastAskedAt() == null) {
                continue;
            }
            if (Duration.between(h.lastAskedAt(), now).compareTo(properties.coldAfter()) > 0) {
                Hexagon after = replace(id, Hexagon::cold);
                repository.saveForecast(after);
                cooled++;
            }
        }
        return cooled;
    }

    // ---------------------------------------------------------------- lifecycle and reads

    /**
     * Phase 2: every hexagon row back into memory; the pictures are computed once the registries are up.
     */
    public void rehydrate() {
        hexagons.clear();
        for (Hexagon h : repository.loadAll(grid)) {
            hexagons.put(h.id(), h);
        }
        version.incrementAndGet();
        log.info("hexagons rehydrated: {} ({} active, {} with a forecast)", hexagons.size(),
                hexagons.values().stream().filter(Hexagon::active).count(),
                hexagons.values().stream().filter(Hexagon::hasForecast).count());
        // States computed per hexagon before the areas existed become their area's state, the most
        // recently computed first, so nothing already spun up is spent again (W-11).
        int adopted = 0;
        List<Hexagon> withDrought = hexagons.values().stream().filter(h -> h.drought() != null && h.drought().area() == null)
                .sorted(Comparator.comparing((Hexagon h) -> h.drought().computedFor()).reversed()).toList();
        for (Hexagon h : withDrought) {
            if (droughtAreas.adopt(h.cell(), zoneOf(h).getId(), h.drought())) {
                adopted++;
            }
        }
        if (adopted > 0) {
            log.info("drought areas: {} adopted from hexagons computed before the areas", adopted);
        }
    }

    private Hexagon replace(String id, java.util.function.UnaryOperator<Hexagon> change) {
        Hexagon out = hexagons.computeIfPresent(id, (k, old) -> change.apply(old));
        version.incrementAndGet();
        return out;
    }

    public Optional<Hexagon> get(String id) {
        return Optional.ofNullable(hexagons.get(id));
    }

    public Optional<Hexagon> at(double lat, double lon) {
        return get(grid.cellOf(lat, lon).id());
    }

    public Collection<Hexagon> all() {
        return List.copyOf(hexagons.values());
    }

    public int size() {
        return hexagons.size();
    }

    /**
     * Bumped on every replacement; the map layer's fingerprint.
     */
    public long version() {
        return version.get();
    }

    public Grid grid() {
        return grid;
    }

    public long servedCount() {
        return served.get();
    }

    public long fetchedCount() {
        return fetched.get();
    }

    public long staleCount() {
        return stale.get();
    }

    public int inFlight() {
        return inFlight.size();
    }

    public ZoneId zoneOf(Hexagon h) {
        try {
            return h.zone() == null ? properties.zoneId() : ZoneId.of(h.zone());
        } catch (RuntimeException e) {
            return properties.zoneId();
        }
    }
}
