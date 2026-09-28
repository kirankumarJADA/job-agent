package com.personal.jobagent.preferences;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression coverage for the PUT /api/v1/preferences 500.
 *
 * The real UI saves a compact payload that omits {@code experienceLevels},
 * {@code companySizePref}, {@code industryPref} and {@code extraFilters}.
 * With plain null binding that payload reached
 * {@code PreferenceSetRepository.update} and died in
 * {@code JdbcConversions.toSqlArray} with
 * {@code NullPointerException: Cannot invoke "java.util.List.toArray()"
 * because "values" is null} — the exact stack trace captured from the live
 * backend during the browser-verified failure.
 *
 * These tests pin the record's contract: omitted optional collections bind to
 * the schema's declared defaults ({@code '{}'}), explicitly-sent values pass
 * through untouched, and weights validation still rejects bad weights with its
 * documented 400 rather than any 500.
 */
class PreferenceSetRecordBindingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void compactUiPayload_bindsOmittedCollectionsToEmptyDefaults() throws Exception {
        // Byte-shape of the real Preferences page save (verified live): no
        // experienceLevels, companySizePref, industryPref or extraFilters.
        String json = """
                {
                  "titles": ["Backend Engineer"],
                  "keywordsInclude": [],
                  "keywordsExclude": [],
                  "requiredSkills": ["Java"],
                  "locationsAllowed": ["UK"],
                  "remoteTypes": ["REMOTE", "HYBRID"],
                  "employmentTypes": ["FULL_TIME"],
                  "salaryMinGbp": 60000,
                  "sponsorshipPolicy": "SHOW_ALL",
                  "applicationMode": "ASSISTED",
                  "scoringWeights": {
                    "skill": 30, "experience": 15, "visa": 20, "location": 10,
                    "salary": 10, "career": 10, "difficulty": 5
                  }
                }
                """;

        PreferenceSetRecord record = MAPPER.readValue(json, PreferenceSetRecord.class);

        assertThat(record.experienceLevels()).isEmpty();
        assertThat(record.companySizePref()).isEmpty();
        assertThat(record.industryPref()).isEmpty();
        assertThat(record.extraFilters()).isEmpty();
        // Sanity: the fields the UI does send arrive intact.
        assertThat(record.titles()).containsExactly("Backend Engineer");
        assertThat(record.salaryMinGbp()).isEqualTo(60000);
        assertThat(record.isActive()).isFalse(); // omitted primitive -> false

        // The exact precondition that used to NPE the repository: null-free
        // collections reaching toSqlArray, and weights that pass validation.
        assertThatCode(() -> ScoringWeights.validate(record.scoringWeights()))
                .doesNotThrowAnyException();
        for (List<String> values : List.of(record.titles(), record.keywordsInclude(),
                record.keywordsExclude(), record.requiredSkills(), record.locationsAllowed(),
                record.remoteTypes(), record.employmentTypes(), record.experienceLevels(),
                record.companySizePref(), record.industryPref())) {
            assertThat(values).isNotNull();
        }
    }

    @Test
    void explicitlySentValuesPassThroughUnchanged() {
        PreferenceSetRecord record = new PreferenceSetRecord(
                null, null,
                List.of("Platform Engineer"),
                List.of("k8s"), List.of("php"),
                List.of("Go"), List.of("UK", "EU"),
                List.of("ONSITE"), List.of("PART_TIME"),
                List.of("SENIOR"),
                90000L,
                "SPONSORSHIP_REQUIRED",
                List.of("SCALEUP"), List.of("FINTECH"),
                "MANUAL",
                Map.of("skill", 30, "experience", 15, "visa", 20, "location", 10,
                        "salary", 10, "career", 10, "difficulty", 5),
                Map.of("minCompanySize", 50),
                true);

        assertThat(record.titles()).containsExactly("Platform Engineer");
        assertThat(record.experienceLevels()).containsExactly("SENIOR");
        assertThat(record.companySizePref()).containsExactly("SCALEUP");
        assertThat(record.industryPref()).containsExactly("FINTECH");
        assertThat(record.extraFilters()).containsEntry("minCompanySize", 50);
        assertThat(record.employmentTypes()).containsExactly("PART_TIME");
        assertThat(record.isActive()).isTrue();
    }

    @Test
    void nullScoringWeightsBindsToEmptyMap_soValidationYieldsTheDocumented400() {
        // Not a 500 path: an empty map reaches ScoringWeights.validate, which
        // refuses it with the contract's user-facing message. The controller
        // translates that to 400 — never a stack trace.
        PreferenceSetRecord record = new PreferenceSetRecord(
                null, null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), null, null, List.of(), List.of(),
                null, null, null, false);

        assertThat(record.scoringWeights()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ScoringWeights.validate(record.scoringWeights()))
                .isInstanceOf(ScoringWeights.ValidationException.class);
    }
}
