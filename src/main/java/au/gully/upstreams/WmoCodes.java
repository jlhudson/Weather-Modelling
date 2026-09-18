package au.gully.upstreams;

import lombok.experimental.UtilityClass;

/**
 * WMO code table 4677, reduced to the twenty-eight values Open-Meteo actually emits. Kept because a
 * bare {@code 95} on an incident screen is not a fact anybody can act on, and because the alternative
 * is asking the upstream for a text field it charges the same to send.
 */
@UtilityClass
public class WmoCodes {

    static String text(Integer code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case 0 -> "Clear";
            case 1 -> "Mainly clear";
            case 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Fog";
            case 51, 53, 55 -> "Drizzle";
            case 56, 57 -> "Freezing drizzle";
            case 61 -> "Light rain";
            case 63 -> "Rain";
            case 65 -> "Heavy rain";
            case 66, 67 -> "Freezing rain";
            case 71, 73, 75 -> "Snow";
            case 77 -> "Snow grains";
            case 80 -> "Light showers";
            case 81 -> "Showers";
            case 82 -> "Heavy showers";
            case 85, 86 -> "Snow showers";
            case 95 -> "Thunderstorm";
            case 96, 99 -> "Thunderstorm with hail";
            default -> "Code " + code;
        };
    }
}
