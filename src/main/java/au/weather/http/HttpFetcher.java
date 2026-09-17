package au.weather.http;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Conditional GET over Spring {@code RestClient}. HTTP is a dependency, not a class to write (16.1).
 */
@Slf4j
@Component
public class HttpFetcher {

    private static final String USER_AGENT = "Weather/0.1 (+https://github.com/jlhudson/Weather-Modelling)";

    private final RestClient client;

    public HttpFetcher(RestClient.Builder builder) {
        this.client = builder.defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT).build();
    }

    /**
     * What the upstream said when it refused, when it said it in words. A JSON API that answers 429
     * puts the one fact that matters in the body - Open-Meteo's says whether it was the minute, the
     * hour or the day that ran out, and those want cooldowns three orders of magnitude apart - and a
     * status line alone throws that away. An HTML error page says nothing worth keeping, so it is not.
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
                .replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? "" : ": " + text;
    }

    public Fetched get(URI uri, String etag, String lastModified) throws SourceException {
        return get(uri, etag, lastModified, Map.of());
    }

    /**
     * As above, with per-request headers. Needed because at least one upstream refuses a generic
     * User-Agent outright: MET Norway blocks unidentified callers rather than throttling them, so the
     * deployment has to name itself on every request.
     */
    public Fetched get(URI uri, String etag, String lastModified, Map<String, String> extraHeaders) throws SourceException {
        try {
            ResponseEntity<byte[]> response = client.get()
                    .uri(uri)
                    .headers(h -> {
                        if (etag != null) h.set(HttpHeaders.IF_NONE_MATCH, etag);
                        if (lastModified != null) h.set(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
                        extraHeaders.forEach(h::set);
                    })
                    .retrieve()
                    .onStatus(status -> true, (req, res) -> { /* status is read below; nothing throws here */ })
                    .toEntity(byte[].class);
            HttpHeaders headers = response.getHeaders();
            int status = response.getStatusCode().value();
            if (status >= 400) {
                throw new SourceException("HTTP " + status + " from " + uri
                        + reason(response.getBody(), headers.getFirst(HttpHeaders.CONTENT_TYPE)));
            }
            return new Fetched(status, response.getBody(), headers.getFirst(HttpHeaders.CONTENT_TYPE),
                    headers.getETag(), headers.getFirst(HttpHeaders.LAST_MODIFIED));
        } catch (ResourceAccessException e) {
            throw new SourceException("unreachable: " + uri + " (" + e.getMessage() + ")", e);
        }
    }
}
