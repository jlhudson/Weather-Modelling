package au.gully.platform.diagnostics;

import java.time.Instant;

/**
 * One distinct warning or error this service has logged, with how often and when. A row is a
 * <em>signature</em>, not a line: the level, the logger and the message with its numbers, ids and
 * timestamps masked. Four hundred identical lines are one row with a count of four hundred and the
 * latest line verbatim.
 */
public record LogEvent(long id, String signature, String level, String logger, String thread, String sourceId,
                       String pattern, String message, String exception, String trace, long count,
                       Instant firstSeenAt, Instant lastSeenAt) {

    public String shortLogger() {
        return logger == null ? "" : logger.substring(logger.lastIndexOf('.') + 1);
    }

    public boolean hasTrace() {
        return trace != null;
    }
}
