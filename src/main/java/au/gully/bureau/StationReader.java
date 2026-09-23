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
import java.util.List;

/**
 * The Bureau's South Australian station file, read every ten minutes and downloaded only when the
 * server says it has changed: a conditional GET, which is what the Bureau asks for. The file is
 * processed whole — every station in the state goes into the register.
 * <p>
 * A failure is logged once per change of state and tried again on the next tick; until it is back
 * the register keeps what it last held, with its time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StationReader {

    /**
     * The one state this service covers, in the Bureau's own lower-case code.
     */
    public static final String STATE = "sa";

    /**
     * The Bureau refreshes the file every ten minutes.
     */
    public static final Duration EVERY = Duration.ofMinutes(10);

    private final HttpFetcher http;
    private final StationRegistry registry;

    private volatile Instant checkedAt;
    private volatile Instant readAt;
    private volatile Instant failedAt;
    private volatile String failure;
    private volatile int stationsInFile;

    /**
     * One read of the file: a conditional GET, so a file the Bureau has not changed costs a round
     * trip and no download.
     *
     * @return whether the file was downloaded and taken into the register
     */
    public synchronized boolean read() {
        Instant now = Instant.now();
        try {
            Fetched f = http.getIfChanged(URI.create(StationFile.url(STATE)));
            checkedAt = now;
            if (f.notModified()) {
                return false;
            }
            List<StationFile.StationReading> readings = StationFile.parse(f.body(), STATE);
            int added = registry.accept(readings, now);
            readAt = now;
            stationsInFile = readings.size();
            if (failure != null) {
                log.info("bureau {}: back, {} stations", STATE, readings.size());
            }
            failure = null;
            if (added > 0) {
                log.info("bureau {}: {} stations, {} new to the register", STATE, readings.size(), added);
            }
            return true;
        } catch (UpstreamException | javax.xml.stream.XMLStreamException | RuntimeException e) {
            // Not taken in: the validators are forgotten, so the next read downloads the file whole rather than hearing it is unchanged.
            http.forget(URI.create(StationFile.url(STATE)));
            if (failure == null) {
                log.warn("bureau {}: {} (tried again every {})", STATE, e.getMessage(), EVERY);
            }
            checkedAt = now;
            failure = e.getMessage();
            failedAt = now;
            return false;
        }
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
