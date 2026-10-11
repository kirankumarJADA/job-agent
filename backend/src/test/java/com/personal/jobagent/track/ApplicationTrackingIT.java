package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.ats.GreenhouseAdapter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.JobIdentity;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.email.EmailIntelligenceService.ConnectorMessage;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import com.personal.jobagent.security.UserRecord;
import com.personal.jobagent.track.ReceiptVerificationPolicy.SenderAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8.3 TRACK end to end on real PostgreSQL 16: the evidence-backed
 * timeline, receipt ingestion and verification, email association review,
 * reconciliation as evidence arrives, idempotency under concurrency,
 * transactional rollback, append-only history, two-account isolation and
 * compatibility with pre-8.3 records.
 *
 * <p>The "mailbox connector" here is a FIXTURE: {@link EmailIntelligenceService#ingestFromMailboxConnector}
 * is called directly with provider-style sender authentication. No live mailbox
 * is connected anywhere in this test or in the product. REAL_SUBMIT stays
 * hard-stopped — nothing here submits or sends anything.
 *
 * <p>Rate limiting is switched off for this class only because it issues more
 * TRACK writes per minute than the production rule allows; the "track" bucket
 * itself is covered by {@code RateLimitServiceTest}. The outbox dispatcher is
 * slowed to once an hour so background preparation (which would call model
 * providers) cannot race the assertions; each application's preparation outcome
 * is recorded deterministically by {@link #application(UUID)} instead, exactly as
 * the pipeline handler records it.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false", "app.rate-limit.enabled=false",
        "app.events.dispatch-interval-ms=3600000"})
@AutoConfigureMockMvc
@Testcontainers
class ApplicationTrackingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** The employer's application form, inspected read-only (same fixture shape as ApplyWorkflowIT). */
    static final String FORM_HTML = """
            <html><body><form id="application_form">
            <div class="field"><input class="requiredInput" required="" aria-hidden="true" tabindex="-1" value=""/>
              <label for="first_name">First name<span>*</span></label><input id="first_name" type="text"/></div>
            <div class="field"><label for="email">Email<span>*</span></label><input id="email" type="email"/></div>
            <div class="field upload"><label for="resume">Resume/CV</label><input id="resume" type="file" required/></div>
            </form></body></html>
            """;

    @TestConfiguration
    static class FixtureForm {
        @Bean
        @Primary
        GreenhouseAdapter fixtureGreenhouse() {
            return new GreenhouseAdapter(url -> FORM_HTML);
        }
    }

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000f830");
    private static final UUID TSENTA_SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000f831");

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private ProfileRepository profiles;
    @Autowired private ApplicationPipelineService pipeline;
    @Autowired private EmailIntelligenceService emails;
    @Autowired private ApplicationStatusService statusService;
    @SpyBean private TrackRepository trackRepository;

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
                values (?, 'GREENHOUSE', 'track-fixture', 'Track Fixture Board', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'TSENTA', 'track-tsenta', 'Tsenta listings', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, TSENTA_SOURCE);
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(trackRepository);
    }

    // ── receipts ─────────────────────────────────────────────────────────

    @Test
    void aForwardedAcknowledgementIsUnverifiedEvidenceAndNeverChangesRobinsStatus() throws Exception {
        String url = greenhouseUrl("fwd", 7001);
        UUID app = application(seedJob("fwd", "Fixture Co", "Platform Engineer", url, null));

        JsonNode ingest = ingestHttp(Map.of("messageId", "fwd-ack-" + UUID.randomUUID(), "from", "Fixture Co <no-reply@fixture.example>",
                "to", "dev@example.local", "subject", "Application received",
                "body", "Hi, we have received your application. Application reference: APP-552100. Role: " + url,
                "receivedAt", "2026-10-01T09:30:00Z"));
        assertThat(ingest.get("associationStatus").asText()).isEqualTo("LINKED");
        assertThat(ingest.get("applicationId").asText()).isEqualTo(app.toString());
        assertThat(ingest.get("receiptVerification").asText()).isEqualTo("UNVERIFIED");

        Map<String, Object> receipt = jdbc.queryForMap("""
                select evidence_type, channel, verification_status, external_reference, verification_reasons::text as reasons
                from application_receipts where application_id = ?""", app);
        assertThat(receipt.get("evidence_type")).isEqualTo("EMPLOYER_ACKNOWLEDGEMENT_EMAIL");
        assertThat(receipt.get("channel")).isEqualTo("CANDIDATE_FORWARDED");
        assertThat(receipt.get("verification_status")).isEqualTo("UNVERIFIED");
        assertThat(receipt.get("external_reference")).isEqualTo("APP-552100");
        assertThat(String.valueOf(receipt.get("reasons"))).contains("CHANNEL_NOT_AUTHENTICATED");

        JsonNode track = track(app);
        assertThat(track.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(track.get("basis").asText()).isEqualTo("UNVERIFIED");
        assertThat(track.get("label").asText()).contains("not verified");
        assertThat(track.at("/submission/state").asText()).isEqualTo("UNVERIFIED");
        assertThat(track.at("/robinSubmission/submitted").asBoolean()).isFalse();
        // Robin's internal workflow status is untouched and no status transition was written.
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, app)).isEqualTo("READY_TO_APPLY");
        assertThat(count("select count(*) from application_events where application_id = ? and type = 'STATUS_CHANGED'", app)).isZero();
        // The body was not kept — only a short redacted excerpt.
        Map<String, Object> stored = jdbc.queryForMap("select body_text, body_retained from emails where id = ?",
                UUID.fromString(ingest.get("emailId").asText()));
        assertThat(stored.get("body_text")).isEqualTo("");
        assertThat(stored.get("body_retained")).isEqualTo(false);
    }

    @Test
    void aFixtureConnectorAcknowledgementWithPassingSenderAuthenticationConfirmsSubmission() throws Exception {
        String url = greenhouseUrl("conn", 7002);
        UUID app = application(seedJob("conn", "Fixture Co", "Data Engineer", url, null));
        String body = "Thank you. We have received your application for Data Engineer. " + url;

        var verified = emails.ingestFromMailboxConnector(devProfile, new ConnectorMessage("fixture-provider",
                "conn-" + UUID.randomUUID(), "Fixture Co Talent <talent@mail.fixture.example>", "dev@example.local",
                "Application received", body, Instant.now().minus(1, ChronoUnit.HOURS), "dev@example.local",
                new SenderAuthentication("pass", "fixture.example", "pass", "pass")));
        assertThat(verified.receiptVerification()).isEqualTo("VERIFIED");

        JsonNode track = track(app);
        assertThat(track.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(track.get("basis").asText()).isEqualTo("VERIFIED");
        assertThat(track.at("/submission/state").asText()).isEqualTo("CONFIRMED");
        assertThat(track.at("/acknowledgement/state").asText()).isEqualTo("VERIFIED");

        // Same connector, failing signature: recorded, but never verified.
        String otherUrl = greenhouseUrl("conn2", 7003);
        UUID other = application(seedJob("conn2", "Fixture Co", "QA Engineer", otherUrl, null));
        var failed = emails.ingestFromMailboxConnector(devProfile, new ConnectorMessage("fixture-provider",
                "conn-" + UUID.randomUUID(), "Fixture Co Talent <talent@mail.fixture.example>", "dev@example.local",
                "Application received", "We have received your application. " + otherUrl,
                Instant.now().minus(1, ChronoUnit.HOURS), "dev@example.local",
                new SenderAuthentication("fail", "fixture.example", "pass", "pass")));
        assertThat(failed.receiptVerification()).isEqualTo("UNVERIFIED");
        assertThat(track(other).at("/submission/state").asText()).isEqualTo("UNVERIFIED");
        // The schema refuses a VERIFIED receipt from any other channel, whatever the code says.
        assertThatThrownBy(() -> jdbc.update("""
                insert into application_receipts (id, profile_id, application_id, evidence_type, channel,
                    evidence_time_precision, fingerprint, verification_status, policy_version, summary)
                values (?, ?, ?, 'CANDIDATE_STATEMENT', 'CANDIDATE_ENTERED', 'UNKNOWN', 'forged', 'VERIFIED', 'x', 'forged')
                """, UuidV7.generate(), devProfile, other)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void preparationApprovalAndAQueuedPackageAreNeverASubmission() throws Exception {
        String url = greenhouseUrl("pkg", 7010);
        UUID app = application(seedJob("pkg", "Fixture Co", "Site Reliability Engineer", url, null));
        seedReviewedCv(app, "pkg");
        seedApprovedLetter(app, "I would love to join Fixture Co.");

        MvcResult created = mvc.perform(post("/api/v1/apply/applications/{id}/package", app).with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn();
        UUID planId = UUID.fromString(json.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("planId").asText());
        jdbc.update("update automation_plans set status = 'AWAITING_APPROVAL' where id = ?", planId);
        JsonNode beforeApproval = track(app);
        assertThat(beforeApproval.get("status").asText()).isEqualTo("READY_TO_APPLY");
        assertThat(codes(beforeApproval.get("attention"))).contains("PACKAGE_REVIEW");

        mvc.perform(post("/api/v1/automation/plans/{id}/approve-submit", planId).with(csrf()).session(session))
                .andExpect(status().isOk());

        JsonNode track = track(app);
        assertThat(track.get("status").asText()).isEqualTo("READY_TO_APPLY");
        assertThat(track.at("/submission/state").asText()).isEqualTo("UNKNOWN");
        assertThat(track.get("reason").asText()).contains("an approval is not a submission");
        assertThat(track.get("label").asText()).isEqualTo("Prepared — not submitted");

        JsonNode timeline = timeline(app, null, 100);
        List<String> types = new ArrayList<>();
        timeline.get("items").forEach(i -> types.add(i.get("type").asText()));
        assertThat(types).contains("APPLICATION_CREATED", "PACKAGE_CREATED", "PACKAGE_APPROVED");
        JsonNode approved = find(timeline.get("items"), "PACKAGE_APPROVED");
        assertThat(approved.get("summary").asText()).contains("nothing was sent");
        assertThat(approved.get("classification").asText()).isEqualTo("INTERNAL");
        assertThat(timeline.toString().toLowerCase()).doesNotContain("greenhouse");
    }

    // ── association review ───────────────────────────────────────────────

    @Test
    void similarTitlesAreLeftToTheCandidateAndAConfirmationIsRecordedAsTheirs() throws Exception {
        UUID first = application(seedJob("sim-a", "Similar Corp", "Backend Engineer", greenhouseUrl("sim", 7101), "SIM-101"));
        UUID second = application(seedJob("sim-b", "Similar Corp", "Backend Engineer", greenhouseUrl("sim", 7102), "SIM-102"));

        JsonNode ingest = ingestHttp(Map.of("messageId", "sim-" + UUID.randomUUID(), "from", "recruiting@similarcorp.example",
                "subject", "Your Backend Engineer application", "body",
                "Thank you for your application for Backend Engineer at Similar Corp. Our recruiter will be in touch.",
                "receivedAt", "2026-10-02T08:00:00Z"));
        assertThat(ingest.get("associationStatus").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(ingest.get("applicationId").isNull()).isTrue();
        UUID emailId = UUID.fromString(ingest.get("emailId").asText());

        JsonNode queue = reviews();
        JsonNode item = findEmail(queue.get("items"), emailId);
        List<String> candidateIds = new ArrayList<>();
        item.get("candidates").forEach(c -> candidateIds.add(c.get("applicationId").asText()));
        assertThat(candidateIds).containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(item.get("reviewType").asText()).isEqualTo("ASSOCIATION");
        // Neither application moved on a guess.
        assertThat(track(first).get("status").asText()).isEqualTo("READY_TO_APPLY");
        assertThat(codes(track(first).get("attention"))).contains("MESSAGE_ASSOCIATION_REVIEW");

        // Confirming without choosing an application is refused.
        mvc.perform(post("/api/v1/track/emails/{id}/review", emailId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/track/emails/{id}/review", emailId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "CONFIRM", "applicationId", second.toString(),
                                "expectedVersion", item.get("decisionVersion").asInt()))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select status from email_application_links where email_id = ?", String.class, emailId))
                .isEqualTo("CONFIRMED");
        JsonNode secondTrack = track(second);
        // The meaning stays an acknowledgement without identifiers: confirmed by the candidate, never verified.
        assertThat(secondTrack.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(secondTrack.get("basis").asText()).isEqualTo("CANDIDATE_CONFIRMED");
        assertThat(secondTrack.at("/submission/state").asText()).isEqualTo("UNVERIFIED");
        JsonNode reviewEvent = find(timeline(second, null, 100).get("items"), "MESSAGE_REVIEWED");
        assertThat(reviewEvent.get("classification").asText()).isEqualTo("CANDIDATE_REPORTED");
        assertThat(reviewEvent.get("label").asText()).isEqualTo("Reported by you");
        assertThat(track(first).get("status").asText()).isEqualTo("READY_TO_APPLY");
        assertThat(count("select count(*) from audit_logs where action = 'TRACK_MESSAGE_REVIEWED' and entity_id = ?", emailId))
                .isEqualTo(1);

        // A stale decision version is a conflict, not a silent overwrite.
        mvc.perform(post("/api/v1/track/emails/{id}/review", emailId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("decision", "REJECT", "expectedVersion", 0))))
                .andExpect(status().isConflict());
    }

    @Test
    void anInferredInterviewWaitsForConfirmationAndReclassificationPreservesHistory() throws Exception {
        String url = greenhouseUrl("intv", 7201);
        UUID app = application(seedJob("intv", "Interview Co", "Product Engineer", url, null));
        report(app, Map.of("kind", "SUBMITTED", "occurredOn", "2026-10-01"));

        JsonNode ingest = ingestHttp(Map.of("messageId", "intv-" + UUID.randomUUID(), "from", "talent@interviewco.example",
                "subject", "Next steps", "body", "We would like to invite you to a video interview for " + url,
                "receivedAt", "2026-10-05T10:00:00Z"));
        assertThat(ingest.get("associationStatus").asText()).isEqualTo("LINKED");
        assertThat(ingest.get("classification").asText()).isEqualTo("INTERVIEW_INVITATION");
        UUID emailId = UUID.fromString(ingest.get("emailId").asText());

        JsonNode before = track(app);
        assertThat(before.get("status").asText()).isEqualTo("APPLIED");
        assertThat(before.at("/pending/stage").asText()).isEqualTo("INTERVIEW");
        assertThat(codes(before.get("attention"))).contains("CONFIRM_MESSAGE_MEANING");
        assertThat(jdbc.queryForObject("select status from applications where id = ?", String.class, app)).isEqualTo("READY_TO_APPLY");

        reviewMessage(emailId, Map.of("decision", "CONFIRM", "applicationId", app.toString()));
        JsonNode confirmed = track(app);
        assertThat(confirmed.get("status").asText()).isEqualTo("INTERVIEW");
        assertThat(confirmed.get("basis").asText()).isEqualTo("CANDIDATE_CONFIRMED");

        // The candidate corrects the meaning later: the earlier rows stay, a new one supersedes them.
        reviewMessage(emailId, Map.of("decision", "CONFIRM", "applicationId", app.toString(), "category", "REJECTION"));
        assertThat(track(app).get("status").asText()).isEqualTo("REJECTED");
        assertThat(count("select count(*) from application_events where application_id = ? and type = 'EMPLOYER_MESSAGE_RECEIVED'", app))
                .isEqualTo(1);
        assertThat(count("select count(*) from application_events where application_id = ? and type = 'MESSAGE_REVIEWED'", app))
                .isEqualTo(2);
        assertThat(count("select count(*) from audit_logs where action = 'TRACK_MESSAGE_RECLASSIFIED' and entity_id = ?", emailId))
                .isEqualTo(1);
        UUID original = jdbc.queryForObject(
                "select id from application_events where application_id = ? and type = 'EMPLOYER_MESSAGE_RECEIVED'", UUID.class, app);
        assertThat(count("select count(*) from application_events where supersedes_event_id = ?", original)).isEqualTo(2);

        // History is append-only at the database boundary.
        assertThatThrownBy(() -> jdbc.update("update application_events set summary = 'rewritten' where id = ?", original))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from application_events where id = ?", original))
                .hasMessageContaining("append-only");
    }

    // ── idempotency and atomicity ────────────────────────────────────────

    @Test
    void concurrentDuplicateIngestionRecordsOneMessageOneLinkOneReceipt() throws Exception {
        String url = greenhouseUrl("dup", 7301);
        UUID app = application(seedJob("dup", "Dup Co", "Platform Engineer", url, null));
        String messageId = "dup-" + UUID.randomUUID();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EmailIntelligenceService.IngestResult>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return emails.ingest(devProfile, messageId, "no-reply@dup.example", "dev@example.local", "Application received",
                        "We have received your application. " + url, Instant.parse("2026-10-03T10:00:00Z"));
            }));
        }
        start.countDown();
        int fresh = 0;
        for (Future<EmailIntelligenceService.IngestResult> f : results) {
            if (!f.get(60, TimeUnit.SECONDS).replayed()) fresh++;
        }
        pool.shutdown();
        assertThat(fresh).isEqualTo(1);
        assertThat(count("select count(*) from emails where message_id = ? and profile_id = ?", messageId, devProfile)).isEqualTo(1);
        assertThat(count("select count(*) from email_application_links l join emails e on e.id = l.email_id where e.message_id = ?",
                messageId)).isEqualTo(1);
        assertThat(count("select count(*) from application_receipts where application_id = ?", app)).isEqualTo(1);
        assertThat(count("select count(*) from application_events where application_id = ? and type = 'EMPLOYER_MESSAGE_RECEIVED'", app))
                .isEqualTo(1);

        // Without a message id, the per-owner content fingerprint de-duplicates retries.
        var first = emails.ingest(devProfile, null, "no-reply@dup.example", null, "Following up", "Following up on next steps.",
                Instant.parse("2026-10-04T10:00:00Z"));
        var retry = emails.ingest(devProfile, null, "no-reply@dup.example", null, "Following up", "Following up on next steps.",
                Instant.parse("2026-10-04T10:00:00Z"));
        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.emailId()).isEqualTo(first.emailId());

        // Repeating a candidate report is idempotent as well.
        Map<String, Object> rep = Map.of("kind", "INTERVIEW", "occurredOn", "2026-10-06", "clientReportId", "retry-1");
        report(app, rep);
        JsonNode again = report(app, rep);
        assertThat(again.get("replayed").asBoolean()).isTrue();
        assertThat(count("select count(*) from application_events where application_id = ? and type = 'CANDIDATE_REPORT'", app))
                .isEqualTo(1);
    }

    @Test
    void aFailedTimelineWriteRollsBackTheWholeOperation() throws Exception {
        String url = greenhouseUrl("rb", 7401);
        UUID app = application(seedJob("rb", "Rollback Co", "Platform Engineer", url, null));

        Mockito.doThrow(new DataAccessResourceFailureException("injected event-write failure"))
                .when(trackRepository).insertEvent(argThat(e -> e != null && "CANDIDATE_REPORT".equals(e.type())));
        mvc.perform(post("/api/v1/applications/{id}/track/reports", app).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"SUBMITTED\",\"occurredOn\":\"2026-10-02\"}"))
                .andExpect(status().isServiceUnavailable());
        // The receipt written before the failing event was rolled back with it.
        assertThat(count("select count(*) from application_receipts where application_id = ?", app)).isZero();
        assertThat(count("select count(*) from application_events where application_id = ? and type = 'CANDIDATE_REPORT'", app)).isZero();

        Mockito.reset(trackRepository);
        Mockito.doThrow(new DataAccessResourceFailureException("injected event-write failure"))
                .when(trackRepository).insertEvent(argThat(e -> e != null && TrackEvidenceRecorder.MESSAGE_EVENT.equals(e.type())));
        String messageId = "rb-" + UUID.randomUUID();
        assertThatThrownBy(() -> emails.ingest(devProfile, messageId, "no-reply@rollback.example", null, "Application received",
                "We have received your application. " + url, Instant.parse("2026-10-03T10:00:00Z")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(count("select count(*) from emails where message_id = ?", messageId)).isZero();
        assertThat(count("select count(*) from email_application_links l join emails e on e.id = l.email_id where e.message_id = ?",
                messageId)).isZero();
        // Through the API the same failure is an explicit, retryable "unavailable" — not a 500, not a partial write.
        MvcResult failed = mvc.perform(post("/api/v1/emails/ingest").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("messageId", messageId, "from", "no-reply@rollback.example",
                                "subject", "Application received", "body", "We have received your application. " + url,
                                "receivedAt", "2026-10-03T10:00:00Z"))))
                .andExpect(status().isServiceUnavailable()).andReturn();
        assertThat(json.readTree(failed.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("availability").asText())
                .isEqualTo("UNAVAILABLE");
        assertThat(count("select count(*) from emails where message_id = ?", messageId)).isZero();

        // After the fault clears, the retry succeeds exactly once.
        Mockito.reset(trackRepository);
        var retried = emails.ingest(devProfile, messageId, "no-reply@rollback.example", null, "Application received",
                "We have received your application. " + url, Instant.parse("2026-10-03T10:00:00Z"));
        assertThat(retried.replayed()).isFalse();
        assertThat(count("select count(*) from application_receipts where application_id = ?", app)).isEqualTo(1);
    }

    // ── reconciliation over time ─────────────────────────────────────────

    @Test
    void reconciliationFollowsNewEvidenceWithoutLettingOlderEvidenceRegressIt() throws Exception {
        String url = greenhouseUrl("rec", 7501);
        UUID app = application(seedJob("rec", "Recon Co", "Platform Engineer", url, "REC-7701"));
        assertThat(track(app).get("status").asText()).isEqualTo("READY_TO_APPLY");

        report(app, Map.of("kind", "SUBMITTED", "occurredOn", "2026-10-01"));
        assertThat(track(app).get("label").asText()).isEqualTo("Applied — reported by you");

        ingestHttp(Map.of("messageId", "rec-ack-" + UUID.randomUUID(), "from", "careers@reconco.example",
                "subject", "Recon Co: application received", "body",
                "Recon Co confirms we have received your application. Requisition ID: REC-7701.",
                "receivedAt", "2026-10-02T09:00:00Z"));
        JsonNode acknowledged = track(app);
        assertThat(acknowledged.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(acknowledged.get("basis").asText()).isEqualTo("UNVERIFIED");

        JsonNode interview = ingestHttp(Map.of("messageId", "rec-int-" + UUID.randomUUID(), "from", "careers@reconco.example",
                "subject", "Interview", "body", "We would like to invite you to a final interview. " + url,
                "receivedAt", "2026-10-08T09:00:00Z"));
        reviewMessage(UUID.fromString(interview.get("emailId").asText()), Map.of("decision", "CONFIRM", "applicationId", app.toString()));
        assertThat(track(app).get("status").asText()).isEqualTo("INTERVIEW");

        // An older acknowledgement added today does not pull the status back.
        ingestHttp(Map.of("messageId", "rec-old-" + UUID.randomUUID(), "from", "careers@reconco.example",
                "subject", "Re: application", "body", "Your application has been received. Requisition ID: REC-7701, Recon Co.",
                "receivedAt", "2026-09-30T09:00:00Z"));
        assertThat(track(app).get("status").asText()).isEqualTo("INTERVIEW");

        report(app, Map.of("kind", "REJECTED", "occurredOn", "2026-10-10"));
        JsonNode rejected = track(app);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("basis").asText()).isEqualTo("CANDIDATE_REPORTED");
    }

    // ── pre-8.3 records ──────────────────────────────────────────────────

    @Test
    void legacyStatusTransitionsAreClassifiedAndAnInferredLegacyStatusNeedsReview() throws Exception {
        UUID app = application(seedJob("legacy", "Legacy Co", "Platform Engineer", greenhouseUrl("legacy", 7601), null));
        // The pre-8.3 manual transition path (the candidate) …
        statusService.apply(devProfile, app, "APPLICATION_STARTED", "legacy-start-" + app, "dev@example.local", Map.of());
        statusService.apply(devProfile, app, "APPLICATION_SUBMITTED", "legacy-submit-" + app, "dev@example.local", Map.of());
        assertThat(track(app).get("status").asText()).isEqualTo("APPLIED");
        assertThat(track(app).get("basis").asText()).isEqualTo("CANDIDATE_REPORTED");
        // … and the pre-8.3 email keyword inference (event key "email:<id>").
        statusService.apply(devProfile, app, "INTERVIEW", "email:" + UUID.randomUUID(), "dev@example.local",
                Map.of("classification", "INTERVIEW_INVITE"));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select payload->>'to' as to_status, classification, verification_status from application_events
                where application_id = ? and type = 'STATUS_CHANGED' order by occurred_at""", app);
        assertThat(rows).extracting(r -> r.get("classification"))
                .containsExactly("CANDIDATE_REPORTED", "CANDIDATE_REPORTED", "INFERRED");
        JsonNode track = track(app);
        assertThat(track.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(track.get("evidenceStage").asText()).isEqualTo("APPLIED");
        assertThat(track.get("recordedStatus").asText()).isEqualTo("INTERVIEW");
        assertThat(codes(track.get("conflicts"))).contains("RECORDED_STATUS_INFERRED");
    }

    @Test
    void aRecordedStatusWithNoHistoryIsReportedAsUnknown() throws Exception {
        UUID app = application(seedJob("nohist", "Legacy Co", "Data Engineer", greenhouseUrl("nohist", 7602), null));
        jdbc.update("update applications set status = 'OFFER' where id = ?", app);
        assertThat(track(app).get("status").asText()).isEqualTo("UNKNOWN");
    }

    // ── timeline ─────────────────────────────────────────────────────────

    @Test
    void theTimelineIsChronologicalPaginatedAndHonestAboutUnknownTimes() throws Exception {
        UUID app = application(seedJob("tl", "Timeline Co", "Platform Engineer", greenhouseUrl("tl", 7701), null));
        report(app, Map.of("kind", "SUBMITTED", "occurredOn", "2026-10-01"));
        report(app, Map.of("kind", "RECRUITER_CONTACT"));
        report(app, Map.of("kind", "INTERVIEW", "occurredOn", "2026-10-09"));

        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = timeline(app, cursor, 2);
            page.get("items").forEach(all::add);
            cursor = page.get("nextCursor").asText();
            pages++;
        } while (!cursor.isEmpty() && pages < 20);
        assertThat(pages).isGreaterThan(1);
        Set<String> ids = new java.util.HashSet<>();
        all.forEach(i -> ids.add(i.get("id").asText()));
        assertThat(ids).hasSize(all.size());

        JsonNode undated = all.stream().filter(i -> i.get("summary").asText().contains("contact from the employer"))
                .findFirst().orElseThrow();
        assertThat(undated.get("occurredAtPrecision").asText()).isEqualTo("UNKNOWN");
        assertThat(undated.get("occurredAt").isNull()).isTrue();
        assertThat(undated.get("recordedAt").asText()).isNotEmpty();
        JsonNode dated = all.stream().filter(i -> i.get("summary").asText().contains("an interview")).findFirst().orElseThrow();
        assertThat(dated.get("occurredOn").asText()).isEqualTo("2026-10-09");
        assertThat(dated.get("label").asText()).isEqualTo("Reported by you");
        assertThat(dated.get("actor").asText()).isEqualTo("YOU");

        mvc.perform(get("/api/v1/applications/{id}/track/timeline", app).param("limit", "0").session(session))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/applications/{id}/track/timeline", app).param("cursor", "not-a-cursor").session(session))
                .andExpect(status().isBadRequest());
    }

    // ── isolation ────────────────────────────────────────────────────────

    @Test
    void anotherAccountCanNeitherReadNorChangeTracking() throws Exception {
        String url = greenhouseUrl("iso", 7801);
        UUID app = application(seedJob("iso", "Isolation Co", "Platform Engineer", url, null));
        JsonNode mine = ingestHttp(Map.of("messageId", "iso-" + UUID.randomUUID(), "from", "talent@isolationco.example",
                "subject", "Thanks", "body", "Thank you for applying to Isolation Co.", "receivedAt", "2026-10-02T09:00:00Z"));
        UUID myEmail = UUID.fromString(mine.get("emailId").asText());
        report(app, Map.of("kind", "SUBMITTED"));
        UUID receiptId = jdbc.queryForObject("select id from application_receipts where application_id = ?", UUID.class, app);

        RequestPostProcessor other = asOtherCandidate();
        mvc.perform(get("/api/v1/applications/{id}/track", app).with(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/applications/{id}/track/timeline", app).with(other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/applications/{id}/track/reports", app).with(other).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"OFFER\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", app, receiptId).with(other).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/track/emails/{id}/review", myEmail).with(other).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/emails/ingest").with(other).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("subject", "x", "body", "y", "applicationId", app.toString()))))
                .andExpect(status().isNotFound());

        // The other candidate's own message naming the same job link is never matched to my application.
        UUID otherProfile = otherProfile();
        var theirs = emails.ingest(otherProfile, "iso-other-" + UUID.randomUUID(), "talent@isolationco.example", null,
                "Application received", "We have received your application. " + url, Instant.parse("2026-10-02T10:00:00Z"));
        assertThat(theirs.applicationId()).isNull();
        assertThat(theirs.associationStatus()).isEqualTo("UNMATCHED");
        MvcResult theirQueue = mvc.perform(get("/api/v1/track/reviews").with(other)).andExpect(status().isOk()).andReturn();
        assertThat(theirQueue.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain(myEmail.toString());
        assertThat(reviews().toString()).doesNotContain(String.valueOf(theirs.emailId()));
        // Nothing of mine changed.
        assertThat(jdbc.queryForObject("select candidate_review from application_receipts where id = ?", String.class, receiptId)).isNull();
        // The database refuses a cross-owner row even if application code tried.
        assertThatThrownBy(() -> jdbc.update("""
                insert into application_events (id, application_id, type, payload, actor, profile_id)
                values (?, ?, 'CANDIDATE_REPORT', '{}'::jsonb, 'x', ?)""", UuidV7.generate(), app, otherProfile))
                .hasMessageContaining("owner of its application");
    }

    // ── receipts: candidate review ───────────────────────────────────────

    @Test
    void aReceiptReviewIsVersionedAndNeverMakesEvidenceVerified() throws Exception {
        String url = greenhouseUrl("rr", 7901);
        UUID app = application(seedJob("rr", "Review Co", "Platform Engineer", url, null));
        ingestHttp(Map.of("messageId", "rr-" + UUID.randomUUID(), "from", "no-reply@reviewco.example",
                "subject", "Application received", "body", "We have received your application. " + url,
                "receivedAt", "2026-10-02T09:00:00Z"));
        UUID receiptId = jdbc.queryForObject("select id from application_receipts where application_id = ?", UUID.class, app);

        mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", app, receiptId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"CONFIRM\",\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select verification_status from application_receipts where id = ?", String.class, receiptId))
                .isEqualTo("UNVERIFIED");
        assertThat(track(app).at("/submission/state").asText()).isEqualTo("UNVERIFIED");

        mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", app, receiptId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", app, receiptId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        JsonNode track = track(app);
        assertThat(track.get("status").asText()).isEqualTo("READY_TO_APPLY");
        assertThat(track.at("/submission/state").asText()).isEqualTo("UNKNOWN");
        assertThat(count("select count(*) from application_events where application_id = ? and type = 'RECEIPT_REVIEWED'", app))
                .isEqualTo(2);
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    void invalidInputIsRefusedWithoutSideEffects() throws Exception {
        UUID app = application(seedJob("val", "Validation Co", "Platform Engineer", greenhouseUrl("val", 8001), null));
        String future = Instant.now().plus(10, ChronoUnit.DAYS).toString().substring(0, 10);
        for (String body : List.of("{\"kind\":\"SUBMITTED\",\"occurredOn\":\"" + future + "\"}", "{\"kind\":\"HIRED_ME\"}",
                "{\"kind\":\"SUBMITTED\",\"occurredOn\":\"yesterday\"}", "{\"kind\":\"SUBMITTED\",\"note\":\"" + "x".repeat(600) + "\"}",
                "{\"kind\":\"SUBMITTED\",\"clientReportId\":\"bad id!\"}")) {
            mvc.perform(post("/api/v1/applications/{id}/track/reports", app).with(csrf()).session(session)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(count("select count(*) from application_receipts where application_id = ?", app)).isZero();
        mvc.perform(post("/api/v1/applications/{id}/track/reports", UUID.randomUUID()).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"SUBMITTED\"}")).andExpect(status().isNotFound());

        JsonNode otp = ingestHttp(Map.of("messageId", "otp-" + UUID.randomUUID(), "from", "no-reply@employer.example",
                "subject", "Your code", "body", "Use 123456 to continue"));
        assertThat(otp.get("associationStatus").asText()).isEqualTo("NOT_APPLICABLE");
        mvc.perform(post("/api/v1/track/emails/{id}/review", otp.get("emailId").asText()).with(csrf()).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("decision", "CONFIRM", "applicationId", app.toString()))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/emails/ingest").with(csrf()).session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"\",\"body\":\"\"}")).andExpect(status().isBadRequest());
    }

    // ── authentication and CSRF ──────────────────────────────────────────

    @Test
    void everyTrackEndpointNeedsASessionAndEveryWriteNeedsTheCsrfToken() throws Exception {
        UUID app = application(seedJob("auth", "Auth Co", "Platform Engineer", greenhouseUrl("auth", 8051), null));
        UUID someId = UUID.randomUUID();

        // No session: the auth layer answers before any TRACK code runs.
        mvc.perform(get("/api/v1/applications/{id}/track", app)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/applications/{id}/track/timeline", app)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/track/reviews")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/applications/{id}/track/reports", app).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"SUBMITTED\"}")).andExpect(status().isUnauthorized());

        // A signed-in session without the CSRF token cannot write anything.
        mvc.perform(post("/api/v1/applications/{id}/track/reports", app).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"SUBMITTED\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/applications/{id}/track/receipts/{rid}/review", app, someId).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/track/emails/{id}/review", someId).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/emails/ingest").session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"subject\":\"Application received\"}"))
                .andExpect(status().isForbidden());

        assertThat(count("select count(*) from application_events where application_id = ? and type = 'CANDIDATE_REPORT'", app))
                .isZero();
        assertThat(count("select count(*) from application_receipts where application_id = ?", app)).isZero();
    }

    // ── source hiding ────────────────────────────────────────────────────

    @Test
    void candidateFacingTrackResponsesNeverNameTheDiscoverySource() throws Exception {
        UUID viaBoard = application(seedJob("hide-a", "Hidden Source Co", "Platform Engineer", greenhouseUrl("hide", 8101), null));
        UUID tsentaJob = UuidV7.generate();
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, 'tsenta-hide', 'dedup-tsenta-hide', 'Hidden Source Co', 'Data Engineer', 'Build.', '{}',
                        'SCORED', 'hash-tsenta-hide', 'https://careers.hiddensource.example/jobs/42', 'https://tsenta.com/jobs/abc')
                """, tsentaJob, TSENTA_SOURCE);
        UUID viaAggregator = application(tsentaJob);
        report(viaBoard, Map.of("kind", "SUBMITTED"));
        report(viaAggregator, Map.of("kind", "SUBMITTED"));

        String combined = (track(viaBoard).toString() + track(viaAggregator).toString()
                + timeline(viaBoard, null, 100).toString() + timeline(viaAggregator, null, 100).toString()
                + mvc.perform(get("/api/v1/applications").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
                + reviews().toString()).toLowerCase();
        // The job's source names / kinds / provider labels never appear in a TRACK response.
        assertThat(combined).doesNotContain("tsenta").doesNotContain("track fixture board").doesNotContain("source_kind")
                .doesNotContain("source_name").doesNotContain("\"source\"");
        String trackOnly = (track(viaBoard).toString() + timeline(viaBoard, null, 100).toString()).toLowerCase();
        assertThat(trackOnly).doesNotContain("greenhouse");
        // Provenance is still in the database for audit and de-duplication.
        assertThat(jdbc.queryForObject("select s.kind from jobs j join job_sources s on s.id = j.source_id where j.id = ?",
                String.class, tsentaJob)).isEqualTo("TSENTA");
    }

    // ── deletions and purge ──────────────────────────────────────────────

    @Test
    void deletedApplicationsAndPurgedProfilesLeaveNoDanglingTrackData() throws Exception {
        String url = greenhouseUrl("del", 8201);
        UUID app = application(seedJob("del", "Delete Co", "Platform Engineer", url, null));
        var ingested = emails.ingest(devProfile, "del-" + UUID.randomUUID(), "no-reply@deleteco.example", null,
                "Application received", "We have received your application. " + url, Instant.parse("2026-10-02T09:00:00Z"));
        assertThat(ingested.applicationId()).isEqualTo(app);

        // An application deleted out from under a link: the message stays, the link no longer points anywhere.
        // (emails.application_id is the pre-8.3 pointer, which has no ON DELETE action, so it is
        // cleared first — no product path deletes a single application; purge removes the profile.)
        jdbc.update("update emails set application_id = null where id = ?", ingested.emailId());
        jdbc.update("delete from applications where id = ?", app);
        Map<String, Object> link = jdbc.queryForMap("select application_id, status from email_application_links where email_id = ?",
                ingested.emailId());
        assertThat(link.get("application_id")).isNull();
        assertThat(count("select count(*) from application_events where application_id = ?", app)).isZero();
        assertThat(count("select count(*) from application_receipts where application_id = ?", app)).isZero();
        mvc.perform(get("/api/v1/applications/{id}/track", app).session(session)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/track/reviews").session(session)).andExpect(status().isOk());

        // A purge of another candidate's profile cascades through every TRACK table.
        UUID otherProfile = otherProfile();
        UUID theirJob = seedJob("del-other", "Delete Co", "Data Engineer", greenhouseUrl("del", 8202), null);
        UUID theirApp = pipeline.createApplicationFromMatch(otherProfile, theirJob).applicationId();
        emails.ingest(otherProfile, "del-other-" + UUID.randomUUID(), "no-reply@deleteco.example", null,
                "Application received", "We have received your application. " + greenhouseUrl("del", 8202),
                Instant.parse("2026-10-02T09:00:00Z"));
        assertThat(count("select count(*) from application_receipts where profile_id = ?", otherProfile)).isEqualTo(1);
        jdbc.update("delete from profiles where id = ?", otherProfile);
        assertThat(count("select count(*) from application_events where application_id = ?", theirApp)).isZero();
        assertThat(count("select count(*) from application_receipts where profile_id = ?", otherProfile)).isZero();
        assertThat(count("select count(*) from email_application_links where profile_id = ?", otherProfile)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String greenhouseUrl(String board, int id) {
        return "https://boards.greenhouse.io/trackfixture" + board + "/jobs/" + id;
    }

    private UUID seedJob(String tag, String company, String title, String url, String requisition) {
        UUID jobId = UuidV7.generate();
        String externalId = "track-" + tag + "-" + jobId;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, ?, ?, ?, ?, 'Build services.', '{}', 'SCORED', ?, ?, ?)
                """, jobId, SOURCE, externalId, "dedup-" + externalId, company, title, "hash-" + externalId, url, url);
        for (JobIdentity.Key key : JobIdentity.keys(SOURCE, externalId, url, url, company, requisition)) {
            jdbc.update("insert into job_identity_keys(key, job_id, key_type, source_id) values (?, ?, ?, ?) on conflict (key) do nothing",
                    key.asString(), jobId, key.type().name(), SOURCE);
        }
        return jobId;
    }

    /** Creates the application and records a completed preparation the way the pipeline handler does. */
    private UUID application(UUID jobId) {
        UUID app = pipeline.createApplicationFromMatch(devProfile, jobId).applicationId();
        for (String step : List.of("CV", "COVER_LETTER", "ANSWERS")) {
            jdbc.update("insert into application_events (id, application_id, type, payload, actor) values (?, ?, 'PREPARATION', ?::jsonb, 'SYSTEM')",
                    UuidV7.generate(), app, "{\"step\":\"" + step + "\",\"status\":\"OK\"}");
        }
        return app;
    }

    private JsonNode ingestHttp(Map<String, Object> body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/emails/ingest").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode track(UUID app) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/applications/{id}/track", app).session(session))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode timeline(UUID app, String cursor, int limit) throws Exception {
        var request = get("/api/v1/applications/{id}/track/timeline", app).param("limit", String.valueOf(limit)).session(session);
        if (cursor != null && !cursor.isEmpty()) request = request.param("cursor", cursor);
        MvcResult r = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode reviews() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/track/reviews").session(session)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode report(UUID app, Map<String, Object> body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/applications/{id}/track/reports", app).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private void reviewMessage(UUID emailId, Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/v1/track/emails/{id}/review", emailId).with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static List<String> codes(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null) array.forEach(n -> out.add(n.get("code").asText()));
        return out;
    }

    private static JsonNode find(JsonNode items, String type) {
        for (JsonNode item : items) if (type.equals(item.get("type").asText())) return item;
        throw new AssertionError("no timeline item of type " + type + " in " + items);
    }

    private static JsonNode findEmail(JsonNode items, UUID emailId) {
        for (JsonNode item : items) if (emailId.toString().equals(item.get("emailId").asText())) return item;
        throw new AssertionError("no review item for " + emailId + " in " + items);
    }

    private UUID otherUserId() {
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, 'other@track.test', null, 'Other Candidate', 'FIREBASE') on conflict (email) do nothing
                """, UuidV7.generate());
        return jdbc.queryForObject("select id from users where email = 'other@track.test'", UUID.class);
    }

    private UUID otherProfile() {
        UUID user = otherUserId();
        return profiles.findByUserId(user).map(p -> p.id())
                .orElseGet(() -> profiles.createProfile(user, null, null, null, null, null, null, null));
    }

    private RequestPostProcessor asOtherCandidate() {
        UUID user = otherUserId();
        otherProfile();
        AppUserDetails principal = new AppUserDetails(new UserRecord(user, "other@track.test", null, "Other Candidate",
                null, "FIREBASE"));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void seedReviewedCv(UUID applicationId, String tag) throws Exception {
        byte[] pdf = ("pdf-bytes-" + tag).getBytes(StandardCharsets.UTF_8);
        String pdfSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf));
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
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("validation", Map.of("passed", true, "validator_version", "fact-validator-1"));
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, 'hash-' || ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UuidV7.generate(), devProfile, jobId, applicationId, tag, json.writeValueAsString(report), cvVersionId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?) on conflict (cv_version_id) do nothing
                """, cvVersionId, devProfile, pdfSha);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvVersionId, applicationId);
    }

    private void seedApprovedLetter(UUID applicationId, String body) throws Exception {
        UUID jobId = jdbc.queryForObject("select job_id from applications where id = ?", UUID.class, applicationId);
        jdbc.update("""
                insert into cover_letters (id, profile_id, job_id, application_id, version, title, body_markdown,
                                           content_sha256, claims_validation, is_approved, origin, created_at, updated_at)
                values (?, ?, ?, ?, 1, 'Cover letter', ?, ?, ?::jsonb, true, 'GENERATED', now(), now())
                """, UuidV7.generate(), devProfile, jobId, applicationId, body,
                com.personal.jobagent.automation.ExecutionPackageService.Sha256.of(body),
                json.writeValueAsString(Map.of("passed", true, "validator_version", "fact-validator-1")));
    }
}
