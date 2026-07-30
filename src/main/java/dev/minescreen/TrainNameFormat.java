package dev.minescreen;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passenger-facing metadata encoded at the start of a Create train name.
 *
 * <p>Supported form: {@code [service type] [LOOP] vehicle name}. LOOP is optional and its
 * absence deliberately means an out-and-back working. Only leading bracket groups are parsed, so
 * brackets in the actual vehicle name remain untouched.</p>
 */
public record TrainNameFormat(String displayName, String serviceType, boolean loop) {
    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*(?:\\[([^\\]\\r\\n]{1,32})\\]|【([^】\\r\\n]{1,32})】)\\s*");

    public TrainNameFormat {
        displayName = safe(displayName).trim();
        serviceType = safe(serviceType).trim();
    }

    public static TrainNameFormat parse(String rawName) {
        String raw = safe(rawName).trim();
        String remaining = raw;
        String serviceType = "";
        boolean loop = false;
        boolean consumed = false;
        while (true) {
            Matcher matcher = PREFIX.matcher(remaining);
            if (!matcher.find()) break;
            String marker = safe(matcher.group(1) == null ? matcher.group(2) : matcher.group(1))
                    .trim();
            if (marker.equalsIgnoreCase("LOOP")) loop = true;
            else if (serviceType.isBlank()) serviceType = marker;
            else break;
            consumed = true;
            remaining = remaining.substring(matcher.end()).trim();
        }
        String displayName = consumed && !remaining.isBlank() ? remaining : raw;
        return new TrainNameFormat(displayName, serviceType, loop);
    }

    /** Legacy names without a bracketed service type remain supported. */
    public String serviceTypeOrLegacy() {
        if (!serviceType.isBlank()) return serviceType;
        String lower = displayName.toLowerCase(Locale.ROOT);
        for (String type : java.util.List.of("通勤特快", "特别快速", "特別快速", "快速急行",
                "通勤快速", "区间快速", "區間快速", "新快速", "特急", "急行", "快速",
                "普通", "各停")) {
            if (lower.contains(type.toLowerCase(Locale.ROOT))) return type;
        }
        if (lower.contains("limited express")) return "Limited Express";
        if (lower.contains("rapid express")) return "Rapid Express";
        if (lower.contains("commuter rapid")) return "Commuter Rapid";
        if (lower.contains("express")) return "Express";
        if (lower.contains("rapid")) return "Rapid";
        if (lower.contains("local")) return "Local";
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
