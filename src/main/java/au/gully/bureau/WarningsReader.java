package au.gully.bureau;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.UpstreamException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The Bureau's current warnings for every state, five minutes behind at most (docs/06 item 5). Each
 * state's listing is a conditional GET; a product is fetched once per publication and kept until the
 * listing drops it or its own end time passes. Held in memory only: a restart re-reads seven small
 * files.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WarningsReader {

    public static final Duration EVERY = Duration.ofMinutes(5);

    private final HttpFetcher http;
    private final Map<String, Warning> warnings = new ConcurrentHashMap<>();
    private final Map<String, Instant> fetchedFor = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> listedByState = new ConcurrentHashMap<>();
    private final Map<String, String> failures = new ConcurrentHashMap<>();
    private final List<Consumer<Instant>> listeners = new ArrayList<>();
    private volatile Instant lastPollAt;

    public void onUpdate(Consumer<Instant> listener) {
        listeners.add(listener);
    }

    public int poll() {
        Instant now = Instant.now();
        boolean changed = false;
        for (String state : StationReader.STATES) {
            try {
                Fetched f = http.getIfChanged(URI.create(WarningFiles.feedUrl(state)));
                if (f.notModified()) {
                    continue;
                }
                List<WarningFiles.Item> items = WarningFiles.parseFeed(f.body());
                Set<String> listed = new HashSet<>();
                for (WarningFiles.Item item : items) {
                    listed.add(item.productId());
                    Instant known = fetchedFor.get(item.productId());
                    if (known != null && item.publishedAt() != null && !item.publishedAt().isAfter(known)) {
                        continue;
                    }
                    changed |= fetchProduct(state, item, now);
                }
                Set<String> before = listedByState.put(state, listed);
                if (before != null) {
                    for (String gone : before) {
                        if (!listed.contains(gone) && warnings.remove(gone) != null) {
                            fetchedFor.remove(gone);
                            changed = true;
                        }
                    }
                }
                failures.remove(state);
            } catch (UpstreamException | javax.xml.stream.XMLStreamException | RuntimeException e) {
                if (failures.put(state, e.getMessage()) == null) {
                    log.warn("bureau warnings {}: {}", state, e.getMessage());
                }
            }
        }
        // A warning past its own end time is not current, whatever the listing still says.
        for (Iterator<Map.Entry<String, Warning>> it = warnings.entrySet().iterator(); it.hasNext(); ) {
            Warning w = it.next().getValue();
            if (w.until() != null && w.until().plus(Duration.ofHours(1)).isBefore(now)) {
                it.remove();
                fetchedFor.remove(w.id());
                changed = true;
            }
        }
        lastPollAt = now;
        if (changed) {
            listeners.forEach(l -> l.accept(now));
        }
        return warnings.size();
    }

    private boolean fetchProduct(String state, WarningFiles.Item item, Instant now) {
        try {
            Fetched f = http.get(URI.create(WarningFiles.productUrl(item.productId())));
            Warning w = WarningFiles.parseProduct(f.body(), state, item.link());
            fetchedFor.put(item.productId(), item.publishedAt() == null ? now : item.publishedAt());
            if (w == null) {
                warnings.remove(item.productId());
                return false;
            }
            Warning before = warnings.put(w.id(), w);
            if (before == null) {
                log.info("bureau warning {}: {} {} for {} districts", w.id(), w.title(), w.phenomena() == null ? "" : w.phenomena(), w.districts().size());
            }
            return true;
        } catch (UpstreamException | javax.xml.stream.XMLStreamException | RuntimeException e) {
            log.warn("bureau warning {}: {}", item.productId(), e.getMessage());
            return false;
        }
    }

    /**
     * The warnings in force for a public weather district at an instant, most recently issued first.
     */
    public List<Warning> forDistrict(String district, Instant at) {
        if (district == null) {
            return List.of();
        }
        return warnings.values().stream()
                .filter(w -> w.covers(district) && w.currentAt(at))
                .sorted(Comparator.comparing(Warning::issuedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<Warning> all() {
        return warnings.values().stream()
                .sorted(Comparator.comparing(Warning::state).thenComparing(Warning::issuedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public Instant lastPollAt() {
        return lastPollAt;
    }

    public Map<String, String> failures() {
        return Map.copyOf(failures);
    }
}
