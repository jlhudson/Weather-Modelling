package au.gully.api;

import au.gully.drought.Drought;
import au.gully.drought.DroughtDays;
import au.gully.hexagons.Geo;
import au.gully.hexagons.Grid;
import au.gully.hexagons.Hexagon;
import au.gully.hexagons.HexagonStore;
import au.gully.science.DroughtIndex;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static au.gully.science.Numbers.round1;

/**
 * {@code GET /api/v1/drought?lat=&lon=&days=} (W-20): the drought at a point, and the record behind
 * it - the soil moisture deficit and the drought factor as the reading carries them, where the
 * inputs came from, the stations feeding it, the last so many days of rain and maximum the deficit
 * was stepped with (each with its source: the stations, the archive, the recent-days call), and the
 * rain totals those days add up to. An ask, like the reading: a hexagon nobody has asked about is
 * created and its drought spun up, which is the one archive call the hexagon ever makes.
 */
@RestController
@RequestMapping(path = "/api/v1/drought", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "drought", description = "The drought at a point, and the daily rain and maximum behind it")
public class DroughtController {

    public static final String SCHEMA = "gully/drought/1";

    /**
     * How many days of the record come back by default, and at most: a month, and the year the deficit is integrated over.
     */
    static final int DAYS_DEFAULT = 30, DAYS_MAX = 366;

    private final HexagonStore store;
    private final Drought drought;
    private final DroughtDays days;

    /**
     * @param from     where the deficit's inputs came from: {@code stations}, {@code archive} or {@code stations+archive}
     * @param stations the stations feeding the hexagon's days - inside it or within its reach, else the nearest within 75 km
     * @param days     the record, oldest first, as many as asked for
     * @param rain     the rain the record adds up to over the last 7, 30, 90 and 365 days, where the record covers them
     */
    public record DroughtReading(String schema, boolean available, String unavailable, Reading.Point point, HexagonRef hexagon,
                                 DroughtIndex drought, String from, List<StationRef> stations, List<Day> days, Rain rain, String disclaimer) {
    }

    public record HexagonRef(String id, double lat, double lon, double widthKm, String zone, String fireBanDistrict, String stationId) {
    }

    public record StationRef(String id, String name, double distanceKm, Double heightM) {
    }

    public record Day(LocalDate date, double rainMm, double maxTemperatureC, String source) {
    }

    public record Rain(Double last7DaysMm, Double last30DaysMm, Double last90DaysMm, Double last365DaysMm, int daysHeld) {
    }

    @GetMapping
    @Operation(summary = "The drought at a point, and the days behind it",
            description = "KBDI and the drought factor as the reading carries them, where the inputs came from, the stations "
                    + "feeding the hexagon, the last `days` days of rain and maximum the deficit was stepped with (each with "
                    + "its source), and the rain totals over 7, 30, 90 and 365 days.")
    public ResponseEntity<DroughtReading> at(
            @Parameter(description = "latitude, -90..90") @RequestParam double lat,
            @Parameter(description = "longitude, -180..180") @RequestParam double lon,
            @Parameter(description = "how many days of the record to return, newest last; 30 by default, 366 at most") @RequestParam(defaultValue = "30") int days,
            @Parameter(description = "what the reading is for - carried on the ask") @RequestParam(required = false) String ref) {
        if (!Geo.plausible(lat, lon)) {
            throw ReadingsController.bad("lat must be -90..90 and lon must be -180..180");
        }
        if (!Geo.inAustralia(lat, lon)) {
            throw ReadingsController.bad("the point is outside Australia, which is all this service holds");
        }
        int n = Math.max(1, Math.min(DAYS_MAX, days));
        Hexagon h = store.ask(lat, lon, false, ref);
        Reading.Point point = new Reading.Point(lat, lon);
        Grid grid = store.grid();
        HexagonRef hex = new HexagonRef(h.id(), round(h.cell().lat()), round(h.cell().lon()), grid.cellKm(), h.zone(), h.fireBanDistrict(), h.stationId());
        List<StationRef> feeding = drought.stationsFor(h.cell()).stream()
                .map(s -> new StationRef(s.id(), s.name(), round1(Grid.planarMetres(h.cell().lat(), h.cell().lon(), s.lat(), s.lon()) / 1000), s.heightM()))
                .toList();
        List<DroughtDays.Day> record = this.days.recent(h.id(), DAYS_MAX);
        List<Day> recent = record.stream().skip(Math.max(0, record.size() - n))
                .map(d -> new Day(d.day(), d.rainMm(), d.maxTemperatureC(), d.source())).toList();
        Rain rain = new Rain(sum(record, 7), sum(record, 30), sum(record, 90), sum(record, 365), record.size());
        DroughtReading out;
        if (h.drought() == null) {
            out = new DroughtReading(SCHEMA, false, "no drought yet: the spin-up needs a year of days and could not be fed - the archive is "
                    + "out of allowance or the upstream is off; it is tried again on a later ask", point, hex, null, null, feeding, recent, rain, Reading.DISCLAIMER);
        } else {
            out = new DroughtReading(SCHEMA, true, null, point, hex, h.drought().index(), h.drought().from(), feeding, recent, rain, Reading.DISCLAIMER);
        }
        // Stepped once a day: an hour is a safe age for a client's copy.
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate()).body(out);
    }

    /**
     * The rain over the last so many days of the record, or null when the record does not reach that far back.
     */
    static Double sum(List<DroughtDays.Day> record, int lastDays) {
        if (record.size() < lastDays) {
            return null;
        }
        double total = 0;
        for (int i = record.size() - lastDays; i < record.size(); i++) {
            total += record.get(i).rainMm();
        }
        return round1(total);
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }
}
