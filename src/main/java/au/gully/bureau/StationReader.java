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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The seven state files, checked every ten minutes and downloaded only when the server says they have
 * changed (docs/06 item 12): a conditional GET each, which is what the Bureau asks for and keeps the
 * traffic to a handful of downloads an hour.
 * <p>
 * Each file is checked live before it is relied on. A state whose file the Bureau has paused answers
 * an error, is logged once per change of state, and simply contributes no stations until it is back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StationReader {

    public static final Duration EVERY = Duration.ofMinutes(10);
    public static final List<String> STATES = List.of("nsw", "vic", "qld", "sa", "wa", "tas", "nt");

    private final HttpFetcher http;
    private final StationRegistry registry;
    private final Map<String, FileState> files = new LinkedHashMap<>();
    private final List<Consumer<Instant>> listeners = new ArrayList<>();

    /**
     * Who wants to know when station values changed: the hexagons, which recompute their fire picture.
     */
    public void onUpdate(Consumer<Instant> listener) {
        listeners.add(listener);
    }

    /**
     * One pass over every state. Returns how many files were downloaded (as against unchanged).
     */
    public int poll() {
        Instant now = Instant.now();
        int downloaded = 0;
        int changed = 0;
        for (String state : STATES) {
            FileState fs = files.computeIfAbsent(state, s -> new FileState());
            try {
                Fetched f = http.getIfChanged(URI.create(StationFile.url(state)));
                fs.checkedAt = now;
                if (f.notModified()) {
                    continue;
                }
                downloaded++;
                List<StationFile.StationReading> readings = StationFile.parse(f.body(), state);
                int added = registry.accept(readings, now);
                fs.readAt = now;
                fs.stations = readings.size();
                fs.failure = null;
                changed++;
                if (added > 0) {
                    log.info("bureau {}: {} stations, {} new to the register", state, readings.size(), added);
                }
            } catch (UpstreamException | javax.xml.stream.XMLStreamException | RuntimeException e) {
                if (fs.failure == null) {
                    log.warn("bureau {}: {} (will keep checking every {})", state, e.getMessage(), EVERY);
                }
                fs.failure = e.getMessage();
                fs.failedAt = now;
            }
        }
        if (changed > 0) {
            listeners.forEach(l -> l.accept(now));
        }
        log.debug("bureau stations: {} of {} files downloaded, {} stations held", downloaded, STATES.size(), registry.size());
        return downloaded;
    }

    /**
     * Each state's file as the console shows it.
     */
    public Map<String, FileState> files() {
        return Map.copyOf(files);
    }

    /**
     * Where one state's file stands.
     */
    public static final class FileState {
        public Instant checkedAt;
        public Instant readAt;
        public Instant failedAt;
        public int stations;
        public String failure;
    }
}
