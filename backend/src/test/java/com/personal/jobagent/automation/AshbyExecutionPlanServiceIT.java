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
 * Phase 14 completion gate: real-PostgreSQL proof for {@link AshbyExecutionPlanService},
 * mirroring {@code ZohoRecruitExecutionPlanServiceIT}'s own direct-service-autowire
 * (no MockMvc) Testcontainers pattern — this adapter's plan-building has no
 * HTTP-level readiness gate of its own to go through, so calling the service
 * directly against a real database is both simpler and the more precise test
 * of exactly the SQL and JSON this phase added. The mocked-{@code JdbcTemplate}
 * unit test ({@code AshbyExecutionPlanServiceTest}) cannot catch a wrong
 * column name, a wrong join, or a cross-profile leak the way a real schema
 * and real rows can.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"})
@Testcontainers
class AshbyExecutionPlanServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private AshbyExecutionPlanService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private static final UUID SOURCE = UUID.randomUUID();
    private static final String BOARD = "https://jobs.ashbyhq.com/ramp/a85afad0-8051-4333-ae89-0f82321dea87";

    private UUID seedProfile(String displayName, String phone, String linksJson) {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, 'x', ?, 'LOCAL')
                """, userId, "ashby-it-" + profileId + "@example.com", displayName);
        jdbc.update("insert into profiles (id, user_id, setup_status, phone, links) values (?, ?, 'READY', ?, ?::jsonb)",
                profileId, userId, phone, linksJson);
        return profileId;
    }

    private void seedSource() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'ASHBY', 'ashby-it-org', 'Ashby IT Source', '{}', 'DISCOVERY_ONLY')
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
                values (?, ?, ?, ?, 'Ashby IT Corp', 'Engineer', 'Remote', 'A role', '{}'::text[], 'DISCOVERED', ?, ?)
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
        return planStepParamsOf(applicationId, "ASHBY_APPLY").get("identity") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planStepParamsOf(UUID applicationId, String stepType) throws Exception {
        String planJson = jdbc.queryForObject(
                "select plan::text from automation_plans where application_id = ?", String.class, applicationId);
        Map<String, Object> plan = json.readValue(planJson, Map.class);
        for (Object step : (java.util.List<?>) plan.get("steps")) {
            Map<?, ?> s = (Map<?, ?>) step;
            if (stepType.equals(s.get("type"))) {
                return (Map<String, Object>) s.get("params");
            }
        }
        throw new AssertionError("No " + stepType + " step found in persisted plan");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> planPackageOf(UUID applicationId) throws Exception {
        String planJson = jdbc.queryForObject(
                "select plan::text from automation_plans where application_id = ?", String.class, applicationId);
        Map<String, Object> plan = json.readValue(planJson, Map.class);
        return plan.get("package") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private String targetUrlOf(UUID applicationId) {
        return jdbc.queryForObject("select target_url from automation_plans where application_id = ?", String.class, applicationId);
    }

    @Test
    void persistsTheCandidatesSingleUnsplitNameEmailPhoneAndLinkedIn() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", "+44 7700 900123",
                "{\"linkedin\":\"https://linkedin.com/in/candidate-a\",\"github\":\"https://github.com/candidate-a\"}");
        UUID jobId = seedJob(BOARD);
        UUID applicationId = seedApplication(profileId, jobId);

        var result = service.createExecutionPlan(profileId, applicationId, jobId);

        assertThat(result).isPresent();
        Map<String, Object> identity = planIdentityOf(applicationId);
        // Ashby's one stable name field is a SINGLE full-name control — never
        // split first/last, unlike every other adapter this project has.
        assertThat(identity.get("name")).isEqualTo("Ada Lovelace");
        assertThat(identity.get("phone")).isEqualTo("+44 7700 900123");
        assertThat(identity.get("linkedIn")).isEqualTo("https://linkedin.com/in/candidate-a");
        // github is a real profiles.links entry too, but Ashby's identity has
        // no github field — only the requested keys are ever persisted.
        assertThat(identity.keySet()).containsExactlyInAnyOrder("name", "email", "phone", "linkedIn");
    }

    @Test
    void navigatesToTheApplicationSubRouteNeverTheBareJobPostingUrl() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", null, "{}");
        UUID jobId = seedJob(BOARD);
        UUID applicationId = seedApplication(profileId, jobId);

        service.createExecutionPlan(profileId, applicationId, jobId);

        String expectedApplyUrl = BOARD + "/application";
        assertThat(targetUrlOf(applicationId)).isEqualTo(expectedApplyUrl);
        Map<String, Object> navigateParams = planStepParamsOf(applicationId, "NAVIGATE");
        assertThat(navigateParams.get("url")).isEqualTo(expectedApplyUrl);
    }

    @Test
    void neverInventsPhoneOrLinkedInWhenTheProfileHasNeither() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", null, "{}");
        UUID jobId = seedJob(BOARD);
        UUID applicationId = seedApplication(profileId, jobId);

        var result = service.createExecutionPlan(profileId, applicationId, jobId);

        assertThat(result).isPresent();
        Map<String, Object> identity = planIdentityOf(applicationId);
        assertThat(identity).doesNotContainKeys("phone", "linkedIn");
    }

    @Test
    void neverLeaksAProfilesOtherLinksWhenLinkedinIsAbsent() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", null,
                "{\"github\":\"https://github.com/candidate-b\",\"twitter\":\"https://twitter.com/candidate-b\"}");
        UUID jobId = seedJob(BOARD);
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
        UUID profileA = seedProfile("Ada Lovelace", "+1 555 0100", "{\"linkedin\":\"https://linkedin.com/in/a\"}");
        UUID profileB = seedProfile("Grace Hopper", "+1 555 0200", "{\"linkedin\":\"https://linkedin.com/in/b\"}");
        UUID jobA = seedJob(BOARD);
        UUID jobB = seedJob(BOARD);
        UUID applicationA = seedApplication(profileA, jobA);
        UUID applicationB = seedApplication(profileB, jobB);

        service.createExecutionPlan(profileA, applicationA, jobA);
        service.createExecutionPlan(profileB, applicationB, jobB);

        Map<String, Object> identityA = planIdentityOf(applicationA);
        Map<String, Object> identityB = planIdentityOf(applicationB);
        assertThat(identityA.get("name")).isEqualTo("Ada Lovelace");
        assertThat(identityA.get("phone")).isEqualTo("+1 555 0100");
        assertThat(identityB.get("name")).isEqualTo("Grace Hopper");
        assertThat(identityB.get("phone")).isEqualTo("+1 555 0200");
    }

    @Test
    void refusesToBuildAPlanForAnApplicationTheCallingProfileDoesNotOwn() {
        UUID ownerProfile = seedProfile("Ada Lovelace", "+1 555 0300", "{}");
        UUID otherProfile = seedProfile("Grace Hopper", null, "{}");
        UUID jobId = seedJob(BOARD);
        UUID applicationId = seedApplication(ownerProfile, jobId);

        // The same ownership-bound query every ATS execution-plan service
        // already uses (where a.id=? and a.profile_id=? and a.job_id=?) —
        // reconfirmed here against a real database for this phase's adapter.
        assertThatThrownBy(() -> service.createExecutionPlan(otherProfile, applicationId, jobId))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void embedsTheSelectedResumeArtifactWhenAReviewedCvExists() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", null, "{}");
        UUID jobId = seedJob(BOARD);
        UUID applicationId = seedApplication(profileId, jobId);

        UUID fileId = UUID.randomUUID();
        UUID cvVersionId = UUID.randomUUID();
        byte[] pdf = ("pdf-bytes-" + applicationId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String pdfSha = sha256Hex(pdf);
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvVersionId + ".pdf", pdfSha, "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Tailored CV', 'CV text', '{}', false, 1, 'snap', ?, true, ?, now())
                """, cvVersionId, jobId, applicationId, profileId, pdfSha, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UUID.randomUUID(), profileId, jobId, applicationId, "hash-" + applicationId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", com.personal.jobagent.documents.DocumentFactValidator.VERSION))), cvVersionId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?)
                on conflict (cv_version_id) do nothing
                """, cvVersionId, profileId, pdfSha);

        service.createExecutionPlan(profileId, applicationId, jobId);

        // Parsed via the real ObjectMapper bean, never a raw-string match —
        // the Spring-managed ObjectMapper's exact whitespace is an
        // implementation detail this test must not depend on.
        Map<String, Object> pkg = planPackageOf(applicationId);
        assertThat(pkg).containsKey("cv").doesNotContainKey("coverLetter");
        @SuppressWarnings("unchecked")
        Map<String, Object> cv = (Map<String, Object>) pkg.get("cv");
        assertThat(cv.get("versionId")).isEqualTo(cvVersionId.toString());
        assertThat(cv.get("sha256")).isEqualTo(pdfSha);
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
