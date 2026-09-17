package au.weather.terrain;

import au.weather.http.HostLimiter;
import au.weather.http.HttpFetcher;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two things about the height lookup, and they are the two that would be silent if they broke.
 * <p>
 * The parse, because Open-Meteo answers one array and a misread of it would put a plausible height on
 * an anchor rather than none. And {@link ElevationService#cached}, because it sits in the lookup path:
 * if it ever reached the network it would put a round trip in front of every cache hit, and the only
 * symptom would be that the service got slow.
 */
class ElevationServiceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /**
     * The real collaborators, unstubbed. Nothing here calls out, and a fetcher that would refuse to is
     * a better assertion than one that cannot: if {@code cached} ever grew a fetch, this would hang or
     * fail rather than quietly pass.
     */
    private static ElevationService service(boolean enabled) {
        return new ElevationService(new HttpFetcher(RestClient.builder()),
                new HostLimiter(List.of()), new TerrainProperties(enabled));
    }

    private static OptionalDouble parse(String json) {
        return service(true).parse(MAPPER.readTree(json));
    }

    @Test
    void itReadsTheOneElementArrayOpenMeteoAnswers() {
        assertThat(parse("{\"elevation\":[123.0]}")).hasValue(123.0);
        assertThat(parse("{\"elevation\":[0]}")).hasValue(0.0);
        assertThat(parse("{\"elevation\":[-14.5],\"generationtime_ms\":0.1}")).hasValue(-14.5);
    }

    /**
     * Every other answer is no answer. An empty array, a null element, a word where a number should be,
     * or the error object the endpoint returns on a bad request: the anchor keeps a null height and is
     * retried, which is what the Hub's tile store did for a point outside coverage.
     */
    @Test
    void anythingElseIsNoAnswerRatherThanAGuess() {
        assertThat(parse("{\"elevation\":[]}")).isEmpty();
        assertThat(parse("{\"elevation\":[null]}")).isEmpty();
        assertThat(parse("{\"elevation\":[\"high\"]}")).isEmpty();
        assertThat(parse("{\"elevation\":123.0}")).isEmpty();
        assertThat(parse("{\"error\":true,\"reason\":\"Latitude must be in range of -90 to 90\"}")).isEmpty();
        assertThat(parse("{}")).isEmpty();
    }

    /**
     * Nothing is held until something asks for it, and {@code cached} is not something that asks.
     */
    @Test
    void cachedNeverFetches() {
        ElevationService service = service(true);

        assertThat(service.cached(-34.93, 138.60)).isEmpty();
        assertThat(service.held()).isZero();
    }

    /**
     * Turned off, both methods answer empty without touching anything, and {@code WeatherCache} falls
     * back to comparing horizontally.
     */
    @Test
    void disabledAnswersEmptyFromBothMethods() {
        ElevationService service = service(false);

        assertThat(service.enabled()).isFalse();
        assertThat(service.cached(-34.93, 138.60)).isEmpty();
        assertThat(service.at(-34.93, 138.60)).isEmpty();
        assertThat(service.held()).isZero();
    }

    /**
     * Four decimal places, so two anchors a metre apart share one entry rather than two. The DEM posts
     * underneath are 90 m, so nothing is lost.
     */
    @Test
    void itDeclaresTheOpenMeteoHostSoTheBudgetIsShared() {
        assertThat(service(true).hostBudgets()).containsOnlyKeys("api.open-meteo.com");
    }
}
