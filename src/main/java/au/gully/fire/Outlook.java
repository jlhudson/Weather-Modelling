package au.gully.fire;

import au.gully.upstreams.Conditions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fire outlook (W-22): the forest fire danger index for every hour of a forecast, and each day's worst
 * hour. Pure arithmetic, no state.
 * <p>
 * <strong>Each hour on its own values.</strong> The index of an hour is that hour's temperature, humidity
 * and wind, never the day's maximum, minimum and maximum put together - those are rarely at the same hour,
 * and the combination overstates the day. A day's figure is its worst hour, with the values of that hour.
 * <p>
 * <strong>The drought carried forward.</strong> The deficit and the factor are the Bureau's, and turn at
 * 9 am: the hours of the day now running take the station's drought as it stands (its deficit to
 * yesterday, today's rain so far in the factor's window); each later day takes the deficit stepped through
 * the day before with the forecast's rain and heat, and the factor recomputed against it and the rain
 * window moved on. So a wet day ahead lowers the index of the day after, as it will. Canopy interception
 * starts full at the start of the projection - the record keeps how much fell, not how much of a running
 * event the canopy has caught - an error of a few millimetres on the first day, only while it rains.
 */
public final class Outlook {

    private Outlook() {
    }

    /**
     * One hour: its index, and the drought it was drawn with.
     */
    public record Hour(Instant at, Double ffdi, String rating, double droughtFactor, double kbdiMm) {
    }

    /**
     * One local day: its worst hour, with that hour's values, and the drought of the day.
     */
    public record Day(LocalDate date, Double ffdiMax, String rating, Instant peakAt, Double temperatureC, Integer humidityPct,
                      Double windSpeedKmh, double droughtFactor, double kbdiMm) {
    }

    /**
     * The index of every forecast hour from the one now running.
     *
     * @param kbdiMm           the station's deficit at the end of yesterday
     * @param meanAnnualRainMm the station's mean annual rain, which sets how fast its soil dries
     * @param recentRainMm     the twenty days of rain behind the factor, oldest first, today so far last
     * @param dayTurnsAt       the hour the Bureau's day turns, 9 am
     */
    public static List<Hour> hours(List<Conditions> hourly, double kbdiMm, double meanAnnualRainMm, double[] recentRainMm,
                                   Instant now, ZoneId zone, LocalTime dayTurnsAt) {
        LocalDate today = bureauDay(now, zone, dayTurnsAt);
        // The drought each Bureau day starts with: today's as it stands, each later one stepped from the one before.
        Map<LocalDate, double[]> start = new LinkedHashMap<>();
        double todaySoFar = recentRainMm.length == 0 ? 0 : recentRainMm[recentRainMm.length - 1];
        start.put(today, new double[]{kbdiMm, Kbdi.droughtFactor(kbdiMm, recentRainMm)});
        List<Double> window = new ArrayList<>();
        for (double r : recentRainMm) {
            window.add(r);
        }
        double kbdi = kbdiMm;
        double interceptionLeft = Kbdi.INTERCEPTION_MM;
        LocalDate last = hourly.isEmpty() || hourly.getLast().at() == null ? today : bureauDay(hourly.getLast().at(), zone, dayTurnsAt);
        for (LocalDate d = today; d.isBefore(last); d = d.plusDays(1)) {
            Instant from = d.atTime(dayTurnsAt).atZone(zone).toInstant(), to = d.plusDays(1).atTime(dayTurnsAt).atZone(zone).toInstant();
            // The day's rain: what has fallen of today and what the forecast brings after now; later days, the forecast's.
            double rain = d.equals(today) ? todaySoFar : 0;
            Double max = null;
            for (Conditions c : hourly) {
                if (c.at() == null || c.at().isBefore(from) || !c.at().isBefore(to)) {
                    continue;
                }
                if (c.precipitationMm() != null && (!d.equals(today) || c.at().isAfter(now))) {
                    rain += c.precipitationMm();
                }
                if (c.temperatureC() != null) {
                    max = max == null ? c.temperatureC() : Math.max(max, c.temperatureC());
                }
            }
            double intercepted;
            if (rain > 0) {
                intercepted = Math.min(rain, interceptionLeft);
                interceptionLeft -= intercepted;
            } else {
                intercepted = 0;
                interceptionLeft = Kbdi.INTERCEPTION_MM;
            }
            kbdi = Kbdi.step(kbdi, max == null ? 20 : max, rain - intercepted, meanAnnualRainMm);
            if (d.equals(today)) {
                window.set(window.size() - 1, rain);
            } else {
                window.add(rain);
            }
            while (window.size() > Kbdi.WINDOW_DAYS) {
                window.removeFirst();
            }
            start.put(d.plusDays(1), new double[]{kbdi, Kbdi.droughtFactor(kbdi, window.stream().mapToDouble(Double::doubleValue).toArray())});
        }
        List<Hour> out = new ArrayList<>();
        for (Conditions c : hourly) {
            // From the hour now running: a series stamped on the hour is kept while the hour lasts.
            if (c.at() == null || !c.at().plusSeconds(3600).isAfter(now)) {
                continue;
            }
            double[] dr = start.get(bureauDay(c.at(), zone, dayTurnsAt));
            if (dr == null) {
                continue;
            }
            Double ffdi = FireDanger.of(c.temperatureC(), c.humidityPct() == null ? null : c.humidityPct().doubleValue(), c.windSpeedKmh(), dr[1]);
            out.add(new Hour(c.at(), ffdi, ffdi == null ? null : FireDanger.rating(ffdi), round1(dr[1]), round1(dr[0])));
        }
        return out;
    }

    /**
     * Each local day's worst hour, from the hours given, in order.
     */
    public static List<Day> days(List<Hour> hours, List<Conditions> hourly, ZoneId zone) {
        Map<Instant, Conditions> byAt = new LinkedHashMap<>();
        for (Conditions c : hourly) {
            if (c.at() != null) {
                byAt.put(c.at(), c);
            }
        }
        Map<LocalDate, Hour> worst = new LinkedHashMap<>();
        for (Hour h : hours) {
            LocalDate date = h.at().atZone(zone).toLocalDate();
            Hour w = worst.get(date);
            if (w == null || (h.ffdi() != null && (w.ffdi() == null || h.ffdi() > w.ffdi()))) {
                worst.put(date, h);
            }
        }
        List<Day> out = new ArrayList<>();
        worst.forEach((date, h) -> {
            Conditions c = byAt.get(h.at());
            out.add(new Day(date, h.ffdi(), h.rating(), h.ffdi() == null ? null : h.at(), c == null ? null : c.temperatureC(),
                    c == null ? null : c.humidityPct(), c == null ? null : c.windSpeedKmh(), h.droughtFactor(), h.kbdiMm()));
        });
        return out;
    }

    static LocalDate bureauDay(Instant at, ZoneId zone, LocalTime dayTurnsAt) {
        var local = at.atZone(zone).toLocalDateTime();
        return local.toLocalTime().isBefore(dayTurnsAt) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
