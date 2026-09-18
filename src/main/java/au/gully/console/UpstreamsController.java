package au.gully.console;

import au.gully.api.StatusController;
import au.gully.upstreams.Ledger;
import au.gully.upstreams.Upstreams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The upstreams page (docs/06 item 8): spend per hour and per day as bars against the allowance,
 * the breaker's history, the recent calls, and the sources with when each last answered.
 */
@Controller
@RequestMapping("/console/upstreams")
@RequiredArgsConstructor
public class UpstreamsController {

    private final Upstreams upstreams;
    private final StatusController status;

    @GetMapping
    public String page(Model model) {
        List<Upstreams.Status> statuses = upstreams.status();
        model.addAttribute("upstreams", statuses);
        Map<String, List<Ledger.HourSpend>> hourly = new LinkedHashMap<>();
        Map<String, List<Ledger.DaySpend>> daily = new LinkedHashMap<>();
        Map<String, Double> hourMax = new LinkedHashMap<>();
        Map<String, Double> dayMax = new LinkedHashMap<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (Upstreams.Status s : statuses) {
            List<Ledger.HourSpend> hours = fill(upstreams.ledger().hourly(s.id(), Instant.now().minus(Duration.ofHours(24))));
            hourly.put(s.id(), hours);
            hourMax.put(s.id(), Math.max(1, hours.stream().mapToDouble(Ledger.HourSpend::units).max().orElse(1)));
            List<Ledger.DaySpend> days = upstreams.ledger().daily(s.id(), today.minusDays(30), today);
            daily.put(s.id(), days);
            Integer perDay = s.limits() == null ? null : s.limits().perDay();
            double top = days.stream().mapToDouble(Ledger.DaySpend::units).max().orElse(1);
            dayMax.put(s.id(), Math.max(1, perDay == null ? top : Math.max(top, perDay)));
        }
        model.addAttribute("hourly", hourly);
        model.addAttribute("daily", daily);
        model.addAttribute("hourMax", hourMax);
        model.addAttribute("dayMax", dayMax);
        model.addAttribute("breakerHistory", upstreams.breaker().history());
        model.addAttribute("recent", upstreams.ledger().recent(40));
        model.addAttribute("sources", status.sources());
        model.addAttribute("held", status.held());
        return "upstreams";
    }

    /**
     * The last 24 hours as 24 bars, zero where no call was made, oldest first.
     */
    private static List<Ledger.HourSpend> fill(List<Ledger.HourSpend> rows) {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        List<Ledger.HourSpend> out = new ArrayList<>();
        for (int i = 23; i >= 0; i--) {
            Instant hour = now.minus(Duration.ofHours(i));
            Ledger.HourSpend found = rows.stream().filter(r -> hour.equals(r.hour())).findFirst().orElse(null);
            out.add(found == null ? new Ledger.HourSpend(hour, 0, 0, 0) : found);
        }
        return out;
    }
}
