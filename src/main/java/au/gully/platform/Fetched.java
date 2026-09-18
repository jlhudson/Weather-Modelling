package au.gully.platform;

import java.nio.charset.StandardCharsets;

/**
 * The result of one GET. A {@code 304} comes back as {@link #notModified()} with an empty body.
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
