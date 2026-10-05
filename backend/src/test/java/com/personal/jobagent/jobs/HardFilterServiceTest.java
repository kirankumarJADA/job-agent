package com.personal.jobagent.jobs;

import com.personal.jobagent.preferences.PreferenceSetRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Comprehensive tests for HardFilterService — each hard filter criterion is
 * tested individually and in combination. The core invariant: hard filters
 * MUST reject before scoring, and semantic matching must never override a
 * hard rejection.
 */
class HardFilterServiceTest {

    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private HardFilterService service;

    @BeforeEach
    void setUp() {
        service = new HardFilterService(db);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static JobRecord job(String city, String country, String remoteType,
                                 String employmentType, String experienceLevel,
                                 BigDecimal salaryMax, UUID companyId,
                                 String title, String description, List<String> skills) {
        return new JobRecord(JOB_ID, SOURCE_ID, "ext-1", companyId, "TestCo", title,
                city + ", " + country, city, country, remoteType, employmentType, experienceLevel,
                null, salaryMax, "GBP", description, skills,
                "https://example.com/job", "https://example.com/job",
                Instant.now(), "DISCOVERED");
    }

    private static JobRecord simpleJob() {
        return job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                BigDecimal.valueOf(70000), COMPANY_ID, "Software Engineer",
                "We need a Java developer for our platform.", List.of("Java", "Spring"));
    }

    private static PreferenceSetRecord prefs(List<String> locations, List<String> remoteTypes,
                                              List<String> employmentTypes, List<String> experienceLevels,
                                              Long salaryMinGbp, String sponsorship,
                                              List<String> companySizePref, List<String> industryPref,
                                              List<String> keywordsExclude) {
        return new PreferenceSetRecord(UUID.randomUUID(), PROFILE_ID,
                List.of(), List.of(), keywordsExclude, List.of(),
                locations, remoteTypes, employmentTypes, experienceLevels,
                salaryMinGbp, sponsorship, companySizePref, industryPref,
                "ASSISTED", Map.of(), Map.of(), true);
    }

    private static PreferenceSetRecord emptyPrefs() {
        return prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
    }

    // ── Null/empty preferences → pass everything ────────────────────

    @Test
    void nullPreferencesPassesEveryJob() {
        assertThat(service.evaluate(simpleJob(), null).passed()).isTrue();
    }

    @Test
    void emptyPreferencesPassesEveryJob() {
        assertThat(service.evaluate(simpleJob(), emptyPrefs()).passed()).isTrue();
    }

    // ── Location filter ─────────────────────────────────────────────

    @Test
    void locationMatchOnCityPassesTheFilter() {
        var p = prefs(List.of("London"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void locationMatchOnCountryPassesTheFilter() {
        var p = prefs(List.of("UK"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void locationMatchIsCaseInsensitive() {
        var p = prefs(List.of("london"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isTrue();
    }

    @Test
    void locationMismatchRejectsWithStructuredReason() {
        var p = prefs(List.of("Berlin", "Munich"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).hasSize(1);
        assertThat(result.reasons().get(0).criterion()).isEqualTo("LOCATION");
        assertThat(result.reasons().get(0).expected()).contains("Berlin");
        assertThat(result.reasons().get(0).actual()).contains("London");
    }

    @Test
    void locationFilterPassesWhenJobHasNoLocationData() {
        var jobNoLocation = new JobRecord(JOB_ID, SOURCE_ID, "ext-1", null, "TestCo", "Engineer",
                null, null, null, null, null, null,
                null, null, null, "A role.", List.of(),
                "https://example.com", "https://example.com", Instant.now(), "DISCOVERED");
        var p = prefs(List.of("London"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(jobNoLocation, p).passed()).isTrue();
    }

    @Test
    void locationMatchesOnRawLocationString() {
        var jobRawOnly = new JobRecord(JOB_ID, SOURCE_ID, "ext-1", null, "TestCo", "Engineer",
                "Greater London Area", null, null, null, null, null,
                null, null, null, "A role.", List.of(),
                "https://example.com", "https://example.com", Instant.now(), "DISCOVERED");
        var p = prefs(List.of("London"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(jobRawOnly, p).passed()).isTrue();
    }

    // ── Remote type filter ──────────────────────────────────────────

    @Test
    void remoteTypeMatchPasses() {
        var p = prefs(List.of(), List.of("REMOTE"), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void remoteTypeMismatchRejects() {
        var p = prefs(List.of(), List.of("ONSITE"), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("REMOTE_TYPE");
    }

    @Test
    void remoteTypeUnknownPassesBenefitOfTheDoubt() {
        var jobUnknown = job("London", "UK", "UNKNOWN", "FULL_TIME", "MID",
                BigDecimal.valueOf(70000), null, "Engineer", "A role.", List.of());
        var p = prefs(List.of(), List.of("REMOTE"), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(jobUnknown, p).passed()).isTrue();
    }

    // ── Employment type filter ──────────────────────────────────────

    @Test
    void employmentTypeMatchPasses() {
        var p = prefs(List.of(), List.of(), List.of("FULL_TIME"), List.of(), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void employmentTypeMismatchRejects() {
        var p = prefs(List.of(), List.of(), List.of("CONTRACT"), List.of(), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("EMPLOYMENT_TYPE");
    }

    // ── Experience level filter ─────────────────────────────────────

    @Test
    void experienceLevelMatchPasses() {
        var p = prefs(List.of(), List.of(), List.of(), List.of("MID", "SENIOR"), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void experienceLevelMismatchRejects() {
        var p = prefs(List.of(), List.of(), List.of(), List.of("SENIOR"), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("EXPERIENCE_LEVEL");
    }

    // ── Salary filter ───────────────────────────────────────────────

    @Test
    void salaryAboveMinimumPasses() {
        var p = prefs(List.of(), List.of(), List.of(), List.of(), 60000L, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void salaryBelowMinimumRejects() {
        var p = prefs(List.of(), List.of(), List.of(), List.of(), 80000L, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("SALARY_MIN");
        assertThat(result.reasons().get(0).message()).contains("80000");
    }

    @Test
    void noSalaryDeclaredPassesBenefitOfTheDoubt() {
        var jobNoSalary = job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                null, null, "Engineer", "A role.", List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), 80000L, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(jobNoSalary, p).passed()).isTrue();
    }

    @Test
    void salaryExactlyAtMinimumPasses() {
        var jobExact = job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                BigDecimal.valueOf(60000), null, "Engineer", "A role.", List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), 60000L, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(jobExact, p).passed()).isTrue();
    }

    // ── Sponsorship filter ──────────────────────────────────────────

    @Test
    void sponsorshipRequiredAndCompanyHasNoLicenceRejects() {
        when(db.queryForList(contains("sponsor_records"), eq(COMPANY_ID)))
                .thenReturn(List.of(Map.of("licence_indicator", "NO_LICENCE", "confidence", BigDecimal.valueOf(0.95))));
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, "REQUIRED", List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("SPONSORSHIP");
    }

    @Test
    void sponsorshipRequiredAndNoSponsorDataPasses() {
        when(db.queryForList(contains("sponsor_records"), eq(COMPANY_ID)))
                .thenReturn(List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, "REQUIRED", List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void sponsorshipNotRequiredSkipsCheck() {
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, "NONE", List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
        verify(db, never()).queryForList(contains("sponsor_records"), any(Object[].class));
    }

    @Test
    void sponsorshipRequiredButNoCompanyIdPasses() {
        var jobNoCompany = job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                BigDecimal.valueOf(70000), null, "Engineer", "A role.", List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, "REQUIRED", List.of(), List.of(), List.of());
        assertThat(service.evaluate(jobNoCompany, p).passed()).isTrue();
    }

    // ── Company size filter ─────────────────────────────────────────

    @Test
    void companySizeMatchPasses() {
        when(db.queryForList(contains("companies"), eq(COMPANY_ID)))
                .thenReturn(List.of(Map.of("size_bucket", "MEDIUM", "industry", "Technology")));
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of("MEDIUM", "LARGE"), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void companySizeMismatchRejects() {
        when(db.queryForList(contains("companies"), eq(COMPANY_ID)))
                .thenReturn(List.of(Map.of("size_bucket", "STARTUP", "industry", "Technology")));
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of("MEDIUM", "LARGE"), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("COMPANY_SIZE");
    }

    // ── Industry filter ─────────────────────────────────────────────

    @Test
    void industryMatchPasses() {
        when(db.queryForList(contains("companies"), eq(COMPANY_ID)))
                .thenReturn(List.of(Map.of("size_bucket", "MEDIUM", "industry", "Technology")));
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of("Technology"), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void industryMismatchRejects() {
        when(db.queryForList(contains("companies"), eq(COMPANY_ID)))
                .thenReturn(List.of(Map.of("size_bucket", "MEDIUM", "industry", "Finance")));
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of("Technology", "Healthcare"), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("INDUSTRY");
    }

    // ── Excluded keywords filter ────────────────────────────────────

    @Test
    void excludedKeywordInTitleRejects() {
        var jobWithKeyword = job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                BigDecimal.valueOf(70000), null, "Senior Java Developer - Crypto",
                "Building platforms.", List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of("Crypto", "Gambling"));
        var result = service.evaluate(jobWithKeyword, p);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons().get(0).criterion()).isEqualTo("EXCLUDED_KEYWORD");
        assertThat(result.reasons().get(0).actual()).contains("Crypto");
    }

    @Test
    void excludedKeywordInDescriptionRejects() {
        var jobWithKeyword = job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                BigDecimal.valueOf(70000), null, "Engineer",
                "We build blockchain and crypto trading platforms.", List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of("crypto"));
        var result = service.evaluate(jobWithKeyword, p);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void excludedKeywordMatchIsCaseInsensitive() {
        var jobWithKeyword = job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                BigDecimal.valueOf(70000), null, "GAMBLING Platform Engineer",
                "A role.", List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of("gambling"));
        var result = service.evaluate(jobWithKeyword, p);
        assertThat(result.passed()).isFalse();
    }

    @Test
    void noExcludedKeywordsFoundPasses() {
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of("Crypto", "Gambling"));
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    // ── Multiple filter failures ────────────────────────────────────

    @Test
    void multipleFilterFailuresProduceMultipleReasons() {
        when(db.queryForList(contains("companies"), eq(COMPANY_ID)))
                .thenReturn(List.of(Map.of("size_bucket", "STARTUP", "industry", "Finance")));
        var p = prefs(
                List.of("Berlin"),          // location mismatch
                List.of("ONSITE"),          // remote type mismatch
                List.of("CONTRACT"),        // employment type mismatch
                List.of("SENIOR"),          // experience level mismatch
                80000L,                     // salary too low
                null,
                List.of("LARGE"),           // company size mismatch
                List.of("Technology"),      // industry mismatch
                List.of()
        );
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        // Should have 7 reasons (location, remote, employment, experience, salary, company size, industry)
        assertThat(result.reasons()).hasSizeGreaterThanOrEqualTo(7);
        // Reasons should be sorted alphabetically by criterion
        var criteria = result.reasons().stream().map(HardFilterResult.FilterReason::criterion).toList();
        assertThat(criteria).isSorted();
    }

    // ── Edge cases ──────────────────────────────────────────────────

    @Test
    void multipleAllowedLocationsAnyMatchSuffices() {
        var p = prefs(List.of("Berlin", "London", "Paris"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void multipleRemoteTypesAnyMatchSuffices() {
        var p = prefs(List.of(), List.of("REMOTE", "HYBRID"), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        assertThat(service.evaluate(simpleJob(), p).passed()).isTrue();
    }

    @Test
    void companyFiltersPassWhenNoCompanyIdOnJob() {
        var jobNoCompany = job("London", "UK", "REMOTE", "FULL_TIME", "MID",
                BigDecimal.valueOf(70000), null, "Engineer", "A role.", List.of());
        var p = prefs(List.of(), List.of(), List.of(), List.of(), null, null, List.of("LARGE"), List.of("Technology"), List.of());
        assertThat(service.evaluate(jobNoCompany, p).passed()).isTrue();
    }

    @Test
    void filterReasonRecordHasAllFields() {
        var p = prefs(List.of("Berlin"), List.of(), List.of(), List.of(), null, null, List.of(), List.of(), List.of());
        var result = service.evaluate(simpleJob(), p);
        assertThat(result.passed()).isFalse();
        var reason = result.reasons().get(0);
        assertThat(reason.criterion()).isNotBlank();
        assertThat(reason.expected()).isNotBlank();
        assertThat(reason.actual()).isNotBlank();
        assertThat(reason.message()).isNotBlank();
    }
}
