package au.gully.record;

import au.gully.bureau.Observation;

import java.time.Instant;

/**
 * Six hours of a station's readings, consolidated as they arrive: how many, the temperature's
 * extremes and mean, the humidity's extremes, the wind's mean and maximum, the strongest gust, and
 * the Bureau's own running figures as they stood at the last reading. Nulls where no reading in the
 * window carried the value.
 *
 * @param end the window's end: the next 3 am, 9 am, 3 pm or 9 pm local after the first reading
 */
public final class Window {

    public final Instant end;
    public int readings;
    private double tSum;
    private int tN;
    public Double tMin, tMax;
    public Integer rhMin, rhMax;
    private double wSum;
    private int wN;
    public Double wMax, gMax;
    public Double rainSince9am, rain24h, publishedMax;
    public Instant lastAt;

    public Window(Instant end) {
        this.end = end;
    }

    public void add(Observation o) {
        readings++;
        lastAt = o.at();
        Double t = o.temperatureC();
        if (t != null) {
            tSum += t;
            tN++;
            tMin = tMin == null ? t : Double.valueOf(Math.min(tMin, t));
            tMax = tMax == null ? t : Double.valueOf(Math.max(tMax, t));
        }
        Integer rh = o.humidityPct();
        if (rh != null) {
            rhMin = rhMin == null ? rh : Integer.valueOf(Math.min(rhMin, rh));
            rhMax = rhMax == null ? rh : Integer.valueOf(Math.max(rhMax, rh));
        }
        Double w = o.windSpeedKmh();
        if (w != null) {
            wSum += w;
            wN++;
            wMax = wMax == null ? w : Double.valueOf(Math.max(wMax, w));
        }
        Double g = o.windGustKmh();
        if (g != null) {
            gMax = gMax == null ? g : Double.valueOf(Math.max(gMax, g));
        }
        if (o.rainSince9amMm() != null) {
            rainSince9am = o.rainSince9amMm();
        }
        if (o.rain24hMm() != null) {
            rain24h = o.rain24hMm();
        }
        if (o.maxTemperatureC() != null) {
            publishedMax = o.maxTemperatureC();
        }
    }

    public Double tMean() {
        return tN == 0 ? null : Math.round(tSum / tN * 10) / 10.0;
    }

    public Double wMean() {
        return wN == 0 ? null : Math.round(wSum / wN * 10) / 10.0;
    }
}
