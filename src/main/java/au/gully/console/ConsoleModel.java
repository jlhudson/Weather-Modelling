package au.gully.console;

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
 * What every console page needs: who is logged in, the map key, and the formatting helpers.
 */
@ControllerAdvice(basePackages = "au.gully.console")
public class ConsoleModel {

    private static final ZoneId ADELAIDE = ZoneId.of("Australia/Adelaide");
    private static final Fmt FMT = new Fmt();
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

    @ModelAttribute("cartoKey")
    public String cartoKey() {
        return cartoKey;
    }

    @ModelAttribute("fmt")
    public Fmt fmt() {
        return FMT;
    }

    /**
     * Small formatting helpers for the templates.
     */
    public static final class Fmt {

        private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ADELAIDE);
        private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("dd/MM HH:mm'Z'").withZone(ZoneOffset.UTC);

        private static String parts(long large, String largeUnit, long small, String smallUnit) {
            return small == 0 ? large + largeUnit : large + largeUnit + " " + small + smallUnit;
        }

        public String ago(Object when) {
            Instant at = au.gully.storage.Db.instant(when);
            if (at == null) {
                return "—";
            }
            Duration d = Duration.between(at, Instant.now());
            if (d.isNegative()) {
                return "in " + human(d.negated());
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

        public String adelaide(Object when) {
            Instant at = au.gully.storage.Db.instant(when);
            return at == null ? "—" : LOCAL.format(at);
        }

        public String utc(Object when) {
            Instant at = au.gully.storage.Db.instant(when);
            return at == null ? "—" : UTC.format(at);
        }

        public String n(Object v) {
            return v == null ? "—" : String.valueOf(v);
        }

        public String n(Object v, String unit) {
            return v == null ? "—" : v + unit;
        }
    }
}
