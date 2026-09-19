package au.gully.hexagons;

import au.gully.bureau.Observation;
import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.science.Conditions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static au.gully.science.Numbers.round1;

/**
 * "Now" from the ground for a hexagon (W-13). A hexagon with several stations inside it blends them
 * ({@link #inCell}): each weighted by its distance from the centre, brought to the hexagon's mean
 * elevation, so two stations 300 m apart in height do not average to a temperature neither has. A
 * hexagon with no station of its own takes the stations around it ({@link #at}): the nearest ring
 * of six first, and when fewer than { #SHARE} of them have a station reporting, the next ring
 * too — eighteen hexagons, the same share. Fewer than that and there is no "now" from the ground;
 * the model's series stands in.
 * <p>
 * <strong>Inverse-distance weighting with a lapse rate</strong>, not a Kalman filter: a Kalman filter
 * estimates a state through <em>time</em> from noisy readings of it, which is not the question here
 * — the question is the value <em>here</em> from values <em>there</em>, at one moment, which is a
 * spatial one. The standard answer to it for surface weather is to weight each station by the
 * inverse square of its distance and, before weighting, move its temperature and dew point to the
 * hexagon's own elevation by the atmosphere's lapse rates: the environmental lapse of
 * {@link #LAPSE_TEMPERATURE_C_PER_KM} for temperature and about {@link #LAPSE_DEW_POINT_C_PER_KM}
 * for dew point, so humidity is recomputed from the two rather than averaged — a ridge 600 m above
 * the plains is 4 °C cooler and noticeably wetter than the plains' stations say, and averaging
 * their humidity would miss it. Wind, pressure and rain are weighted as they are: pressure the
 * Bureau has already reduced to sea level, and wind's dependence on height is exposure, not lapse.
 * <p>
 * The reading says it was done: { currentFrom} is { stations} or { neighbours}, and
 * the { nearby} block names the stations, their distances, their weights, the ring they were
 * found in (0 is inside the hexagon) and the elevation the values were brought to.
 */
public final class Interpolation {

    /**
     * How many rings out the search goes: the six neighbours, then the twelve beyond them.
     */
    public static final int RINGS = 2;

    /**
     * The share of a neighbourhood's hexagons that must have a station reporting: 2 of 6, 6 of 18.
     */
    public static final double SHARE = 0.30;

    /**
     * The power in the inverse-distance weight: 2, the usual.
     */
    public static final double POWER = 2;

    /**
     * Degrees per kilometre climbed, for temperature (the environmental lapse rate) and dew point.
     */
    public static final double LAPSE_TEMPERATURE_C_PER_KM = -6.5;
    public static final double LAPSE_DEW_POINT_C_PER_KM = -2.0;

    private Interpolation() {
    }

    /**
     * One station's part in an answer.
     */
    public record Used(String id, String name, double distanceKm, Double heightM, double weight) {
    }

    /**
     * @param ring             how far out the search went: 0 inside the hexagon itself, else 1 or 2
     * @param elevationM       the hexagon's elevation the values were brought to; null when it had none
     * @param elevationApplied whether the lapse rates were applied (the hexagon and the stations all had a height)
     */
    public record Result(Conditions conditions, int ring, List<Used> stations, Double elevationM, boolean elevationApplied) {
    }

    /**
     * The hexagon's own stations blended, for a hexagon with more than one inside it, or empty when
     * fewer than two are reporting: a single station is the hexagon's "now" as it is, unblended and
     * unmoved, since an observation is a fact and there is nothing to reconcile it with.
     */
    public static Optional<Result> inCell(Grid grid, StationRegistry stations, Cell cell, Double elevationM, Instant at) {
        List<Candidate> inside = candidates(grid, stations, List.of(cell), cell, at);
        if (inside.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(blend(inside, 0, elevationM));
    }

    /**
     * The neighbourhood's answer for a hexagon at a moment, or empty when too few stations are reporting.
     */
    public static Optional<Result> at(Grid grid, StationRegistry stations, Cell cell, Double elevationM, Instant at) {
        List<Candidate> ring1 = candidates(grid, stations, grid.ring(cell), cell, at);
        int ring = 1;
        List<Candidate> used = ring1;
        int needed = (int) Math.ceil(6 * SHARE);
        if (ring1.size() < needed && RINGS >= 2) {
            List<Candidate> ring2 = candidates(grid, stations, grid.ring(cell, 2), cell, at);
            List<Candidate> both = new ArrayList<>(ring1);
            // A station within reach of a hexagon in each ring counts once.
            for (Candidate c : ring2) {
                if (both.stream().noneMatch(b -> b.station().id().equals(c.station().id()))) {
                    both.add(c);
                }
            }
            needed = (int) Math.ceil(18 * SHARE);
            used = both;
            ring = 2;
        }
        if (used.size() < needed) {
            return Optional.empty();
        }
        return Optional.of(blend(used, ring, elevationM));
    }

    private record Candidate(Station station, Observation observation, double distanceKm, double weight) {
    }

    private static List<Candidate> candidates(Grid grid, StationRegistry stations, List<Cell> cells, Cell centre, Instant at) {
        List<Candidate> out = new ArrayList<>();
        for (Station s : stations.inCells(grid, cells)) {
            Optional<Observation> o = stations.latest(s.id());
            if (o.isEmpty() || o.get().at() == null || Duration.between(o.get().at(), at).compareTo(FirePictures.STATION_STALE) >= 0) {
                continue;
            }
            if (o.get().temperatureC() == null && o.get().humidityPct() == null && o.get().windSpeedKmh() == null) {
                continue;
            }
            double km = Math.max(0.5, Grid.planarMetres(centre.lat(), centre.lon(), s.lat(), s.lon()) / 1000);
            out.add(new Candidate(s, o.get(), km, 1 / Math.pow(km, POWER)));
        }
        return out;
    }

    private static Result blend(List<Candidate> used, int ring, Double elevationM) {
        boolean applyLapse = elevationM != null && used.stream().allMatch(c -> c.station().heightM() != null);
        // Each station's temperature and dew point at the hexagon's height, then weighted.
        double wT = 0, sumT = 0, wTd = 0, sumTd = 0, wW = 0, sumW = 0, wG = 0, sumG = 0, wP = 0, sumP = 0, wR = 0, sumR = 0, wV = 0, sumV = 0;
        double u = 0, v = 0, wDir = 0;
        Instant latest = null;
        List<Used> stations = new ArrayList<>();
        double totalWeight = used.stream().mapToDouble(Candidate::weight).sum();
        for (Candidate c : used) {
            Observation o = c.observation();
            double dz = applyLapse ? (elevationM - c.station().heightM()) / 1000.0 : 0;
            if (o.temperatureC() != null) {
                sumT += c.weight() * (o.temperatureC() + LAPSE_TEMPERATURE_C_PER_KM * dz);
                wT += c.weight();
            }
            Double td = o.dewPointC() != null ? o.dewPointC()
                    : o.temperatureC() != null && o.humidityPct() != null ? dewPoint(o.temperatureC(), o.humidityPct()) : null;
            if (td != null) {
                sumTd += c.weight() * (td + LAPSE_DEW_POINT_C_PER_KM * dz);
                wTd += c.weight();
            }
            if (o.windSpeedKmh() != null) {
                sumW += c.weight() * o.windSpeedKmh();
                wW += c.weight();
                if (o.windDirectionDeg() != null) {
                    double rad = Math.toRadians(o.windDirectionDeg());
                    u += c.weight() * Math.sin(rad);
                    v += c.weight() * Math.cos(rad);
                    wDir += c.weight();
                }
            }
            if (o.windGustKmh() != null) {
                sumG += c.weight() * o.windGustKmh();
                wG += c.weight();
            }
            if (o.pressureMslHpa() != null) {
                sumP += c.weight() * o.pressureMslHpa();
                wP += c.weight();
            }
            if (o.rainSince9amMm() != null) {
                sumR += c.weight() * o.rainSince9amMm();
                wR += c.weight();
            }
            if (o.visibilityKm() != null) {
                sumV += c.weight() * o.visibilityKm();
                wV += c.weight();
            }
            if (latest == null || o.at().isAfter(latest)) {
                latest = o.at();
            }
            stations.add(new Used(c.station().id(), c.station().name(), round1(c.distanceKm()), c.station().heightM(),
                    Math.round(c.weight() / totalWeight * 1000) / 1000.0));
        }
        Double t = wT == 0 ? null : sumT / wT;
        Double td = wTd == 0 ? null : Math.min(sumTd / wTd, t == null ? Double.MAX_VALUE : t);
        Integer rh = t == null || td == null ? null : (int) Math.round(Math.max(0, Math.min(100, humidity(t, td))));
        Double wind = wW == 0 ? null : sumW / wW;
        Integer dir = wDir == 0 ? null : (int) Math.round((Math.toDegrees(Math.atan2(u, v)) + 360) % 360);
        Double apparent = t == null || rh == null || wind == null ? null : apparent(t, rh, wind);
        Conditions conditions = Conditions.at(latest)
                .temperature(t == null ? null : round1(t)).dewPoint(td == null ? null : round1(td)).humidity(rh)
                .apparent(apparent == null ? null : round1(apparent))
                .wind(wind == null ? null : round1(wind)).windDirection(dir).gust(wG == 0 ? null : round1(sumG / wG))
                .pressure(wP == 0 ? null : round1(sumP / wP)).precipitation(wR == 0 ? null : round1(sumR / wR))
                .visibility(wV == 0 ? null : round1(sumV / wV) * 1000)
                .build();
        return new Result(conditions, ring, stations, elevationM, applyLapse);
    }

    /**
     * Magnus: dew point from temperature and relative humidity.
     */
    static double dewPoint(double t, double rh) {
        double a = 17.625, b = 243.04;
        double gamma = Math.log(Math.max(1, rh) / 100.0) + a * t / (b + t);
        return b * gamma / (a - gamma);
    }

    /**
     * Magnus, back again: relative humidity from temperature and dew point.
     */
    static double humidity(double t, double td) {
        double a = 17.625, b = 243.04;
        return 100 * Math.exp(a * td / (b + td)) / Math.exp(a * t / (b + t));
    }

    /**
     * Steadman's apparent temperature as the Bureau publishes it: {@code AT = T + 0.33e − 0.70ws − 4.0},
     * {@code e} the water vapour pressure in hPa, {@code ws} the wind in m/s.
     */
    static double apparent(double t, double rh, double windKmh) {
        double e = rh / 100.0 * 6.105 * Math.exp(17.27 * t / (237.7 + t));
        return t + 0.33 * e - 0.70 * (windKmh / 3.6) - 4.0;
    }
}
