package com.personal.jobagent.preferences;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wire shape of {@code GET}/{@code PUT /api/v1/preferences} (Jackson default
 * camelCase binding, confirmed against the live API).
 *
 * <p>The compact canonical constructor below is the whole point of this class:
 * a client that omits an optional collection (the real UI sends exactly
 * {@code titles, keywordsInclude/Exclude, requiredSkills, locationsAllowed,
 * remoteTypes, employmentTypes, salaryMinGbp, sponsorshipPolicy,
 * applicationMode, scoringWeights} — never {@code experienceLevels},
 * {@code companySizePref}, {@code industryPref} or {@code extraFilters}) gets
 * the column's <em>declared default</em> rather than {@code null}. Every one of
 * those columns carries a default in V001 ({@code text[] ... default '{}'},
 * {@code extra_filters jsonb not null default '{}'}), and the contract's
 * full-replacement PUT semantics treat "not sent" as "default", so this is the
 * documented meaning of an omission — not a fabricated value.
 *
 * <p>History that makes this load-bearing: with plain null binding, the UI's
 * normal save died in {@code PreferenceSetRepository.update} with
 * {@code NullPointerException: Cannot invoke "java.util.List.toArray()" because
 * "values" is null} (JdbcConversions.toSqlArray) — a 500 on every save. Nulls
 * for the required {@code scoringWeights} map would instead be normalised to an
 * empty map here and rejected by {@link ScoringWeights#validate} with a clear
 * 400, which is the contract's documented behaviour for bad weights.
 *
 * <p>Nothing about validation is relaxed: unknown/extra fields in
 * {@code scoringWeights} are still refused, the sum-to-100 rule is untouched,
 * and explicitly-sent values pass through exactly as provided.
 */
public record PreferenceSetRecord(
        UUID id,
        UUID profileId,
        List<String> titles,
        List<String> keywordsInclude,
        List<String> keywordsExclude,
        List<String> requiredSkills,
        List<String> locationsAllowed,
        List<String> remoteTypes,
        List<String> employmentTypes,
        List<String> experienceLevels,
        Long salaryMinGbp,
        String sponsorshipPolicy,
        List<String> companySizePref,
        List<String> industryPref,
        String applicationMode,
        Map<String, Object> scoringWeights,
        Map<String, Object> extraFilters,
        boolean isActive
) {

    public PreferenceSetRecord {
        titles = titles == null ? List.of() : titles;
        keywordsInclude = keywordsInclude == null ? List.of() : keywordsInclude;
        keywordsExclude = keywordsExclude == null ? List.of() : keywordsExclude;
        requiredSkills = requiredSkills == null ? List.of() : requiredSkills;
        locationsAllowed = locationsAllowed == null ? List.of() : locationsAllowed;
        remoteTypes = remoteTypes == null ? List.of() : remoteTypes;
        employmentTypes = employmentTypes == null ? List.of() : employmentTypes;
        experienceLevels = experienceLevels == null ? List.of() : experienceLevels;
        companySizePref = companySizePref == null ? List.of() : companySizePref;
        industryPref = industryPref == null ? List.of() : industryPref;
        scoringWeights = scoringWeights == null ? Map.of() : scoringWeights;
        extraFilters = extraFilters == null ? Map.of() : extraFilters;
    }
}
