package com.personal.jobagent.preferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stage 3I Phase 8: Explicit opt-in standing autonomous submission policy stored inside
 * {@code preference_sets.extra_filters["standingAutoSubmit"]}.
 *
 * <p>Disabled by default ({@code enabled = false}): ordinary {@code CONTROLLED_AUTO}
 * application preparation still stops in {@code AWAITING_APPROVAL} unless the candidate
 * explicitly enables standing autonomous submission and satisfies every constraint
 * (role, location/country, salary, sponsorship/work eligibility, minimum match score,
 * daily submit cap, verified package, zero required gaps / prohibited declarations).
 */
public record StandingAutoApplyPolicy(
        boolean enabled,
        int minScore,
        int dailySubmitCap,
        List<String> allowedCountries,
        List<String> permittedRoles,
        boolean requireConfirmedSponsorship
) {

    public static final String EXTRA_FILTERS_KEY = "standingAutoSubmit";
    public static final int DEFAULT_MIN_SCORE = 85;
    public static final int DEFAULT_DAILY_SUBMIT_CAP = 10;
    public static final int MAX_DAILY_SUBMIT_CAP = 25;

    public StandingAutoApplyPolicy {
        minScore = Math.max(1, Math.min(100, minScore));
        dailySubmitCap = Math.max(1, Math.min(MAX_DAILY_SUBMIT_CAP, dailySubmitCap));
        allowedCountries = cleanList(allowedCountries);
        permittedRoles = cleanList(permittedRoles);
    }

    public static StandingAutoApplyPolicy disabled() {
        return new StandingAutoApplyPolicy(
                false, DEFAULT_MIN_SCORE, DEFAULT_DAILY_SUBMIT_CAP, List.of(), List.of(), true);
    }

    public static StandingAutoApplyPolicy fromExtraFilters(Map<String, Object> extraFilters) {
        if (extraFilters == null || !(extraFilters.get(EXTRA_FILTERS_KEY) instanceof Map<?, ?> raw)) {
            return disabled();
        }
        boolean enabled = Boolean.TRUE.equals(raw.get("enabled"));
        Integer parsedMinScore = parseBoundedInt(raw.get("minScore"), 1, 100);
        if (raw.containsKey("minScore") && raw.get("minScore") != null && parsedMinScore == null) {
            return disabled();
        }
        Integer parsedCap = parseBoundedInt(raw.get("dailySubmitCap"), 1, MAX_DAILY_SUBMIT_CAP);
        if (raw.containsKey("dailySubmitCap") && raw.get("dailySubmitCap") != null && parsedCap == null) {
            return disabled();
        }
        int effectiveScore = parsedMinScore != null ? parsedMinScore : DEFAULT_MIN_SCORE;
        int effectiveCap = parsedCap != null ? parsedCap : DEFAULT_DAILY_SUBMIT_CAP;
        List<String> countries = readStringList(raw.get("allowedCountries"));
        List<String> roles = readStringList(raw.get("permittedRoles"));
        boolean requireConfirmedSponsorship = !Boolean.FALSE.equals(raw.get("requireConfirmedSponsorship"));
        return new StandingAutoApplyPolicy(
                enabled, effectiveScore, effectiveCap, countries, roles, requireConfirmedSponsorship);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("minScore", minScore);
        map.put("dailySubmitCap", dailySubmitCap);
        map.put("allowedCountries", allowedCountries);
        map.put("permittedRoles", permittedRoles);
        map.put("requireConfirmedSponsorship", requireConfirmedSponsorship);
        return map;
    }

    public List<String> effectiveAllowedCountries(PreferenceSetRecord prefs) {
        if (!allowedCountries.isEmpty()) {
            return allowedCountries;
        }
        if (prefs == null || prefs.extraFilters() == null) {
            return List.of();
        }
        return readStringList(prefs.extraFilters().get("countries"));
    }

    public List<String> effectivePermittedRoles(PreferenceSetRecord prefs) {
        if (!permittedRoles.isEmpty()) {
            return permittedRoles;
        }
        return prefs == null || prefs.titles() == null ? List.of() : cleanList(prefs.titles());
    }

    public boolean matchesPermittedRole(String jobTitle, PreferenceSetRecord prefs) {
        List<String> roles = effectivePermittedRoles(prefs);
        if (roles.isEmpty()) {
            return true;
        }
        if (jobTitle == null || jobTitle.isBlank()) {
            return false;
        }
        String lower = jobTitle.toLowerCase(Locale.ROOT).trim();
        for (String role : roles) {
            String target = role.toLowerCase(Locale.ROOT).trim();
            if (target.isBlank()) {
                continue;
            }
            if (lower.equals(target) || Pattern.compile("\\b" + Pattern.quote(target) + "\\b")
                    .matcher(lower).find()) {
                return true;
            }
        }
        return false;
    }

    private static Integer parseBoundedInt(Object val, int min, int max) {
        if (val == null) return null;
        if (!(val instanceof Number n)) return null;
        double d = n.doubleValue();
        if (!Double.isFinite(d) || d != Math.rint(d)) return null;
        int i = n.intValue();
        return i >= min && i <= max ? i : null;
    }

    private static List<String> readStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String trimmed = item.toString().trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<String> cleanList(List<String> input) {
        if (input == null || input.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String item : input) {
            if (item != null) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return List.copyOf(out);
    }
}
