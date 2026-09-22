package au.gully.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every outbound GET this service makes, over Spring's {@code RestClient}, identifying the
 * deployment on each one: the Bureau wants a contact in the request, and it is polite everywhere else.
 * <p>
 * {@link #getIfChanged} is the conditional read the Bureau asks for — the file's ETag and
 * Last-Modified are remembered per URL and sent back, so a file that has not changed costs a
 * {@code 304} and no body.
 * <p>
 * Every read is given the {@code spring.http.clients} timeouts (thirty seconds to answer whole),
 * except one that says how long it will wait ({@link #get(URI, Duration)}): a year of the archive is
 * one such read, and a slow answer is still an answer.
 */
@Slf4j
@Component
public class HttpFetcher {

    private final RestClient.Builder builder;
    private final ClientHttpRequestFactoryBuilder<?> factories;
    private final HttpClientSettings settings;
    private final RestClient client;
    private final Map<Duration, RestClient> patient = new ConcurrentHashMap<>();
    private final Map<String, Validators> validators = new ConcurrentHashMap<>();

    public HttpFetcher(RestClient.Builder builder, ClientHttpRequestFactoryBuilder<?> factories, HttpClientSettings settings,
                       GullyProperties properties) {
        String contact = properties.contact() == null || properties.contact().isBlank()
                ? "https://github.com/jlhudson/Weather-Modelling" : properties.contact().trim();
        this.builder = builder.defaultHeader(HttpHeaders.USER_AGENT, "Gully/1.0 (+" + contact + ")");
        this.factories = factories;
        this.settings = settings;
        this.client = this.builder.build();
    }

    /**
     * What the upstream said when it refused, when it said it in words. Open-Meteo's 429 body names
     * the window that ran out, which decides the pause; an HTML error page says nothing worth keeping.
     */
    private static String reason(byte[] body, String contentType) {
        if (body == null || body.length == 0 || contentType == null) {
            return "";
        }
        String type = contentType.toLowerCase();
        if (!type.contains("json") && !type.contains("text/plain")) {
            return "";
        }
        String text = new String(body, 0, Math.min(body.length, 200), StandardCharsets.UTF_8)
                .replaceAll("\s+", " ").trim();
        return text.isEmpty() ? "" : ": " + text;
    }

    public Fetched get(URI uri) throws UpstreamException {
        return get(client, uri, null, null);
    }

    /**
     * A GET that will wait {@code patience} for the whole answer instead of the configured read
     * timeout: the same client otherwise, built once per distinct patience.
     */
    public Fetched get(URI uri, Duration patience) throws UpstreamException {
        RestClient c = patient.computeIfAbsent(patience, p ->
                builder.clone().requestFactory(factories.build(settings.withReadTimeout(p))).build());
        return get(c, uri, null, null);
    }

    /**
     * A GET that sends the validators it last saw for this URL and answers {@link Fetched#notModified}
     * on a 304. The validators are remembered only on a 200 that carried them.
     */
    public Fetched getIfChanged(URI uri) throws UpstreamException {
        Validators v = validators.get(uri.toString());
        Fetched fetched = get(uri, v == null ? null : v.etag(), v == null ? null : v.lastModified());
        if (fetched.ok() && (fetched.etag() != null || fetched.lastModified() != null)) {
            validators.put(uri.toString(), new Validators(fetched.etag(), fetched.lastModified()));
        }
        return fetched;
    }

    /**
     * The validators for a URL forgotten, so the next {@link #getIfChanged} downloads it whole.
     */
    public void forget(URI uri) {
        validators.remove(uri.toString());
    }

    public Fetched get(URI uri, String etag, String lastModified) throws UpstreamException {
        return get(client, uri, etag, lastModified);
    }

    private static Fetched get(RestClient client, URI uri, String etag, String lastModified) throws UpstreamException {
        try {
            ResponseEntity<byte[]> response = client.get()
                    .uri(uri)
                    .headers(h -> {
                        if (etag != null) h.set(HttpHeaders.IF_NONE_MATCH, etag);
                        if (lastModified != null) h.set(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
                    })
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { /* the status is read below */ })
                    .toEntity(byte[].class);
            HttpHeaders headers = response.getHeaders();
            int status = response.getStatusCode().value();
            if (status >= 400) {
                throw new UpstreamException("HTTP " + status + " from " + uri.getHost() + uri.getPath()
                        + reason(response.getBody(), headers.getFirst(HttpHeaders.CONTENT_TYPE)), status);
            }
            return new Fetched(status, response.getBody(), headers.getFirst(HttpHeaders.CONTENT_TYPE),
                    headers.getETag(), headers.getFirst(HttpHeaders.LAST_MODIFIED));
        } catch (ResourceAccessException e) {
            throw new UpstreamException("unreachable: " + uri.getHost() + " (" + e.getMessage() + ")", e);
        }
    }

    private record Validators(String etag, String lastModified) {
    }
}
