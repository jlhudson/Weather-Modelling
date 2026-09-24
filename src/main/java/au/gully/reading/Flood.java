package au.gully.reading;

import au.gully.bureau.Station;
import au.gully.record.Record;
import au.gully.upstreams.Conditions;
import au.gully.upstreams.DayOutlook;
import au.gully.upstreams.Forecast;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * Flood weather at a place (W-26): the rain already down, the rain still coming, and the river. Antecedent rain is
 * the half people forget - fifty millimetres onto dry ground is a wet afternoon, onto ground that took eighty in the
 * three days before it is a callout - so the station's own record is read back: today so far and the Bureau days
 * before it. What is coming is the forecast's, hour by hour for the next day and three. The river is GloFAS's.
 */
public final class Flood {

    private Flood() {
    }

    /**
     * The flood block.
     *
     * @param record    the station whose record the antecedent rain is read from, or null
     * @param days      its days, oldest first
     * @param rainSoFar today's rain since 9 am
     * @param today     the Bureau day now running
     * @param f         the forecast, or null
     * @param river     the river block, or null where the upstreams could not say
     */
    public static Map<String, Object> block(Station record, NavigableMap<LocalDate, Record.Day> days, Double rainSoFar, LocalDate today,
                                            Forecast f, Instant now, Map<String, Object> river) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> fallen = new LinkedHashMap<>();
        fallen.put("station", record == null ? null : record.id());
        fallen.put("todayMm", rainSoFar);
        fallen.put("last3DaysMm", before(days, today, 2, rainSoFar));
        fallen.put("last7DaysMm", before(days, today, 6, rainSoFar));
        fallen.put("last30DaysMm", before(days, today, 29, rainSoFar));
        m.put("fallen", fallen);
        Map<String, Object> coming = new LinkedHashMap<>();
        coming.put("next24hMm", ahead(f, now, Duration.ofHours(24)));
        coming.put("next72hMm", ahead(f, now, Duration.ofHours(72)));
        Integer chance = null;
        if (f != null) {
            for (Conditions c : f.hourly()) {
                if (c.at() != null && c.at().isAfter(now) && c.at().isBefore(now.plus(Duration.ofHours(72))) && c.precipitationProbabilityPct() != null) {
                    chance = chance == null ? c.precipitationProbabilityPct() : Math.max(chance, c.precipitationProbabilityPct());
                }
            }
        }
        coming.put("maxChancePct", chance);
        m.put("coming", coming);
        Double past3 = (Double) fallen.get("last3DaysMm"), next72 = (Double) coming.get("next72hMm");
        m.put("threeDaysEachSideMm", past3 == null && next72 == null ? null : round1((past3 == null ? 0 : past3) + (next72 == null ? 0 : next72)));
        List<Map<String, Object>> outlook = new ArrayList<>();
        if (f != null) {
            for (DayOutlook d : f.daily()) {
                if (d.date() == null || d.date().isBefore(today) || outlook.size() >= Forecasts.DAYS) {
                    continue;
                }
                Map<String, Object> day = new LinkedHashMap<>();
                day.put("date", d.date().toString());
                day.put("rainMm", d.precipitationMm());
                day.put("chancePct", d.precipitationProbabilityPct());
                day.put("riverCumecs", riverOn(river, d.date()));
                outlook.add(day);
            }
        }
        m.put("outlook", outlook);
        m.put("river", river);
        return m;
    }

    /**
     * Today's rain so far and that of the Bureau days before it; null when the record holds none of those days.
     */
    static Double before(NavigableMap<LocalDate, Record.Day> days, LocalDate today, int daysBack, Double rainSoFar) {
        double sum = rainSoFar == null ? 0 : rainSoFar;
        boolean any = rainSoFar != null;
        for (LocalDate d = today.minusDays(daysBack); d.isBefore(today); d = d.plusDays(1)) {
            Record.Day day = days == null ? null : days.get(d);
            if (day != null && day.rainMm() != null) {
                sum += day.rainMm();
                any = true;
            }
        }
        return any ? round1(sum) : null;
    }

    static Double ahead(Forecast f, Instant now, Duration span) {
        if (f == null) {
            return null;
        }
        double sum = 0;
        boolean any = false;
        for (Conditions c : f.hourly()) {
            if (c.at() != null && c.at().isAfter(now) && !c.at().isAfter(now.plus(span)) && c.precipitationMm() != null) {
                sum += c.precipitationMm();
                any = true;
            }
        }
        return any ? round1(sum) : null;
    }

    @SuppressWarnings("unchecked")
    private static Object riverOn(Map<String, Object> river, LocalDate date) {
        if (river == null || !(river.get("days") instanceof List<?> days)) {
            return null;
        }
        for (Object o : days) {
            Map<String, Object> d = (Map<String, Object>) o;
            if (date.toString().equals(d.get("date"))) {
                return d.get("cumecs");
            }
        }
        return null;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
