package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.email.EmailIntelligenceService.ConnectorMessage;
import com.personal.jobagent.security.SecretCipher;
import com.personal.jobagent.track.ReceiptVerificationPolicy.SenderAuthentication;
import com.personal.jobagent.track.receipt.CanonicalJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3I Phase 2, Gate 6: durable receipt linking and reconciliation end to end on real
 * PostgreSQL 16, through the production routes — candidate approval, worker claim, the
 * PRE_SUBMIT_ATTEMPTING fence, worker completion with Universal evidence v2, mailbox-connector
 * ingestion, candidate review, and the TRACK and receipt reads.
 *
 * <p>The mailbox connector is a FIXTURE ({@link EmailIntelligenceService#ingestFromMailboxConnector}
 * called directly with provider-style authentication); the "worker" is this test holding the
 * worker token and reporting synthetic, well-formed evidence. Nothing contacts an employer.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.events.dispatch-interval-ms=3600000",
        "app.worker-event-token=" + ReceiptReconciliationIT.WORKER_TOKEN,
        "app.secrets.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
@Testcontainers
class ReceiptReconciliationIT {

    static final String WORKER_TOKEN = "reconciliation-it-worker-token";
    private static final String DEV = "dev@example.local";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private EmailIntelligenceService emails;
    @Autowired private SecretCipher cipher;

    private static final UUID SOURCE = UUID.randomUUID();
    private MockHttpSession dev;
    private UUID devProfile;
    private MockHttpSession other;
    private UUID otherProfile;

    @BeforeEach
    void setUp() throws Exception {
        dev = login(DEV);
        devProfile = profileOf(DEV);
        String hash = jdbc.queryForObject("select password_hash from users where email = ?", String.class, DEV);
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values ('00000000-0000-7000-8000-0000000000e9', 'other-recon@example.local', ?, 'Other', 'LOCAL')
                on conflict (email) do update set password_hash = excluded.password_hash
                """, hash);
        jdbc.update("""
                insert into profiles (id, user_id, setup_status, phone, location, links)
                values ('00000000-0000-7000-8000-0000000000e8', '00000000-0000-7000-8000-0000000000e9', 'READY', null, null, '{}')
                on conflict (id) do nothing
                """);
        otherProfile = UUID.fromString("00000000-0000-7000-8000-0000000000e8");
        other = login("other-recon@example.local");
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'recon-it', 'Recon IT', '{}', 'DISCOVERY_ONLY') on conflict (id) do nothing
                """, SOURCE);
    }

    // ── fixtures and production routes ──────────────────────────────────

    private MockHttpSession login(String email) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private UUID profileOf(String email) {
        return jdbc.queryForObject("select p.id from profiles p join users u on u.id = p.user_id where u.email = ?", UUID.class, email);
    }

    /** One Universal application on its own employer host, with the latest plan's ids. */
    private record App(UUID jobId, UUID applicationId, UUID planId, String jobUrl, String host) {}

    private App universalApplication(UUID profile) throws Exception {
        String slug = UUID.randomUUID().toString().substring(0, 8);
        String host = "careers.acme-" + slug + ".example";
        String jobUrl = "https://" + host + "/jobs/505";
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title, location_raw, description_text,
                                  skills_extracted, status, content_hash, application_url)
                values (?, ?, ?, ?, ?, 'Platform Engineer', 'Remote', 'x', '{}'::text[], 'DISCOVERED', ?, ?)
                """, jobId, SOURCE, "ext-" + jobId, "dedup-" + jobId, "Acme " + slug, "hash-" + jobId, jobUrl);
        UUID appId = UUID.randomUUID();
        jdbc.update("insert into applications (id, job_id, profile_id, mode, identity_key, status) values (?, ?, ?, 'ASSISTED', ?, 'READY_TO_APPLY')",
                appId, jobId, profile, "identity-" + appId);
        UUID cvId = seedCv(profile, jobId, appId);
        String cvSha = jdbc.queryForObject("select content_sha256 from cv_versions where id = ?", String.class, cvId);
        Map<String, Object> plan = Map.of(
                "planType", "UNIVERSAL",
                "correlation", Map.of("applicationId", appId.toString(), "jobId", jobId.toString()),
                "package", Map.of("cv", Map.of("versionId", cvId.toString(), "sha256", cvSha, "fileName", "cv.pdf", "byteSize", 1024)),
                "steps", List.of(Map.of("id", "apply", "type", "UNIVERSAL_APPLY", "params", Map.of())));
        UUID planId = UUID.randomUUID();
        jdbc.update("""
                insert into automation_plans(id, profile_id, application_id, target_url, status, submit_approved, idempotency_key, plan, result)
                values (?, ?, ?, ?, 'AWAITING_APPROVAL', false, ?, ?::jsonb, '[{"type":"UNIVERSAL_OUTCOME","requiredGaps":[]}]'::jsonb)
                """, planId, profile, appId, jobUrl, "universal:recon:" + appId, json.writeValueAsString(plan));
        return new App(jobId, appId, planId, jobUrl, host);
    }

    /** An approved, validated tailored CV for the application, as preparation leaves it (same shape as ApplicationReceiptIT). */
    private UUID seedCv(UUID profileId, UUID jobId, UUID applicationId) throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID cvVersionId = UUID.randomUUID();
        byte[] bytes = ("PDF-CV-" + cvVersionId).getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        String email = jdbc.queryForObject("select u.email from users u join profiles p on p.user_id = u.id where p.id = ?",
                String.class, profileId);
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvVersionId + ".pdf", sha, "application/pdf", (long) bytes.length, "TAILORED_CV", bytes);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Tailored CV', 'CV markdown', '{"passed":true}', true, 1, 'snap', ?, true, ?, now())
                """, cvVersionId, jobId, applicationId, profileId, sha, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UUID.randomUUID(), profileId, jobId, applicationId, "hash-" + cvVersionId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", com.personal.jobagent.documents.DocumentFactValidator.VERSION))), cvVersionId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, ?, ?) on conflict (cv_version_id) do nothing
                """, cvVersionId, profileId, email, sha);
        return cvVersionId;
    }

    private void approveQueueClaim(App a, MockHttpSession session) throws Exception {
        mvc.perform(post("/api/v1/automation/plans/" + a.planId() + "/approve-submit").session(session).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/" + a.planId() + "/prepare-submit").session(session).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/automation/plans/" + a.planId() + "/claim").header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RUNNING"));
    }

    private int fence(App a) throws Exception {
        return mvc.perform(post("/api/v1/automation/plans/" + a.planId() + "/steps")
                        .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("index", 99, "stepId", "pre-submit-attempting", "status", "RUNNING",
                                "result", Map.of("phase", "PRE_SUBMIT_ATTEMPTING")))))
                .andReturn().getResponse().getStatus();
    }

    private Instant fenceTime(App a) {
        return jdbc.queryForObject("select created_at from automation_steps where plan_id = ? and step_key = 'pre-submit-attempting'",
                Timestamp.class, a.planId()).toInstant();
    }

    /** Worker-built Universal evidence v2 for this attempt, sealed with its canonical digest after {@code edit}. */
    private String evidenceV2(App a, String reference, Consumer<ObjectNode> edit) {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", "robin-submission-evidence-v2");
        root.put("atsPlatform", "UNIVERSAL");
        ObjectNode corr = root.putObject("attemptCorrelation");
        corr.put("planId", a.planId().toString());
        corr.put("applicationId", a.applicationId().toString());
        corr.put("jobId", a.jobId().toString());
        root.put("observedAt", Instant.now().toString());
        ArrayNode hops = root.putArray("redirectChain");
        hops.addObject().put("host", a.host()).put("status", 200);
        root.put("finalOrigin", "https://" + a.host());
        if (reference == null) root.putNull("confirmationNumber"); else root.put("confirmationNumber", reference);
        root.put("confirmationText", "Thank you for applying. Your application has been received.");
        ObjectNode docs = root.putObject("documents");
        docs.putObject("cv").put("artifactSha256", "b".repeat(64)).put("formAttachment", "ATTACHED")
                .put("submissionRequestFilePart", "OBSERVED").put("uploadVerification", "FILE_PART_OBSERVED")
                .put("bytesTransmissionObserved", false);
        docs.putObject("coverLetter").putNull("artifactSha256").put("formAttachment", "NO_FIELD")
                .put("submissionRequestFilePart", "NOT_OBSERVED").put("uploadVerification", "NO_FIELD")
                .put("bytesTransmissionObserved", false);
        root.put("submittedCvSha256", "b".repeat(64));
        root.putNull("submittedCoverLetterSha256");
        root.put("employerVerification", "NOT_VERIFIED_BY_BROWSER");
        edit.accept(root);
        root.put("evidenceSha256", sha256(CanonicalJson.write(root)));
        return CanonicalJson.write(root);
    }

    private int completeSubmitted(App a, String reference, String rawEvidence) throws Exception {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", "SUBMISSION_EVIDENCE");
        entry.put("planType", "UNIVERSAL");
        entry.put("category", "CONFIRMED");
        entry.put("confirmed", true);
        entry.put("evidence", "Browser observed Universal submission via HTTP 200 and confirmation DOM.");
        if (reference != null) entry.put("confirmationNumber", reference);
        entry.put("signals", List.of("UNIVERSAL_SUBMISSION_RESPONSE_2XX", "UNIVERSAL_CONFIRMATION_DOM_MATCHED"));
        entry.put("rawEvidence", rawEvidence);
        return mvc.perform(post("/api/v1/automation/plans/" + a.planId() + "/complete")
                        .header("Authorization", "Bearer " + WORKER_TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("outcome", "SUBMITTED",
                                "detail", "Submission confirmed: confirmation page rendered", "result", List.of(entry)))))
                .andReturn().getResponse().getStatus();
    }

    /** A Level B Universal submission through the production route, with a pre-submit fence. */
    private App submittedLevelB(String reference) throws Exception {
        App a = universalApplication(devProfile);
        approveQueueClaim(a, dev);
        assertThat(fence(a)).isEqualTo(204);
        assertThat(completeSubmitted(a, reference, evidenceV2(a, reference, r -> {}))).isEqualTo(204);
        return a;
    }

    private EmailIntelligenceService.IngestResult acknowledgement(UUID profile, String to, App a, String reference,
                                                                  Instant receivedAt, String messageId) {
        String body = "Hello, we have received your application for Platform Engineer. " + a.jobUrl()
                + (reference == null ? "" : " Application reference: " + reference);
        String domain = a.host().substring("careers.".length());
        return emails.ingestFromMailboxConnector(profile, new ConnectorMessage("gmail", messageId,
                "Talent <talent@" + domain + ">", to, "Application received", body, receivedAt, to,
                new SenderAuthentication("pass", domain, "pass", "pass")));
    }

    private EmailIntelligenceService.IngestResult acknowledgement(App a, String reference, Instant receivedAt) {
        return acknowledgement(devProfile, DEV, a, reference, receivedAt, "ack-" + UUID.randomUUID());
    }

    private JsonNode track(UUID app, MockHttpSession session) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/applications/{id}/track", app).session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode receipt(UUID app) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/applications/{id}/receipt", app).session(dev)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> links(UUID app) {
        return jdbc.queryForList("select * from application_receipt_links where application_id = ? order by linked_at, id", app);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ── 1, 3: Level B, then a verified C1 acknowledgement → RECEIVED (ACKNOWLEDGED, verified) ──

    @Test
    void levelBThenVerifiedAcknowledgementAdvancesToReceivedWithADurableLink() throws Exception {
        App a = submittedLevelB("APP-50599");
        JsonNode before = track(a.applicationId(), dev);
        assertThat(before.get("status").asText()).isEqualTo("APPLIED");
        assertThat(before.get("basis").asText()).isEqualTo("UNVERIFIED");
        assertThat(before.at("/submission/verificationLevel").asText()).isEqualTo("BROWSER_OBSERVED");
        assertThat(before.get("needsReview").asBoolean()).isFalse();
        assertThat(before.at("/submissionEvidence/anchor/source").asText()).isEqualTo("PRE_SUBMIT_ATTEMPT");
        assertThat(before.at("/submissionEvidence/employerVerified").asBoolean()).isFalse();
        assertThat(receipt(a.applicationId()).at("/browserEvidence/assessment").asText()).isEqualTo("BROWSER_OBSERVED_V2");

        var ingest = acknowledgement(a, "APP-50599", Instant.now().minusSeconds(30));
        assertThat(ingest.receiptVerification()).isEqualTo("VERIFIED");

        JsonNode after = track(a.applicationId(), dev);
        assertThat(after.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(after.get("basis").asText()).isEqualTo("VERIFIED");
        assertThat(after.at("/submission/state").asText()).isEqualTo("CONFIRMED");
        assertThat(after.at("/submission/verificationLevel").asText()).isEqualTo("EMPLOYER_EMAIL_VERIFIED");
        assertThat(after.at("/acknowledgement/state").asText()).isEqualTo("VERIFIED");
        assertThat(after.at("/receipts/0/link/referenceConsistency").asText()).isEqualTo("ALIGNED");
        assertThat(after.at("/receipts/0/link/temporalStatus").asText()).isEqualTo("PLAUSIBLE");
        assertThat(after.at("/receipts/0/evidenceAtSource").asText()).isEqualTo("PROVIDER_RECEIPT");

        List<Map<String, Object>> rows = links(a.applicationId());
        assertThat(rows).hasSize(1);
        Map<String, Object> link = rows.get(0);
        assertThat(link.get("origin")).isEqualTo("INGESTION");
        assertThat(link.get("plan_id")).isEqualTo(a.planId());
        assertThat(link.get("submission_evidence_id")).isEqualTo(
                jdbc.queryForObject("select id from submission_evidence where plan_id = ?", UUID.class, a.planId()));
        assertThat(link.get("receipt_reference")).isEqualTo("APP-50599");
        assertThat(link.get("submission_reference")).isEqualTo("APP-50599");
        assertThat(link.get("anchor_source")).isEqualTo("PRE_SUBMIT_ATTEMPT");
        assertThat(((Timestamp) link.get("anchor_time")).toInstant()).isEqualTo(fenceTime(a));
        assertThat(link.get("evidence_time_source")).isEqualTo("PROVIDER_RECEIPT");
        assertThat(link.get("provider_id")).isEqualTo("gmail");
        assertThat(String.valueOf(link.get("evidence_sha256")).trim()).isEqualTo(
                jdbc.queryForObject("select raw_evidence_sha256 from submission_evidence where plan_id = ?", String.class, a.planId()).trim());
    }

    // ── 2: Level B, then an unverified email ──────────────────────────────

    @Test
    void levelBThenAnUnverifiedEmailStaysUnverified() throws Exception {
        App a = submittedLevelB("APP-50600");
        String domain = a.host().substring("careers.".length());
        var failing = emails.ingestFromMailboxConnector(devProfile, new ConnectorMessage("gmail", "unverified-" + UUID.randomUUID(),
                "Talent <talent@" + domain + ">", DEV, "Application received",
                "We have received your application. " + a.jobUrl(), Instant.now().minusSeconds(10), DEV,
                new SenderAuthentication("fail", domain, "fail", "fail")));
        assertThat(failing.receiptVerification()).isEqualTo("UNVERIFIED");
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(t.get("basis").asText()).isEqualTo("UNVERIFIED");
        assertThat(t.at("/acknowledgement/state").asText()).isEqualTo("UNVERIFIED");
        assertThat(t.at("/submission/verificationLevel").asText()).isEqualTo("BROWSER_OBSERVED");
    }

    // ── 4, 5, 8: later D-level stages, rejection, and out-of-order messages ──

    @Test
    void laterInterviewThenRejectionPreserveSubmissionEvidenceAndOlderMessagesNeverWin() throws Exception {
        App a = submittedLevelB("APP-50601");
        String domain = a.host().substring("careers.".length());
        // An interview invitation arrives first; the candidate confirms it.
        var interview = emails.ingestFromMailboxConnector(devProfile, new ConnectorMessage("gmail", "iv-" + UUID.randomUUID(),
                "Talent <talent@" + domain + ">", DEV, "Interview invitation",
                "We would like to invite you to an interview for the role. " + a.jobUrl(), Instant.now().minusSeconds(60), DEV,
                new SenderAuthentication("pass", domain, "pass", "pass")));
        assertThat(track(a.applicationId(), dev).at("/pending/stage").asText()).isEqualTo("INTERVIEW");
        mvc.perform(post("/api/v1/track/emails/{id}/review", interview.emailId()).session(dev).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "CONFIRM", "applicationId", a.applicationId().toString(),
                                "category", "INTERVIEW_INVITATION"))))
                .andExpect(status().isOk());
        assertThat(track(a.applicationId(), dev).get("status").asText()).isEqualTo("INTERVIEW");

        // An acknowledgement dated earlier, delivered later (out of order): verified, but never pulls the stage back.
        acknowledgement(a, "APP-50601", Instant.now().minusSeconds(120));
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("INTERVIEW");
        assertThat(t.at("/acknowledgement/state").asText()).isEqualTo("VERIFIED");

        // A later rejection ends progress; the submission record is untouched.
        mvc.perform(post("/api/v1/applications/{id}/track/reports", a.applicationId()).session(dev).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"REJECTED\"}"))
                .andExpect(status().isOk());
        JsonNode rejected = track(a.applicationId(), dev);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.at("/robinSubmission/submitted").asBoolean()).isTrue();
        assertThat(rejected.at("/submission/state").asText()).isEqualTo("CONFIRMED");
        assertThat(count("select count(*) from submission_evidence where plan_id = ?", a.planId())).isEqualTo(1);
        assertThat(receipt(a.applicationId()).get("verificationLevel").asText()).isEqualTo("BROWSER_OBSERVED");
    }

    // ── 6: withdrawal preserves the submission evidence ───────────────────

    @Test
    void withdrawalPreservesTheBrowserObservedSubmission() throws Exception {
        App a = submittedLevelB("APP-50602");
        mvc.perform(post("/api/v1/applications/{id}/track/reports", a.applicationId()).session(dev).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"WITHDRAWN\"}"))
                .andExpect(status().isOk());
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(t.at("/submission/verificationLevel").asText()).isEqualTo("BROWSER_OBSERVED");
        assertThat(count("select count(*) from submission_evidence where plan_id = ?", a.planId())).isEqualTo(1);
    }

    // ── 7, 17: duplicate and concurrent delivery ──────────────────────────

    @Test
    void duplicateAndConcurrentDeliveriesProduceExactlyOneReceiptAndOneLink() throws Exception {
        App a = submittedLevelB("APP-50603");
        String messageId = "dup-" + UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(40);
        var first = acknowledgement(devProfile, DEV, a, "APP-50603", at, messageId);
        var replay = acknowledgement(devProfile, DEV, a, "APP-50603", at, messageId);
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();

        String concurrentId = "concurrent-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return acknowledgement(devProfile, DEV, a, "APP-50603", at.plusSeconds(5), concurrentId);
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            try {
                f.get(60, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // a losing concurrent insert may surface as a constraint error; the database decides the winner
            }
        }
        pool.shutdown();
        assertThat(count("select count(*) from emails where profile_id = ? and message_id in (?, ?)", devProfile, messageId, concurrentId))
                .isEqualTo(2);
        assertThat(count("select count(*) from application_receipts where application_id = ?", a.applicationId())).isEqualTo(2);
        assertThat(count("select count(*) from application_receipt_links where application_id = ?", a.applicationId())).isEqualTo(2);
        assertThat(count("""
                select count(*) from application_receipt_links l where l.application_id = ?
                  and not exists (select 1 from application_receipt_links s where s.supersedes_link_id = l.id)""",
                a.applicationId())).isEqualTo(2);
    }

    // ── 9: conflicting confirmation identifiers, then candidate resolution ──

    @Test
    void conflictingConfirmationReferencesNeedReviewAndTheCandidateResolvesIt() throws Exception {
        App a = submittedLevelB("APP-50604");
        var ingest = acknowledgement(a, "APP-99999", Instant.now().minusSeconds(20));
        assertThat(ingest.receiptVerification()).isEqualTo("VERIFIED");
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(t.at("/submission/state").asText()).isEqualTo("CONFLICTING");
        assertThat(t.at("/acknowledgement/state").asText()).isNotEqualTo("VERIFIED");
        assertThat(t.at("/conflicts/0/code").asText()).isEqualTo("SUBMISSION_REFERENCE_CONFLICT");
        assertThat(t.at("/conflicts/0/references").toString()).contains("APP-99999").contains("APP-50604");
        Map<String, Object> link = links(a.applicationId()).get(0);
        assertThat(link.get("reference_consistency")).isEqualTo("CONFLICTING");
        assertThat(link.get("receipt_reference")).isEqualTo("APP-99999");
        assertThat(link.get("submission_reference")).isEqualTo("APP-50604");
        // Neither value was replaced.
        assertThat(jdbc.queryForObject("select employer_confirmation_id from submission_evidence where plan_id = ?", String.class,
                a.planId())).isEqualTo("APP-50604");
        String receiptId = t.at("/receipts/0/id").asText();

        mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", a.applicationId(), receiptId).session(dev).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        JsonNode resolved = track(a.applicationId(), dev);
        assertThat(resolved.get("conflicts")).isEmpty();
        assertThat(resolved.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(resolved.get("basis").asText()).isEqualTo("CANDIDATE_CONFIRMED");
        assertThat(resolved.at("/acknowledgement/state").asText()).isEqualTo("UNVERIFIED");
        List<Map<String, Object>> rows = links(a.applicationId());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("origin")).isEqualTo("CANDIDATE_REVIEW");
        assertThat(rows.get(1).get("decision")).isEqualTo("CANDIDATE_CONFIRMED");
        assertThat(rows.get(1).get("supersedes_link_id")).isEqualTo(rows.get(0).get("id"));
        assertThat(rows.get(1).get("receipt_reference")).isEqualTo("APP-99999");
        assertThat(resolved.at("/receipts/0/link/decision").asText()).isEqualTo("CANDIDATE_CONFIRMED");
    }

    // ── 10, 11: receipts before the submission attempt, and in the future ──

    @Test
    void anAcknowledgementBeforeTheSubmitAttemptAndAnImpossibleFutureOneAreNeverVerified() throws Exception {
        App a = submittedLevelB("APP-50605");
        Instant fence = fenceTime(a);
        var early = acknowledgement(a, "APP-50605", fence.minus(Duration.ofHours(2)));
        assertThat(early.receiptVerification()).isEqualTo("UNVERIFIED");
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(t.at("/conflicts/0/code").asText()).isEqualTo("RECEIPT_TEMPORAL_CONFLICT");
        Map<String, Object> link = links(a.applicationId()).get(0);
        assertThat(link.get("temporal_status")).isEqualTo("BEFORE_SUBMISSION");
        assertThat(link.get("anchor_source")).isEqualTo("PRE_SUBMIT_ATTEMPT");
        assertThat(((Number) link.get("clock_skew_seconds")).intValue()).isEqualTo(300);

        App b = submittedLevelB("APP-50606");
        var future = acknowledgement(b, "APP-50606", Instant.now().plus(Duration.ofHours(2)));
        assertThat(future.receiptVerification()).isEqualTo("REJECTED");
        JsonNode fb = track(b.applicationId(), dev);
        assertThat(fb.get("status").asText()).isEqualTo("APPLIED");
        assertThat(fb.at("/acknowledgement/state").asText()).isEqualTo("NONE");
        assertThat(links(b.applicationId()).get(0).get("temporal_status")).isEqualTo("FUTURE_DATED");
    }

    // ── 12: cross-profile receipt access ──────────────────────────────────

    @Test
    void anotherProfileCanNeitherReadNorReviewNorAttachToTheseReceipts() throws Exception {
        App a = submittedLevelB("APP-50607");
        acknowledgement(a, "APP-50607", Instant.now().minusSeconds(15));
        String receiptId = track(a.applicationId(), dev).at("/receipts/0/id").asText();

        mvc.perform(get("/api/v1/applications/{id}/track", a.applicationId()).session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/{id}/receipt", a.applicationId()).session(other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", a.applicationId(), receiptId).session(other)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isNotFound());
        // The other profile's mailbox quoting this application's URL and reference links to nothing of the first profile.
        var foreign = acknowledgement(otherProfile, "other-recon@example.local", a, "APP-50607", Instant.now().minusSeconds(5),
                "foreign-" + UUID.randomUUID());
        assertThat(foreign.applicationId()).isNull();
        assertThat(count("select count(*) from application_receipt_links where profile_id = ?", otherProfile)).isZero();
        assertThat(count("select count(*) from application_receipt_links where application_id = ?", a.applicationId())).isEqualTo(1);
    }

    // ── 13, 15: tampered digest and cross-origin redirect in the evidence ──

    @Test
    void tamperedDigestOrOffHostRedirectEvidenceIsNeverABrowserObservation() throws Exception {
        App tampered = universalApplication(devProfile);
        approveQueueClaim(tampered, dev);
        assertThat(fence(tampered)).isEqualTo(204);
        String sealed = evidenceV2(tampered, "APP-50608", r -> {});
        String broken = sealed.replaceAll("\"evidenceSha256\":\"[0-9a-f]{64}\"", "\"evidenceSha256\":\"" + "0".repeat(64) + "\"");
        assertThat(completeSubmitted(tampered, "APP-50608", broken)).isEqualTo(204);
        JsonNode r = receipt(tampered.applicationId());
        assertThat(r.get("verificationLevel").asText()).isEqualTo("ATTEMPT_RECORDED");
        assertThat(r.at("/reconciliation/required").asBoolean()).isTrue();
        assertThat(r.at("/browserEvidence/assessment").asText()).isEqualTo("EVIDENCE_DIGEST_MISMATCH");
        JsonNode t = track(tampered.applicationId(), dev);
        assertThat(t.at("/submission/verificationLevel").asText()).isEqualTo("ATTEMPT_RECORDED");
        assertThat(t.at("/attention").toString()).contains("RECEIPT_UNVERIFIED");
        // Its reference is not trusted, so a differing acknowledgement reference is not compared.
        acknowledgement(tampered, "APP-11111", Instant.now().minusSeconds(5));
        assertThat(track(tampered.applicationId(), dev).at("/conflicts").toString()).doesNotContain("SUBMISSION_REFERENCE_CONFLICT");

        App redirected = universalApplication(devProfile);
        approveQueueClaim(redirected, dev);
        assertThat(fence(redirected)).isEqualTo(204);
        String offHost = evidenceV2(redirected, "APP-50609",
                root -> ((ArrayNode) root.get("redirectChain")).insertObject(0).put("host", "login.sso-elsewhere.example").put("status", 302));
        assertThat(completeSubmitted(redirected, "APP-50609", offHost)).isEqualTo(204);
        JsonNode rr = receipt(redirected.applicationId());
        assertThat(rr.at("/browserEvidence/assessment").asText()).isEqualTo("REDIRECT_OUTSIDE_TARGET_HOST");
        assertThat(rr.get("verificationLevel").asText()).isEqualTo("ATTEMPT_RECORDED");
    }

    // ── 14: missing document upload proof ─────────────────────────────────

    @Test
    void missingDocumentUploadProofIsShownHonestlyWithoutForcingReview() throws Exception {
        App a = universalApplication(devProfile);
        approveQueueClaim(a, dev);
        assertThat(fence(a)).isEqualTo(204);
        String evidence = evidenceV2(a, "APP-50610", root -> {
            ((ObjectNode) root.get("documents").get("cv")).put("submissionRequestFilePart", "NOT_OBSERVED")
                    .put("uploadVerification", "ATTACHED_NOT_CONFIRMED");
            root.putNull("submittedCvSha256");
        });
        assertThat(completeSubmitted(a, "APP-50610", evidence)).isEqualTo(204);
        JsonNode r = receipt(a.applicationId());
        assertThat(r.get("verificationLevel").asText()).isEqualTo("BROWSER_OBSERVED");
        assertThat(r.at("/browserEvidence/documents/0/kind").asText()).isEqualTo("cv");
        assertThat(r.at("/browserEvidence/documents/0/uploadVerification").asText()).isEqualTo("ATTACHED_NOT_CONFIRMED");
        assertThat(r.at("/browserEvidence/documents/0/bytesObserved").asBoolean()).isFalse();
        assertThat(r.at("/browserEvidence/employerVerification").asText()).isEqualTo("NOT_VERIFIED_BY_BROWSER");
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("unknowns").toString()).contains("could not confirm that your CV was included");
        assertThat(t.get("needsReview").asBoolean()).isFalse();
        assertThat(t.at("/submissionEvidence/documentsWithoutUploadProof").toString()).contains("cv");
    }

    // ── 16: crash recovery after PRE_SUBMIT_ATTEMPTING ────────────────────

    @Test
    void afterACrashPastTheFenceNothingRetriesAndALaterAcknowledgementIsCheckedAgainstTheFence() throws Exception {
        App a = universalApplication(devProfile);
        approveQueueClaim(a, dev);
        assertThat(fence(a)).isEqualTo(204);
        // A restarted worker cannot record a second attempt.
        assertThat(fence(a)).isEqualTo(409);
        // The restarted worker reports the unconfirmed outcome.
        mvc.perform(post("/api/v1/automation/plans/" + a.planId() + "/complete").header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("outcome", "RECONCILIATION_REQUIRED",
                                "detail", "a prior PRE_SUBMIT_ATTEMPTING checkpoint exists for this plan",
                                "result", List.of(Map.of("type", "SUBMISSION_RECONCILIATION", "planType", "UNIVERSAL",
                                        "category", "PRIOR_ATTEMPT_UNVERIFIED", "evidence", "crashed after the fence"))))))
                .andExpect(status().isNoContent());
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("READY_TO_APPLY");
        assertThat(t.at("/attention").toString()).contains("RECONCILIATION_REQUIRED");
        assertThat(t.at("/submissionEvidence/anchor/source").asText()).isEqualTo("PRE_SUBMIT_ATTEMPT");

        // The click did reach the employer: an authenticated acknowledgement after the fence verifies it.
        var ack = acknowledgement(a, null, Instant.now().minusSeconds(5));
        assertThat(ack.receiptVerification()).isEqualTo("VERIFIED");
        JsonNode acked = track(a.applicationId(), dev);
        assertThat(acked.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(acked.get("basis").asText()).isEqualTo("VERIFIED");
        Map<String, Object> link = links(a.applicationId()).get(0);
        assertThat(link.get("plan_id")).isEqualTo(a.planId());
        assertThat(link.get("submission_evidence_id")).isNull();
        assertThat(link.get("anchor_source")).isEqualTo("PRE_SUBMIT_ATTEMPT");
        // The plan is never moved or retried by any of this.
        assertThat(jdbc.queryForObject("select status from automation_plans where id = ?", String.class, a.planId()))
                .isEqualTo("RECONCILIATION_REQUIRED");
    }

    // ── 17: concurrent candidate reviews of the same receipt ──────────────

    @Test
    void concurrentReviewsOfOneReceiptAppendExactlyOneDecision() throws Exception {
        App a = submittedLevelB("APP-50611");
        acknowledgement(a, "APP-77711", Instant.now().minusSeconds(15));
        String receiptId = track(a.applicationId(), dev).at("/receipts/0/id").asText();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (String decision : List.of("CONFIRM", "REJECT")) {
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", a.applicationId(), receiptId)
                                .session(dev).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"decision\":\"" + decision + "\",\"expectedVersion\":0}"))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) statuses.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(count("select count(*) from application_receipt_links where application_id = ? and origin = 'CANDIDATE_REVIEW'",
                a.applicationId())).isEqualTo(1);
    }

    // ── manual application: the candidate's own statement and date anchor ──

    @Test
    void aCandidateStatementIsLinkedAsTheirsAndItsDateAnchorsLaterAcknowledgements() throws Exception {
        App a = universalApplication(devProfile);
        String reportedOn = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(3).toString();
        mvc.perform(post("/api/v1/applications/{id}/track/reports", a.applicationId()).session(dev).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SUBMITTED\",\"occurredOn\":\"" + reportedOn + "\"}"))
                .andExpect(status().isOk());
        Map<String, Object> statement = links(a.applicationId()).get(0);
        assertThat(statement.get("channel")).isEqualTo("CANDIDATE_ENTERED");
        assertThat(statement.get("receipt_verification")).isEqualTo("UNVERIFIED");
        assertThat(statement.get("reference_consistency")).isEqualTo("NOT_COMPARABLE");
        assertThat(statement.get("reference_detail")).isEqualTo("CANDIDATE_STATEMENT");
        assertThat(statement.get("evidence_time_source")).isEqualTo("CANDIDATE_SUPPLIED");
        assertThat(track(a.applicationId(), dev).at("/submission/verificationLevel").asText()).isEqualTo("CANDIDATE_REPORTED");

        // Robin never attempted this application, so the candidate's own date is the anchor: an
        // acknowledgement days before it is contradictory, one after it verifies normally.
        var early = acknowledgement(a, null, Instant.now().minus(Duration.ofDays(6)));
        assertThat(early.receiptVerification()).isEqualTo("UNVERIFIED");
        Map<String, Object> earlyLink = jdbc.queryForMap(
                "select * from application_receipt_links where application_id = ? and channel = 'MAILBOX_CONNECTOR'", a.applicationId());
        assertThat(earlyLink.get("anchor_source")).isEqualTo("CANDIDATE_REPORTED_SUBMISSION");
        assertThat(earlyLink.get("anchor_precision")).isEqualTo("DATE");
        assertThat(earlyLink.get("temporal_status")).isEqualTo("BEFORE_SUBMISSION");
        assertThat(track(a.applicationId(), dev).at("/conflicts/0/code").asText()).isEqualTo("RECEIPT_TEMPORAL_CONFLICT");

        App b = universalApplication(devProfile);
        mvc.perform(post("/api/v1/applications/{id}/track/reports", b.applicationId()).session(dev).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SUBMITTED\",\"occurredOn\":\"" + reportedOn + "\"}"))
                .andExpect(status().isOk());
        var later = acknowledgement(b, null, Instant.now().minusSeconds(30));
        assertThat(later.receiptVerification()).isEqualTo("VERIFIED");
        JsonNode t = track(b.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(t.get("basis").asText()).isEqualTo("VERIFIED");
    }

    // ── 18: historical V069 evidence and pre-V071 receipts ────────────────

    @Test
    void historicalV1EvidenceAndPreV071ReceiptsStillReconcile() throws Exception {
        App a = universalApplication(devProfile);
        approveQueueClaim(a, dev);
        assertThat(fence(a)).isEqualTo(204);
        assertThat(completeSubmitted(a, "APP-50612",
                "{\"atsPlatform\":\"UNIVERSAL\",\"schemaVersion\":\"robin-submission-evidence-v1\"}")).isEqualTo(204);
        JsonNode r = receipt(a.applicationId());
        assertThat(r.get("verificationLevel").asText()).isEqualTo("BROWSER_OBSERVED");
        assertThat(r.at("/browserEvidence/schemaKind").asText()).isEqualTo("V1");
        assertThat(r.at("/browserEvidence/documents/0/uploadVerification").asText()).isEqualTo("NOT_RECORDED");

        // A receipt written by pre-V071 code (no link row), verified under policy v1 with no time.
        UUID legacy = UUID.randomUUID();
        jdbc.update("""
                insert into application_receipts (id, profile_id, application_id, evidence_type, channel, external_reference,
                    evidence_time, evidence_time_precision, fingerprint, verification_status, policy_version, summary)
                values (?, ?, ?, 'EMPLOYER_ACKNOWLEDGEMENT_EMAIL', 'MAILBOX_CONNECTOR', 'APP-50612', null, 'UNKNOWN', ?,
                        'VERIFIED', 'track-receipt-policy-v1', 'legacy ack')
                """, legacy, devProfile, a.applicationId(), "legacy-" + legacy);
        JsonNode t = track(a.applicationId(), dev);
        assertThat(t.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(t.get("basis").asText()).isEqualTo("UNVERIFIED");
        assertThat(t.at("/receipts/0/link").isNull()).isTrue();

        // A new, timed acknowledgement links against the v1 evidence and verifies.
        acknowledgement(a, "APP-50612", Instant.now().minusSeconds(5));
        JsonNode after = track(a.applicationId(), dev);
        assertThat(after.at("/acknowledgement/state").asText()).isEqualTo("VERIFIED");
        Map<String, Object> link = links(a.applicationId()).get(0);
        assertThat(link.get("reference_consistency")).isEqualTo("ALIGNED");
        assertThat(String.valueOf(link.get("correlation"))).contains("V1");
    }
}
