package au.weather.terrain;

import au.weather.http.Fetched;
import au.weather.http.HostBudgets;
import au.weather.http.HostLimiter;
import au.weather.http.HttpFetcher;
import au.weather.http.SourceException;
import au.weather.json.Nodes;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Height at a point, for the one caller that needs it: {@code WeatherCache}, which compares an anchor
 * to a query point in three dimensions rather than two.
 *
 * <p><strong>Why this is not the Hub's {@code services/terrain}.</strong> That one is a tile store —
 * a slippy-tile downloader, a disk volume, a prefetch job with a cancel button, a sampler, profiles,
 * horizons, viewshed coverage, height fields and the radio and solar work built on top. Three thousand
 * lines, and it exists because MeshCore needs a <em>surface</em>: heights every few metres along a path,
 * thousands of samples per question, which only a local tile answers affordably. The weather cache asks
 * a different question. It wants one number per anchor, at most five hundred anchors alive at once, each
 * one asked about exactly once and then remembered for as long as the process runs. That is a memo over
 * a free HTTP endpoint, and carrying a tile volume across to serve it would have brought a disk mount, a
 * prefetch UI and a licence attribution into a service that has no map to draw them on.
 *
 * <p>Open-Meteo's elevation endpoint answers from Copernicus DEM GLO-90 at no cost and with no key, on
 * a host this service already talks to and already has a budget for. The trade is honest: a height costs
 * a round trip the first time instead of a disk read, and heights that a tile store would have had for
 * free now arrive one at a time. Hence the two methods below, which are the whole of the contract.
 *
 * <p>{@link #cached} never leaves the process; it is called from the lookup path, where a network round
 * trip in front of every cache hit would be indefensible. {@link #at} may call out; it is called only
 * where an upstream call has just been made anyway, or from the sweep. That split is
 * {@code WeatherCache}'s, and it is preserved exactly.
 */
@Slf4j
@Service
public class ElevationService implements HostBudgets {

    /**
     * Copernicus DEM GLO-90 through Open-Meteo. Free, no key, and the response is one array:
     * {@code {"elevation":[123.0]}}.
     */
    public static final String ENDPOINT = "https://api.open-meteo.com/v1/elevation";

    /**
     * The host, which is the same one the forecast endpoint uses and therefore shares its bucket.
     */
    public static final String HOST = "api.open-meteo.com";

    /**
     * A second between calls, matching {@code WeatherHostBudgets}: politeness, not compliance. Declared
     * here as well so this class carries its own reason, as {@link HostBudgets} asks; where two
     * declarations name one host the longest interval wins, so saying it twice cannot loosen anything.
     */
    private static final Duration POLITE = Duration.ofSeconds(1);

    /**
     * The decimal places a lookup is keyed on. Four is about eleven metres of latitude, which is finer
     * than the 90 m DEM posts underneath and so cannot lose a distinction the data ever held. It also
     * means two anchors metres apart share one entry instead of two.
     */
    private static final int KEY_DECIMALS = 4;

    /**
     * No expiry. The ground does not move, so a height once resolved is correct for the life of the
     * process; the only bound needed is on how many are held, and five hundred live anchors plus the
     * query points asked about around them do not come close to twenty thousand.
     */
    private static final int MAX_ENTRIES = 20_000;

    private final HttpFetcher fetcher;
    private final HostLimiter hostLimiter;
    private final TerrainProperties properties;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Cache<String, OptionalDouble> memo = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .build();

    public ElevationService(HttpFetcher fetcher, HostLimiter hostLimiter, TerrainProperties properties) {
        this.fetcher = fetcher;
        this.hostLimiter = hostLimiter;
        this.properties = properties;
    }

    private static String key(double lat, double lon) {
        return String.format("%." + KEY_DECIMALS + "f,%." + KEY_DECIMALS + "f", lat, lon);
    }

    /**
     * Whether heights are resolved at all. {@code WeatherCache} asks before every use, and falls back to
     * comparing horizontally when this is false.
     */
    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * Height at a point only if it has already been resolved; never touches the network.
     */
    public OptionalDouble cached(double lat, double lon) {
        if (!enabled()) {
            return OptionalDouble.empty();
        }
        OptionalDouble held = memo.getIfPresent(key(lat, lon));
        return held == null ? OptionalDouble.empty() : held;
    }

    /**
     * Height at a point, calling Open-Meteo when it is not already held.
     *
     * <p>A failure is remembered as "unknown" only for this call, not in the memo: a 429 or a dropped
     * connection is a fact about the minute, not about the point, and caching it would make one bad
     * minute permanent. The anchor keeps a null height and is retried on the next backfill pass, which
     * is the behaviour the Hub's tile store had for a point outside coverage.
     */
    public OptionalDouble at(double lat, double lon) {
        if (!enabled()) {
            return OptionalDouble.empty();
        }
        String key = key(lat, lon);
        OptionalDouble held = memo.getIfPresent(key);
        if (held != null) {
            return held;
        }
        OptionalDouble fetched = fetch(lat, lon);
        if (fetched.isPresent()) {
            memo.put(key, fetched);
        }
        return fetched;
    }

    /**
     * How many heights are held, for the diagnostics block.
     */
    public long held() {
        return memo.estimatedSize();
    }

    @Override
    public Map<String, Duration> hostBudgets() {
        return Map.of(HOST, POLITE);
    }

    private OptionalDouble fetch(double lat, double lon) {
        String url = ENDPOINT + "?latitude=" + String.format("%.4f", lat) + "&longitude=" + String.format("%.4f", lon);
        try {
            hostLimiter.acquire(HOST);
            Fetched fetched = fetcher.get(URI.create(url), null, null);
            return parse(mapper.readTree(fetched.bodyAsString()));
        } catch (SourceException e) {
            log.debug("elevation: {} ({})", e.getMessage(), key(lat, lon));
            return OptionalDouble.empty();
        } catch (RuntimeException e) {
            log.debug("elevation: unreadable answer for {} ({})", key(lat, lon), e.toString());
            return OptionalDouble.empty();
        }
    }

    /**
     * {@code {"elevation":[123.0]}} — one array, one element per point asked about, and one point is
     * asked about. Anything else is treated as no answer.
     */
    OptionalDouble parse(JsonNode root) {
        JsonNode elevation = Nodes.at(root, "elevation");
        if (elevation == null || !elevation.isArray() || elevation.isEmpty()) {
            return OptionalDouble.empty();
        }
        JsonNode first = elevation.get(0);
        if (first == null || first.isNull() || !first.isNumber()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(first.doubleValue());
    }
}
