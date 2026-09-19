package au.gully.drought;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.hexagons.Cell;
import au.gully.hexagons.DroughtState;
import au.gully.hexagons.Grid;
import au.gully.science.Kbdi;
import au.gully.upstreams.OpenMeteo;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The drought of a hexagon, stepped forward daily (docs/06 item 7): a soil moisture deficit
 * integrated from a year of daily rain and maximum temperature, and the drought factor that falls
 * out of it. It is the hexagon's, like everything else the hexagon holds — one state per hexagon,
 * on its row — and it needs no cell of its own: at 17 km a hexagon is already the scale a drought
 * factor describes.
 * <p>
 * The inputs are the Bureau stations inside the hexagon (or the nearest within reach) — the day's
 * rain to 9 am and the day's maximum, which the station ledger has for every day the service has
 * been running — and, for the days they do not cover, Open-Meteo's archive at the hexagon's centre:
 * one archive fetch per hexagon, about six allowance units, once. After a year of the ledger the
 * archive is never asked again.
 */
@Slf4j
@Service
public class Drought {

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
     * When a hexagon has no station in it, the nearest within this reach stands in.
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
     * How long after a spin-up or a step that produced nothing before it is tried again for the hexagon.
     */
    static final Duration RETRY_AFTER = Duration.ofMinutes(15);

    private final Grid grid;
    private final StationRegistry stations;
    private final Upstreams upstreams;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Instant> attempts = new ConcurrentHashMap<>();

    public Drought(Grid grid, StationRegistry stations, Upstreams upstreams) {
        this.grid = grid;
        this.stations = stations;
        this.upstreams = upstreams;
    }

    /**
     * The state a hexagon should hold now: spun up when it has none, stepped to yesterday when it is
     * behind, the state it holds when that is current. One spin-up per hexagon, whatever asks
     * concurrently; a spin-up or a step that could not be fed — the archive out of allowance, the
     * day's inputs not in yet — is tried again after a while, not on every ask. Empty when the
     * hexagon has nothing and nothing could be made.
     */
    public Optional<DroughtState> ensure(Cell cell, DroughtState held, ZoneId zone, LocalDate today) {
        synchronized (locks.computeIfAbsent(cell.id(), k -> new Object())) {
            // The last complete rain day: yesterday once today's 9 am total is in the files, the day before until then.
            ZonedDateTime local = Instant.now().atZone(zone);
            LocalDate yesterday = local.toLocalTime().isBefore(DAY_CLOSES) ? today.minusDays(2) : today.minusDays(1);
            if (held != null && !held.computedFor().isBefore(yesterday)) {
                return Optional.of(held);
            }
            Instant last = attempts.get(cell.id());
            if (last != null && Instant.now().isBefore(last.plus(RETRY_AFTER))) {
                return Optional.ofNullable(held);
            }
            attempts.put(cell.id(), Instant.now());
            if (held == null) {
                return spinUp(cell, today);
            }
            return Optional.of(stepTo(cell, held, yesterday));
        }
    }

    // ---------------------------------------------------------------- the maths

    /**
     * A year of daily rain and maximum temperature for the hexagon, from the stations where they
     * have it and the archive at the centre for the rest, integrated into a deficit. Empty when
     * neither could supply enough days.
     */
    Optional<DroughtState> spinUp(Cell cell, LocalDate today) {
        LocalDate from = today.minusDays(SPIN_UP_DAYS);
        LocalDate to = today.minusDays(1);
        List<Station> around = stationsFor(cell);
        SortedMap<LocalDate, Input> days = new TreeMap<>();
        stations.daily(around, from, to).forEach((d, in) -> {
            if (in.rainMm() != null && in.maxTemperatureC() != null) {
                days.put(d, new Input(in.rainMm(), in.maxTemperatureC()));
            }
        });
        int fromStations = days.size();
        fillGaps(cell, days, from, to, today);
        if (days.size() < Kbdi.WINDOW_DAYS) {
            log.warn("drought spin-up for {}: only {} days available", cell.id(), days.size());
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
                dates.size(), window, source);
        log.info("drought {}: KBDI {} mm, DF {}, from {} days ({} from {} stations)", cell.id(),
                Math.round(state.kbdiMm()), au.gully.science.Numbers.round1(state.droughtFactor()), dates.size(),
                fromStations, around.size());
        return Optional.of(state);
    }

    /**
     * The state stepped forward through every complete day up to {@code upTo}, from the stations,
     * with the recent-days call at the centre filling any day they missed. Stops at the first day
     * nothing can supply, and says how far it got.
     */
    DroughtState stepTo(Cell cell, DroughtState state, LocalDate upTo) {
        LocalDate from = state.computedFor().plusDays(1);
        if (from.isAfter(upTo)) {
            return state;
        }
        List<Station> around = stationsFor(cell);
        SortedMap<LocalDate, Input> days = new TreeMap<>();
        stations.daily(around, from, upTo).forEach((d, in) -> {
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
            upstreams.recentDays(cell.lat(), cell.lon(), Math.min(92, pastDays)).ifPresent(rows -> {
                for (OpenMeteo.DailyRow r : rows) {
                    if (!days.containsKey(r.date()) && r.rainMm() != null && r.maxTemperatureC() != null) {
                        days.put(r.date(), new Input(r.rainMm(), r.maxTemperatureC()));
                    }
                }
            });
        }
        DroughtState current = state;
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
     * call (newer), each fetched at the hexagon's centre and only when there is something missing in
     * its range.
     */
    private void fillGaps(Cell cell, SortedMap<LocalDate, Input> days, LocalDate from, LocalDate to, LocalDate today) {
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
            upstreams.archive(cell.lat(), cell.lon(), from, archiveEnd).ifPresent(rows -> put(days, rows));
        }
        if (missingRecent) {
            upstreams.recentDays(cell.lat(), cell.lon(), ARCHIVE_LAG_DAYS + 2).ifPresent(rows -> put(days, rows));
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
     * The stations of a hexagon: those inside it, else the nearest within reach of its centre.
     */
    List<Station> stationsFor(Cell cell) {
        List<Station> inside = stations.inCells(grid, List.of(cell));
        if (!inside.isEmpty()) {
            return inside;
        }
        return stations.within(cell.lat(), cell.lon(), STATION_REACH_KM).stream()
                .limit(1).map(StationRegistry.Nearest::station).toList();
    }

    private record Input(double rainMm, double maxTemperatureC) {
    }
}
