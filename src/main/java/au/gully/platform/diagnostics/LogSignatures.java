package au.gully.platform.diagnostics;

import au.gully.platform.Hashing;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * How a log line becomes a signature (D-234): the parts that vary between two occurrences of one
 * problem are masked, and secrets are redacted before anything is stored.
 *
 * <p>The Hub also lifted the register id a line named, so a source's own page could list its lines
 * ({@code sourceMatcher}, {@code sourceOf}). There are no sources here, so both went.
 */
@UtilityClass
public class LogSignatures {

    static final int MESSAGE_MAX = 2048;
    static final int PATTERN_MAX = 512;
    static final int TRACE_MAX = 6_000;
    /**
     * The value after any key-like name in a query string or an assignment. Every metered upstream
     * this system calls puts its key in the URL, and the grabber logs the URL it failed on.
     */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:api[_-]?key|key|token|password|passwd|pwd|secret|credential)s?\\s*[=:]\\s*)([^&\\s\"',;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)\\S+");
    private static final Pattern UUID = Pattern.compile("\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern TIMESTAMP = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?)?\\b");
    private static final Pattern HEX = Pattern.compile("\\b(?=[0-9a-f]*\\d)[0-9a-f]{8,}\\b");
    /**
     * A number on its own: not the digits inside {@code INC0103}, {@code PT4M} or {@code HHH000247},
     * which are the reference, the duration and the code an agent will search for.
     */
    private static final Pattern NUMBER = Pattern.compile("(?<!\\w)-?\\d+(?:[.,]\\d+)*(?!\\w)");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /**
     * The line as it may be stored: keys, tokens and passwords replaced, whatever their carrier.
     */
    public static String redact(String message) {
        if (message == null) {
            return "";
        }
        String out = SECRET.matcher(message).replaceAll("$1***");
        out = BEARER.matcher(out).replaceAll("$1***");
        return out.length() > MESSAGE_MAX ? out.substring(0, MESSAGE_MAX) : out;
    }

    /**
     * The message with its variable parts masked: ids, hashes, timestamps and numbers become {@code #},
     * so "incident 685e4ee6-… reopened after 3 pages" and "incident f70a5305-… reopened after 12 pages"
     * are one problem.
     */
    public static String pattern(String message) {
        String out = redact(message);
        out = UUID.matcher(out).replaceAll("#");
        out = TIMESTAMP.matcher(out).replaceAll("#");
        out = HEX.matcher(out).replaceAll("#");
        out = NUMBER.matcher(out).replaceAll("#");
        out = SPACES.matcher(out).replaceAll(" ").trim();
        return out.length() > PATTERN_MAX ? out.substring(0, PATTERN_MAX) : out;
    }

    /**
     * The identity of a problem: forty hex characters of the level, the logger and the pattern.
     */
    public static String signature(String level, String logger, String pattern) {
        return Hashing.sha256Hex(level + "|" + logger + "|" + pattern).substring(0, 40);
    }

    /**
     * {@code Class: message} of a throwable, or null.
     */
    public static String exceptionOf(IThrowableProxy t) {
        if (t == null) {
            return null;
        }
        String out = t.getClassName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        return out.length() > 256 ? out.substring(0, 256) : out;
    }

    /**
     * The trace, trimmed to what places the failure: every {@code au.gully} frame, the first two frames of
     * each throwable whatever their package, and every cause the same way. A full trace through Spring,
     * Hibernate and Tomcat runs to two hundred lines that say nothing this code did.
     */
    public static String traceOf(IThrowableProxy t) {
        if (t == null) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        IThrowableProxy current = t;
        int depth = 0;
        while (current != null && depth++ < 6) {
            if (out.length() > 0) {
                out.append("\nCaused by: ");
            }
            out.append(current.getClassName());
            if (current.getMessage() != null) {
                out.append(": ").append(redact(current.getMessage()));
            }
            StackTraceElementProxy[] frames = current.getStackTraceElementProxyArray();
            int kept = 0;
            for (int i = 0; frames != null && i < frames.length; i++) {
                String frame = frames[i].getSTEAsString();
                boolean ours = frame.startsWith("at au.gully.");
                if (i < 2 || (ours && kept < 12)) {
                    out.append("\n  ").append(frame);
                    kept += ours ? 1 : 0;
                }
            }
            current = current.getCause();
        }
        return out.length() > TRACE_MAX ? out.substring(0, TRACE_MAX) : out.toString();
    }
}
