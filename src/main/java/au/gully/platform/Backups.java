package au.gully.platform;

import au.gully.hexagons.History;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A nightly export of the history (docs/06 item 11): the day's snapshots as one JSON-lines file in
 * {@code gully.history.backups}, kept for thirty days. Nothing is ever deleted from the table; this
 * is the copy that survives the table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Backups implements ApplicationRunner {

    private static final int KEEP_DAYS = 30;

    private final GullyProperties properties;
    private final History history;
    private final Json json;
    private final TaskScheduler scheduler;

    @Override
    public void run(ApplicationArguments args) {
        String dir = properties.history().backups();
        if (dir == null || dir.isBlank()) {
            return;
        }
        // 02:15 UTC, then every day.
        ZonedDateTime next = ZonedDateTime.now(ZoneOffset.UTC).withHour(2).withMinute(15).withSecond(0);
        if (next.toInstant().isBefore(Instant.now())) {
            next = next.plusDays(1);
        }
        scheduler.scheduleAtFixedRate(this::nightly, next.toInstant(), Duration.ofDays(1));
        log.info("history backups to {} nightly at 02:15 UTC", dir);
    }

    void nightly() {
        try {
            write(Path.of(properties.history().backups()), LocalDate.now(ZoneOffset.UTC).minusDays(1));
        } catch (IOException | RuntimeException e) {
            log.warn("history backup failed: {}", e.toString());
        }
    }

    /**
     * The snapshots asked for on a UTC day, one JSON object per line.
     */
    public Path write(Path dir, LocalDate day) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("reading-snapshots-" + day + ".jsonl");
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<Map<String, Object>> rows = history.rowsSince(from);
        int written = 0;
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map<String, Object> row : rows) {
                Instant asked = au.gully.storage.Db.instant(row.get("asked_at"));
                if (asked == null || !asked.isBefore(to)) {
                    continue;
                }
                w.write(json.write(row));
                w.newLine();
                written++;
            }
        }
        prune(dir);
        log.info("history backup: {} snapshots to {}", written, file.getFileName());
        return file;
    }

    private static void prune(Path dir) throws IOException {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(KEEP_DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("reading-snapshots-") && name.endsWith(".jsonl")) {
                    try {
                        LocalDate d = LocalDate.parse(name.substring("reading-snapshots-".length(), name.length() - ".jsonl".length()));
                        if (d.isBefore(cutoff)) {
                            Files.deleteIfExists(p);
                        }
                    } catch (RuntimeException ignored) {
                        // not one of ours
                    }
                }
            }
        }
    }
}
