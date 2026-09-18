package au.gully.platform;

/**
 * An upstream did not answer, or answered with a refusal. Carries the HTTP status where there was
 * one, because a 429 and a 503 want different pauses.
 */
public class UpstreamException extends Exception {

    private final int status;

    public UpstreamException(String message) {
        this(message, 0);
    }

    public UpstreamException(String message, int status) {
        super(message);
        this.status = status;
    }

    public UpstreamException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /**
     * The HTTP status the upstream answered with, or 0 when nothing answered.
     */
    public int status() {
        return status;
    }
}
