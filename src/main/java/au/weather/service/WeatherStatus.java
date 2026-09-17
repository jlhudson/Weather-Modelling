package au.weather.service;

import java.time.Instant;
import java.util.Map;

/**
 * One provider as the console shows it: what it is, whether it may be called, and how much of its free
 * allowance is gone. The last of those is the point - a fallback that quietly starts billing is
 * indistinguishable from one that never fires until the invoice arrives.
 *
 * @param spent          allowance used per window, keyed {@code minute · hour · day · month}
 * @param commercialSafe false where the free tier forbids commercial use, which is a licence fact and
 *                       not a technical one, and belongs in front of whoever decides how this is deployed
 */
public record WeatherStatus(
        String id,
        String host,
        String model,
        boolean configured,
        String unavailableReason,
        boolean withinBudget,
        String budgetReason,
        boolean commercialSafe,
        double callWeight,
        double guardFraction,
        WeatherProvider.Limits limits,
        Map<String, Double> spent,
        String attribution,
        String lastFailure,
        Instant lastFailureAt,
        Instant coolingDownUntil
) {

    public boolean usable() {
        return configured && withinBudget;
    }

    /**
     * How much of the tightest published window is gone, 0 to 1, for the console bar.
     */
    public double dayFraction() {
        Integer perDay = limits() == null ? null : limits().perDay();
        if (perDay == null || perDay <= 0) {
            return 0;
        }
        return Math.min(1.0, spent.getOrDefault("day", 0.0) / perDay);
    }

    public double monthFraction() {
        Integer perMonth = limits() == null ? null : limits().perMonth();
        if (perMonth == null || perMonth <= 0) {
            return 0;
        }
        return Math.min(1.0, spent.getOrDefault("month", 0.0) / perMonth);
    }
}
