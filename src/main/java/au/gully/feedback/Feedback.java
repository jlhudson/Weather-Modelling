package au.gully.feedback;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One message for James, as the Hub's {@code POST /api/feedback} takes it: trimmed, a blank field null, held to the
 * limits every application shares (The-Hub-Database/docs/feedback/README.md).
 *
 * @param kind      {@code problem}, {@code idea}, {@code praise} or {@code other}
 * @param message   what they wrote, the one field required
 * @param name      the name they gave, or the console's user
 * @param email     an address to reply to
 * @param account   who is signed in to the console
 * @param page      the page they came from, a path on this site
 * @param userAgent the browser's {@code User-Agent}, cut to its limit
 */
public record Feedback(String kind, String message, String name, String email, String account, String page,
                       String userAgent) {

    public static final Set<String> KINDS = Set.of("problem", "idea", "praise", "other");
    public static final int MESSAGE_MAX = 4000;
    public static final int NAME_MAX = 100;
    public static final int EMAIL_MAX = 200;
    public static final int PAGE_MAX = 300;
    public static final int USER_AGENT_MAX = 300;

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    /**
     * The form's fields as sent: each trimmed, a blank one null, the kind one of {@link #KINDS}, the page a path
     * or nothing. {@link #problem()} says whether it can go.
     */
    public static Feedback of(String kind, String message, String name, String email, String account, String page,
                              String userAgent) {
        String agent = blankToNull(userAgent);
        return new Feedback(kind(kind), blankToNull(message), blankToNull(name), blankToNull(email),
                blankToNull(account), path(page),
                agent == null || agent.length() <= USER_AGENT_MAX ? agent : agent.substring(0, USER_AGENT_MAX));
    }

    /**
     * Anything not a kind the Hub knows is {@code other}.
     */
    public static String kind(String raw) {
        String k = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return KINDS.contains(k) ? k : "other";
    }

    /**
     * The page a link names, kept only when it is a path on this site: one leading {@code /}, not {@code //} or
     * {@code /\} (which a browser takes for another host), no control characters, at most {@link #PAGE_MAX}.
     * It becomes the thank-you's link back, so anything else is dropped rather than repaired.
     */
    public static String path(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        if (p.isEmpty() || p.length() > PAGE_MAX || p.charAt(0) != '/') {
            return null;
        }
        if (p.length() > 1 && (p.charAt(1) == '/' || p.charAt(1) == '\\')) {
            return null;
        }
        for (int i = 0; i < p.length(); i++) {
            if (Character.isISOControl(p.charAt(i))) {
                return null;
            }
        }
        return p;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Why it cannot be sent as it is, as a sentence for the person who wrote it; null when it can.
     */
    public String problem() {
        if (message == null) {
            return "Write a few words before sending.";
        }
        if (message.length() > MESSAGE_MAX) {
            return "Your feedback is " + String.format(Locale.ENGLISH, "%,d", message.length()) + " characters: please keep it to "
                    + String.format(Locale.ENGLISH, "%,d", MESSAGE_MAX) + ".";
        }
        if (name != null && name.length() > NAME_MAX) {
            return "Your name can be at most " + NAME_MAX + " characters.";
        }
        if (email != null && (email.length() > EMAIL_MAX || !EMAIL.matcher(email).matches())) {
            return "That does not look like an email address.";
        }
        return null;
    }
}
