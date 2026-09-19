package au.gully.hexagons;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.cfs.Districts;
import au.gully.drought.Drought;
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
 *   <li>A forecast is kept for its life - three hours, five when the allowance is tight ({@link Life}) -
 *       and thrown out early when the station in the hexagon says it has drifted ({@link Drift}). Served
 *       close to the end of its life, it is refreshed in the background so the next ask is already
 *       fresh. Several asks arriving at once for a hexagon with nothing fetch it once.</li>
 *   <li>A hexagon with a Bureau station in it is always alive: its "now" is the station's, free,
 *       every ten minutes, and the upstream is called for its forecast only, when a forecast is asked for.</li>
 *   <li>A caller that keeps asking keeps its hexagons warm; they go cold on their own when it stops.</li>
 *   <li>History is written only when an ask carries a ref - what the reading is for.</li>
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
    private final Drought drought;
    private final Rivers rivers;
    private final Life life;
    private final Drifts drifts;
    private final GullyProperties properties;

    private final Map<String, Hexagon> hexagons = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Hexagon>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService refreshes = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("refresh-", 0).factory());
    private final AtomicLong version = new AtomicLong();
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong fetched = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    /** When a hexagon's forecast was last thrown out for drift, so a model that is simply wrong is not re-fetched every ten minutes. */
    private final Map<String, Instant> discarded = new ConcurrentHashMap<>();

    /**
     * How long after a forecast is thrown out for drift before one is fetched again: the next model
     * run, roughly, and meanwhile the station is "now" and the days ahead wait.
     */
    public static final Duration REFETCH_AFTER_DRIFT = Duration.ofHours(1);

    public HexagonStore(Grid grid, HexagonRepository repository, Terrain terrain, StationRegistry stations, Districts districts,
                        Upstreams upstreams, FirePictures pictures, History history, Drought drought, Rivers rivers,
                        Life life, Drifts drifts, GullyProperties properties) {
        this.grid = grid;
        this.repository = repository;
        this.terrain = terrain;
        this.stations = stations;
        this.districts = districts;
        this.upstreams = upstreams;
        this.pictures = pictures;
        this.history = history;
        this.drought = drought;
        this.rivers = rivers;
        this.life = life;
        this.drifts = drifts;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- the ask

    /**
     * The hexagon for a point, with a reading as fresh as the upstream allows.
     *
     * @param wantForecast whether the series is wanted; a station hexagon is not fetched without it
     * @param ref          what the reading is for, when the caller says - an incident id, a job number,
     *                     anything - which is what writes history; null otherwise
     * @return the hexagon as it stands after the ask; its reading may be stale, and says so, when no
     * upstream could answer
     */
    public Hexagon ask(double lat, double lon, boolean wantForecast, String ref) {
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

        // The station's word on the forecast, before deciding whether one is needed.
        h = checkDrift(h, now);
        boolean stationFresh = pictures.now(h, now).map(n -> "station".equals(n.from())).orElse(false);
        Forecast f = h.forecast();
        Instant discardedAt = discarded.get(h.id());
        boolean mayFetch = discardedAt == null || Duration.between(discardedAt, now).compareTo(REFETCH_AFTER_DRIFT) >= 0;
        boolean need;
        Instant nextExpiry = life.expiresAt(f);
        if (f == null) {
            need = (wantForecast || !stationFresh) && mayFetch;
        } else if (stationFresh) {
            need = wantForecast && life.expired(f, now);
        } else {
            need = life.expired(f, now);
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
        if (h.forecast() != null && !stationFresh && life.expired(h.forecast(), now)) {
            stale.incrementAndGet();
        }
        if (ref != null && !ref.isBlank()) {
            Optional<FirePictures.Now> current = pictures.now(h, now);
            if (current.isPresent() && history.snapshot(h, current.get(), ref.trim(), now)) {
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
            repository.saveForecast(after, life.expiresAt(forecast));
            discarded.remove(id);
            fetched.incrementAndGet();
            // Judged the moment it arrives: a fresh forecast that already disagrees with the station is the
            // comparison most worth having.
            checkDrift(after, now);
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
                checkDrift(h, now);
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

    /**
     * The station in the hexagon against the forecast it holds (W-12). A forecast that has drifted is
     * thrown out: the station is "now" regardless, and the days ahead are fetched again after
     * {@link #REFETCH_AFTER_DRIFT} - the next model run, roughly - rather than at once, so a model
     * that is simply wrong today is not re-fetched every ten minutes.
     */
    private Hexagon checkDrift(Hexagon h, Instant now) {
        Optional<Drift> drift = drifts.check(h, now);
        if (drift.isEmpty() || !drift.get().drifted()) {
            return h;
        }
        log.info("forecast for {} thrown out: {} against station {}", h.id(), drift.get().describe(), h.stationId());
        Hexagon after = replace(h.id(), old -> old.withForecast(null, now));
        repository.saveForecast(after, null);
        discarded.put(h.id(), now);
        return recompute(h.id(), now);
    }

    public Optional<Drift> drift(String id) {
        return drifts.latest(id);
    }

    public Life life() {
        return life;
    }

    // ---------------------------------------------------------------- drought and rivers

    /**
     * The hexagon's drought: spun up on the first ask that can feed it, stepped to yesterday when it is
     * behind, and otherwise left alone. Cheap when current.
     */
    private Hexagon ensureDrought(Hexagon h, Instant now) {
        ZoneId zone = zoneOf(h);
        DroughtState state;
        try {
            state = drought.ensure(h.cell(), h.drought(), zone, LocalDate.now(zone)).orElse(null);
        } catch (RuntimeException e) {
            log.warn("drought for {} not computed: {}", h.id(), e.getMessage());
            return h;
        }
        if (state == null || state == h.drought()) {
            return h;
        }
        Hexagon after = replace(h.id(), old -> old.withDrought(state));
        repository.saveDrought(after);
        return after;
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
     * The daily step (docs/06 item 7): every hexagon whose last complete day is behind the calendar is
     * stepped forward from the station ledger, exactly once per hexagon per day, after 9:10 am in its
     * zone when the rain day has closed, and has its picture recomputed. Runs on a short timer.
     *
     * @return how many hexagons were stepped
     */
    public int stepDrought() {
        Instant now = Instant.now();
        int stepped = 0;
        for (String id : hexagons.keySet()) {
            Hexagon h = hexagons.get(id);
            if (h == null || h.drought() == null) {
                continue;
            }
            try {
                DroughtState next = drought.stepDaily(h.cell(), h.drought(), zoneOf(h), now).orElse(null);
                if (next != null) {
                    Hexagon after = replace(id, old -> old.withDrought(next));
                    repository.saveDrought(after);
                    recompute(id, now);
                    stepped++;
                }
            } catch (RuntimeException e) {
                log.warn("drought step for {} failed: {}", id, e.getMessage());
            }
        }
        if (stepped > 0) {
            log.info("drought: {} hexagons stepped", stepped);
        }
        return stepped;
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
                repository.saveForecast(after, null);
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

    /**
     * The grid against the one the tables were written with; a change resets the hexagon-keyed tables.
     */
    public boolean ensureGrid() {
        return repository.ensureGrid(grid);
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
