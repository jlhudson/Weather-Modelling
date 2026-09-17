package au.weather.json;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;

/**
 * Reading a field out of an upstream JSON payload without trusting it to be there, written once.
 *
 * <p>Three modules had grown their own copy — {@code Nodes} for the weather providers,
 * {@code text/integer/number} on {@code FeedParsers}, {@code text} again on {@code PoiParsers} — and
 * they disagreed in the corners: one truncated a fractional count, one rounded it, one returned null;
 * one trimmed before testing for blank and another after. Three answers to one question is one answer
 * nobody chose, so this is the answer, here beside {@link Json} because that is the lowest module
 * every JSON reader in this system already depends on (D-149).
 *
 * <p><strong>Every accessor returns a boxed null, never a zero or an empty string.</strong> In this
 * domain the difference between "no wind" and "wind not reported" decides whether a fire danger index
 * may be computed at all, and the same is true of a resource count, a coordinate and a station name.
 *
 * <p><strong>Tolerant on the way in.</strong> A number that arrives as a string is a number, because
 * upstreams do that and failing on it would lose a whole record over a quoting choice. A fractional
 * value asked for as a whole number is rounded rather than truncated or refused.
 */
@UtilityClass
public class Nodes {

    /**
     * The field, or null when the parent is absent, the field is absent, or the value is JSON null.
     */
    public static JsonNode at(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode n = parent.get(field);
        return n == null || n.isNull() ? null : n;
    }

    /**
     * Trimmed, or null. A field present but blank carries no more information than an absent one.
     */
    public static String str(JsonNode parent, String field) {
        JsonNode n = at(parent, field);
        if (n == null) {
            return null;
        }
        String s = n.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static Double dbl(JsonNode parent, String field) {
        JsonNode n = at(parent, field);
        return n == null ? null : num(n);
    }

    public static Integer integer(JsonNode parent, String field) {
        Double d = dbl(parent, field);
        return d == null ? null : (int) Math.round(d);
    }

    /**
     * A flag, whether the upstream writes it as a boolean or as 0 and 1. Both are in use here.
     */
    public static Boolean bool(JsonNode parent, String field) {
        JsonNode n = at(parent, field);
        if (n == null) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        Double d = num(n);
        return d == null ? null : d != 0.0;
    }

    /**
     * Element {@code i} of a parallel array, the shape every gridded forecast API returns.
     */
    public static Double element(JsonNode series, String field, int i) {
        JsonNode array = at(series, field);
        if (array == null || !array.isArray() || i >= array.size()) {
            return null;
        }
        JsonNode n = array.get(i);
        return n == null || n.isNull() ? null : num(n);
    }

    public static Integer elementInt(JsonNode series, String field, int i) {
        Double d = element(series, field, i);
        return d == null ? null : (int) Math.round(d);
    }

    /**
     * A bare string that ought to be a number — a coordinate split out of a combined field.
     */
    public static Double parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double num(JsonNode n) {
        return n.isNumber() ? n.asDouble() : parse(n.asText());
    }
}
