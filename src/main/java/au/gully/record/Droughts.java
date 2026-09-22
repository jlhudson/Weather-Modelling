package au.gully.record;

import au.gully.bureau.Station;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Every station's drought, on demand from its record, computed when asked (W-15): a year of days
 * integrates in a millisecond, so nothing is memoised - the map asks for eighty at once and gets
 * eighty fresh.
 */
@Service
public class Droughts {

    private final Record record;

    public Droughts(Record record, au.gully.bureau.StationsFeed feed) {
        this.record = record;
        if (feed != null) {
            feed.decorate((s, p) -> {
                Optional<Drought> d = of(s);
                p.put("kbdiMm", d.map(Drought::kbdiMm).orElse(null));
                p.put("droughtFactor", d.map(Drought::droughtFactor).orElse(null));
                p.put("droughtDays", d.map(Drought::days).orElse(null));
                p.put("droughtComplete", d.map(Drought::complete).orElse(null));
            });
        }
    }

    /**
     * What one station's drawer says of its drought and its record.
     */
    public Map<String, Object> detail(Station s) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        Optional<Drought> d = of(s);
        Map<String, Object> dm = new java.util.LinkedHashMap<>();
        dm.put("held", d.isPresent());
        java.util.NavigableMap<LocalDate, Record.Day> days = record.days(s.id());
        dm.put("days", days.size());
        dm.put("from", days.isEmpty() ? null : days.firstKey().toString());
        dm.put("to", days.isEmpty() ? null : days.lastKey().toString());
        dm.put("bureauDays", days.values().stream().filter(x -> Record.SOURCE_BUREAU.equals(x.source())).count());
        dm.put("archiveDays", days.values().stream().filter(x -> Record.SOURCE_ARCHIVE.equals(x.source())).count());
        dm.put("rainSoFarMm", record.rainSoFar(s, Instant.now()));
        d.ifPresent(x -> {
            dm.put("kbdiMm", x.kbdiMm());
            dm.put("band", x.band());
            dm.put("droughtFactor", x.droughtFactor());
            dm.put("meanAnnualRainMm", x.meanAnnualRainMm());
            dm.put("yearDays", x.days());
            dm.put("complete", x.complete());
            dm.put("integratedFrom", x.from().toString());
            dm.put("integratedTo", x.to().toString());
            dm.put("computedFor", x.computedFor().toString());
            dm.put("recentRainMm", x.recentRainMm());
        });
        out.put("drought", dm);
        java.util.List<Map<String, Object>> last = new java.util.ArrayList<>();
        days.descendingMap().values().stream().limit(30).forEach(x -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("day", x.day().toString());
            m.put("rainMm", x.rainMm());
            m.put("maxTempC", x.maxTempC());
            m.put("source", x.source());
            last.add(m);
        });
        out.put("recordDays", last);
        out.put("recordWindows", record.recentWindows(s.id(), 8));
        return out;
    }

    public Optional<Drought> of(Station s) {
        return of(s, Instant.now());
    }

    public Optional<Drought> of(Station s, Instant now) {
        LocalDate today = Record.dayOf(now, Record.zoneOf(s));
        return Drought.of(record.days(s.id()), today, record.rainSoFar(s, now));
    }
}
