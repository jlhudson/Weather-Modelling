package au.gully.feedback;

import au.gully.platform.GullyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feedback handed to the Hub: {@code POST <WEATHER_HUB_URL>/api/feedback} with {@code X-Api-Key:
 * <WEATHER_HUB_API_KEY>}, the one call this service makes to it. The Hub names the application from the key, emails
 * James and pushes a copy to his phone, and answers with an outcome, read here as the person is told it
 * (The-Hub-Database/docs/feedback/README.md).
 * <p>
 * Whenever the Hub cannot take it - not linked, not answering, a 5xx, {@code UNCONFIGURED}, {@code FAILED},
 * anything not in the table - the whole message is logged at WARN, so it is not lost.
 * <p>
 * Over the same {@code RestClient} and request factory as {@link au.gully.platform.HttpFetcher}, with short timeouts
 * of its own: a person is waiting on the page, and the Hub only queues the message.
 */
@Slf4j
@Component
public class HubFeedback {

    static final String HEADER = "X-Api-Key";
    static final Duration CONNECT = Duration.ofSeconds(3);
    static final Duration ANSWER = Duration.ofSeconds(10);

    private final RestClient client;
    private final URI endpoint;
    private final String apiKey;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    public HubFeedback(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factories, HttpClientSettings settings,
                       GullyProperties properties) {
        this(builder, factories, settings, properties.hub());
    }

    HubFeedback(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factories, HttpClientSettings settings,
                GullyProperties.Hub hub) {
        this.client = builder.clone().requestFactory(factories.build(settings.withTimeouts(CONNECT, ANSWER))).build();
        boolean linked = hub != null && hub.linked();
        this.endpoint = linked ? URI.create(hub.url().trim().replaceAll("/+$", "") + "/api/feedback") : null;
        this.apiKey = linked ? hub.apiKey().trim() : null;
    }

    public boolean linked() {
        return endpoint != null;
    }

    /**
     * The message sent, and what the Hub made of it. Never throws: a failure is {@link Outcome#UNAVAILABLE}, logged.
     */
    public Result send(Feedback feedback) {
        if (endpoint == null) {
            return unavailable(feedback, "not linked: WEATHER_HUB_URL or WEATHER_HUB_API_KEY is blank");
        }
        ResponseEntity<String> response;
        try {
            response = client.post()
                    .uri(endpoint)
                    .header(HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body(feedback))
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { /* the status is read below */ })
                    .toEntity(String.class);
        } catch (RestClientException e) {
            return unavailable(feedback, "unreachable: " + endpoint.getHost() + " (" + e.getMessage() + ")");
        }
        int status = response.getStatusCode().value();
        JsonNode answer = parse(response.getBody());
        String outcome = text(answer, "outcome");
        String reason = text(answer, "reason");
        if (response.getStatusCode().is2xxSuccessful() && ("QUEUED".equals(outcome) || "DEDUPED".equals(outcome))) {
            return new Result(Outcome.SENT, null);
        }
        if ("CAPPED".equals(outcome)) {
            log.info("feedback refused by the Hub: CAPPED, too many from this application in the hour");
            return new Result(Outcome.CAPPED, reason);
        }
        if ("INVALID".equals(outcome)) {
            log.info("feedback refused by the Hub as INVALID: {}", reason);
            return new Result(Outcome.INVALID, reason);
        }
        return unavailable(feedback, "HTTP " + status + (outcome == null ? "" : " " + outcome)
                + (reason == null ? "" : ": " + reason));
    }

    /**
     * The JSON the Hub takes, without the fields not given.
     */
    static Map<String, Object> body(Feedback f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", f.kind());
        m.put("message", f.message());
        putIfPresent(m, "name", f.name());
        putIfPresent(m, "email", f.email());
        putIfPresent(m, "account", f.account());
        putIfPresent(m, "page", f.page());
        putIfPresent(m, "userAgent", f.userAgent());
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, String value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Result unavailable(Feedback f, String why) {
        log.warn("feedback not sent to the Hub ({}), kept here instead: kind={} name={} email={} account={} page={} userAgent={} message={}",
                why, f.kind(), f.name(), f.email(), f.account(), f.page(), f.userAgent(), f.message());
        return new Result(Outcome.UNAVAILABLE, null);
    }

    public enum Outcome {
        /** Queued, or the same message already was. */
        SENT,
        /** More than the Hub takes from this application in an hour. */
        CAPPED,
        /** The Hub would not take it as it is; the reason says why. */
        INVALID,
        /** Not linked, not answering, or refused for its own reasons: try later. */
        UNAVAILABLE
    }

    /**
     * @param reason the Hub's own words, for {@link Outcome#INVALID}; may be null
     */
    public record Result(Outcome outcome, String reason) {
    }
}
