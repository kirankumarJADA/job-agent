package com.personal.jobagent.apply;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.GreenhouseAdapter;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8.2 APPLY workflow end to end on real PostgreSQL: the exact document
 * versions an execution package may carry, the readiness gate, package
 * creation and approval with real persisted records, approval invalidation
 * when records change underneath it, cross-source duplicate protection (also
 * under concurrent creation), and two-user data isolation. REAL_SUBMIT stays
 * hard-stopped — nothing here submits anything.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class ApplyWorkflowIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /**
     * The employer's real (fixture) application form, inspected read-only,
     * in Greenhouse's actual field-wrapper shape: required fields carry the
     * hidden required-input mirror (or the label's * marker), optional fields
     * are rendered as labelled wrappers without the mirror, and one field
     * carries no required-ness metadata at all.
     */
    static final String FORM_HTML = """
            <html><body>
            <form id="application_form">
            <div class="field">
              <input class="requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
              <label for="first_name">First name<span>*</span></label>
              <input id="first_name" type="text"/>
            </div>
            <div class="field">
              <label for="email">Email<span>*</span></label>
              <input id="email" type="email"/>
            </div>
            <div class="field">
              <input class="requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
              <label for="question_42">Why do you want to join?<span>*</span></label>
              <textarea id="question_42"></textarea>
            </div>
            <div class="field">
              <label for="cover_letter">Cover letter</label>
              <textarea id="cover_letter"></textarea>
            </div>
            <div class="field upload">
              <label for="resume">Resume/CV</label>
              <input id="resume" type="file" required/>
            </div>
            <div class="field">
              <input id="linkedin" aria-label="LinkedIn profile" type="text"/>
            </div>
            </form>
            </body></html>
            """;

    @TestConfiguration
    static class FixtureForm {
        @Bean
        @Primary
        GreenhouseAdapter fixtureGreenhouse() {
            return new GreenhouseAdapter(url -> FORM_HTML);
        }
    }

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000fe82");
    private static final String APPLY_URL = "https://boards.greenhouse.io/fixtureco/jobs/42";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ProfileRepository profiles;
    @Autowired private ApplicationIdentityService identity;
    @Autowired private ApplicationPipelineService pipeline;
    @Autowired private ApplyReadinessService readiness;

    private MockHttpSession session;
    private UUID devProfile;

    @BeforeEach
    void setUp() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        devProfile = jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'", UUID.class);
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'fixture-co', 'Fixture Board', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);
    }

    // ── readiness, package creation and approval with real records ───

    @Test
    void readinessReportsExactDocumentsConfirmedAnswersAndFormProvenance() throws Exception {
        UUID applicationId = seedApplication("apply-1", fixtureUrl(101));
        seedReviewedCv(applicationId, "apply-1");
        seedApprovedLetter(applicationId, 1, "I would love to join Fixture Co.");
        UUID answerId = seedConfirmedAnswer(applicationId, "apply-1", "Why do you want to join?", "For the challenge.");

        // Before the employer's form has ever been inspected, its requirements
        // are UNKNOWN — never assumed optional or satisfied.
        mvc.perform(get("/api/v1/apply/applications/{id}/readiness", applicationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.questions.formCaptured").value(false))
                .andExpect(jsonPath("$.documents.coverLetterRequirement").value("UNKNOWN"))
                .andExpect(jsonPath("$.packageReady").value(true));

        // Building the package inspects the form and captures its questions
        // with provenance and evidence-backed required-ness.
        mvc.perform(post("/api/v1/apply/applications/{id}/package", applicationId)
                        .with(csrf()).session(session))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/apply/applications/{id}/readiness", applicationId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.documents.cv.versionId").isNotEmpty())
                .andExpect(jsonPath("$.documents.coverLetter.version").value(1))
                .andExpect(jsonPath("$.documents.coverLetterRequirement").value("OPTIONAL"))
                .andExpect(jsonPath("$.questions.formCaptured").value(true))
                .andExpect(jsonPath("$.questions.items[0].questionKey").value("question_42"))
                .andExpect(jsonPath("$.questions.items[0].requiredState").value("REQUIRED"))
                .andExpect(jsonPath("$.questions.items[0].answerState").value("CONFIRMED"))
                .andExpect(jsonPath("$.questions.items[0].answerOrigin").value("CANDIDATE_CONFIRMED"))
                .andExpect(jsonPath("$.questions.items[0].source").value("GREENHOUSE_PUBLIC_FORM"))
                .andExpect(jsonPath("$.duplicates.status").value("NONE"))
                .andExpect(jsonPath("$.packageReady").value(true));
        assertThat(answerId).isNotNull();
    }

    @Test
    void packageCreationAndApprovalWorkWithRealPersistedRecords() throws Exception {
        UUID applicationId = seedApplication("apply-2", fixtureUrl(102));
        UUID cvVersionId = seedReviewedCv(applicationId, "apply-2");
        seedApprovedLetter(applicationId, 1, "I would love to join Fixture Co.");
        seedConfirmedAnswer(applicationId, "apply-2", "Why do you want to join?", "For the challenge.");

        MvcResult created = mvc.perform(post("/api/v1/apply/applications/{id}/package", applicationId)
                        .with(csrf()).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").isNotEmpty()).andReturn();
        UUID planId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("planId").asText());

        // The package binds the exact reviewed CV version and its digest.
        Map<String, Object> pkg = jdbc.queryForMap("select plan from automation_plans where id = ?", planId);
        String planJson = String.valueOf(pkg.get("plan"));
        assertThat(planJson).contains(cvVersionId.toString());

        // Employer form questions were captured with provenance and tri-state
        // required-ness from the form itself.
        Map<String, Object> question = jdbc.queryForMap("""
                select required_state, source, answer_type from job_form_questions
                where job_id = (select job_id from applications where id = ?) and question_key = 'question_42'
                """, applicationId);
        assertThat(question.get("required_state")).isEqualTo("REQUIRED");
        assertThat(question.get("source")).isEqualTo("GREENHOUSE_PUBLIC_FORM");
        // Required-ness is captured as evidence, never invented: the aria-label
        // field carries no required/optional metadata at all, so it stays
        // UNKNOWN and is not silently assumed optional.
        Map<String, Object> unmarked = jdbc.queryForMap("""
                select required_state from job_form_questions
                where job_id = (select job_id from applications where id = ?) and question_key = 'linkedin'
                """, applicationId);
        assertThat(unmarked.get("required_state")).isEqualTo("UNKNOWN");

        jdbc.update("update automation_plans set status = 'AWAITING_APPROVAL' where id = ?", planId);
        MvcResult approval = mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId)
                        .with(csrf()).session(session)).andReturn();
        assertThat(approval.getResponse().getStatus())
                .as("approve-submit response body: %s", approval.getResponse().getContentAsString())
                .isEqualTo(200);
        Map<String, Object> approvalBody = json.readValue(approval.getResponse().getContentAsString(), Map.class);
        assertThat(approvalBody.get("status")).isEqualTo("READY_TO_SUBMIT");
        assertThat(approvalBody.get("submissionEnabled")).isEqualTo(false);
        Boolean approved = jdbc.queryForObject(
                "select submit_approved from automation_plans where id = ?", Boolean.class, planId);
        assertThat(approved).isTrue();
    }

    @Test
    void anUnreviewedCvBlocksPackageCreation() throws Exception {
        UUID applicationId = seedApplication("apply-3", fixtureUrl(103));
        seedCvWithoutReview(applicationId, "apply-3");

        MvcResult result = mvc.perform(post("/api/v1/apply/applications/{id}/package", applicationId)
                        .with(csrf()).session(session))
                .andExpect(status().is4xxClientError()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("CV_REVIEW_REQUIRED");
    }

    // ── approval invalidation on mutation ────────────────────────────

    @Test
    void anAnswerChangeInvalidatesAnApprovedPackage() throws Exception {
        UUID applicationId = seedApplication("apply-4", fixtureUrl(104));
        seedReviewedCv(applicationId, "apply-4");
        seedApprovedLetter(applicationId, 1, "I would love to join Fixture Co.");
        UUID answerId = seedConfirmedAnswer(applicationId, "apply-4", "Why do you want to join?", "For the challenge.");
        UUID planId = createApprovedPlan(applicationId);

        // The answer changes after approval: the approval statement is no
        // longer true, so the package returns to human review.
        mvc.perform(put("/api/v1/application-answers/{id}", answerId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answerText\":\"A completely different answer\",\"confirmForAutofill\":true}"))
                .andExpect(status().isOk());

        Map<String, Object> plan = jdbc.queryForMap(
                "select status, submit_approved from automation_plans where id = ?", planId);
        assertThat(plan.get("status")).isEqualTo("AWAITING_APPROVAL");
        assertThat(plan.get("submit_approved")).isEqualTo(false);

        // Re-approval is refused until the package reflects the change again.
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isConflict());
    }

    @Test
    void withdrawingACvReviewInvalidatesAnApprovedPackage() throws Exception {
        UUID applicationId = seedApplication("apply-5", fixtureUrl(105));
        UUID cvVersionId = seedReviewedCv(applicationId, "apply-5");
        seedApprovedLetter(applicationId, 1, "I would love to join Fixture Co.");
        seedConfirmedAnswer(applicationId, "apply-5", "Why do you want to join?", "For the challenge.");
        UUID planId = createApprovedPlan(applicationId);

        mvc.perform(put("/api/v1/resume-intelligence/cv/{id}/review", cvVersionId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":false}"))
                .andExpect(status().isOk());

        Map<String, Object> plan = jdbc.queryForMap(
                "select status, submit_approved from automation_plans where id = ?", planId);
        assertThat(plan.get("status")).isEqualTo("AWAITING_APPROVAL");
        assertThat(plan.get("submit_approved")).isEqualTo(false);
    }

    // ── duplicate protection ─────────────────────────────────────────

    @Test
    void aCrossSourceDuplicateIsRefusedAndIdentifiedOnlyToItsOwner() throws Exception {
        UUID jobA = seedJob("apply-dup-a", APPLY_URL);
        UUID applicationId = seedApplicationForJob(jobA);
        // The same requisition seen through another board row.
        UUID jobB = seedJob("apply-dup-b", "https://job-boards.greenhouse.io/fixtureco/jobs/42");

        assertThat(identity.identityKeyForJob(jobA)).isEqualTo(identity.identityKeyForJob(jobB));
        assertThatThrownBy(() -> pipeline.createApplicationFromMatch(devProfile, jobB))
                .isInstanceOf(DuplicateApplicationException.class)
                .satisfies(e -> assertThat(((DuplicateApplicationException) e).existingApplicationId())
                        .isEqualTo(applicationId));

        // The HTTP surface answers 409 with the caller's OWN record only.
        MvcResult result = mvc.perform(post("/api/v1/jobs/{id}/apply", jobB).with(csrf()).session(session))
                .andExpect(status().isConflict()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains(applicationId.toString());
    }

    @Test
    void concurrentDuplicateCreationCommitsExactlyOneApplication() throws Exception {
        UUID jobA = seedJob("apply-race-a", "https://boards.greenhouse.io/fixtureco/jobs/77");
        UUID jobB = seedJob("apply-race-b", "https://job-boards.greenhouse.io/fixtureco/jobs/77");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Runnable attemptA = attempt(devProfile, jobA, start);
        Runnable attemptB = attempt(devProfile, jobB, start);
        pool.submit(attemptA);
        pool.submit(attemptB);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Integer live = jdbc.queryForObject("""
                select count(*) from applications where profile_id = ? and status not in ('FAILED','WITHDRAWN')
                and job_id in (?, ?)
                """, Integer.class, devProfile, jobA, jobB);
        assertThat(live).as("exactly one live application for one role across sources").isEqualTo(1);
        // And the persistence layer holds the line even if both callers raced.
        Integer liveByIdentity = jdbc.queryForObject("""
                select count(*) from applications where profile_id = ? and identity_key = 'greenhouse:fixtureco:77'
                and status not in ('FAILED','WITHDRAWN')
                """, Integer.class, devProfile);
        assertThat(liveByIdentity).isEqualTo(1);
    }

    private Runnable attempt(UUID profileId, UUID jobId, CountDownLatch start) {
        return () -> {
            try {
                start.await();
                pipeline.createApplicationFromMatch(profileId, jobId);
            } catch (DuplicateApplicationException expectedForTheLoser) {
                // exactly one of the two attempts may win
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    void trackingOnlyUrlDifferencesAreNotDistinctRoles() {
        assertThat(identity.identityKeyForJob(seedJob("apply-track-1",
                        "https://boards.greenhouse.io/fixtureco/jobs/88?utm_source=mail")))
                .isEqualTo(identity.identityKeyForJob(seedJob("apply-track-2",
                        "https://boards.greenhouse.io/fixtureco/jobs/88?gclid=abc")));
    }

    // ── idempotent retries ───────────────────────────────────────────

    @Test
    void repeatedRequestsCreateNeitherDuplicatePackagesNorDuplicateApprovals() throws Exception {
        UUID applicationId = seedApplication("apply-idem", fixtureUrl(107));
        seedReviewedCv(applicationId, "apply-idem");
        seedApprovedLetter(applicationId, 1, "I would love to join Fixture Co.");
        seedConfirmedAnswer(applicationId, "apply-idem", "Why do you want to join?", "For the challenge.");

        // A client retry of the package request reuses the identical plan —
        // one application, one package, one plan row.
        UUID first = packageId(applicationId);
        UUID retry = packageId(applicationId);
        assertThat(retry).as("a retried request reuses the identical plan").isEqualTo(first);
        Integer planCount = jdbc.queryForObject(
                "select count(*) from automation_plans where application_id = ?", Integer.class, applicationId);
        assertThat(planCount).isEqualTo(1);

        // A retried approval does not double-approve: the second attempt finds
        // no plan awaiting approval, is refused, and the single approval stands.
        jdbc.update("update automation_plans set status = 'AWAITING_APPROVAL' where id = ?", first);
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", first).with(csrf()).session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", first).with(csrf()).session(session))
                .andExpect(status().isConflict());
        Map<String, Object> plan = jdbc.queryForMap(
                "select status, submit_approved from automation_plans where id = ?", first);
        assertThat(plan.get("submit_approved")).isEqualTo(true);
        assertThat(plan.get("status")).isEqualTo("READY_TO_SUBMIT");
    }

    private UUID packageId(UUID applicationId) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/apply/applications/{id}/package", applicationId)
                        .with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("planId").asText());
    }

    // ── persistence-layer constraints ────────────────────────────────

    @Test
    void theLiveIdentityUniquenessIsADatabaseConstraintAndViolationsRollBack() {
        UUID jobA = seedJob("apply-constraint-a", "https://boards.greenhouse.io/fixtureco/jobs/108");
        UUID jobB = seedJob("apply-constraint-b", "https://job-boards.greenhouse.io/fixtureco/jobs/108");
        UUID winner = seedApplicationForJob(jobA);
        String identityKey = jdbc.queryForObject(
                "select identity_key from applications where id = ?", String.class, winner);

        // A second live row for the same role — seen through another board row —
        // is a database constraint violation, and the failed statement rolls
        // back completely. This is the persistence layer, not frontend code.
        assertThatThrownBy(() -> insertApplicationFrom(winner, jobB, identityKey))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        Integer live = jdbc.queryForObject(
                "select count(*) from applications where profile_id = ? and identity_key = ?",
                Integer.class, devProfile, identityKey);
        assertThat(live).as("the rolled-back violation left exactly one live application").isEqualTo(1);

        // The same insert with a different identity succeeds: the violation
        // above was the uniqueness rule, not the row shape.
        insertApplicationFrom(winner, jobB, "greenhouse:fixtureco:constraint-sentinel-108");

        // The index covers LIVE applications only: after a withdrawal, a new
        // application for the same role is legitimate.
        jdbc.update("update applications set status = 'WITHDRAWN' where id = ?", winner);
        insertApplicationFrom(winner, seedJob("apply-constraint-c", fixtureUrl(109)), identityKey);
    }

    /** Raw insert copying owner and mode from a template row — bypasses every service guard on purpose. */
    private void insertApplicationFrom(UUID templateRowId, UUID jobId, String identityKey) {
        jdbc.update("""
                insert into applications (id, job_id, profile_id, mode, identity_key, status)
                select ?, ?, profile_id, mode, ?, 'READY_TO_APPLY' from applications where id = ?
                """, UuidV7.generate(), jobId, identityKey, templateRowId);
    }

    // ── owner isolation ──────────────────────────────────────────────

    @Test
    void anotherUsersApplicationIsInvisible() throws Exception {
        UUID applicationId = seedApplication("apply-iso", fixtureUrl(106));
        seedReviewedCv(applicationId, "apply-iso");

        mvc.perform(get("/api/v1/apply/applications/{id}/readiness", applicationId).session(session))
                .andExpect(status().isOk()); // the owner sees it

        // A second candidate exists with their own profile.
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, 'other@apply.test', null, 'Other Candidate', 'FIREBASE') on conflict (email) do nothing
                """, UuidV7.generate());
        UUID otherUser = jdbc.queryForObject("select id from users where email = 'other@apply.test'", UUID.class);
        UUID otherProfile = profiles.findByUserId(otherUser).map(p -> p.id())
                .orElseGet(() -> profiles.createProfile(otherUser, null, null, null, null, null, null, null));

        // The stranger's identity sees the application as nonexistent — the
        // same failure as an unknown id — and never as someone else's record.
        assertThatThrownBy(() -> readiness.evaluate(otherProfile, applicationId))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(identity.findById(otherProfile, applicationId)).isNull();
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private UUID createApprovedPlan(UUID applicationId) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/apply/applications/{id}/package", applicationId)
                        .with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn();
        UUID planId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .get("planId").asText());
        jdbc.update("update automation_plans set status = 'AWAITING_APPROVAL' where id = ?", planId);
        MvcResult approval = mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId)
                        .with(csrf()).session(session)).andReturn();
        assertThat(approval.getResponse().getStatus())
                .as("approve-submit response body: %s", approval.getResponse().getContentAsString())
                .isEqualTo(200);
        return planId;
    }

    /** Each test uses its own requisition so tests never collide on identity. */
    private static String fixtureUrl(int requisition) {
        return "https://boards.greenhouse.io/fixtureco/jobs/" + requisition;
    }

    private UUID seedJob(String externalId, String applicationUrl) {
        UUID jobId = UuidV7.generate();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, ?, ?, 'Fixture Co', 'Platform Engineer', 'Build services.', '{}', 'SCORED', ?, ?, ?)
                """, jobId, SOURCE, externalId, "dedup-" + externalId, "hash-" + externalId,
                applicationUrl, applicationUrl);
        return jobId;
    }

    private UUID seedApplication(String externalId, String applicationUrl) {
        UUID jobId = seedJob(externalId, applicationUrl);
        return seedApplicationForJob(jobId);
    }

    private UUID seedApplicationForJob(UUID jobId) {
        var created = pipeline.createApplicationFromMatch(devProfile, jobId);
        return created.applicationId();
    }

    private UUID seedReviewedCv(UUID applicationId, String tag) throws Exception {
        byte[] pdf = ("pdf-bytes-" + tag).getBytes(StandardCharsets.UTF_8);
        String pdfSha = sha256Hex(pdf);
        UUID fileId = UuidV7.generate();
        UUID cvVersionId = UuidV7.generate();
        UUID jobId = jdbc.queryForObject("select job_id from applications where id = ?", UUID.class, applicationId);
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvVersionId + ".pdf", pdfSha, "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Tailored CV', 'CV text', '{}', false, 1, 'snap', ?, true, ?, now())
                """, cvVersionId, jobId, applicationId, devProfile, pdfSha, fileId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, 'hash-' || ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UuidV7.generate(), devProfile, jobId, applicationId, tag,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", "fact-validator-1"))), cvVersionId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?)
                on conflict (cv_version_id) do nothing
                """, cvVersionId, devProfile, pdfSha);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);
        return cvVersionId;
    }

    private void seedCvWithoutReview(UUID applicationId, String tag) throws Exception {
        byte[] pdf = ("pdf-bytes-" + tag).getBytes(StandardCharsets.UTF_8);
        String pdfSha = sha256Hex(pdf);
        UUID fileId = UuidV7.generate();
        UUID cvVersionId = UuidV7.generate();
        UUID jobId = jdbc.queryForObject("select job_id from applications where id = ?", UUID.class, applicationId);
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvVersionId + ".pdf", pdfSha, "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Tailored CV', 'CV text', '{}', false, 1, 'snap', ?, true, ?, now())
                """, cvVersionId, jobId, applicationId, devProfile, pdfSha, fileId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, 'hash-' || ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UuidV7.generate(), devProfile, jobId, applicationId, tag,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", "fact-validator-1"))), cvVersionId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);
    }

    private void seedApprovedLetter(UUID applicationId, int version, String body) throws Exception {
        UUID jobId = jdbc.queryForObject("select job_id from applications where id = ?", UUID.class, applicationId);
        jdbc.update("""
                insert into cover_letters (id, profile_id, job_id, application_id, version, title, body_markdown,
                                           content_sha256, claims_validation, is_approved, origin, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'Cover letter', ?, ?, ?::jsonb, true, 'GENERATED', now(), now())
                """, UuidV7.generate(), devProfile, jobId, applicationId, version, body,
                com.personal.jobagent.automation.ExecutionPackageService.Sha256.of(body),
                json.writeValueAsString(Map.of("passed", true, "validator_version", "fact-validator-1")));
    }

    private UUID seedConfirmedAnswer(UUID applicationId, String tag, String question, String answer) {
        UUID jobId = jdbc.queryForObject("select job_id from applications where id = ?", UUID.class, applicationId);
        UUID answerId = UuidV7.generate();
        jdbc.update("""
                insert into application_answers (id, profile_id, job_id, application_id, question_text, question_type,
                                                 answer_text, confidence, status, validation_notes, human_confirmed,
                                                 answer_origin, created_at, updated_at)
                values (?, ?, ?, ?, ?, 'GENERAL_OPEN_ENDED', ?, 0.95, 'ANSWERED', '{}'::jsonb, true,
                        'CANDIDATE_CONFIRMED', now(), now())
                """, answerId, devProfile, jobId, applicationId, question, answer);
        return answerId;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
