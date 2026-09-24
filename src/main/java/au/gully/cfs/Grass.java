package au.gully.cfs;

import au.gully.fire.CsiroGrassland;
import au.gully.fire.GrassFireDanger;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The grass fire danger at a place (W-24): McArthur's grassland index (Mk5, Noble, Bary and Gill 1980) and the
 * AFDRS grass model (CSIRO, Cheney, Gould and Catchpole 1998) with its Fire Behaviour Index and rating, on one
 * set of weather and the district's curing and fuel load. Without a curing figure for the district there is no
 * grass index, and the block says why.
 */
public final class Grass {

    private Grass() {
    }

    /**
     * The grass block for one set of conditions: the inputs the weather cannot supply, then both models.
     */
    public static Map<String, Object> block(String district, Curing.Entry curing, Double temperatureC, Integer humidityPct, Double windKmh, LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("district", district);
        if (curing == null) {
            m.put("note", "no curing figure has been entered for " + district + "; the grass indices need one");
            return m;
        }
        m.put("curingPct", curing.percent());
        m.put("fuelLoadTHa", curing.fuelLoadTHa());
        m.put("curingFor", curing.enteredOn() == null ? null : curing.enteredOn().toString());
        m.put("curingOld", curing.old(today));
        Double gfdi = GrassFireDanger.of(temperatureC, humidityPct, windKmh, curing.percent().doubleValue(), curing.fuelLoadTHa());
        m.put("gfdi", gfdi);
        m.put("gfdiRating", gfdi == null ? null : GrassFireDanger.rating(gfdi));
        CsiroGrassland.Result r = CsiroGrassland.of(temperatureC, humidityPct, windKmh, curing.percent().doubleValue(), curing.fuelLoadTHa(), null);
        m.put("condition", r == null ? null : r.condition().name().toLowerCase().replace('_', '-'));
        m.put("fbi", r == null ? null : r.fbi());
        m.put("afdrsRating", r == null ? null : r.rating());
        m.put("rateOfSpreadKmh", r == null ? null : r.rateOfSpreadKmh());
        m.put("flameHeightM", r == null ? null : r.flameHeightM());
        m.put("intensityKwm", r == null ? null : r.intensityKwm());
        return m;
    }
}
