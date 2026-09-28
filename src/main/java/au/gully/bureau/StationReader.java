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
import java.util.Objects;

/**
 * The Bureau's station files - South Australia's and Tasmania's (W-47) - read every ten minutes, each downloaded only
 * when the server says it has changed: a conditional GET, which is what the Bureau asks for. A file is processed whole:
 * every station in the state goes into the register.
 * <p>
 * A failure is logged once per change of state and tried again on the next tick; until it is back
 * the register keeps what it last held of that state, with its time. One state failing leaves the other read.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StationReader {

    /**
     * The states this service covers, in the Bureau's own lower-case codes, in the order they are read.
     */
    public static final List<String> STATES = List.of("sa", "tas");

    /**
     * The Bureau refreshes the file every ten minutes.
     */
    public static final Duration EVERY = Duration.ofMinutes(10);

    private final HttpFetcher http;
    private final StationRegistry registry;
    private final Map<String, File> files = STATES.stream().collect(LinkedHashMap::new, (m, s) -> m.put(s, new File(s)), Map::putAll);

    /**
     * One state's file as last read.
     */
    public static final class File {
        private final String state;
        private volatile Instant checkedAt;
        private volatile Instant readAt;
        private volatile Instant failedAt;
        private volatile String failure;
        private volatile int stationsInFile;

        File(String state) {
            this.state = state;
        }

        public String state() {
            return state;
        }

        public Instant checkedAt() {
            return checkedAt;
        }

        public Instant readAt() {
            return readAt;
        }

        public Instant failedAt() {
            return failedAt;
        }

        public String failure() {
            return failure;
        }

        public int stationsInFile() {
            return stationsInFile;
        }
    }

    /**
     * One read of every state's file.
     *
     * @return whether any file was downloaded and taken into the register
     */
    public synchronized boolean read() {
        boolean any = false;
        for (File f : files.values()) {
            any |= read(f);
        }
        return any;
    }

    private boolean read(File file) {
        Instant now = Instant.now();
        String state = file.state;
        URI uri = URI.create(StationFile.url(state));
        try {
            Fetched f = http.getIfChanged(uri);
            file.checkedAt = now;
            if (f.notModified()) {
                return false;
            }
            List<StationFile.StationReading> readings = StationFile.parse(f.body(), state);
            int added = registry.accept(readings, now);
            file.readAt = now;
            file.stationsInFile = readings.size();
            if (file.failure != null) {
                log.info("bureau {}: back, {} stations", state, readings.size());
            }
            file.failure = null;
            if (added > 0) {
                log.info("bureau {}: {} stations, {} new to the register", state, readings.size(), added);
            }
            return true;
        } catch (UpstreamException | javax.xml.stream.XMLStreamException | RuntimeException e) {
            // Not taken in: the validators are forgotten, so the next read downloads the file whole rather than hearing it is unchanged.
            http.forget(uri);
            if (file.failure == null) {
                log.warn("bureau {}: {} (tried again every {})", state, e.getMessage(), EVERY);
            }
            file.checkedAt = now;
            file.failure = e.getMessage();
            file.failedAt = now;
            return false;
        }
    }

    /**
     * Each state's file, in the order read.
     */
    public List<File> files() {
        return List.copyOf(files.values());
    }

    public Instant checkedAt() {
        return latest(File::checkedAt);
    }

    /**
     * When the newest of the files was last downloaded.
     */
    public Instant readAt() {
        return latest(File::readAt);
    }

    public Instant failedAt() {
        return latest(File::failedAt);
    }

    /**
     * What is wrong, state by state, or null while every file reads.
     */
    public String failure() {
        List<String> out = new ArrayList<>();
        files.values().stream().filter(f -> f.failure != null).forEach(f -> out.add(f.state + ": " + f.failure));
        return out.isEmpty() ? null : String.join("; ", out);
    }

    /**
     * How many stations the files name between them.
     */
    public int stationsInFile() {
        return files.values().stream().mapToInt(File::stationsInFile).sum();
    }

    private Instant latest(java.util.function.Function<File, Instant> field) {
        return files.values().stream().map(field).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
    }
}
