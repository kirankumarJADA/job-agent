package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.JobIdentity;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import com.personal.jobagent.security.UserRecord;
import com.personal.jobagent.track.ApplicationTrackingService;
import com.personal.jobagent.track.ApplicationTrackingService.MessageReviewCommand;
import com.personal.jobagent.track.ApplicationTrackingService.TimelinePage;
import com.personal.jobagent.track.TrackRepository;
import com.personal.jobagent.track.TrackText;
import com.personal.jobagent.track.TrackVocabulary.Basis;
import com.personal.jobagent.track.TrackVocabulary.Stage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.events.dispatch-interval-ms=3600000",
        "app.worker-event-token=managed-mail-it-token",
        "app.managed-mail.domain=" + CloudflareRecruiterLifecycleIT.DOMAIN,
        "app.managed-mail.provider=cloudflare",
        "app.managed-mail.address-prefix=robin+",
        "app.managed-mail.cloudflare.webhook-secret=" + CloudflareRecruiterLifecycleIT.SECRET,
        "app.managed-mail.cloudflare.max-age-seconds=900",
        "app.managed-mail.retention-sweep-initial-delay-ms=3600000",
        "app.secrets.encryption-key=6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U="
})
@AutoConfigureMockMvc
@Testcontainers
class CloudflareRecruiterLifecycleIT {

    static final String DOMAIN = "mail.aplivenosi.com";
    static final String SECRET = "it-cloudflare-secret-key-abcdef123456";
    static final String INBOUND = "/api/v1/managed-mail/inbound/cloudflare";
    private static final UUID FIXTURE_SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000cf21");

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
    @Autowired private ProfileRepository profiles;
    @Autowired private ApplicationPipelineService pipeline;
    @Autowired private ApplicationTrackingService tracking;
    @Autowired private TrackRepository trackRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'cf-lifecycle-fixture', 'CF Lifecycle Fixture Board', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, FIXTURE_SOURCE);

        jdbc.update("delete from email_application_links");
        jdbc.update("delete from emails");
        jdbc.update("delete from managed_inbound_messages");
        jdbc.update("delete from managed_mail_domains");
    }

    private record TestCandidate(UUID userId, UUID profileId, RequestPostProcessor auth, String emailAddress) {}

    private TestCandidate createCandidate(String email, String displayName) throws Exception {
        UUID userId = UuidV7.generate();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, ?, 'FIREBASE')",
                userId, email, displayName);
        UUID profileId = profiles.createProfile(userId, null, null, null, null, null, null, null);
        AppUserDetails principal = new AppUserDetails(new UserRecord(userId, email, null, displayName, null, "FIREBASE"));
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        MvcResult res = mvc.perform(post("/api/v1/managed-mail/address").with(csrf()).with(auth))
                .andExpect(status().isOk()).andReturn();
        String address = json(res).get("address").asText();
        return new TestCandidate(userId, profileId, auth, address);
    }

    private UUID createApplicationWithJob(UUID profileId, String company, String title, String url, String req) {
        UUID jobId = UuidV7.generate();
        String externalId = "cf-recruiter-job-" + jobId;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, ?, ?, ?, ?, 'Job description for recruiter testing', '{}', 'SCORED', ?, ?, ?)
                """, jobId, FIXTURE_SOURCE, externalId, "dedup-" + externalId, company, title, "hash-" + externalId, url, url);
        UUID appId = pipeline.createApplicationFromMatch(profileId, jobId).applicationId();
        for (JobIdentity.Key k : JobIdentity.keys(FIXTURE_SOURCE, externalId, url, url, company, req)) {
            jdbc.update("insert into job_identity_keys (key, job_id, key_type, source_id) values (?, ?, ?, ?) on conflict (key) do nothing",
                    k.asString(), jobId, k.type().name(), FIXTURE_SOURCE);
        }
        return appId;
    }

    private String buildCloudflarePayload(String envelopeRecipient, String from, String subject,
                                          String text, String messageId, String date,
                                          String inReplyTo, String references) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("envelopeRecipient", envelopeRecipient);
        map.put("envelopeSender", "bounces@employer.example");
        map.put("from", from);
        map.put("to", envelopeRecipient);
        map.put("subject", subject);
        map.put("text", text);
        map.put("messageId", messageId != null ? messageId : "<msg-" + UUID.randomUUID() + "@employer.example>");
        map.put("date", date != null ? date : "Tue, 27 Oct 2026 10:00:00 +0000");
        if (inReplyTo != null) map.put("inReplyTo", inReplyTo);
        if (references != null) map.put("references", references);
        map.put("spfPass", true);
        map.put("dkimPass", true);
        map.put("spam", false);
        return json.writeValueAsString(map);
    }

    private MvcResult deliverSigned(String body, String token, Instant timestamp) throws Exception {
        String ts = String.valueOf(timestamp.getEpochSecond());
        String payloadSha256 = TrackText.sha256(body);
        String sig = CloudflareSignatureVerifier.hmacHex(SECRET, ts + "." + token + "." + payloadSha256);

        return mvc.perform(post(INBOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Robin-Timestamp", ts)
                        .header("X-Robin-Token", token)
                        .header("X-Robin-Signature", sig))
                .andReturn();
    }

    @Test
    @DisplayName("Scenario 1: Recruiter interview invitation links to application, records timeline event, and transitions stage on candidate confirmation")
    void scenario1_recruiterInterviewInvitationLifecycle() throws Exception {
        TestCandidate candA = createCandidate("cand-a-lifecycle@example.com", "Candidate A");
        String jobUrl = "https://careers.acme.com/jobs/platform-eng-101";
        String reqId = "REQ-ACME-101";
        UUID appA = createApplicationWithJob(candA.profileId(), "Acme Corp", "Senior Platform Engineer", jobUrl, reqId);

        // Deliver signed recruiter interview invitation webhook
        Instant t1 = Instant.now().minus(Duration.ofMinutes(3));
        String body = buildCloudflarePayload(
                candA.emailAddress(),
                "Sarah Recruiter <recruiter@acme.com>",
                "Interview Invitation: Senior Platform Engineer",
                "Hello, we were impressed by your background and would like to invite you to an interview for the Senior Platform Engineer role at Acme Corp (Req " + reqId + "). Job details: " + jobUrl + ". Please let us know your availability.",
                "<interview-inv-101@acme.com>",
                "Tue, 27 Oct 2026 10:00:00 +0000",
                null,
                null
        );
        String token = "tok-inv-" + UUID.randomUUID();
        MvcResult res = deliverSigned(body, token, t1);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(res).get("status").asText()).isEqualTo("stored");

        // Verify managed_inbound_messages persistence
        List<Map<String, Object>> managedMsgs = jdbc.queryForList(
                "select profile_id, sender_domain, content_encrypted from managed_inbound_messages where profile_id = ?",
                candA.profileId());
        assertThat(managedMsgs).hasSize(1);
        assertThat(managedMsgs.get(0).get("profile_id")).isEqualTo(candA.profileId());
        assertThat(managedMsgs.get(0).get("sender_domain")).isEqualTo("acme.com");
        assertThat(managedMsgs.get(0).get("content_encrypted")).isNotNull();

        // Verify emails persistence and classification
        List<Map<String, Object>> emails = jdbc.queryForList(
                "select id, profile_id, application_id, classification, message_id from emails where profile_id = ?",
                candA.profileId());
        assertThat(emails).hasSize(1);
        UUID emailId = (UUID) emails.get(0).get("id");
        assertThat(emails.get(0).get("profile_id")).isEqualTo(candA.profileId());
        assertThat(emails.get(0).get("application_id")).isEqualTo(appA);
        assertThat(emails.get(0).get("classification")).isEqualTo("INTERVIEW_INVITATION");
        assertThat(emails.get(0).get("message_id")).isEqualTo("interview-inv-101@acme.com");

        // Verify email_application_links persistence
        List<Map<String, Object>> links = jdbc.queryForList(
                "select email_id, application_id, status, match_strength, inferred_category, profile_id from email_application_links where profile_id = ?",
                candA.profileId());
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("profile_id")).isEqualTo(candA.profileId());
        assertThat(links.get(0).get("email_id")).isEqualTo(emailId);
        assertThat(links.get(0).get("application_id")).isEqualTo(appA);
        assertThat(links.get(0).get("status")).isEqualTo("LINKED");
        assertThat(links.get(0).get("match_strength")).isEqualTo("STRONG");
        assertThat(links.get(0).get("inferred_category")).isEqualTo("INTERVIEW_INVITATION");

        // Verify timeline event in application_events
        List<Map<String, Object>> events = jdbc.queryForList(
                "select id, profile_id, application_id, type, classification, verification_status, email_id, summary from application_events where application_id = ? and type = 'EMPLOYER_MESSAGE_RECEIVED'",
                appA);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("profile_id")).isEqualTo(candA.profileId());
        assertThat(events.get(0).get("email_id")).isEqualTo(emailId);
        assertThat(events.get(0).get("classification")).isEqualTo("INFERRED");
        assertThat(events.get(0).get("verification_status")).isEqualTo("UNVERIFIED");
        assertThat(String.valueOf(events.get(0).get("summary"))).contains("interview invitation");

        // Verify application tracking state before confirmation
        Map<String, Object> viewBefore = tracking.view(candA.profileId(), appA).orElseThrow();
        assertThat(viewBefore.get("status")).isEqualTo("PREPARING");
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) viewBefore.get("pending");
        assertThat(pending).isNotNull();
        assertThat(pending.get("stage")).isEqualTo("INTERVIEW");
        assertThat(pending.get("emailId")).isEqualTo(emailId.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> commsBefore = (Map<String, Object>) viewBefore.get("communications");
        assertThat(commsBefore.get("linkedMessages")).isEqualTo(1);
        assertThat(commsBefore.get("awaitingReview")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attentionBefore = (List<Map<String, Object>>) viewBefore.get("attention");
        assertThat(attentionBefore).extracting(m -> m.get("code")).contains("CONFIRM_MESSAGE_MEANING");

        // Candidate confirms the interview invitation
        tracking.reviewMessage(candA.profileId(), emailId, new MessageReviewCommand("CONFIRM", appA, "INTERVIEW_INVITATION", null));

        // Verify updated application state after candidate confirmation
        Map<String, Object> viewAfter = tracking.view(candA.profileId(), appA).orElseThrow();
        assertThat(viewAfter.get("status")).isEqualTo("INTERVIEW");
        assertThat(viewAfter.get("basis")).isEqualTo("CANDIDATE_CONFIRMED");
        assertThat(viewAfter.get("label")).isEqualTo("Interview — confirmed by you");

        // Verify MESSAGE_REVIEWED event was added to timeline
        Integer reviewedCount = jdbc.queryForObject(
                "select count(*) from application_events where application_id = ? and type = 'MESSAGE_REVIEWED'",
                Integer.class, appA);
        assertThat(reviewedCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario 2: Recruiter reply with In-Reply-To and References maintains thread continuity, links to application, and preserves chronological timeline ordering")
    void scenario2_recruiterReplyAndThreadContinuity() throws Exception {
        TestCandidate cand = createCandidate("cand-thread@example.com", "Thread Candidate");
        String jobUrl = "https://careers.acme.com/jobs/platform-eng-101";
        String reqId = "REQ-ACME-101";
        UUID app = createApplicationWithJob(cand.profileId(), "Acme Corp", "Senior Platform Engineer", jobUrl, reqId);

        // 1. First message: Interview invitation at t1
        Instant t1 = Instant.now().minus(Duration.ofMinutes(4));
        String msg1Body = buildCloudflarePayload(
                cand.emailAddress(),
                "Sarah Recruiter <recruiter@acme.com>",
                "Interview Invitation: Senior Platform Engineer",
                "Hello, we would like to invite you to an interview for the Senior Platform Engineer position (Req " + reqId + ", " + jobUrl + ").",
                "<inv-thread-01@acme.com>",
                "Tue, 27 Oct 2026 10:00:00 +0000",
                null,
                null
        );
        MvcResult res1 = deliverSigned(msg1Body, "tok-th-01-" + UUID.randomUUID(), t1);
        assertThat(res1.getResponse().getStatus()).isEqualTo(200);

        UUID email1Id = jdbc.queryForObject(
                "select id from emails where message_id = 'inv-thread-01@acme.com' and profile_id = ?",
                UUID.class, cand.profileId());
        assertThat(email1Id).isNotNull();

        // 2. Second message: Recruiter reply referencing the first message at t2 (later timestamp)
        Instant t2 = Instant.now().minus(Duration.ofMinutes(1));
        String msg2Body = buildCloudflarePayload(
                cand.emailAddress(),
                "Sarah Recruiter <recruiter@acme.com>",
                "Re: Interview Invitation: Senior Platform Engineer",
                "In-Reply-To: <inv-thread-01@acme.com>\nReferences: <inv-thread-01@acme.com>\n\nFollowing up on our interview invitation for Senior Platform Engineer (Req " + reqId + ", " + jobUrl + "). Are you available this Thursday at 2:00 PM?",
                "<reply-thread-02@acme.com>",
                "Tue, 27 Oct 2026 12:00:00 +0000",
                "<inv-thread-01@acme.com>",
                "<inv-thread-01@acme.com>"
        );
        MvcResult res2 = deliverSigned(msg2Body, "tok-th-02-" + UUID.randomUUID(), t2);
        assertThat(res2.getResponse().getStatus()).isEqualTo(200);

        UUID email2Id = jdbc.queryForObject(
                "select id from emails where message_id = 'reply-thread-02@acme.com' and profile_id = ?",
                UUID.class, cand.profileId());
        assertThat(email2Id).isNotNull();

        // Verify both messages link to the same application
        List<Map<String, Object>> emailRows = jdbc.queryForList(
                "select id, application_id, message_id from emails where profile_id = ? order by received_at asc",
                cand.profileId());
        assertThat(emailRows).hasSize(2);
        assertThat(emailRows.get(0).get("application_id")).isEqualTo(app);
        assertThat(emailRows.get(1).get("application_id")).isEqualTo(app);

        // Verify email_application_links has 2 linked rows
        List<Map<String, Object>> linkRows = jdbc.queryForList(
                "select email_id, application_id, status from email_application_links where profile_id = ?",
                cand.profileId());
        assertThat(linkRows).hasSize(2);
        assertThat(linkRows).extracting(r -> r.get("status")).containsOnly("LINKED");
        assertThat(linkRows).extracting(r -> r.get("application_id")).containsOnly(app);

        // Verify tracking view communications shows linkedMessages = 2
        Map<String, Object> view = tracking.view(cand.profileId(), app).orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> comms = (Map<String, Object>) view.get("communications");
        assertThat(comms.get("linkedMessages")).isEqualTo(2);

        // Verify timeline ordering: oldest event first
        TimelinePage timeline = tracking.timeline(cand.profileId(), app, null, 50).orElseThrow();
        List<Map<String, Object>> messageEvents = timeline.items().stream()
                .filter(item -> "EMPLOYER_MESSAGE_RECEIVED".equals(item.get("type")))
                .toList();
        assertThat(messageEvents).hasSize(2);
        assertThat(messageEvents.get(0).get("emailId")).isEqualTo(email1Id.toString());
        assertThat(messageEvents.get(1).get("emailId")).isEqualTo(email2Id.toString());

        Instant event1Time = Instant.parse(String.valueOf(messageEvents.get(0).get("occurredAt")));
        Instant event2Time = Instant.parse(String.valueOf(messageEvents.get(1).get("occurredAt")));
        assertThat(event1Time).isBefore(event2Time);
    }

    @Test
    @DisplayName("Scenario 3: Candidate isolation ensures recruiter messages never affect another candidate's application, timeline, or review state")
    void scenario3_candidateIsolationAndCrossTenantProtection() throws Exception {
        TestCandidate candA = createCandidate("cand-iso-a@example.com", "Candidate A");
        TestCandidate candB = createCandidate("cand-iso-b@example.com", "Candidate B");

        UUID appA = createApplicationWithJob(candA.profileId(), "Acme Corp", "Senior Platform Engineer",
                "https://careers.acme.com/jobs/platform-eng-101", "REQ-ACME-101");
        UUID appB = createApplicationWithJob(candB.profileId(), "Initech", "Backend Lead",
                "https://initech.example/jobs/backend-lead", "REQ-INIT-202");

        // Deliver recruiter interview invitation for Candidate A
        Instant t = Instant.now().minus(Duration.ofMinutes(5));
        String bodyA = buildCloudflarePayload(
                candA.emailAddress(),
                "Sarah Recruiter <recruiter@acme.com>",
                "Interview Invitation: Senior Platform Engineer",
                "Hello, we invite you to interview for Senior Platform Engineer (Req REQ-ACME-101, https://careers.acme.com/jobs/platform-eng-101).",
                "<iso-inv-1@acme.com>",
                null,
                null,
                null
        );
        deliverSigned(bodyA, "tok-iso-a-" + UUID.randomUUID(), t);

        UUID emailAId = jdbc.queryForObject(
                "select id from emails where message_id = 'iso-inv-1@acme.com' and profile_id = ?",
                UUID.class, candA.profileId());

        // 1. Verify Candidate B has 0 emails and 0 links
        Integer candBEmails = jdbc.queryForObject("select count(*) from emails where profile_id = ?", Integer.class, candB.profileId());
        assertThat(candBEmails).isZero();

        Integer candBLinks = jdbc.queryForObject("select count(*) from email_application_links where profile_id = ?", Integer.class, candB.profileId());
        assertThat(candBLinks).isZero();

        // 2. Verify Candidate B's application has 0 employer message events
        Integer appBEvents = jdbc.queryForObject(
                "select count(*) from application_events where application_id = ? and type = 'EMPLOYER_MESSAGE_RECEIVED'",
                Integer.class, appB);
        assertThat(appBEvents).isZero();

        // 3. Verify Candidate B's tracking view is pristine
        Map<String, Object> viewB = tracking.view(candB.profileId(), appB).orElseThrow();
        assertThat(viewB.get("status")).isEqualTo("PREPARING");
        assertThat(viewB.get("pending")).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> commsB = (Map<String, Object>) viewB.get("communications");
        assertThat(commsB.get("linkedMessages")).isEqualTo(0);
        assertThat(commsB.get("awaitingReview")).isEqualTo(0);

        // 4. Verify Candidate B cannot access or review Candidate A's email
        assertThatThrownBy(() -> tracking.reviewMessage(candB.profileId(), emailAId,
                new MessageReviewCommand("CONFIRM", appB, "INTERVIEW_INVITATION", null)))
                .isInstanceOf(NoSuchElementException.class);

        // 5. Verify Candidate B's review queue is empty
        List<Map<String, Object>> reviewsB = tracking.reviews(candB.profileId());
        assertThat(reviewsB).isEmpty();
    }

    @Test
    @DisplayName("Scenario 4: Non-recruiter generic messages and ambiguous company-only messages are never incorrectly classified or auto-linked")
    void scenario4_nonRecruiterAndAmbiguousMessagesHandling() throws Exception {
        TestCandidate cand = createCandidate("cand-ambig@example.com", "Filter Candidate");
        String jobUrl = "https://careers.acme.com/jobs/platform-eng-101";
        String reqId = "REQ-ACME-101";
        UUID app = createApplicationWithJob(cand.profileId(), "Acme Corp", "Senior Platform Engineer", jobUrl, reqId);

        // 1. Generic non-recruiter newsletter message
        String newsletterBody = buildCloudflarePayload(
                cand.emailAddress(),
                "Tech Digest <newsletter@techdigest.example>",
                "Weekly Tech Roundup: System Architecture",
                "Explore the latest insights on cloud-native architecture, event-driven microservices, and CI/CD pipelines.",
                "<newsletter-888@techdigest.example>",
                null,
                null,
                null
        );
        deliverSigned(newsletterBody, "tok-nl-" + UUID.randomUUID(), Instant.now().minus(Duration.ofMinutes(3)));

        Map<String, Object> nlEmail = jdbc.queryForMap(
                "select id, application_id, classification from emails where message_id = 'newsletter-888@techdigest.example' and profile_id = ?",
                cand.profileId());
        assertThat(nlEmail.get("application_id")).isNull();
        assertThat(nlEmail.get("classification")).isEqualTo("UNRELATED_OR_AMBIGUOUS");

        Map<String, Object> nlLink = jdbc.queryForMap(
                "select application_id, status, match_strength from email_application_links where email_id = ?",
                nlEmail.get("id"));
        assertThat(nlLink.get("application_id")).isNull();
        assertThat(nlLink.get("status")).isEqualTo("UNMATCHED");
        assertThat(nlLink.get("match_strength")).isEqualTo("NONE");

        // 2. Ambiguous recruiter message with only weak company-name signal (no job URL, title, or requisition)
        String ambiguousBody = buildCloudflarePayload(
                cand.emailAddress(),
                "Acme Careers <recruiting@acme.com>",
                "Update from Acme Corp Recruiting",
                "Hello, our recruiting team at Acme Corp is currently reviewing general talent network submissions. We will reach out if there are updates.",
                "<ambiguous-acme-999@acme.com>",
                null,
                null,
                null
        );
        deliverSigned(ambiguousBody, "tok-amb-" + UUID.randomUUID(), Instant.now().minus(Duration.ofMinutes(1)));

        Map<String, Object> ambEmail = jdbc.queryForMap(
                "select id, application_id, classification from emails where message_id = 'ambiguous-acme-999@acme.com' and profile_id = ?",
                cand.profileId());
        assertThat(ambEmail.get("application_id")).isNull();
        assertThat(ambEmail.get("classification")).isEqualTo("RECRUITER_FOLLOW_UP");

        Map<String, Object> ambLink = jdbc.queryForMap(
                "select application_id, status, match_strength from email_application_links where email_id = ?",
                ambEmail.get("id"));
        assertThat(ambLink.get("application_id")).isNull();
        assertThat(ambLink.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(ambLink.get("match_strength")).isEqualTo("WEAK");

        // Neither generic nor ambiguous message created an EMPLOYER_MESSAGE_RECEIVED event on app
        Integer employerMsgCount = jdbc.queryForObject(
                "select count(*) from application_events where application_id = ? and type = 'EMPLOYER_MESSAGE_RECEIVED'",
                Integer.class, app);
        assertThat(employerMsgCount).isZero();

        // The reviews queue contains both the unmatched newsletter and the pending recruiter review
        List<Map<String, Object>> reviews = tracking.reviews(cand.profileId());
        assertThat(reviews).hasSize(2);
        assertThat(reviews).extracting(r -> r.get("status")).containsExactlyInAnyOrder("PENDING_REVIEW", "UNMATCHED");
        assertThat(reviews).extracting(r -> r.get("emailId")).contains(ambEmail.get("id").toString());
    }

    @Test
    @DisplayName("Scenario 5: A reply that no longer quotes the job joins its thread through In-Reply-To/References")
    void scenario5_replyWithoutIdentifiersFollowsItsThread() throws Exception {
        TestCandidate cand = createCandidate("cand-thread-only@example.com", "Thread Only Candidate");
        String jobUrl = "https://careers.acme.com/jobs/platform-eng-101";
        String reqId = "REQ-ACME-101";
        UUID app = createApplicationWithJob(cand.profileId(), "Acme Corp", "Senior Platform Engineer", jobUrl, reqId);

        String invitation = buildCloudflarePayload(cand.emailAddress(), "Sarah Recruiter <recruiter@acme.com>",
                "Interview Invitation: Senior Platform Engineer",
                "Hello, we would like to invite you to an interview for the Senior Platform Engineer position (Req "
                        + reqId + ", " + jobUrl + ").",
                "<thread-only-01@acme.com>", "Tue, 27 Oct 2026 10:00:00 +0000", null, null);
        assertThat(deliverSigned(invitation, "tok-to-01-" + UUID.randomUUID(), Instant.now().minus(Duration.ofMinutes(3)))
                .getResponse().getStatus()).isEqualTo(200);

        // No job link, no requisition: without the thread headers this would only be a weak, review-only match.
        String reply = buildCloudflarePayload(cand.emailAddress(), "Sarah Recruiter <recruiter@acme.com>",
                "Re: Interview Invitation: Senior Platform Engineer",
                "Thanks for confirming. Does Thursday at two still suit you?",
                "<thread-only-02@acme.com>", "Tue, 27 Oct 2026 12:00:00 +0000",
                "<thread-only-01@acme.com>", "<thread-only-01@acme.com>");
        assertThat(deliverSigned(reply, "tok-to-02-" + UUID.randomUUID(), Instant.now().minus(Duration.ofMinutes(1)))
                .getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> link = jdbc.queryForMap("""
                select l.application_id, l.status, l.match_strength, l.rationale::text as rationale
                from email_application_links l join emails e on e.id = l.email_id
                where e.message_id = 'thread-only-02@acme.com' and e.profile_id = ?
                """, cand.profileId());
        assertThat(link.get("application_id")).isEqualTo(app);
        assertThat(link.get("status")).isEqualTo("LINKED");
        assertThat(link.get("match_strength")).isEqualTo("STRONG");
        assertThat((String) link.get("rationale")).contains("THREAD_REPLY");

        Integer employerMessages = jdbc.queryForObject(
                "select count(*) from application_events where application_id = ? and type = 'EMPLOYER_MESSAGE_RECEIVED'",
                Integer.class, app);
        assertThat(employerMessages).isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario 6: A thread id from another candidate's mail links nothing")
    void scenario6_threadReferencesNeverCrossCandidates() throws Exception {
        TestCandidate owner = createCandidate("cand-thread-owner@example.com", "Thread Owner");
        TestCandidate other = createCandidate("cand-thread-other@example.com", "Thread Other");
        String jobUrl = "https://initech.example/jobs/backend-lead";
        UUID ownersApp = createApplicationWithJob(owner.profileId(), "Initech", "Backend Lead", jobUrl, "REQ-INIT-202");
        createApplicationWithJob(other.profileId(), "Acme Corp", "Senior Platform Engineer",
                "https://careers.acme.com/jobs/platform-eng-101", "REQ-ACME-101");

        String ownersInvitation = buildCloudflarePayload(owner.emailAddress(), "Peter Recruiter <peter@initech.example>",
                "Interview Invitation: Backend Lead",
                "We would like to invite you to interview for Backend Lead at Initech (Req REQ-INIT-202, " + jobUrl + ").",
                "<cross-thread-01@initech.example>", null, null, null);
        assertThat(deliverSigned(ownersInvitation, "tok-ct-01-" + UUID.randomUUID(), Instant.now().minus(Duration.ofMinutes(2)))
                .getResponse().getStatus()).isEqualTo(200);
        Integer ownersEventsBefore = jdbc.queryForObject(
                "select count(*) from application_events where application_id = ?", Integer.class, ownersApp);

        // The other candidate receives a message claiming to continue the owner's thread.
        String forgedReply = buildCloudflarePayload(other.emailAddress(), "Mallory <mallory@unrelated.example>",
                "Re: Interview Invitation",
                "Please confirm your availability.",
                "<cross-thread-02@unrelated.example>", null,
                "<cross-thread-01@initech.example>", "<cross-thread-01@initech.example>");
        assertThat(deliverSigned(forgedReply, "tok-ct-02-" + UUID.randomUUID(), Instant.now().minus(Duration.ofMinutes(1)))
                .getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> link = jdbc.queryForMap("""
                select l.application_id, l.status, l.rationale::text as rationale
                from email_application_links l join emails e on e.id = l.email_id
                where e.message_id = 'cross-thread-02@unrelated.example' and e.profile_id = ?
                """, other.profileId());
        assertThat(link.get("application_id")).isNull();
        assertThat((String) link.get("rationale")).doesNotContain("THREAD_REPLY");
        assertThat(jdbc.queryForObject(
                "select count(*) from application_events where application_id = ?", Integer.class, ownersApp))
                .isEqualTo(ownersEventsBefore);
    }

    private JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
