package au.weather.core;

import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static au.weather.core.Numbers.round1;

/**
 * The one place these records become JSON-shaped maps.
 * <p>
 * Written once because the same shape has to reach two very different consumers - the component stored
 * on an incident and the {@code /api/weather} response - and two hand-written serialisers would drift
 * within a month, leaving the API and the incident disagreeing about the same reading. Field names are
 * the API contract, so they live here rather than being derived from the record.
 */
@UtilityClass
public class WeatherJson {

    /**
     * The window the forecast is read across: the wind change is searched within this many hours of
     * the series' first timestep, and the incident's hourly grassland index is cut at it. A constant
     * rather than configuration because a consumer's "next two days" has to mean the same thing on
     * every installation (docs/24 24.3.4, "the bounds are constants").
     */
    public static final int FORECAST_HOURS = 48;

    public static Map<String, Object> conditions(Conditions c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", c.at() == null ? null : c.at().toString());
        m.put("temperatureC", c.temperatureC());
        m.put("apparentTemperatureC", c.apparentTemperatureC());
        m.put("dewPointC", c.dewPointC());
        m.put("humidityPct", c.humidityPct());
        m.put("windSpeedKmh", c.windSpeedKmh());
        m.put("windDirectionDeg", c.windDirectionDeg());
        m.put("windGustKmh", c.windGustKmh());
        m.put("precipitationMm", c.precipitationMm());
        m.put("precipitationProbabilityPct", c.precipitationProbabilityPct());
        m.put("pressureMslHpa", c.pressureMslHpa());
        m.put("cloudCoverPct", c.cloudCoverPct());
        m.put("visibilityM", c.visibilityM());
        m.put("uvIndex", c.uvIndex());
        m.put("daytime", c.daytime());
        m.put("condition", c.condition());
        m.put("vapourPressureDeficitKpa", c.vapourPressureDeficitKpa());
        m.put("evapotranspirationMm", c.evapotranspirationMm());
        m.put("soilMoistureSurface", c.soilMoistureSurface());
        m.put("soilMoistureShallow", c.soilMoistureShallow());
        m.put("soilMoistureRootZone", c.soilMoistureRootZone());
        m.put("soilTemperatureC", c.soilTemperatureC());
        m.put("boundaryLayerHeightM", c.boundaryLayerHeightM());
        m.put("capeJkg", c.capeJkg());
        m.put("liftedIndex", c.liftedIndex());
        m.put("convectiveInhibitionJkg", c.convectiveInhibitionJkg());
        m.put("windSpeed80mKmh", c.windSpeed80mKmh());
        m.put("windDirection80mDeg", c.windDirection80mDeg());
        m.put("shortwaveRadiationWm2", c.shortwaveRadiationWm2());
        m.put("showersMm", c.showersMm());
        m.put("snowfallCm", c.snowfallCm());
        return m;
    }

    public static Map<String, Object> day(DayOutlook d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", d.date() == null ? null : d.date().toString());
        m.put("maxTemperatureC", d.maxTemperatureC());
        m.put("minTemperatureC", d.minTemperatureC());
        m.put("maxApparentTemperatureC", d.maxApparentTemperatureC());
        m.put("minHumidityPct", d.minHumidityPct());
        m.put("maxWindKmh", d.maxWindKmh());
        m.put("maxGustKmh", d.maxGustKmh());
        m.put("windDirectionDeg", d.dominantWindDirectionDeg());
        m.put("precipitationMm", d.precipitationMm());
        m.put("precipitationProbabilityPct", d.precipitationProbabilityPct());
        m.put("uvIndexMax", d.uvIndexMax());
        m.put("sunrise", d.sunrise() == null ? null : d.sunrise().toString());
        m.put("sunset", d.sunset() == null ? null : d.sunset().toString());
        m.put("condition", d.condition());
        return m;
    }

    /**
     * Always carries {@code estimated} and {@code basis}: an index whose assumptions are invisible is a trap.
     */
    public static Map<String, Object> fire(FireWeather f) {
        if (f == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ffdi", f.ffdi());
        m.put("ffdiRating", f.ffdiRating());
        m.put("peakFfdi", f.peakFfdi());
        m.put("droughtFactor", f.droughtFactor());
        m.put("kbdiMm", f.kbdiMm());
        m.put("kbdiBand", f.kbdiBand());
        m.put("meanAnnualRainfallMm", f.meanAnnualRainfallMm());
        m.put("vapourPressureDeficitKpa", f.vapourPressureDeficitKpa());
        m.put("soilMoistureSurface", f.soilMoistureSurface());
        m.put("soilMoistureRootZone", f.soilMoistureRootZone());
        m.put("boundaryLayerHeightM", f.boundaryLayerHeightM());
        m.put("windSpeed80mKmh", f.windSpeed80mKmh());
        m.put("windDirection80mDeg", f.windDirection80mDeg());
        m.put("capeJkg", f.capeJkg());
        m.put("liftedIndex", f.liftedIndex());
        m.put("estimated", f.estimated());
        m.put("basis", f.basis());
        return m;
    }

    /**
     * One forecast day's fire index. Lives here rather than inline in {@link #fire} because the same day
     * is now reached from two places - {@link #forecast}, where it hangs off the day it belongs to, and
     * the metrics component, which is fire and nothing else - and two loops writing the same nine fields
     * is how the two drift.
     */
    public static Map<String, Object> fireDay(FireOutlook o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("date", o.date() == null ? null : o.date().toString());
        d.put("ffdi", o.ffdi());
        d.put("ffdiRating", o.ffdiRating());
        d.put("kbdiMm", o.kbdiMm());
        d.put("droughtFactor", o.droughtFactor());
        d.put("maxTemperatureC", o.maxTemperatureC());
        d.put("minHumidityPct", o.minHumidityPct());
        d.put("maxWindKmh", o.maxWindKmh());
        d.put("maxGustKmh", o.maxGustKmh());
        d.put("rainMm", o.rainMm());
        return d;
    }

    public static List<Map<String, Object>> fireDays(FireWeather f) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (f != null) {
            for (FireOutlook o : f.outlook()) {
                out.add(fireDay(o));
            }
        }
        return out;
    }

    /**
     * The forest index for one forecast hour (docs/24 G6): FFDI from the hour's own temperature,
     * humidity and wind, with the drought factor of the day the hour falls on - the same figure
     * {@code days[].fire.droughtFactor} carries, projected through the forecast's rain - or the current
     * factor when the outlook does not reach that day. The factor used travels with the index, because
     * the calculators downstream must be able to reproduce the number from what is beside it
     * (docs/24 24.7); {@code estimated} travels with it as it does on every index. Null when there is
     * no fire block at all: without a drought factor there is no honest index.
     *
     * @param zone the zone the point keeps its days in, which decides which day's factor an hour gets
     */
    public static Map<String, Object> hourFire(Conditions c, FireWeather fire, ZoneId zone) {
        if (c == null || fire == null) {
            return null;
        }
        return hourFire(c, fire, droughtFactorsByDate(fire), zone);
    }

    private static Map<String, Object> hourFire(Conditions c, FireWeather fire, Map<LocalDate, Double> byDate, ZoneId zone) {
        Double dayFactor = c.at() == null || zone == null ? null : byDate.get(c.at().atZone(zone).toLocalDate());
        double factor = dayFactor == null ? fire.droughtFactor() : dayFactor;
        Double ffdi = FireDanger.of(c, factor);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ffdi", ffdi);
        m.put("ffdiRating", ffdi == null ? null : FireDanger.rating(ffdi));
        m.put("droughtFactor", factor);
        m.put("estimated", fire.estimated());
        return m;
    }

    /**
     * Each outlook day's projected drought factor by date, for the hours that fall on it.
     */
    private static Map<LocalDate, Double> droughtFactorsByDate(FireWeather fire) {
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        if (fire != null) {
            for (FireOutlook o : fire.outlook()) {
                if (o.date() != null && o.droughtFactor() != null) {
                    out.put(o.date(), o.droughtFactor());
                }
            }
        }
        return out;
    }

    /**
     * The wind change as the API carries it, or null when the series holds none - the key is always
     * present, so "no change in the window" and "not computed" cannot be confused.
     */
    public static Map<String, Object> windChange(WindChange w) {
        if (w == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", w.at() == null ? null : w.at().toString());
        m.put("fromDeg", w.fromDeg());
        m.put("toDeg", w.toDeg());
        m.put("speedKmh", w.speedKmh());
        m.put("gustKmh", w.gustKmh());
        return m;
    }

    public static Map<String, Object> flood(FloodWeather f) {
        if (f == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rain1dMm", f.rain1dMm());
        m.put("rain2dMm", f.rain2dMm());
        m.put("rain3dMm", f.rain3dMm());
        m.put("rain7dMm", f.rain7dMm());
        m.put("forecastRain6hMm", f.forecastRain6hMm());
        m.put("forecastRain12hMm", f.forecastRain12hMm());
        m.put("forecastRain24hMm", f.forecastRain24hMm());
        m.put("forecastRain48hMm", f.forecastRain48hMm());
        m.put("forecastRain72hMm", f.forecastRain72hMm());
        m.put("threeDayTotalMm", f.threeDayTotalMm());
        m.put("maxRainProbabilityPct", f.maxRainProbabilityPct());
        m.put("soilMoistureSurface", f.soilMoistureSurface());
        m.put("soilMoistureRootZone", f.soilMoistureRootZone());
        m.put("riverDischargeCumecs", f.riverDischargeCumecs());
        m.put("riverDischargeMeanCumecs", f.riverDischargeMeanCumecs());
        m.put("dischargeRatioToMean", f.dischargeRatioToMean());
        m.put("riverTrend", f.riverTrend());
        m.put("basis", f.basis());
        return m;
    }

    /**
     * One forecast day's flood figures, for the same reason {@link #fireDay} exists.
     */
    public static Map<String, Object> floodDay(FloodOutlook o) {
        if (o == null) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("date", o.date() == null ? null : o.date().toString());
        d.put("rainMm", o.rainMm());
        d.put("rainProbabilityPct", o.rainProbabilityPct());
        d.put("riverDischargeCumecs", o.riverDischargeCumecs());
        d.put("dischargeRatioToMean", o.dischargeRatioToMean());
        return d;
    }

    /**
     * The deficit and how it was arrived at. Separate because it is cached on its own radius and clock.
     */
    public static Map<String, Object> drought(DroughtIndex d) {
        if (d == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kbdiMm", round1(d.kbdiMm()));
        m.put("kbdiBand", d.kbdiBand());
        m.put("droughtFactor", round1(d.droughtFactor()));
        m.put("meanAnnualRainfallMm", round1(d.meanAnnualRainfallMm()));
        m.put("spunUpFrom", d.spunUpFrom() == null ? null : d.spunUpFrom().toString());
        m.put("computedFor", d.computedFor() == null ? null : d.computedFor().toString());
        m.put("spinUpDays", d.spinUpDays());
        m.put("complete", d.complete());
        m.put("basis", d.basis());
        return m;
    }

    public static List<Map<String, Object>> hourly(List<Conditions> series) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Conditions c : series) {
            out.add(conditions(c));
        }
        return out;
    }

    /**
     * The hourly series as a map popup or a console table wants it: the eight fields anyone actually
     * reads across a row, not the thirty a fire index is built from.
     * <p>
     * Field names match {@link #conditions} exactly, so this is a narrower view of the same contract
     * and never a second, drifting one. A map drawing every anchor at once is the reason it exists:
     * seventy-two full timesteps per anchor is a payload measured in megabytes for a popup nobody has
     * opened yet.
     */
    public static Map<String, Object> brief(Conditions c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", c.at() == null ? null : c.at().toString());
        m.put("temperatureC", c.temperatureC());
        m.put("humidityPct", c.humidityPct());
        m.put("windSpeedKmh", c.windSpeedKmh());
        m.put("windDirectionDeg", c.windDirectionDeg());
        m.put("windGustKmh", c.windGustKmh());
        m.put("precipitationMm", c.precipitationMm());
        m.put("precipitationProbabilityPct", c.precipitationProbabilityPct());
        m.put("condition", c.condition());
        return m;
    }

    public static List<Map<String, Object>> hourlyBrief(List<Conditions> series) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Conditions c : series) {
            out.add(brief(c));
        }
        return out;
    }

    public static List<Map<String, Object>> daily(List<DayOutlook> days) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DayOutlook d : days) {
            out.add(day(d));
        }
        return out;
    }

    /**
     * Everything anyone knows about the days ahead at one point, in one structure: {@code days}, each
     * carrying its own weather, fire and flood, and {@code hours} beneath them.
     * <p>
     * <strong>Four parallel arrays were the problem.</strong> The daily weather, the fire outlook and the
     * flood outlook were three series in three different places - two of them nested inside blocks whose
     * other fields describe <em>now</em> - and the hourly series was a fourth. Every consumer that wanted
     * "what does Thursday look like" had to join three lists on a date string and hope they were aligned,
     * and the console, the map popup and the incident page each did that join differently. They are joined
     * once, here.
     * <p>
     * Joined on the date rather than by position, and the day list is the union of the three: a provider
     * that returns seven days of weather and five of river discharge produces seven days, two of them
     * without a {@code flood} block, which is the truth. Zipping by index would have silently attributed
     * Wednesday's discharge to Thursday.
     *
     * <p>
     * Since G6 each hour carries its own {@code fire} block ({@link #hourFire}) and the structure
     * carries {@code windChange} ({@link WindChange#find}, within {@link #FORECAST_HOURS}) - both
     * computed here so that {@code /api/weather} and the incident's forecast component cannot disagree
     * about them, which is the reason this class exists.
     *
     * @param days  the weather days, which set the order
     * @param fire  the fire block whose outlook is folded onto its days and whose drought factors the
     *              hours are indexed with, or null
     * @param flood the flood block whose outlook is folded onto its days, or null
     * @param hours the hourly series to carry, already trimmed by the caller to what it wants to pay for
     * @param brief the eight fields anyone reads across a row rather than the thirty an index is built
     *              from. What a popup wants; a consumer asking for the whole series does not
     * @param zone  the zone the point keeps its days in, so an hour finds the day - and the drought
     *              factor - it belongs to
     */
    public static Map<String, Object> forecast(List<DayOutlook> days, FireWeather fire, FloodWeather flood,
                                               List<Conditions> hours, boolean brief, ZoneId zone) {
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (DayOutlook d : days == null ? List.<DayOutlook>of() : days) {
            Map<String, Object> m = day(d);
            if (m != null) {
                byDate.put(String.valueOf(m.get("date")), m);
            }
        }
        fold(byDate, "fire", fireDays(fire));
        List<Map<String, Object>> floodDays = new ArrayList<>();
        if (flood != null) {
            for (FloodOutlook o : flood.outlook()) {
                floodDays.add(floodDay(o));
            }
        }
        fold(byDate, "flood", floodDays);

        List<Conditions> series = hours == null ? List.of() : hours;
        Map<LocalDate, Double> factors = droughtFactorsByDate(fire);
        List<Map<String, Object>> hourRows = new ArrayList<>();
        for (Conditions c : series) {
            Map<String, Object> row = brief ? brief(c) : conditions(c);
            if (row != null && fire != null) {
                row.put("fire", hourFire(c, fire, factors, zone));
            }
            hourRows.add(row);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", new ArrayList<>(byDate.values()));
        m.put("hours", hourRows);
        m.put("windChange", windChange(WindChange.find(series, FORECAST_HOURS)));
        return m;
    }

    /**
     * Hangs each day of a series onto the day it shares a date with, creating the day if it is new.
     */
    private static void fold(Map<String, Map<String, Object>> byDate, String key, List<Map<String, Object>> series) {
        for (Map<String, Object> d : series) {
            if (d == null) {
                continue;
            }
            String date = String.valueOf(d.get("date"));
            byDate.computeIfAbsent(date, k -> {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("date", k);
                return fresh;
            }).put(key, d);
        }
    }

    /**
     * Where the answer came from and how far it was stretched to get here. Attached to everything, on
     * purpose: an estimate from 22 km away and 40 minutes ago is a different fact from one taken here
     * and now, and nothing downstream can tell them apart unless this travels with it.
     */
    public static Map<String, Object> provenance(WeatherAnswer a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", a.provider());
        m.put("model", a.report().model());
        m.put("attribution", a.report().attribution());
        m.put("observedAt", a.report().current() == null || a.report().current().at() == null
                ? null : a.report().current().at().toString());
        m.put("fetchedAt", a.report().fetchedAt() == null ? null : a.report().fetchedAt().toString());
        m.put("ageMinutes", a.age().toMinutes());
        m.put("cached", a.cached());
        m.put("offsetMetres", a.offsetMetres());
        // Both, never one: the ground distance is what a reader pictures, the reach is what decided it, and
        // a null delta is the honest signal that no terrain height was available on one side or the other.
        m.put("reachMetres", a.reachMetres());
        m.put("elevationDeltaMetres", a.elevationDeltaMetres());
        m.put("anchor", a.anchorId());
        m.put("anchorLat", a.report().lat());
        m.put("anchorLon", a.report().lon());
        m.put("elevationM", a.report().elevationM());
        m.put("zone", a.report().zoneId());
        m.put("decision", a.decision());
        return m;
    }

}
