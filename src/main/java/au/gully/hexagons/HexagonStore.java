package au.gully.hexagons;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.cfs.Districts;
import au.gully.drought.Drought;
import au.gully.drought.Rivers;
import au.gully.platform.GullyProperties;
import au.gully.science.LandUse;
import au.gully.terrain.DeaLandCover;
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
    private final DeaLandCover landCover;
    private final StationRegistry stations;
    private final Districts districts;
    private final Upstreams upstreams;
    private final FirePictures pictures;
    private final History history;
    private final Drought drought;
    private final Rivers rivers;
    private final Life life;
    private final Drifts drifts;
    private final Sources sources;
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
    /** When a hexagon's elevation was last asked for and not answered, so a failing endpoint is not asked on every ask. */
    private final Map<String, Instant> elevationTried = new ConcurrentHashMap<>();
    /** The same for its land cover. */
    private final Map<String, Instant> landCoverTried = new ConcurrentHashMap<>();
    /** The land-cover rasters read lately, a few kilobytes each, for the class at a point; the rest are in the row. */
    private final Map<String, byte[]> rasters = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 256;
        }
    });

    /**
     * How long after a forecast is thrown out for drift before one is fetched again: the next model
     * run, roughly, and meanwhile the station is "now" and the days ahead wait.
     */
    public static final Duration REFETCH_AFTER_DRIFT = Duration.ofHours(1);

    public HexagonStore(Grid grid, HexagonRepository repository, Terrain terrain, DeaLandCover landCover, StationRegistry stations, Districts districts,
                        Upstreams upstreams, FirePictures pictures, History history, Drought drought, Rivers rivers,
                        Life life, Drifts drifts, Sources sources, GullyProperties properties) {
        this.grid = grid;
        this.repository = repository;
        this.terrain = terrain;
        this.landCover = landCover;
        this.stations = stations;
        this.districts = districts;
        this.upstreams = upstreams;
        this.pictures = pictures;
        this.history = history;
        this.drought = drought;
        this.rivers = rivers;
        this.life = life;
        this.drifts = drifts;
        this.sources = sources;
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
        // The sources this answer draws on, read now if they are older than their cadence (W-14): the
        // station file and the warnings of the state the hexagon is in, the CFS feeds for South Australia.
        sources.ensureFor(cell, now);
        boolean created = !hexagons.containsKey(cell.id());
        Hexagon h = hexagons.computeIfAbsent(cell.id(), k -> create(cell, now));
        boolean firstAsk = h.activatedAt() == null;
        h = replace(cell.id(), old -> old.asked(now));
        if (firstAsk || created) {
            repository.saveActivity(h);
        }
        h = ensureElevation(h, now);
        h = ensureLandUse(h, now);
        h = ensureRiver(h, lat, lon, now);
        // Every ask, not only the first: cheap when current, and the way a hexagon picks up a spin-up
        // that could not be fed the last time.
        h = ensureDrought(h, now);

        // The station's word on the forecast, before deciding whether one is needed.
        h = checkDrift(h, now);
        boolean stationFresh = pictures.now(h, now).map(FirePictures.Now::observed).orElse(false);
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
        // The picture is drawn on the ask, from what is held now: nothing keeps it up between asks.
        h = recompute(h.id(), now);
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
                terrain.landUse(grid, cell).orElse(null),
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
        log.info("forecast for {} thrown out: {} against station {}", h.id(), drift.get().describe(), drift.get().stationId());
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
     * The hexagon's mean elevation, for a hexagon that is asked about (W-13): from the terrain file when
     * one is mounted (read at creation), else from Open-Meteo's elevation model over a lattice of
     * points across the hexagon, one call, once. A station hexagon nobody asks about keeps its
     * station's height; the interpolation brings the neighbours' values to the elevation of the hexagon
     * asked about, and that is the one that has to be right.
     */
    private Hexagon ensureElevation(Hexagon h, Instant now) {
        if ("terrain".equals(h.elevationFrom()) || "open-meteo".equals(h.elevationFrom())) {
            return h;
        }
        Instant tried = elevationTried.get(h.id());
        if (tried != null && Duration.between(tried, now).compareTo(Duration.ofMinutes(15)) < 0) {
            return h;
        }
        elevationTried.put(h.id(), now);
        List<double[]> points = grid.lattice(h.cell(), 7);
        Optional<List<Double>> heights = upstreams.elevation(points, h.id());
        if (heights.isEmpty()) {
            return h;
        }
        double sum = 0;
        int count = 0;
        for (Double z : heights.get()) {
            if (z != null) {
                sum += z;
                count++;
            }
        }
        if (count == 0) {
            return h;
        }
        double mean = Math.round(sum / count * 10) / 10.0;
        Hexagon after = replace(h.id(), old -> old.withElevation(mean, "open-meteo"));
        repository.saveElevation(after);
        elevationTried.remove(h.id());
        log.debug("hexagon {}: mean elevation {} m from {} points", h.id(), mean, count);
        return after;
    }

    /**
     * The hexagon's land use, for a hexagon that is asked about (W-15): from the mounted file when one
     * is there (counted at creation), else Digital Earth Australia's land cover read once for the
     * hexagon's box and kept with it.
     */
    private Hexagon ensureLandUse(Hexagon h, Instant now) {
        if (h.landUse() != null || terrain.hasLandCover() || !properties.enabled()) {
            return h;
        }
        Instant tried = landCoverTried.get(h.id());
        if (tried != null && Duration.between(tried, now).compareTo(Duration.ofMinutes(15)) < 0) {
            return h;
        }
        landCoverTried.put(h.id(), now);
        Optional<DeaLandCover.Cover> cover = landCover.fetch(grid, h.cell(), h.id());
        if (cover.isEmpty()) {
            return h;
        }
        Optional<LandUse> use = DeaLandCover.landUse(cover.get(), grid, h.cell());
        if (use.isEmpty()) {
            return h;
        }
        Hexagon after = replace(h.id(), old -> old.withLandUse(use.get()));
        repository.saveLandUse(after, cover.get().tiff());
        rasters.put(h.id(), cover.get().tiff());
        landCoverTried.remove(h.id());
        log.debug("hexagon {}: land use {} from {}", h.id(), use.get().byKey(), use.get().source());
        return after;
    }

    /**
     * The land-cover class at a point in a hexagon: from the mounted file, else from the raster the
     * hexagon was read from. Empty when neither is there.
     */
    public Optional<LandUse.LandClass> landClassAt(Hexagon h, double lat, double lon) {
        Optional<LandUse.LandClass> mounted = terrain.landClassAt(lat, lon);
        if (mounted.isPresent()) {
            return mounted;
        }
        if (h.landUse() == null) {
            return Optional.empty();
        }
        byte[] tiff = rasters.get(h.id());
        if (tiff == null) {
            tiff = repository.landCover(h.id()).orElse(null);
            if (tiff == null) {
                return Optional.empty();
            }
            rasters.put(h.id(), tiff);
        }
        return DeaLandCover.classAt(tiff, lat, lon);
    }

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
