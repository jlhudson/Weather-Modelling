package au.gully.reading;

import au.gully.bureau.Observation;
import au.gully.record.Record;
import au.gully.upstreams.Conditions;
import au.gully.upstreams.Forecast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The model's answer at a point, in the shape a station's reading has (W-7): the current block for
 * the moment, and the hourly series behind it for what a station's file carries as running figures
 * - the rain since 9 am, the total of the day to 9 am, the maximum since 9 am. Each is null where
 * the series does not reach: a total is never made from part of a day.
 */
public final class PointCurrent {

    private PointCurrent() {
    }

    public static Observation of(String pointId, Forecast f, ZoneId zone) {
        Conditions c = f.current();
        if (c == null || c.at() == null) {
            return null;
        }
        Instant at = c.at();
        LocalDate today = Record.dayOf(at, zone);
        Instant dayStart = today.atTime(Record.DAY_TURNS_AT).atZone(zone).toInstant();
        Instant yesterdayStart = today.minusDays(1).atTime(Record.DAY_TURNS_AT).atZone(zone).toInstant();
        Double sinceNine = sum(f, dayStart, at);
        Double toNine = sum(f, yesterdayStart, dayStart);
        Double max = c.temperatureC();
        for (Conditions h : f.hourly()) {
            if (h.at() != null && !h.at().isBefore(dayStart) && !h.at().isAfter(at) && h.temperatureC() != null) {
                max = max == null ? h.temperatureC() : Math.max(max, h.temperatureC());
            }
        }
        return new Observation(pointId, at, c.temperatureC(), c.apparentTemperatureC(), c.dewPointC(), c.humidityPct(),
                c.windSpeedKmh(), c.windDirectionDeg(), null, c.windGustKmh(), c.pressureMslHpa(), sinceNine, toNine, max, null,
                c.visibilityM() == null ? null : c.visibilityM() / 1000.0, c.condition(), null, null);
    }

    /**
     * The rain of the hours beginning in a window, or null when the series does not cover the window.
     */
    static Double sum(Forecast f, Instant from, Instant to) {
        if (f.hourly().isEmpty() || !from.isBefore(to)) {
            return null;
        }
        Instant first = f.hourly().getFirst().at();
        if (first == null || first.isAfter(from)) {
            return null;
        }
        double total = 0;
        boolean any = false;
        for (Conditions h : f.hourly()) {
            if (h.at() == null || h.at().isBefore(from) || !h.at().isBefore(to)) {
                continue;
            }
            if (h.precipitationMm() != null) {
                total += h.precipitationMm();
                any = true;
            }
        }
        return any ? Math.round(total * 10) / 10.0 : null;
    }

}
