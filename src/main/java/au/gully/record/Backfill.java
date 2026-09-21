package au.gully.record;

import au.gully.bureau.Station;
import au.gully.bureau.StationRegistry;
import au.gully.upstreams.OpenMeteo;
import au.gully.upstreams.Upstreams;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fills the days a station's record lacks from Open-Meteo (W-6): the year behind today from the
 * reanalysis archive, which lags real time by a few days, and the last week from the forecast
 * endpoint's past days. A real station gets a whole year once, when this service first sees it,
 * and after that only the days its own file did not cover - a night the service was down, a
 * station that fell silent. Only the days missing are asked for, so a station asked about again
 * after a month costs a month, not a year.
 * <p>
 * One station a tick, so the day's allowance is touched lightly: a year is twenty-six units and a
 * week one, against ten thousand a day.
 */
@Slf4j
@Component
public class Backfill {

    public static final Duration EVERY = Duration.ofSeconds(15);
    /**
     * Fewer missing days than this in the year behind today is a station in good order: a gap of a
     * day or two is not worth a call, and the archive cannot fill the last few days anyway.
     */
    static final int TOLERANCE_DAYS = 3;
    /**
     * A station filled or tried is left alone this long: what the archive could not give today it
     * cannot give in a quarter of an hour either, and the file may close the gap itself.
     */
    static final Duration REST = Duration.ofHours(6);

    private final StationRegistry stations;
    private final Record record;
    private final Upstreams upstreams;
    private final java.util.Map<String, Instant> attempted = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastFailure;
    private volatile Instant lastFailedAt;

    public Backfill(StationRegistry stations, Record record, Upstreams upstreams) {
        this.stations = stations;
        this.record = record;
        this.upstreams = upstreams;
    }

    /**
     * The stations whose year has days missing beyond the tolerance, or beyond the archive's lag.
     */
    public List<Station> pending() {
        return pending(Instant.now());
    }

    List<Station> pending(Instant now) {
        List<Station> out = new ArrayList<>();
        for (Station s : stations.all()) {
            if (wants(s, now) != null) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The range a station wants filled, or null when it is in good order: the first missing day
     * in the year behind today to the last, when more than the tolerance is missing and any of it
     * is old enough for the archive or the recent week to hold.
     */
    public Range wants(Station s, Instant now) {
        return wants(s, now, false);
    }

    /**
     * The range a station wants filled; forced (W-13), any missing day at all, rest or no rest.
     */
    public Range wants(Station s, Instant now, boolean force) {
        Instant last = attempted.get(s.id());
        if (!force && last != null && Duration.between(last, now).compareTo(REST) < 0) {
            return null;
        }
        LocalDate today = Record.dayOf(now, Record.zoneOf(s));
        LocalDate yesterday = today.minusDays(1);
        List<LocalDate> missing = record.missing(s.id(), today.minusDays(Record.SPIN_UP_DAYS), yesterday);
        if (missing.isEmpty() || (!force && missing.size() <= TOLERANCE_DAYS)) {
            return null;
        }
        return new Range(missing.getFirst(), missing.getLast(), missing.size());
    }

    public record Range(LocalDate from, LocalDate to, int missing) {
    }

    /**
     * One tick: the first station wanting days, filled.
     */
    public boolean tick() {
        Instant now = Instant.now();
        for (Station s : stations.all()) {
            Range r = wants(s, now);
            if (r != null) {
                fill(s, r, now);
                return true;
            }
        }
        return false;
    }

    /**
     * A station's missing range, from the archive for the days old enough and the recent week for
     * the rest; what came is kept where the station has nothing of its own.
     *
     * @return how many days were new
     */
    public int fill(Station s, Range r, Instant now) {
        attempted.put(s.id(), now);
        LocalDate today = Record.dayOf(now, Record.zoneOf(s));
        LocalDate archiveEnd = today.minusDays(OpenMeteo.ARCHIVE_LAG_DAYS);
        int added = 0;
        boolean any = false;
        if (!r.from().isAfter(archiveEnd)) {
            LocalDate to = r.to().isBefore(archiveEnd) ? r.to() : archiveEnd;
            Optional<List<OpenMeteo.DailyRow>> rows = upstreams.archive(s.lat(), s.lon(), r.from(), to, s.id());
            if (rows.isEmpty()) {
                failed(s, "the archive did not answer for " + r.from() + " to " + to);
                return 0;
            }
            added += record.fill(s.id(), rows.get());
            any = true;
        }
        if (r.to().isAfter(archiveEnd)) {
            int pastDays = (int) java.time.temporal.ChronoUnit.DAYS.between(r.from().isAfter(archiveEnd) ? r.from() : archiveEnd, today) + 1;
            Optional<List<OpenMeteo.DailyRow>> rows = upstreams.recentDays(s.lat(), s.lon(), Math.min(pastDays, 30), s.id());
            if (rows.isEmpty()) {
                failed(s, "the recent days did not answer");
                return added;
            }
            added += record.fill(s.id(), rows.get());
            any = true;
        }
        if (any) {
            lastFailure = null;
            log.info("record {} ({}): {} days filled of {} missing, {} to {}", s.id(), s.name(), added, r.missing(), r.from(), r.to());
        }
        return added;
    }

    private void failed(Station s, String why) {
        lastFailure = s.id() + ": " + why;
        lastFailedAt = Instant.now();
        log.warn("record {}: {}; tried again later", s.id(), why);
    }

    public String lastFailure() {
        return lastFailure;
    }

    public Instant lastFailedAt() {
        return lastFailedAt;
    }
}
