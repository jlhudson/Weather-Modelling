package au.weather.http;

import java.nio.charset.StandardCharsets;

/**
 * The result of one conditional, host-limited fetch made through the grabber. A {@code 304} comes
 * back as {@link #notModified()} with an empty body; the caller emits nothing, which is fine, because
 * a source that found nothing emits nothing (docs/03-sources.md 3.0).
 */
public record Fetched(int status, byte[] body, String contentType, String etag, String lastModified) {

    public boolean notModified() {
        return status == 304;
    }

    public boolean ok() {
        return status >= 200 && status < 300;
    }

    public String bodyAsString() {
        return body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }
}
