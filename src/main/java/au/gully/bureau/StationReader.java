package au.gully.bureau;

import au.gully.platform.Fetched;
import au.gully.platform.HttpFetcher;
import au.gully.platform.ReadOutcome;
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
 * The seven state files, each read on request when it is older than a quarter of an hour and
 * downloaded only when the server says it has changed (docs/06 item 12, W-14): a conditional GET,
 * which is what the Bureau asks for. A file that is downloaded is processed whole - every station
 * in the state goes into the register, not only the one asked about - so the next ask for any
 * hexagon in that state is answered from memory.
 * <p>
 * Each file is checked live before it is relied on. A state whose file the Bureau has paused answers
 * an error, is logged once per change of state, and simply contributes no stations until it is back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StationReader {

    /**
     * How old a state's file may be before an ask for a hexagon in that state reads it again (W-14).
     * The Bureau refreshes the files every ten minutes; a quarter of an hour is what an ask accepts.
     */
    public static final Duration EVERY = Duration.ofMinutes(15);
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
     * One state's file, read now if it has not been checked inside {@link #EVERY} (W-14): a conditional
     * GET, so a file the Bureau has not changed costs a round trip and no download. Called from an
     * ask, for the state the hexagon is in; nothing reads a file nobody has asked about. One thread
     * reads a state at a time; the others wait for it and find it fresh.
     *
     * @return what came of it: skipped inside the cadence, unchanged, read whole, or failed
     */
    public ReadOutcome ensure(String state, Instant now) {
        if (!STATES.contains(state)) {
            return ReadOutcome.SKIPPED;
        }
        FileState fs = files.computeIfAbsent(state, s -> new FileState());
        synchronized (fs) {
            if (fs.checkedAt != null && Duration.between(fs.checkedAt, now).compareTo(EVERY) < 0) {
                return ReadOutcome.SKIPPED;
            }
            try {
                Fetched f = http.getIfChanged(URI.create(StationFile.url(state)));
                fs.checkedAt = now;
                if (f.notModified()) {
                    return ReadOutcome.UNCHANGED;
                }
                List<StationFile.StationReading> readings = StationFile.parse(f.body(), state);
                int added = registry.accept(readings, now);
                fs.readAt = now;
                fs.stations = readings.size();
                fs.failure = null;
                if (added > 0) {
                    log.info("bureau {}: {} stations, {} new to the register", state, readings.size(), added);
                }
                listeners.forEach(l -> l.accept(now));
                return ReadOutcome.READ;
            } catch (UpstreamException | javax.xml.stream.XMLStreamException | RuntimeException e) {
                if (fs.failure == null) {
                    log.warn("bureau {}: {} (tried again on the next ask after {})", state, e.getMessage(), EVERY);
                }
                fs.checkedAt = now;
                fs.failure = e.getMessage();
                fs.failedAt = now;
                return ReadOutcome.FAILED;
            }
        }
    }

    /**
     * Every state, for the console's "read them all now".
     */
    public int poll() {
        Instant now = Instant.now();
        int downloaded = 0;
        for (String state : STATES) {
            FileState fs = files.get(state);
            if (fs != null) {
                fs.checkedAt = null;
            }
            if (ensure(state, now) == ReadOutcome.READ) {
                downloaded++;
            }
        }
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
