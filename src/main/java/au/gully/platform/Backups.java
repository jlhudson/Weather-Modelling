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
 * A nightly export of the history (docs/06 item 11): the day's rows of the ground's record (W-19) -
 * the stations' ledger, the model's stand-ins, the drought's days - as one JSON-lines file each in
 * {@code gully.history.backups}, kept for thirty days. The tables keep five years; this is the copy
 * that leaves the database.
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
     * The record's rows for a UTC day, one JSON object per line, one file per table; the ledger's
     * file is the one returned.
     */
    public Path write(Path dir, LocalDate day) throws IOException {
        Files.createDirectories(dir);
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Path first = null;
        for (String[] table : new String[][]{{"station_sample", "at"}, {"model_now", "at"}, {"drought_day", "written_at"}}) {
            Path file = dir.resolve(table[0].replace('_', '-') + "-" + day + ".jsonl");
            int written = 0;
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (Map<String, Object> row : history.rowsOn(table[0], table[1], from, to)) {
                    w.write(json.write(row));
                    w.newLine();
                    written++;
                }
            }
            log.info("history backup: {} {} rows to {}", written, table[0], file.getFileName());
            if (first == null) {
                first = file;
            }
        }
        prune(dir);
        return first;
    }

    private static void prune(Path dir) throws IOException {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(KEEP_DAYS);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".jsonl") && name.length() > 16) {
                    try {
                        LocalDate d = LocalDate.parse(name.substring(name.length() - 16, name.length() - ".jsonl".length()));
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
