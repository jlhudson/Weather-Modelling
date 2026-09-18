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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Drought over a hexagon and its ring, stepped forward daily (docs/06 item 7). Drought is a property
 * of a district, not a 15 km cell, so the area is the seven hexagons — about 45 km across — and the
 * inputs are the Bureau stations inside them: the day's rain to 9 am and the day's maximum, which
 * the station ledger has for every day the service has been running. Starting an area fetches only
 * the days the stations do not cover, from Open-Meteo's archive; after that it is free.
 */
@Slf4j
@Service
public class DroughtAreas {

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
     * When an area has no station in its seven hexagons, the nearest within this reach stands in.
     */
    static final double STATION_REACH_KM = 75;

    /**
     * Used for the equation's mean annual rainfall only when the window is too short to derive it.
     */
    static final double DEFAULT_ANNUAL_RAIN_MM = 550;

    private final Grid grid;
    private final StationRegistry stations;
    private final Upstreams upstreams;

    public DroughtAreas(Grid grid, StationRegistry stations, Upstreams upstreams) {
        this.grid = grid;
        this.stations = stations;
        this.upstreams = upstreams;
    }

    /**
     * A year of daily rain and maximum temperature for the area, from the stations where they have
     * it and the archive for the rest, integrated into a deficit. Empty when neither could supply
     * enough days.
     */
    public Optional<DroughtState> spinUp(Cell cell, ZoneId zone, LocalDate today) {
        LocalDate from = today.minusDays(SPIN_UP_DAYS);
        LocalDate to = today.minusDays(1);
        List<Station> area = stationsFor(cell);
        SortedMap<LocalDate, Input> days = new TreeMap<>();
        stations.daily(area, from, to).forEach((d, in) -> {
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
        log.info("drought area {}: KBDI {} mm, DF {}, from {} days ({} from {} stations)", cell.id(),
                Math.round(state.kbdiMm()), au.gully.science.Numbers.round1(state.droughtFactor()), dates.size(),
                fromStations, area.size());
        return Optional.of(state);
    }

    /**
     * The state stepped forward through every complete day up to {@code upTo}, from the stations,
     * with the recent-days call filling any day they missed. Stops at the first day nothing can
     * supply, and says how far it got.
     */
    public DroughtState stepTo(Cell cell, DroughtState state, LocalDate upTo) {
        LocalDate from = state.computedFor().plusDays(1);
        if (from.isAfter(upTo)) {
            return state;
        }
        List<Station> area = stationsFor(cell);
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
     * call (newer), each fetched only when there is something missing in its range.
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
     * The stations of an area: those inside its seven hexagons, else the nearest within reach.
     */
    List<Station> stationsFor(Cell cell) {
        List<Station> inside = stations.inCells(grid, grid.area(cell));
        if (!inside.isEmpty()) {
            return inside;
        }
        return stations.within(cell.lat(), cell.lon(), STATION_REACH_KM).stream()
                .limit(1).map(StationRegistry.Nearest::station).toList();
    }

    private record Input(double rainMm, double maxTemperatureC) {
    }
}
