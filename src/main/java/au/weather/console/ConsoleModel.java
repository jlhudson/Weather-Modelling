package au.weather.console;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
/**
 * What every console page needs: who is logged in, and the formatting helpers.
 *
 * <p>In the Hub this also carried the pending-approval badge, the global source stop and the map layer
 * catalogue. None of those exist here — there is no approval queue, no source scheduler and no layer
 * registry — so the three model attributes went with them.
 */
@ControllerAdvice(basePackages = "au.weather.console")
public class ConsoleModel {

    /**
     * The one zone this system operates in; a console reader is standing in it (D-132).
     */
    private static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");
    private static final Fmt FMT = new Fmt();
    /**
     * CARTO basemaps, light and dark, for every map the console draws. Optional: without it the maps keep
     * OpenStreetMap, darkened by a filter. Its own constructor, because @Value is not copied onto a Lombok
     * one (lombok.config).
     */
    private final String cartoKey;

    ConsoleModel(@Value("${CARTO_API_KEY:}") String cartoKey) {
        this.cartoKey = cartoKey == null ? "" : cartoKey.trim();
    }

    public static String operatorName() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "system" : a.getName();
    }

    @ModelAttribute("operator")
    public String operator() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "" : a.getName();
    }

    /**
     * The CARTO key, for the page to hand to Leaflet: the browser fetches the tiles, so it needs the key.
     * Empty when none is configured, and map.js then leaves CARTO off the basemap list.
     */
    @ModelAttribute("cartoKey")
    public String cartoKey() {
        return cartoKey;
    }

    /**
     * The formatting helpers, as one instance for the whole application rather than a new one per
     * request. It holds no state, so there was never a reason for a second.
     */
    @ModelAttribute("fmt")
    public Fmt fmt() {
        return FMT;
    }

    /**
     * Small formatting helpers for the templates.
     */
    public static final class Fmt {

        /**
         * Built once. A {@code DateTimeFormatter} is immutable and thread-safe; rebuilding it per cell is not free.
         */
        private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss").withZone(ADELAIDE);
        private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

        /**
         * "1h 30m", but a plain "30m" rather than "30m 0s" — the empty half is noise on a page.
         */
        private static String parts(long large, String largeUnit, long small, String smallUnit) {
            return small == 0 ? large + largeUnit : large + largeUnit + " " + small + smallUnit;
        }

        public String ago(Instant at) {
            if (at == null) {
                return "never";
            }
            Duration d = Duration.between(at, Instant.now());
            if (d.isNegative()) {
                d = d.negated();
                return "in " + human(d);
            }
            return human(d) + " ago";
        }

        public String human(Duration d) {
            if (d == null) {
                return "";
            }
            long s = d.getSeconds();
            if (s < 60) return s + "s";
            if (s < 3600) return parts(s / 60, "m", s % 60, "s");
            if (s < 86_400) return parts(s / 3600, "h", (s % 3600) / 60, "m");
            return parts(s / 86_400, "d", (s % 86_400) / 3600, "h");
        }

        /**
         * Operational time. Every screen an operator reads is in the zone they are standing in.
         */
        public String adelaide(Instant at) {
            return at == null ? "" : LOCAL.format(at);
        }

        /**
         * The stored time, for a screen where the difference between the two matters (docs/01 0.8).
         */
        public String utc(Instant at) {
            return at == null ? "" : UTC.format(at);
        }
    }
}
