package com.personal.jobagent.apply;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8.2 duplicate identity: only the strongest reliable signals decide
 * that two postings are the same role — never titles — and tracking-only URL
 * parameters never split one posting into two.
 */
class ApplicationIdentityServiceTest {

    @Test
    void trackingParametersDoNotCreateDistinctJobs() {
        String plain = ApplicationIdentityService.normalizeUrl("https://boards.greenhouse.io/acme/jobs/42?gh_src=xyz");
        String tracked = ApplicationIdentityService.normalizeUrl(
                "https://boards.greenhouse.io/acme/jobs/42?utm_source=mail&utm_campaign=weekly&gclid=abc");
        assertThat(plain).isEqualTo(tracked);
    }

    @Test
    void fragmentsPortsAndTrailingSlashesAreNormalised() {
        assertThat(ApplicationIdentityService.normalizeUrl("https://Example.COM:443/jobs/1/"))
                .isEqualTo(ApplicationIdentityService.normalizeUrl("https://example.com/jobs/1#top"));
        assertThat(ApplicationIdentityService.normalizeUrl("https://example.com/jobs/1"))
                .isEqualTo("https://example.com/jobs/1");
    }

    @Test
    void genuineQueryParametersSurviveNormalisation() {
        assertThat(ApplicationIdentityService.normalizeUrl("https://example.com/apply?job=42"))
                .isNotEqualTo(ApplicationIdentityService.normalizeUrl("https://example.com/apply?job=43"));
    }

    @Test
    void theGreenhouseRequisitionIdentifierIsTheStrongestSignal() {
        assertThat(ApplicationIdentityService.boardIdentity("https://boards.greenhouse.io/acme/jobs/42"))
                .isEqualTo("greenhouse:acme:42");
        assertThat(ApplicationIdentityService.boardIdentity("https://job-boards.greenhouse.io/acme/jobs/99?x=1"))
                .isEqualTo("greenhouse:acme:99");
        assertThat(ApplicationIdentityService.boardIdentity("https://jobs.ashbyhq.com/acme/8f1e2c"))
                .isEqualTo("ashby:acme:8f1e2c");
    }

    @Test
    void differentRequisitionsAreDifferentRolesEvenWithIdenticalTitles() {
        assertThat(ApplicationIdentityService.boardIdentity("https://boards.greenhouse.io/acme/jobs/1"))
                .isNotEqualTo(ApplicationIdentityService.boardIdentity("https://boards.greenhouse.io/acme/jobs/2"));
    }

    @Test
    void aSourceExternalIdFallsBackWhenNoUrlExists() {
        var service = new ApplicationIdentityService(null);
        assertThat(service.identityKey(null, null, "ASHBY", "ext-1")).isEqualTo("src:ashby:ext-1");
        assertThat(service.identityKey(null, null, "ASHBY", "ext-1"))
                .isNotEqualTo(service.identityKey(null, null, "ASHBY", "ext-2"));
    }

    @Test
    void anUnknownIdentityNeverMatchesAnything() {
        var service = new ApplicationIdentityService(null);
        // No URL, no source id: an unknown identity is not "the same as" another
        // unknown, so it can never silently merge two genuinely different rows.
        assertThat(service.identityKey(null, "not a url", null, null)).isNull();
    }

    @Test
    void theIdentityKeyNeverContainsATitleOrAnyPersonalData() {
        var service = new ApplicationIdentityService(null);
        String key = service.identityKey("https://boards.greenhouse.io/acme/jobs/42",
                "https://boards.greenhouse.io/acme/jobs/42", "GREENHOUSE", "42");
        assertThat(key).isEqualTo("greenhouse:acme:42");
    }

    @Test
    void matchReasonsNameTheActualSignal() {
        assertThat(ApplicationIdentityService.describeMatch("greenhouse:acme:42")).contains("requisition");
        assertThat(ApplicationIdentityService.describeMatch("url:https://example.com/apply")).contains("URL");
        assertThat(ApplicationIdentityService.describeMatch("src:ashby:ext-1")).contains("external job id");
    }

    @Test
    void duplicateDetectionIsScopedToTheCallersOwnApplications() {
        org.springframework.jdbc.core.JdbcTemplate db = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service = new ApplicationIdentityService(db);
        UUID profile = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        org.mockito.Mockito.when(db.query(org.mockito.ArgumentMatchers.contains("from jobs j join job_sources"),
                        org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowMapper.class),
                        org.mockito.ArgumentMatchers.eq(job)))
                .thenReturn(List.of("greenhouse:acme:42"));
        org.mockito.Mockito.when(db.queryForList(org.mockito.ArgumentMatchers.contains("where a.profile_id = ?"),
                        org.mockito.ArgumentMatchers.eq(profile)))
                .thenReturn(List.of());

        service.findDuplicates(profile, job);

        // The read is a profile-scoped query: one candidate's application can
        // never be reported to another.
        org.mockito.Mockito.verify(db).queryForList(
                org.mockito.ArgumentMatchers.contains("a.profile_id = ?"),
                org.mockito.ArgumentMatchers.eq(profile));
    }
}
