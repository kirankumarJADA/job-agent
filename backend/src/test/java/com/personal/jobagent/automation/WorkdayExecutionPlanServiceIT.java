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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 19 completion gate: real-PostgreSQL proof for {@link WorkdayExecutionPlanService}
 * and {@link AutomationPlanRepository#prepareWorkdaySubmission(UUID)}.
 *
 * Verifies against real schema and real rows:
 * - Candidate identity mapping (firstName, lastName, email, phone, location, LinkedIn)
 * - Navigation to /apply
 * - Cross-profile isolation and ownership enforcement
 * - Document artifact embedding
 * - Independent retry plan generation with workday-retry key
 * - Atomic submission queuing via prepareWorkdaySubmission (transition from READY_TO_SUBMIT to PREPARED)
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"
})
@Testcontainers
class WorkdayExecutionPlanServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private WorkdayExecutionPlanService service;
    @Autowired private AutomationPlanRepository plans;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private static final UUID SOURCE = UUID.randomUUID();
    private static final String WORKDAY_JOB_URL =
            "https://nvidia.wd5.myworkdayjobs.com/en-US/NVIDIAExternalCareerSite/job/Taiwan-Taipei/Senior-HR-Operation-Manager---APAC_JR2024946";

    private UUID seedProfile(String displayName, String phone, String location, String linksJson) {
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, 'x', ?, 'LOCAL')
                """, userId, "workday-it-" + profileId + "@example.com", displayName);
        jdbc.update("""
                insert into profiles (id, user_id, setup_status, phone, location, links)
                values (?, ?, 'READY', ?, ?, ?::jsonb)
                """, profileId, userId, phone, location, linksJson);
        return profileId;
    }

    private void seedSource() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'WORKDAY', 'workday-it-org', 'Workday IT Source', '{}', 'DISCOVERY_ONLY')
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
                values (?, ?, ?, ?, 'Workday IT Corp', 'Engineer', 'Taipei', 'A role', '{}'::text[], 'DISCOVERED', ?, ?)
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> planIdentityOf(UUID applicationId) throws Exception {
        return (Map<String, Object>) planStepParamsOf(applicationId, "WORKDAY_APPLY").get("identity");
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
    void persistsCandidateIdentityWithSplitNamePhoneLocationAndLinkedIn() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", "+44 7700 900123", "London, UK",
                "{\"linkedin\":\"https://linkedin.com/in/ada-lovelace\",\"github\":\"https://github.com/ada\"}");
        UUID jobId = seedJob(WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(profileId, jobId);

        var result = service.createExecutionPlan(profileId, applicationId, jobId);

        assertThat(result).isPresent();
        Map<String, Object> identity = planIdentityOf(applicationId);
        assertThat(identity.get("name")).isEqualTo("Ada Lovelace");
        assertThat(identity.get("firstName")).isEqualTo("Ada");
        assertThat(identity.get("lastName")).isEqualTo("Lovelace");
        assertThat(identity.get("phone")).isEqualTo("+44 7700 900123");
        assertThat(identity.get("addressLine1")).isEqualTo("London, UK");
        assertThat(identity.get("linkedIn")).isEqualTo("https://linkedin.com/in/ada-lovelace");
        // GitHub or unrelated keys must not leak into Workday identity
        assertThat(identity.keySet()).containsExactlyInAnyOrder("name", "firstName", "lastName", "email", "phone", "addressLine1", "linkedIn");

        // Target URL must append /apply
        assertThat(targetUrlOf(applicationId)).isEqualTo(WORKDAY_JOB_URL + "/apply");
        Map<String, Object> navParams = planStepParamsOf(applicationId, "NAVIGATE");
        assertThat(navParams.get("url")).isEqualTo(WORKDAY_JOB_URL + "/apply");
    }

    @Test
    void skipsExecutionPlanCreationForNonWorkdayUrl() {
        UUID profileId = seedProfile("Ada Lovelace", null, null, "{}");
        UUID jobId = seedJob("https://boards.greenhouse.io/acme/jobs/12345");
        UUID applicationId = seedApplication(profileId, jobId);

        var result = service.createExecutionPlan(profileId, applicationId, jobId);

        assertThat(result).isEmpty();
        Integer planCount = jdbc.queryForObject(
                "select count(*) from automation_plans where application_id = ?", Integer.class, applicationId);
        assertThat(planCount).isEqualTo(0);
    }

    @Test
    void refusesToBuildPlanForApplicationOwnedByAnotherProfile() {
        UUID ownerProfile = seedProfile("Owner Candidate", "+1 555 0100", "New York", "{}");
        UUID foreignProfile = seedProfile("Foreign Candidate", "+1 555 0200", "Chicago", "{}");
        UUID jobId = seedJob(WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(ownerProfile, jobId);

        assertThatThrownBy(() -> service.createExecutionPlan(foreignProfile, applicationId, jobId))
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void embedsCvArtifactWhenReviewedCvExists() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", null, null, "{}");
        UUID jobId = seedJob(WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(profileId, jobId);

        UUID fileId = UUID.randomUUID();
        UUID cvVersionId = UUID.randomUUID();
        byte[] pdf = ("workday-pdf-" + applicationId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
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

        Map<String, Object> pkg = planPackageOf(applicationId);
        assertThat(pkg).containsKey("cv");
        @SuppressWarnings("unchecked")
        Map<String, Object> cv = (Map<String, Object>) pkg.get("cv");
        assertThat(cv.get("versionId")).isEqualTo(cvVersionId.toString());
        assertThat(cv.get("sha256")).isEqualTo(pdfSha);
    }

    @Test
    void createRetryExecutionPlanCreatesIndependentPlanWithRetryIdempotencyKey() {
        UUID profileId = seedProfile("Ada Lovelace", null, null, "{}");
        UUID jobId = seedJob(WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(profileId, jobId);

        UUID supersededPlanId = service.createExecutionPlan(profileId, applicationId, jobId).orElseThrow();
        UUID retryPlanId = service.createRetryExecutionPlan(profileId, applicationId, jobId, supersededPlanId).orElseThrow();

        assertThat(retryPlanId).isNotEqualTo(supersededPlanId);

        String retryKey = jdbc.queryForObject(
                "select idempotency_key from automation_plans where id = ?", String.class, retryPlanId);
        assertThat(retryKey).isEqualTo("workday-retry:" + supersededPlanId);
    }

    @Test
    void prepareWorkdaySubmissionAtomicallyTransitionsApprovedPlanAndAppendsSubmitStep() throws Exception {
        UUID profileId = seedProfile("Ada Lovelace", null, null, "{}");
        UUID jobId = seedJob(WORKDAY_JOB_URL);
        UUID applicationId = seedApplication(profileId, jobId);

        UUID planId = service.createExecutionPlan(profileId, applicationId, jobId).orElseThrow();

        // Initially PREPARED, submit_approved is false. Calling prepareWorkdaySubmission must fail.
        boolean unapprovedResult = plans.prepareWorkdaySubmission(planId);
        assertThat(unapprovedResult).isFalse();

        // Simulate human review approval: set READY_TO_SUBMIT and submit_approved = true
        jdbc.update("update automation_plans set status = 'READY_TO_SUBMIT', submit_approved = true where id = ?", planId);

        // Now prepareWorkdaySubmission should atomically succeed
        boolean success = plans.prepareWorkdaySubmission(planId);
        assertThat(success).isTrue();

        // Verify status transitioned back to PREPARED
        String updatedStatus = jdbc.queryForObject(
                "select status from automation_plans where id = ?", String.class, planId);
        assertThat(updatedStatus).isEqualTo("PREPARED");

        // Verify WORKDAY_SUBMIT_CLICK step was appended
        String planJson = jdbc.queryForObject(
                "select plan::text from automation_plans where id = ?", String.class, planId);
        @SuppressWarnings("unchecked")
        Map<String, Object> plan = json.readValue(planJson, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("steps");
        Map<String, Object> lastStep = steps.get(steps.size() - 1);
        assertThat(lastStep.get("type")).isEqualTo("WORKDAY_SUBMIT_CLICK");
        assertThat(lastStep.get("id")).isEqualTo("workday-submit-click");
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
