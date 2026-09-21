package au.gully.console;

import au.gully.api.StatusController;
import au.gully.upstreams.Ledger;
import au.gully.upstreams.Upstreams;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The upstreams page (docs/06 item 8): every budgeted upstream's spend against its allowance in
 * one table, the spend over time as one chart from {@code spend.json}, the breaker's history, the
 * sources with when each last answered, and the recent calls - the budgeted upstreams' own and
 * every failure, the free sources' polls behind {@code ?calls=all}.
 */
@Controller
@RequestMapping("/console/upstreams")
@RequiredArgsConstructor
public class UpstreamsController {

    private static final int RECENT = 40;

    private final Upstreams upstreams;
    private final StatusController status;

    @GetMapping
    public String page(@RequestParam(required = false) String calls, Model model) {
        List<Upstreams.Status> statuses = upstreams.status();
        boolean all = "all".equals(calls);
        model.addAttribute("upstreams", statuses);
        model.addAttribute("breakerHistory", upstreams.breaker().history());
        model.addAttribute("allCalls", all);
        model.addAttribute("recent", all ? upstreams.ledger().recent(RECENT)
                : upstreams.ledger().recent(RECENT, statuses.stream().map(Upstreams.Status::id).toList()));
        model.addAttribute("sources", status.sources());
        model.addAttribute("held", status.held());
        return "upstreams";
    }

    /**
     * The chart's series: per budgeted upstream, the last 24 hours by hour and the last 31 UTC days
     * by day, every bucket present, oldest first.
     */
    @GetMapping(value = "/spend.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> spend() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> series = new ArrayList<>();
        for (Upstreams.Status s : upstreams.status()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", s.id());
            one.put("bills", s.bills());
            one.put("perDay", s.limits() == null ? null : s.limits().perDay());
            one.put("perMonth", s.limits() == null ? null : s.limits().perMonth());
            one.put("hours", fill(upstreams.ledger().hourly(s.id(), now.minus(Duration.ofHours(24))), now));
            one.put("days", upstreams.ledger().daily(s.id(), today.minusDays(30), today));
            series.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", now);
        out.put("upstreams", series);
        return out;
    }

    /**
     * The last 24 hours as 24 buckets, zero where no call was made, oldest first.
     */
    private static List<Ledger.HourSpend> fill(List<Ledger.HourSpend> rows, Instant now) {
        Instant top = now.truncatedTo(ChronoUnit.HOURS);
        List<Ledger.HourSpend> out = new ArrayList<>();
        for (int i = 23; i >= 0; i--) {
            Instant hour = top.minus(Duration.ofHours(i));
            Ledger.HourSpend found = rows.stream().filter(r -> hour.equals(r.hour())).findFirst().orElse(null);
            out.add(found == null ? new Ledger.HourSpend(hour, 0, 0, 0) : found);
        }
        return out;
    }
}
