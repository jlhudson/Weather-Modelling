package au.gully.hexagons;

import au.gully.bureau.Observation;
import au.gully.science.Conditions;
import au.gully.upstreams.Forecast;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import static au.gully.science.Numbers.round1;

/**
 * How far a forecast has drifted from the station in its hexagon (docs/06 item 0, W-12): the
 * station's latest values - or the blend of the stations, where the hexagon holds several - against
 * the forecast read off its series at the same moment, on the four
 * things a station measures directly and a fire index turns on — temperature, humidity, wind speed
 * and rain — each as a difference and as a share of the difference that would be accepted.
 * <p>
 * The score is the <em>worst</em> of the four, not their average: a forecast with the humidity
 * twenty-five points wrong is no use to a fire index however good its temperature, and averaging
 * would hide it. At 1 the forecast has drifted and is thrown out. Direction is not compared — it
 * swings with every gust and a station's vane and a model cell disagree about it on the calmest day
 * — and nor is anything a station does not measure.
 *
 * @param at           the station observation the forecast was compared at
 * @param temperatureC station minus forecast, °C; null when either side lacks it
 * @param humidityPct  station minus forecast, points
 * @param windKmh      station minus forecast, km/h, ten-minute means both
 * @param rainMm       station's total since 9 am minus the forecast's for the same hours; null when
 *                     the series does not reach back to 9 am
 * @param score        the worst difference as a multiple of its tolerance: 0 agrees, 1 has drifted
 * @param worst        which of the four the score came from
 */
public record Drift(Instant at, String stationId, String upstream, Double temperatureC, Integer humidityPct,
                    Double windKmh, Double rainMm, double score, String worst, boolean drifted) {

    /**
     * The differences accepted before a forecast is thrown out: about twice the error a good model
     * makes on an ordinary day at a station it was not tuned to.
     */
    public static final double TEMPERATURE_C = 3.0;
    public static final double HUMIDITY_PCT = 20;
    public static final double WIND_KMH = 15.0;
    public static final double RAIN_MM = 5.0;

    /**
     * The Bureau's rain day starts at 9 am local; a station's total is since then.
     */
    static final LocalTime RAIN_DAY_STARTS = LocalTime.of(9, 0);

    /**
     * The station against the forecast at the station's time. Empty when the two share none of the
     * four, or the forecast holds nothing for that time.
     */
    public static Optional<Drift> of(Observation o, Forecast f, ZoneId zone) {
        if (o == null) {
            return Optional.empty();
        }
        return of(FirePictures.conditions(o), o.stationId(), f, zone);
    }

    /**
     * Observed conditions - one station's, or several blended - against the forecast at their time;
     * { precipitationMm} is the rain since 9 am, as a station reports it.
     */
    public static Optional<Drift> of(Conditions o, String stationId, Forecast f, ZoneId zone) {
        if (o == null || o.at() == null || f == null) {
            return Optional.empty();
        }
        Conditions m = f.at(o.at());
        if (m == null) {
            return Optional.empty();
        }
        Double dT = o.temperatureC() == null || m.temperatureC() == null ? null : o.temperatureC() - m.temperatureC();
        Integer dRh = o.humidityPct() == null || m.humidityPct() == null ? null : o.humidityPct() - m.humidityPct();
        Double dW = o.windSpeedKmh() == null || m.windSpeedKmh() == null ? null : o.windSpeedKmh() - m.windSpeedKmh();
        Double dRain = null;
        if (o.precipitationMm() != null) {
            ZonedDateTime local = o.at().atZone(zone);
            ZonedDateTime nine = local.with(RAIN_DAY_STARTS);
            if (nine.isAfter(local)) {
                nine = nine.minusDays(1);
            }
            Double forecastRain = f.precipitationBetween(nine.toInstant(), o.at());
            dRain = forecastRain == null ? null : o.precipitationMm() - forecastRain;
        }
        if (dT == null && dRh == null && dW == null && dRain == null) {
            return Optional.empty();
        }
        double score = 0;
        String worst = null;
        if (dT != null && Math.abs(dT) / TEMPERATURE_C >= score) {
            score = Math.abs(dT) / TEMPERATURE_C;
            worst = "temperature";
        }
        if (dRh != null && Math.abs(dRh) / HUMIDITY_PCT >= score) {
            score = Math.abs(dRh) / HUMIDITY_PCT;
            worst = "humidity";
        }
        if (dW != null && Math.abs(dW) / WIND_KMH >= score) {
            score = Math.abs(dW) / WIND_KMH;
            worst = "wind";
        }
        if (dRain != null && Math.abs(dRain) / RAIN_MM >= score) {
            score = Math.abs(dRain) / RAIN_MM;
            worst = "rain";
        }
        return Optional.of(new Drift(o.at(), stationId, f.upstream(), dT == null ? null : round1(dT), dRh,
                dW == null ? null : round1(dW), dRain == null ? null : round1(dRain), Math.round(score * 100) / 100.0, worst, score >= 1));
    }

    /**
     * One line for a log or a change note: {@code drift 1.4 (humidity +28)}.
     */
    public String describe() {
        String figure = switch (worst == null ? "" : worst) {
            case "temperature" -> signed(temperatureC) + " °C";
            case "humidity" -> signed(humidityPct == null ? null : (double) humidityPct) + " points";
            case "wind" -> signed(windKmh) + " km/h";
            case "rain" -> signed(rainMm) + " mm";
            default -> "";
        };
        return "drift " + score + (worst == null ? "" : " (" + worst + " " + figure + ")");
    }

    private static String signed(Double v) {
        if (v == null) {
            return "?";
        }
        String s = round1(v) + "";
        return v > 0 ? "+" + s : s;
    }
}
