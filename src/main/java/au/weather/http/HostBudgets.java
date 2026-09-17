package au.weather.http;

import java.time.Duration;
import java.util.Map;

/**
 * How a client that is not a polled source declares the host budget it needs.
 *
 * <p>A scheduled source declares its host spacing on its own {@code @Source} annotation, next to the
 * feed it was learned from. The on-demand callers — the weather providers, the drought and flood
 * clients — have no such annotation, but they hit hosts that need the same protection, and they share
 * the bucket with everything else on that host. This is where they say so.
 *
 * <p>Put the number and the reason in the implementing class, at the top, as a constant with a comment.
 * That is the whole point of it not being in configuration: the rule lives with the code that knows why
 * the rule exists.
 *
 * <p>Where more than one declaration names the same host, the shortest interval does not win — the
 * longest does. A budget is a promise not to exceed a rate, and the most cautious promise is the only
 * one that keeps all of them.
 */
public interface HostBudgets {

    /**
     * Host name to the closest together it may be called.
     */
    Map<String, Duration> hostBudgets();
}
