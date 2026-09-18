package au.gully.bureau;

import java.time.Instant;
import java.util.List;

/**
 * One current Bureau warning, as its product XML states it: the title the public see, the districts
 * it covers, and when it runs from and to (docs/06 item 5).
 *
 * @param id        the product identifier, e.g. {@code IDS21037}
 * @param state     lower case, from the feed it was listed in
 * @param title     e.g. {@code Severe Weather Warning}, {@code Fire Weather Warning}
 * @param phenomena e.g. {@code for DAMAGING WINDS}, as the product phrases it
 * @param headline  the first period's headline, where the product carries one
 * @param hazard    the AMOC hazard type code ({@code SWW}, {@code FWW}, ...)
 * @param severity  the AMOC severity code, where present
 * @param districts the public weather district AACs the warning names ({@code SA_PW001} ...)
 * @param issuedAt  when the product was issued
 * @param from      when the hazard starts, or the issue time
 * @param until     when the hazard ends, or the product's expiry
 * @param link      the product page on the Bureau's site
 */
public record Warning(String id, String state, String title, String phenomena, String headline, String hazard,
                      String severity, List<String> districts, Instant issuedAt, Instant from, Instant until, String link) {

    public Warning {
        districts = districts == null ? List.of() : List.copyOf(districts);
    }

    public boolean covers(String district) {
        return district != null && districts.contains(district);
    }

    /**
     * Whether the warning is in force at an instant: from its start (or issue) to its end (or expiry).
     */
    public boolean currentAt(Instant at) {
        Instant start = from == null ? issuedAt : from;
        return (start == null || !at.isBefore(start)) && (until == null || at.isBefore(until));
    }

    /**
     * Whether this is a fire weather warning, which the fire picture flags on its own.
     */
    public boolean fireWeather() {
        return "FWW".equals(hazard) || (title != null && title.toLowerCase().contains("fire weather"));
    }
}
