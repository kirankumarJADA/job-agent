package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.JobIdentity;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import com.personal.jobagent.security.UserRecord;
import com.personal.jobagent.track.TrackText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.events.dispatch-interval-ms=3600000",
        "app.worker-event-token=managed-mail-it-token",
        "app.managed-mail.domain=" + CloudflareManagedMailIT.DOMAIN,
        "app.managed-mail.provider=cloudflare",
        "app.managed-mail.address-prefix=robin+",
        "app.managed-mail.cloudflare.webhook-secret=" + CloudflareManagedMailIT.SECRET,
        "app.managed-mail.cloudflare.max-age-seconds=900",
        "app.managed-mail.retention-sweep-initial-delay-ms=3600000",
        "app.secrets.encryption-key=6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U="
})
@AutoConfigureMockMvc
@Testcontainers
class CloudflareManagedMailIT {

    static final String DOMAIN = "mail.aplivenosi.com";
    static final String SECRET = "it-cloudflare-secret-key-abcdef123456";
    static final String INBOUND = "/api/v1/managed-mail/inbound/cloudflare";
    private static final UUID FIXTURE_SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000cf20");

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
    @Autowired private ManagedMailboxService mailboxes;

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
                values (?, 'GREENHOUSE', 'cf-managed-fixture', 'CF Managed Fixture Board', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, FIXTURE_SOURCE);

        jdbc.update("delete from email_application_links");
        jdbc.update("delete from emails");
        jdbc.update("delete from managed_inbound_messages");
        jdbc.update("delete from managed_mail_domains");
    }

    private String reserve(MockHttpSession userSession) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/managed-mail/address").with(csrf()).session(userSession))
                .andExpect(status().isOk()).andReturn();
        return json(res).get("address").asText();
    }

    private String reserve(RequestPostProcessor auth) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/managed-mail/address").with(csrf()).with(auth))
                .andExpect(status().isOk()).andReturn();
        return json(res).get("address").asText();
    }

    private record TestCandidate(UUID userId, UUID profileId, RequestPostProcessor auth, String emailAddress) {}

    private TestCandidate createCandidate(String email, String displayName) throws Exception {
        UUID userId = UuidV7.generate();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, ?, 'FIREBASE')",
                userId, email, displayName);
        UUID profileId = profiles.createProfile(userId, null, null, null, null, null, null, null);
        AppUserDetails principal = new AppUserDetails(new UserRecord(userId, email, null, displayName, null, "FIREBASE"));
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        String address = reserve(auth);
        return new TestCandidate(userId, profileId, auth, address);
    }

    private UUID createApplicationWithJob(UUID profileId, String company, String title, String url, String req) {
        UUID jobId = UuidV7.generate();
        String externalId = "cf-mail-job-" + jobId;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, ?, ?, ?, ?, 'Job description for testing', '{}', 'SCORED', ?, ?, ?)
                """, jobId, FIXTURE_SOURCE, externalId, "dedup-" + externalId, company, title, "hash-" + externalId, url, url);
        UUID appId = pipeline.createApplicationFromMatch(profileId, jobId).applicationId();
        for (JobIdentity.Key k : JobIdentity.keys(FIXTURE_SOURCE, externalId, url, url, company, req)) {
            jdbc.update("insert into job_identity_keys (key, job_id, key_type, source_id) values (?, ?, ?, ?) on conflict (key) do nothing",
                    k.asString(), jobId, k.type().name(), FIXTURE_SOURCE);
        }
        return appId;
    }

    private String buildCloudflarePayload(String envelopeRecipient, String from, String subject, String text, String messageId) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("envelopeRecipient", envelopeRecipient);
        map.put("envelopeSender", "bounces@employer.example");
        map.put("from", from);
        map.put("to", envelopeRecipient);
        map.put("subject", subject);
        map.put("text", text);
        map.put("messageId", messageId != null ? messageId : "<msg-" + UUID.randomUUID() + "@employer.example>");
        map.put("date", "Sat, 24 Oct 2026 10:00:00 +0000");
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
    @DisplayName("Address reservation generates prefix robin+ and readiness transitions to ACTIVE on signed Cloudflare delivery")
    void addressReservationAndReceivingEvidenceTransition() throws Exception {
        // Initial state before receiving evidence
        JsonNode before = json(mvc.perform(get("/api/v1/managed-mail/address").session(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(before.get("readiness").asText()).isEqualTo("PENDING_RECEIVING_VERIFICATION");
        assertThat(before.get("usableForRegistration").asBoolean()).isFalse();

        // Reserve address
        String address = reserve(session);
        assertThat(address).startsWith("robin+").endsWith("@" + DOMAIN);
        String localPart = address.substring(0, address.indexOf('@'));
        assertThat(localPart).matches("^robin\\+[a-km-np-z][a-km-np-z2-9]{15}$");

        // Verify still pending verification until signed delivery
        JsonNode reserved = json(mvc.perform(get("/api/v1/managed-mail/address").session(session)).andReturn());
        assertThat(reserved.get("readiness").asText()).isEqualTo("PENDING_RECEIVING_VERIFICATION");
        assertThat(reserved.get("usableForRegistration").asBoolean()).isFalse();

        // Signed delivery from Cloudflare worker
        String body = buildCloudflarePayload(address, "hr@company.example", "Test Receiving", "Verification body", null);
        String token = "tok-" + UUID.randomUUID();
        MvcResult deliveryResult = deliverSigned(body, token, Instant.now());
        assertThat(deliveryResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(deliveryResult).get("status").asText()).isEqualTo("stored");

        // Verify evidence recorded in managed_mail_domains
        List<Map<String, Object>> domains = jdbc.queryForList(
                "select domain, provider_id, receiving_verified_at, last_inbound_at from managed_mail_domains where domain = ?", DOMAIN);
        assertThat(domains).hasSize(1);
        assertThat(domains.get(0).get("provider_id")).isEqualTo("cloudflare");
        assertThat(domains.get(0).get("receiving_verified_at")).isNotNull();

        // Status is now ACTIVE and usable for registration
        JsonNode active = json(mvc.perform(get("/api/v1/managed-mail/address").session(session)).andReturn());
        assertThat(active.get("readiness").asText()).isEqualTo("ACTIVE");
        assertThat(active.get("usableForRegistration").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Cloudflare webhook idempotency: duplicate delivery acknowledged, token replay rejected")
    void webhookIdempotencyAndReplayProtection() throws Exception {
        String address = reserve(session);
        String body = buildCloudflarePayload(address, "recruiter@acme.example", "Interview invitation", "Congratulations", "<inv-1@acme.example>");
        String token = "tok-replay-test-" + UUID.randomUUID();
        Instant now = Instant.now();

        // First delivery: stored
        MvcResult first = deliverSigned(body, token, now);
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(first).get("status").asText()).isEqualTo("stored");

        // Duplicate delivery (same payload, same token): acknowledged with 200 duplicate
        MvcResult duplicate = deliverSigned(body, token, now);
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(duplicate).get("status").asText()).isEqualTo("duplicate");

        // Replay attempt (different payload, same token): rejected with 406
        String alteredBody = buildCloudflarePayload(address, "recruiter@acme.example", "Tampered", "Hacked body", "<inv-1@acme.example>");
        MvcResult replay = deliverSigned(alteredBody, token, now);
        assertThat(replay.getResponse().getStatus()).isEqualTo(406);

        // Same message re-sent with new token: acknowledged with 200 duplicate
        String newToken = "tok-fresh-" + UUID.randomUUID();
        MvcResult redeliver = deliverSigned(body, newToken, Instant.now());
        assertThat(redeliver.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(redeliver).get("status").asText()).isEqualTo("duplicate");

        // Database has exactly 1 stored message
        Integer count = jdbc.queryForObject("select count(*) from managed_inbound_messages where envelope_recipient = ?", Integer.class, address);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Unknown envelope recipient rejected with 406 and not stored")
    void unknownRecipientRejected() throws Exception {
        String unknownRecipient = "robin+unknown99999999999@" + DOMAIN;
        String body = buildCloudflarePayload(unknownRecipient, "recruiter@acme.example", "Hello", "Unknown", null);
        String token = "tok-unknown-" + UUID.randomUUID();

        MvcResult result = deliverSigned(body, token, Instant.now());
        assertThat(result.getResponse().getStatus()).isEqualTo(406);
        assertThat(json(result).get("status").asText()).isEqualTo("rejected");

        Integer count = jdbc.queryForObject("select count(*) from managed_inbound_messages", Integer.class);
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("Unauthenticated, forged or stale Cloudflare deliveries are refused")
    void authenticationAndStaleRefusal() throws Exception {
        String address = reserve(session);
        String body = buildCloudflarePayload(address, "recruiter@acme.example", "Hello", "Test", null);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String token = "tok-" + UUID.randomUUID();

        // 1. Missing signature header
        mvc.perform(post(INBOUND).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Robin-Timestamp", ts).header("X-Robin-Token", token))
                .andExpect(status().isUnauthorized());

        // 2. Forged signature
        mvc.perform(post(INBOUND).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Robin-Timestamp", ts).header("X-Robin-Token", token)
                        .header("X-Robin-Signature", "0000000000000000000000000000000000000000000000000000000000000000"))
                .andExpect(status().isUnauthorized());

        // 3. Stale delivery (older than maxAge = 900s)
        Instant staleTime = Instant.now().minus(Duration.ofSeconds(1000));
        MvcResult stale = deliverSigned(body, "tok-stale-" + UUID.randomUUID(), staleTime);
        assertThat(stale.getResponse().getStatus()).isEqualTo(406);

        // Database remains pristine
        Integer count = jdbc.queryForObject("select count(*) from managed_inbound_messages", Integer.class);
        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("Cross-candidate isolation: deliveries routed exclusively to recipient owner")
    void crossCandidateIsolation() throws Exception {
        String cand1Address = reserve(session);

        UUID user2 = UuidV7.generate();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, 'cand2@test.local', null, 'Candidate 2', 'FIREBASE')", user2);
        UUID profile2 = profiles.createProfile(user2, null, null, null, null, null, null, null);
        AppUserDetails principal2 = new AppUserDetails(new UserRecord(user2, "cand2@test.local", null, "Candidate 2", null, "FIREBASE"));
        RequestPostProcessor cand2Auth = authentication(new UsernamePasswordAuthenticationToken(principal2, null, principal2.getAuthorities()));

        String cand2Address = reserve(cand2Auth);
        assertThat(cand2Address).isNotEqualTo(cand1Address);

        // Deliver message to Candidate 1
        String body1 = buildCloudflarePayload(cand1Address, "hr@company.example", "Candidate 1 Letter", "Dear Candidate 1", null);
        deliverSigned(body1, "tok-c1-" + UUID.randomUUID(), Instant.now());

        // Deliver message to Candidate 2
        String body2 = buildCloudflarePayload(cand2Address, "hr@company.example", "Candidate 2 Letter", "Dear Candidate 2", null);
        deliverSigned(body2, "tok-c2-" + UUID.randomUUID(), Instant.now());

        // Verify database isolation
        List<Map<String, Object>> c1Rows = jdbc.queryForList("select subject_redacted from managed_inbound_messages where profile_id = ?", devProfile);
        assertThat(c1Rows).hasSize(1);
        assertThat(c1Rows.get(0).get("subject_redacted")).isEqualTo("Candidate 1 Letter");

        List<Map<String, Object>> c2Rows = jdbc.queryForList("select subject_redacted from managed_inbound_messages where profile_id = ?", profile2);
        assertThat(c2Rows).hasSize(1);
        assertThat(c2Rows.get(0).get("subject_redacted")).isEqualTo("Candidate 2 Letter");

        // Verify API endpoint isolation
        String c1Messages = mvc.perform(get("/api/v1/managed-mail/messages").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(c1Messages).contains("Candidate 1 Letter").doesNotContain("Candidate 2 Letter");

        String c2Messages = mvc.perform(get("/api/v1/managed-mail/messages").with(cand2Auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(c2Messages).contains("Candidate 2 Letter").doesNotContain("Candidate 1 Letter");
    }

    @Test
    @DisplayName("Cloudflare inbound webhook associates employer email with candidate's job application")
    void inboundCloudflareWebhookApplicationAssociationAndIsolation() throws Exception {
        // 1. Create isolated candidate A and candidate B
        TestCandidate candidateA = createCandidate("candA-" + UUID.randomUUID() + "@test.local", "Candidate A");
        TestCandidate candidateB = createCandidate("candB-" + UUID.randomUUID() + "@test.local", "Candidate B");

        // 2. Create active job application for Candidate A at Acme Corp
        String acmeUrl = "https://boards.greenhouse.io/acme/jobs/1000001";
        UUID appA = createApplicationWithJob(candidateA.profileId(), "Acme Corp", "Senior Platform Engineer", acmeUrl, "REQ-ACME-101");

        // Create an unrelated job application for Candidate B at Initech
        String initechUrl = "https://boards.greenhouse.io/initech/jobs/2000002";
        UUID appB = createApplicationWithJob(candidateB.profileId(), "Initech", "Backend Lead", initechUrl, "REQ-INI-202");

        // 3. Construct realistic employer email matching Candidate A's application
        String emailBody = buildCloudflarePayload(
                candidateA.emailAddress(),
                "Acme Careers <recruiting@acme.com>",
                "Application Received: Senior Platform Engineer",
                "Hi Candidate A, thank you for applying to the Senior Platform Engineer role at Acme Corp. "
                        + "Your application for " + acmeUrl + " (Requisition: REQ-ACME-101) has been received.",
                "<ack-acme-101@acme.com>"
        );

        // 4. Sign and send Cloudflare webhook
        String tokenA = "tok-assoc-" + UUID.randomUUID();
        MvcResult deliveryResult = deliverSigned(emailBody, tokenA, Instant.now());

        // 5. Verify webhook response
        assertThat(deliveryResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(deliveryResult).get("status").asText()).isEqualTo("stored");

        // 6. Verify inbound message is persisted in managed_inbound_messages and emails
        List<Map<String, Object>> managedMsgs = jdbc.queryForList(
                "select profile_id, envelope_recipient, sender_domain, content_encrypted from managed_inbound_messages where envelope_recipient = ?",
                candidateA.emailAddress());
        assertThat(managedMsgs).hasSize(1);
        assertThat(managedMsgs.get(0).get("profile_id")).isEqualTo(candidateA.profileId());
        assertThat(managedMsgs.get(0).get("sender_domain")).isEqualTo("acme.com");
        assertThat(managedMsgs.get(0).get("content_encrypted")).isNotNull();

        List<Map<String, Object>> emailsA = jdbc.queryForList(
                "select id, profile_id, application_id, classification, message_id from emails where profile_id = ?",
                candidateA.profileId());
        assertThat(emailsA).hasSize(1);
        UUID emailIdA = (UUID) emailsA.get(0).get("id");
        assertThat(emailsA.get(0).get("application_id")).isEqualTo(appA);
        assertThat(emailsA.get(0).get("message_id")).isEqualTo("ack-acme-101@acme.com");
        assertThat(emailsA.get(0).get("classification")).isEqualTo("APPLICATION_ACKNOWLEDGEMENT");

        // 7. Verify email_application_links record links message to intended application
        List<Map<String, Object>> linksA = jdbc.queryForList(
                "select id, profile_id, email_id, application_id, status, match_strength from email_application_links where profile_id = ?",
                candidateA.profileId());
        assertThat(linksA).hasSize(1);
        assertThat(linksA.get(0).get("email_id")).isEqualTo(emailIdA);
        assertThat(linksA.get(0).get("application_id")).isEqualTo(appA);
        assertThat(linksA.get(0).get("status")).isEqualTo("LINKED");
        assertThat(linksA.get(0).get("match_strength")).isEqualTo("STRONG");

        // 8. Verify the association belongs strictly to Candidate A
        assertThat(linksA.get(0).get("profile_id")).isEqualTo(candidateA.profileId());

        // 9. Verify no other candidate or unrelated application was modified
        Integer candidateBEmailCount = jdbc.queryForObject(
                "select count(*) from emails where profile_id = ?", Integer.class, candidateB.profileId());
        assertThat(candidateBEmailCount).isZero();

        Integer candidateBLinkCount = jdbc.queryForObject(
                "select count(*) from email_application_links where profile_id = ?", Integer.class, candidateB.profileId());
        assertThat(candidateBLinkCount).isZero();

        Integer appBEventCount = jdbc.queryForObject(
                "select count(*) from application_events where application_id = ? and type != 'APPLICATION_CREATED'",
                Integer.class, appB);
        assertThat(appBEventCount).isZero();

        // 10. Negative Case: Ambiguous / weak message cannot be confidently associated
        // An email from Acme mentioning only company name with no job URL, title, or requisition
        String ambiguousBody = buildCloudflarePayload(
                candidateA.emailAddress(),
                "Acme Careers <recruiting@acme.com>",
                "Checking in from Acme Corp",
                "Hello, this is a general update regarding our hiring pipeline at Acme Corp. We are reviewing candidates.",
                "<general-acme-update@acme.com>"
        );
        String tokenAmbiguous = "tok-ambig-" + UUID.randomUUID();
        MvcResult ambResult = deliverSigned(ambiguousBody, tokenAmbiguous, Instant.now());
        assertThat(ambResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(ambResult).get("status").asText()).isEqualTo("stored");

        // Inbound message is persisted in emails, but application_id is null
        Map<String, Object> ambEmailRow = jdbc.queryForMap(
                "select id, application_id from emails where message_id = 'general-acme-update@acme.com' and profile_id = ?",
                candidateA.profileId());
        assertThat(ambEmailRow.get("application_id")).isNull();

        // In email_application_links, application_id is null and status is PENDING_REVIEW (weak signals only)
        Map<String, Object> ambLinkRow = jdbc.queryForMap(
                "select application_id, status, match_strength from email_application_links where email_id = ?",
                ambEmailRow.get("id"));
        assertThat(ambLinkRow.get("application_id")).isNull();
        assertThat(ambLinkRow.get("status")).isEqualTo("PENDING_REVIEW");
        assertThat(ambLinkRow.get("match_strength")).isEqualTo("WEAK");

        // Candidate B and App B remain completely untouched
        assertThat(jdbc.queryForObject("select count(*) from emails where profile_id = ?", Integer.class, candidateB.profileId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from email_application_links where profile_id = ?", Integer.class, candidateB.profileId())).isZero();
    }

    private JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }
}
