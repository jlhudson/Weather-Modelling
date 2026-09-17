package au.weather.http;

/**
 * A poll failed. Counted toward {@code consecutive_failures}; an open breaker is what {@code DEGRADED} means.
 */
public class SourceException extends Exception {

    public SourceException(String message) {
        super(message);
    }

    public SourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
