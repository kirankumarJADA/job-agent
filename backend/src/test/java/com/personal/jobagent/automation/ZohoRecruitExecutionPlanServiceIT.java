package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 13 completion gate: real-PostgreSQL proof for the phone/LinkedIn
 * identity wiring {@link ZohoRecruitExecutionPlanService} gained this phase —
 * the exact thing no mocked-{@code JdbcTemplate} unit test can prove, since a
 * mock can never catch a wrong column name, a wrong join, or a cross-profile
 * leak the way a real schema and real rows can.
 *
 * <p>Mirrors {@code AutoApplyBatchServiceIT}'s own direct-service-autowire
 * (no MockMvc) Testcontainers pattern — this adapter's plan-building has no
 * HTTP-level readiness gate of its own to go through, so calling the service
 * directly against a real database is both simpler and the more precise
 * test of exactly the SQL this phase changed.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"})
@Testcontainers
class ZohoRecruitExecutionPlanServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private ZohoRecruitExecutionPlanService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private static final UUID SOURCE = UUID.randomUUID();

    private UUID seedProfile(String phone, String linksJson) {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, 'x', 'Zoho IT Candidate', 'LOCAL')
                """, userId, "zoho-it-" + profileId + "@example.com");
        jdbc.update("insert into profiles (id, user_id, setup_status, phone, links) values (?, ?, 'READY', ?, ?::jsonb)",
                profileId, userId, phone, linksJson);
        return profileId;
    }

    private void seedSource() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'ZOHO_RECRUIT', 'zoho-it-org', 'Zoho IT Source', '{}', 'DISCOVERY_ONLY')
                on conflict (id) do nothing
                """, SOURCE);
    }

    private UUID seedJob(String applicationUrl) {
        seedSource();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  location_raw, description_text, skills_extracted, status, content_hash,
                                  application_url)
                values (?, ?, ?, ?, 'Zoho IT Corp', 'Engineer', 'Remote', 'A role', '{}'::text[], 'DISCOVERED', ?, ?)
                """, jobId, SOURCE, "ext-" + jobId, "dedup-" + jobId, "hash-" + jobId, applicationUrl);
        return jobId;
    }

    private UUID seedApplication(UUID profileId, UUID jobId) {
        UUID applicationId = UUID.randomUUID();
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')
                """, applicationId, jobId, profileId, "identity-" + applicationId);
        return applicationId;
    }

    private Map<String, Object> planIdentityOf(UUID applicationId) throws Exception {
        String planJson = jdbc.queryForObject(
                "select plan::text from automation_plans where application_id = ?", String.class, applicationId);
        Map<String, Object> plan = json.readValue(planJson, Map.class);
        Map<String, Object> steps = null;
        for (Object step : (java.util.List<?>) plan.get("steps")) {
            Map<?, ?> s = (Map<?, ?>) step;
            if ("ZOHO_RECRUIT_APPLY".equals(s.get("type"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) s.get("params");
                @SuppressWarnings("unchecked")
                Map<String, Object> identity = (Map<String, Object>) params.get("identity");
                return identity;
            }
        }
        throw new AssertionError("No ZOHO_RECRUIT_APPLY step found in persisted plan");
    }

    @Test
    void persistsTheCorrectCandidatesPhoneAndLinkedInOnlyNeverAnUnrelatedLink() throws Exception {
        UUID profileId = seedProfile("+44 7700 900123",
                "{\"linkedin\":\"https://linkedin.com/in/candidate-a\",\"github\":\"https://github.com/candidate-a\"}");
        UUID jobId = seedJob("https://acme.zohorecruit.com/jobs/Careers/1/Engineer");
        UUID applicationId = seedApplication(profileId, jobId);

        var result = service.createExecutionPlan(profileId, applicationId, jobId);

        assertThat(result).isPresent();
        Map<String, Object> identity = planIdentityOf(applicationId);
        assertThat(identity.get("phone")).isEqualTo("+44 7700 900123");
        assertThat(identity.get("linkedIn")).isEqualTo("https://linkedin.com/in/candidate-a");
        // The candidate's github link is real data too, but Zoho's identity
        // has no github field — only the requested keys are ever persisted.
        assertThat(identity.keySet()).containsExactlyInAnyOrder("email", "phone", "linkedIn");
    }

    @Test
    void neverInventsPhoneOrLinkedInWhenTheProfileHasNeither() throws Exception {
        UUID profileId = seedProfile(null, "{}");
        UUID jobId = seedJob("https://acme.zohorecruit.com/jobs/Careers/2/Engineer");
        UUID applicationId = seedApplication(profileId, jobId);

        var result = service.createExecutionPlan(profileId, applicationId, jobId);

        assertThat(result).isPresent();
        Map<String, Object> identity = planIdentityOf(applicationId);
        assertThat(identity).doesNotContainKeys("phone", "linkedIn");
    }

    @Test
    void neverLeaksAProfilesOtherLinksWhenLinkedinIsAbsent() throws Exception {
        UUID profileId = seedProfile(null,
                "{\"github\":\"https://github.com/candidate-b\",\"twitter\":\"https://twitter.com/candidate-b\"}");
        UUID jobId = seedJob("https://acme.zohorecruit.com/jobs/Careers/3/Engineer");
        UUID applicationId = seedApplication(profileId, jobId);

        service.createExecutionPlan(profileId, applicationId, jobId);

        Map<String, Object> identity = planIdentityOf(applicationId);
        assertThat(identity).doesNotContainKey("linkedIn");
        String planJson = jdbc.queryForObject(
                "select plan::text from automation_plans where application_id = ?", String.class, applicationId);
        assertThat(planJson).doesNotContain("github.com").doesNotContain("twitter.com");
    }

    @Test
    void twoCandidatesApplyingToDifferentJobsNeverCrossContaminateIdentity() throws Exception {
        UUID profileA = seedProfile("+1 555 0100", "{\"linkedin\":\"https://linkedin.com/in/a\"}");
        UUID profileB = seedProfile("+1 555 0200", "{\"linkedin\":\"https://linkedin.com/in/b\"}");
        UUID jobA = seedJob("https://acme.zohorecruit.com/jobs/Careers/4/Engineer");
        UUID jobB = seedJob("https://acme.zohorecruit.com/jobs/Careers/5/Engineer");
        UUID applicationA = seedApplication(profileA, jobA);
        UUID applicationB = seedApplication(profileB, jobB);

        service.createExecutionPlan(profileA, applicationA, jobA);
        service.createExecutionPlan(profileB, applicationB, jobB);

        Map<String, Object> identityA = planIdentityOf(applicationA);
        Map<String, Object> identityB = planIdentityOf(applicationB);
        assertThat(identityA.get("phone")).isEqualTo("+1 555 0100");
        assertThat(identityA.get("linkedIn")).isEqualTo("https://linkedin.com/in/a");
        assertThat(identityB.get("phone")).isEqualTo("+1 555 0200");
        assertThat(identityB.get("linkedIn")).isEqualTo("https://linkedin.com/in/b");
    }

    @Test
    void refusesToBuildAPlanForAnApplicationTheCallingProfileDoesNotOwn() {
        UUID ownerProfile = seedProfile("+1 555 0300", "{}");
        UUID otherProfile = seedProfile(null, "{}");
        UUID jobId = seedJob("https://acme.zohorecruit.com/jobs/Careers/6/Engineer");
        UUID applicationId = seedApplication(ownerProfile, jobId);

        // The same ownership-bound query every ATS execution-plan service
        // already uses (where a.id=? and a.profile_id=? and a.job_id=?) —
        // unchanged by this phase, reconfirmed here against a real database
        // specifically for the service this phase touched.
        assertThatThrownBy(() -> service.createExecutionPlan(otherProfile, applicationId, jobId))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }
}
