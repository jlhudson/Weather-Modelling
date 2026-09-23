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
 * The housekeeping fills every Bureau station wanting days, once a day (W-15), one station after
 * another and only as far as the day's allowance allows: a year is twenty-six units and a week one,
 * against ten thousand a day. A point of ours is filled when an ask lands in its reach, and never on
 * a timer (W-14); a Bureau station in reach of an ask that has no drought to give is filled then too.
 */
@Slf4j
@Component
public class Backfill {

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
     * The Bureau's stations whose year has days missing beyond the tolerance, or beyond the archive's lag.
     */
    public List<Station> pending() {
        return pending(Instant.now());
    }

    List<Station> pending(Instant now) {
        List<Station> out = new ArrayList<>();
        for (Station s : stations.bureau()) {
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
        return new Range(missing.getFirst(), missing.getLast(), missing);
    }

    /**
     * What a station wants: its first and last missing day, and every missing day between.
     */
    public record Range(LocalDate from, LocalDate to, List<LocalDate> days) {
        public int missing() {
            return days.size();
        }
    }

    /**
     * Days in runs to fetch: consecutive, or near enough that one fetch costs no more than two - the archive
     * counts a fortnight as one unit - so a gap a year back and a few recent days are two small fetches, not
     * a year of archive.
     */
    static List<LocalDate[]> runs(List<LocalDate> days) {
        List<LocalDate[]> out = new ArrayList<>();
        LocalDate start = null, last = null;
        for (LocalDate d : days) {
            if (start != null && java.time.temporal.ChronoUnit.DAYS.between(last, d) > OpenMeteo.ARCHIVE_DAYS_PER_UNIT) {
                out.add(new LocalDate[]{start, last});
                start = null;
            }
            if (start == null) {
                start = d;
            }
            last = d;
        }
        if (start != null) {
            out.add(new LocalDate[]{start, last});
        }
        return out;
    }

    /**
     * Every station's rest forgotten: after the admin reset (W-18), every station is filled on the next run.
     */
    public void clear() {
        attempted.clear();
    }

    /**
     * The housekeeping's fill: every Bureau station wanting days, one after another.
     *
     * @return how many days were new, over every station
     */
    public int fillPending(Instant now) {
        int added = 0;
        for (Station s : stations.bureau()) {
            Range r = wants(s, now);
            if (r != null) {
                added += fill(s, r, now);
            }
        }
        return added;
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
        // Only the missing days are asked for (W-6): the archive's in runs, the recent week's from the first of them.
        for (LocalDate[] run : runs(r.days().stream().filter(d -> !d.isAfter(archiveEnd)).toList())) {
            Optional<List<OpenMeteo.DailyRow>> rows = upstreams.archive(s.lat(), s.lon(), run[0], run[1], s.id());
            if (rows.isEmpty()) {
                failed(s, "the archive did not answer for " + run[0] + " to " + run[1]);
                return added;
            }
            added += record.fill(s.id(), rows.get());
            any = true;
        }
        LocalDate firstRecent = r.days().stream().filter(d -> d.isAfter(archiveEnd)).findFirst().orElse(null);
        if (firstRecent != null) {
            int pastDays = (int) java.time.temporal.ChronoUnit.DAYS.between(firstRecent, today) + 1;
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
