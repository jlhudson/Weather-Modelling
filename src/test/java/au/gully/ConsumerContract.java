package au.gully;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fields other applications read off this service ({@code contract/consumers.json}), checked
 * against an answer the tests produced: a test that fails here is a change that would break the Hub
 * or IncidentWatch silently, found in this repository's build instead.
 */
public final class ConsumerContract {

    private static final Map<String, Object> PATHS = load();

    private ConsumerContract() {
    }

    /**
     * The paths of a section the body does not have, as {@code section: path}; empty when it has them all.
     */
    public static List<String> missing(Object body, String section) {
        List<String> out = new ArrayList<>();
        Object paths = PATHS.get(section);
        if (!(paths instanceof List<?> list)) {
            throw new IllegalArgumentException("no section " + section + " in contract/consumers.json");
        }
        for (Object p : list) {
            if (!has(body, String.valueOf(p).split("\\."), 0)) {
                out.add(section + ": " + p);
            }
        }
        return out;
    }

    private static boolean has(Object node, String[] path, int i) {
        if (i == path.length) {
            return true;
        }
        String step = path[i];
        boolean each = step.endsWith("[]");
        String key = each ? step.substring(0, step.length() - 2) : step;
        if (!(node instanceof Map<?, ?> m) || !m.containsKey(key)) {
            return false;
        }
        Object next = m.get(key);
        if (!each) {
            return i == path.length - 1 || has(next, path, i + 1);
        }
        // Every element of a non-empty list; an empty list says nothing either way and passes.
        if (!(next instanceof List<?> l)) {
            return false;
        }
        for (Object e : l) {
            if (!has(e, path, i + 1)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream in = ConsumerContract.class.getResourceAsStream("/contract/consumers.json")) {
            return JsonMapper.builder().build().readValue(in, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
