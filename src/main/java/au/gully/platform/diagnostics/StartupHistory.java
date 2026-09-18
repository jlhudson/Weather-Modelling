package au.gully.platform.diagnostics;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What this start did, phase by phase, kept where a layer can read it (D-234). In memory: a restart is
 * exactly when the record should begin again.
 *
 * <p>The Hub's {@code PhasedStartup} wrote this, ordering forty sources and a dozen managers across six
 * phases. There are two ordered things here, so the two {@code ApplicationRunner}s in
 * {@code au.gully.platform.Startup} writes it and the phase numbers are theirs.
 */
@Component
public class StartupHistory {

    private final Instant startedAt = Instant.now();
    private final List<PhaseRecord> records = new ArrayList<>();
    private volatile Instant readyAt;

    public void record(int phase, String name, Duration took, String outcome) {
        synchronized (records) {
            records.add(new PhaseRecord(phase, name, took, outcome));
        }
    }

    public void ready() {
        readyAt = Instant.now();
    }

    public List<PhaseRecord> records() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    public Instant startedAt() {
        return startedAt;
    }

    /**
     * When the readiness gate opened, or null while it is still shut.
     */
    public Instant readyAt() {
        return readyAt;
    }

    public record PhaseRecord(int phase, String name, Duration took, String outcome) {
        public boolean failed() {
            return outcome != null && outcome.startsWith("FAILED");
        }
    }
}
