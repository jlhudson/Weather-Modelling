package au.gully.upstreams;

import au.gully.storage.Db;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every upstream call, written to {@code upstream_call} before it is counted, so the month's spend
 * survives a restart and "how much is left" is a fact rather than a hope. The budget counts from
 * this table (docs/06 item 6); a ten-second memo per window keeps a burst of fetches from turning
 * into a burst of sums.
 */
@Component
public class Ledger {

    private static final Duration MEMO = Duration.ofSeconds(10);

    private final JdbcClient db;
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();

    public Ledger(JdbcClient db) {
        this.db = db;
    }

    public void record(String upstream, double units, boolean ok, Duration latency, String detail) {
        db.sql("insert into upstream_call (upstream, at, units, ok, latency_ms, detail) values (:u, :at, :units, :ok, :ms, :detail)")
                .param("u", upstream).param("at", Db.ts(Instant.now())).param("units", units).param("ok", ok)
                .param("ms", latency == null ? null : latency.toMillis())
                .param("detail", detail == null ? null : detail.substring(0, Math.min(512, detail.length())))
                .update();
        memo.keySet().removeIf(k -> k.startsWith(upstream + "|"));
    }

    /**
     * Units spent by one upstream since an instant.
     */
    public double spentSince(String upstream, Instant since) {
        String key = upstream + "|" + since.getEpochSecond() / 60;
        Memo m = memo.get(key);
        Instant now = Instant.now();
        if (m != null && Duration.between(m.at(), now).compareTo(MEMO) < 0) {
            return m.units();
        }
        Double units = db.sql("select coalesce(sum(units), 0) from upstream_call where upstream = :u and at >= :since")
                .param("u", upstream).param("since", Db.ts(since)).query(Double.class).single();
        double total = units == null ? 0 : units;
        memo.put(key, new Memo(now, total));
        return total;
    }

    public double spent(String upstream, Duration window) {
        return spentSince(upstream, Instant.now().minus(window));
    }

    /**
     * Spend per UTC day, inclusive at both ends, with the calls and how many failed: every day in
     * the range answered, zero where nothing was called.
     */
    public List<DaySpend> daily(String upstream, LocalDate from, LocalDate to) {
        List<DaySpend> out = new ArrayList<>();
        Map<LocalDate, DaySpend> byDay = new ConcurrentHashMap<>();
        db.sql("select date_trunc('day', at at time zone 'UTC')::date as day, sum(units) as units, count(*) as calls,"
                        + " sum(case when ok then 0 else 1 end) as failures from upstream_call"
                        + " where upstream = :u and at >= :from and at < :to group by 1")
                .param("u", upstream)
                .param("from", Db.ts(from.atStartOfDay(ZoneOffset.UTC).toInstant()))
                .param("to", Db.ts(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()))
                .query().listOfRows()
                .forEach(row -> {
                    LocalDate day = Db.date(row.get("day"));
                    if (day != null) {
                        byDay.put(day, new DaySpend(day, orZero(Db.dbl(row.get("units"))),
                                orZero(Db.integer(row.get("calls"))), orZero(Db.integer(row.get("failures")))));
                    }
                });
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(byDay.getOrDefault(d, new DaySpend(d, 0, 0, 0)));
        }
        return out;
    }

    /**
     * Spend per UTC hour since an instant, for the console's chart.
     */
    public List<HourSpend> hourly(String upstream, Instant since) {
        List<HourSpend> out = new ArrayList<>();
        db.sql("select date_trunc('hour', at) as hour, sum(units) as units, count(*) as calls,"
                        + " sum(case when ok then 0 else 1 end) as failures from upstream_call"
                        + " where upstream = :u and at >= :since group by 1 order by 1")
                .param("u", upstream).param("since", Db.ts(since)).query().listOfRows()
                .forEach(row -> out.add(new HourSpend(Db.instant(row.get("hour")), orZero(Db.dbl(row.get("units"))),
                        orZero(Db.integer(row.get("calls"))), orZero(Db.integer(row.get("failures"))))));
        return out;
    }

    /**
     * The most recent calls, newest first, for the console.
     */
    public List<Map<String, Object>> recent(int limit) {
        return db.sql("select upstream, at, units, ok, latency_ms, detail from upstream_call order by at desc limit :n")
                .param("n", Math.max(1, Math.min(limit, 500))).query().listOfRows();
    }

    /**
     * The most recent calls to the named upstreams, and every call that failed whoever made it,
     * newest first: the paid calls without the free sources' polls drowning them, and no failure
     * hidden.
     */
    public List<Map<String, Object>> recent(int limit, Collection<String> upstreams) {
        if (upstreams == null || upstreams.isEmpty()) {
            return recent(limit);
        }
        return db.sql("select upstream, at, units, ok, latency_ms, detail from upstream_call"
                        + " where upstream in (:ids) or not ok order by at desc limit :n")
                .param("ids", List.copyOf(upstreams))
                .param("n", Math.max(1, Math.min(limit, 500))).query().listOfRows();
    }

    /**
     * Rows older than any window cares about go.
     */
    public int prune() {
        return db.sql("delete from upstream_call where at < :before")
                .param("before", Db.ts(Instant.now().minus(Duration.ofDays(400)))).update();
    }

    private static double orZero(Double d) {
        return d == null ? 0 : d;
    }

    private static int orZero(Integer i) {
        return i == null ? 0 : i;
    }

    private record Memo(Instant at, double units) {
    }

    public record DaySpend(LocalDate date, double units, int calls, int failures) {
    }

    public record HourSpend(Instant hour, double units, int calls, int failures) {
    }
}
