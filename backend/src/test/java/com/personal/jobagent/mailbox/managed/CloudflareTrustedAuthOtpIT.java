package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.documents.DocumentFactValidator;
import com.personal.jobagent.identity.IdentityService;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import com.personal.jobagent.security.UserRecord;
import com.personal.jobagent.track.TrackText;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 3I Phase 3 Integration Test on PostgreSQL 16 (Flyway V001-V072) + MockMvc:
 *
 * - Separated transport vs senderAuthentication vs employerVerification readiness
 * - Cloudflare OTP/link resolution fail-closed on untrusted auth and fail-open on trusted DKIM-aligned mail
 * - Bounded verification lease lifecycle (/resolve -> /release, expired lease reclamation, /confirm consumption & erasure)
 * - Managed Mail VERIFICATION_CODE non-retention in emails.body_text
 * - Cross-provider domain isolation (mailgun vs cloudflare)
 * - IdentityService ROBIN_MANAGED email protection and RegistrationIdentityService disabled-identity fail-closed checks
 * - POST /api/v1/apply/applications/{id}/universal-account authorization flow and refusal gates
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.events.dispatch-interval-ms=3600000",
        "app.worker-event-token=" + CloudflareTrustedAuthOtpIT.WORKER_TOKEN,
        "app.managed-mail.domain=" + CloudflareTrustedAuthOtpIT.DOMAIN,
        "app.managed-mail.provider=cloudflare",
        "app.managed-mail.address-prefix=robin+",
        "app.managed-mail.cloudflare.webhook-secret=" + CloudflareTrustedAuthOtpIT.SECRET,
        "app.managed-mail.cloudflare.max-age-seconds=900",
        "app.managed-mail.cloudflare.trusted-authserv-ids=mx.cloudflare.net",
        "app.managed-mail.retention-sweep-initial-delay-ms=3600000",
        "app.automation.universal-enabled=true",
        "app.secrets.encryption-key=6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U="
})
@AutoConfigureMockMvc
@Testcontainers
class CloudflareTrustedAuthOtpIT {

    static final String DOMAIN = "mail.aplivenosi.com";
    static final String SECRET = "cf-trusted-otp-webhook-secret-999999999";
    static final String WORKER_TOKEN = "cf-trusted-otp-worker-token";
    static final String WORKER_HEADER = "Bearer " + WORKER_TOKEN;
    static final String INBOUND = "/api/v1/managed-mail/inbound/cloudflare";
    private static final UUID FIXTURE_SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000cf72");

    @TestConfiguration
    static class OfflineAddressGuardConfig {
        @Bean
        @Primary
        AddressGuard trustedOtpAddressGuard() {
            return new AddressGuard(host -> {
                if ("acme-verify.example".equalsIgnoreCase(host) || "careers.acme-verify.example".equalsIgnoreCase(host)) {
                    return new InetAddress[]{InetAddress.getByName("93.184.216.34")};
                }
                throw new UnknownHostException(host);
            });
        }
    }

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
    @Autowired private IdentityService identityService;
    @Autowired private RegistrationIdentityService registrationIdentities;

    @BeforeEach
    void setUp() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'cf-trusted-otp-fixture', 'CF Trusted OTP Fixture', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, FIXTURE_SOURCE);
        jdbc.update("delete from mailbox_verifications");
        jdbc.update("delete from email_application_links");
        jdbc.update("delete from emails");
        jdbc.update("delete from managed_inbound_messages");
        jdbc.update("delete from managed_mail_addresses");
        jdbc.update("delete from managed_mail_domains");
        jdbc.update("delete from managed_mail_webhook_tokens");
    }

    private record Candidate(UUID userId, UUID profileId, RequestPostProcessor auth, String managedAddress) {}

    private Candidate createCandidate(String email, boolean reserveAddress) throws Exception {
        UUID userId = UuidV7.generate();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, ?, 'FIREBASE')",
                userId, email, "Candidate " + email);
        UUID profileId = profiles.createProfile(userId, null, null, null, null, null, null, null);
        AppUserDetails principal = new AppUserDetails(new UserRecord(userId, email, null, "Candidate", null, "FIREBASE"));
        RequestPostProcessor auth = authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        String addr = null;
        if (reserveAddress) {
            MvcResult res = mvc.perform(post("/api/v1/managed-mail/address").with(csrf()).with(auth))
                    .andExpect(status().isOk()).andReturn();
            addr = json(res).get("address").asText();
        }
        return new Candidate(userId, profileId, auth, addr);
    }

    private UUID createApp(UUID profileId, String url) {
        UUID jobId = UuidV7.generate();
        String externalId = "cf-otp-job-" + jobId;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, ?, ?, 'Acme Verify', 'Backend Engineer', 'Build reliable systems', '{}', 'SCORED', ?, ?, ?)
                """, jobId, FIXTURE_SOURCE, externalId, "dedup-" + externalId, "hash-" + externalId, url, url);
        return pipeline.createApplicationFromMatch(profileId, jobId).applicationId();
    }

    private UUID startSession(UUID profileId, UUID appId, String employerDomain) {
        return identityService.startSession(appId, employerDomain, profileId).orElseThrow();
    }

    private void seedRunningPlan(UUID appId, String targetUrl) {
        jdbc.update("""
                insert into automation_plans (id, profile_id, application_id, target_url, status, idempotency_key, plan)
                select ?, profile_id, id, ?, 'RUNNING', ?, '{"planType":"UNIVERSAL","steps":[]}'::jsonb
                from applications where id = ?
                """, UuidV7.generate(), targetUrl, "it-cf-" + UUID.randomUUID(), appId);
    }

    private MvcResult deliverCloudflare(String recipient, String from, String subject, String text,
                                        String dkimDomain, String authServId, boolean authTrusted,
                                        Instant timestamp) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("envelopeRecipient", recipient);
        map.put("envelopeSender", "bounces@acme-verify.example");
        map.put("from", from);
        map.put("to", recipient);
        map.put("subject", subject);
        map.put("text", text);
        map.put("messageId", "<msg-" + UUID.randomUUID() + "@acme-verify.example>");
        map.put("date", "Thu, 26 Nov 2026 12:00:00 +0000");
        map.put("spf", "pass");
        map.put("dkim", "pass");
        map.put("dmarc", "pass");
        if (dkimDomain != null) map.put("dkimDomain", dkimDomain);
        if (authServId != null) map.put("authServId", authServId);
        map.put("authTrusted", authTrusted);
        String body = json.writeValueAsString(map);

        String ts = String.valueOf(timestamp.getEpochSecond());
        String token = "tok-" + UUID.randomUUID();
        String sig = CloudflareSignatureVerifier.hmacHex(SECRET, ts + "." + token + "." + TrackText.sha256(body));
        return mvc.perform(post(INBOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Robin-Timestamp", ts)
                        .header("X-Robin-Token", token)
                        .header("X-Robin-Signature", sig))
                .andReturn();
    }

    private JsonNode json(MvcResult res) throws Exception {
        return json.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void cloudflareTrustedOtpFailClosedUntrustedReleaseExpiryConfirmNonRetentionAndProviderIsolation() throws Exception {
        Candidate cand = createCandidate("cand-cf-otp@personal.example", true);

        // 0. Cross-provider isolation: Mailgun domain row does not make Cloudflare transport ACTIVE
        jdbc.update("""
                insert into managed_mail_domains (domain, provider_id, receiving_verified_at, last_inbound_at)
                values (?, 'mailgun', now(), now()) on conflict (domain) do nothing
                """, DOMAIN);
        JsonNode preStatus = json(mvc.perform(get("/api/v1/managed-mail/address").with(cand.auth()))
                .andExpect(status().isOk()).andReturn());
        assertThat(preStatus.get("readiness").asText()).isEqualTo("PENDING_RECEIVING_VERIFICATION");

        // 1. Untrusted Cloudflare delivery activates transport, creates message, but fails closed for OTP resolution
        Instant base = Instant.now().minusSeconds(120);
        assertThat(deliverCloudflare(cand.managedAddress(), "Security <security@acme-verify.example>",
                "Untrusted OTP", "Your verification code is 112233",
                "acme-verify.example", "mx.cloudflare.net", false, base).getResponse().getStatus()).isEqualTo(200);

        JsonNode activeStatus = json(mvc.perform(get("/api/v1/managed-mail/address").with(cand.auth()))
                .andExpect(status().isOk()).andReturn());
        assertThat(activeStatus.get("readiness").asText()).isEqualTo("ACTIVE");
        assertThat(activeStatus.get("transportReadiness").asText()).isEqualTo("ACTIVE");
        assertThat(activeStatus.get("senderAuthenticationReadiness").asText()).isEqualTo("CONFIGURED");
        assertThat(activeStatus.get("employerVerificationReadiness").asText()).isEqualTo("ACTIVE");
        assertThat(activeStatus.get("usableForAutomatedVerification").asBoolean()).isTrue();
        assertThat(activeStatus.get("trustedAuthServConfigured").asBoolean()).isTrue();

        // Also deliver an authTrusted=true message with UNALIGNED dkimDomain -> must fail closed too
        assertThat(deliverCloudflare(cand.managedAddress(), "Security <security@acme-verify.example>",
                "Unaligned DKIM", "Your verification code is 445566",
                "unrelated-sender.example", "mx.cloudflare.net", true, base.plusSeconds(10)).getResponse().getStatus()).isEqualTo(200);

        UUID app1 = createApp(cand.profileId(), "https://acme-verify.example/jobs/1");
        seedRunningPlan(app1, "https://acme-verify.example/jobs/1");
        UUID sess1 = startSession(cand.profileId(), app1, "acme-verify.example");
        jdbc.update("update account_sessions set created_at = now() - interval '4 minutes' where id = ?", sess1);
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app1.toString(),
                                "sessionId", sess1.toString(),
                                "employerDomain", "acme-verify.example"))))
                .andExpect(status().isOk());

        // Both untrusted & unaligned OTPs are inside the session window yet rejected (404)
        String resolveBody1 = json.writeValueAsString(Map.of(
                "applicationId", app1.toString(),
                "sessionId", sess1.toString(),
                "employerDomain", "acme-verify.example"));
        mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(resolveBody1))
                .andExpect(status().isNotFound());

        // 2. Trusted + DKIM-aligned Cloudflare OTP upgrades senderAuthenticationReadiness to VERIFIED,
        // resolves cleanly, and retains NO body text in emails table
        assertThat(deliverCloudflare(cand.managedAddress(), "Security <security@acme-verify.example>",
                "Verification code", "Your verification code is 739204",
                "mail.acme-verify.example", "mx.cloudflare.net", true, Instant.now()).getResponse().getStatus()).isEqualTo(200);

        JsonNode verifiedStatus = json(mvc.perform(get("/api/v1/managed-mail/address").with(cand.auth()))
                .andExpect(status().isOk()).andReturn());
        assertThat(verifiedStatus.get("senderAuthenticationReadiness").asText()).isEqualTo("VERIFIED");

        Map<String, Object> emailRow = jdbc.queryForMap(
                "select body_text, body_retained, classification from emails where subject = 'Verification code' and profile_id = ?",
                cand.profileId());
        assertThat(emailRow.get("classification")).isEqualTo("VERIFICATION_CODE");
        assertThat(emailRow.get("body_text")).isEqualTo("");
        assertThat(emailRow.get("body_retained")).isEqualTo(false);

        JsonNode lease1 = json(mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(resolveBody1))
                .andExpect(status().isOk()).andReturn());
        assertThat(lease1.get("otp").asText()).isEqualTo("739204");
        String vid1 = lease1.get("verificationId").asText();
        String tok1 = lease1.get("leaseToken").asText();

        // 3. Releasing lease preserves content_encrypted and makes OTP resolvable again
        mvc.perform(post("/api/v1/mailbox-verification/release").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app1.toString(),
                                "sessionId", sess1.toString(),
                                "verificationId", vid1,
                                "leaseToken", tok1,
                                "reason", "TEST_RELEASE"))))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from mailbox_verifications where id = ?::uuid", String.class, vid1))
                .isEqualTo("RELEASED");

        JsonNode lease2 = json(mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(resolveBody1))
                .andExpect(status().isOk()).andReturn());
        assertThat(lease2.get("otp").asText()).isEqualTo("739204");
        String vid2 = lease2.get("verificationId").asText();
        String tok2 = lease2.get("leaseToken").asText();

        // 4. Simulate worker crash expiring lease -> app2 can reclaim and confirm it
        jdbc.update("update mailbox_verifications set reserved_until = now() - interval '2 minutes' where id = ?::uuid", vid2);
        jdbc.update("update managed_inbound_messages set reservation_expires_at = now() - interval '2 minutes' where reserved_for_application_id = ?",
                app1);

        UUID app2 = createApp(cand.profileId(), "https://acme-verify.example/jobs/2");
        seedRunningPlan(app2, "https://acme-verify.example/jobs/2");
        UUID sess2 = startSession(cand.profileId(), app2, "acme-verify.example");
        jdbc.update("update account_sessions set created_at = now() - interval '4 minutes' where id = ?", sess2);
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app2.toString(),
                                "sessionId", sess2.toString(),
                                "employerDomain", "acme-verify.example"))))
                .andExpect(status().isOk());

        JsonNode lease3 = json(mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app2.toString(),
                                "sessionId", sess2.toString(),
                                "employerDomain", "acme-verify.example"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(lease3.get("otp").asText()).isEqualTo("739204");
        assertThat(jdbc.queryForObject("select status from mailbox_verifications where id = ?::uuid", String.class, vid2))
                .isEqualTo("EXPIRED");

        // Old expired lease confirm is rejected (409), active lease3 confirm succeeds (200) and erases content
        mvc.perform(post("/api/v1/mailbox-verification/confirm").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app1.toString(),
                                "verificationId", vid2,
                                "leaseToken", tok2))))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/mailbox-verification/confirm").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app2.toString(),
                                "sessionId", sess2.toString(),
                                "verificationId", lease3.get("verificationId").asText(),
                                "leaseToken", lease3.get("leaseToken").asText()))))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from managed_inbound_messages where content_encrypted is not null and sender_domain = 'acme-verify.example' and consumed_at is not null", Integer.class))
                .isZero();
    }

    @Test
    void universalAccountAuthorizationAndIdentityProtectionsEndToEnd() throws Exception {
        Candidate unreadyCand = createCandidate("cand-unready@personal.example", true);
        UUID unreadyApp = createApp(unreadyCand.profileId(), "https://acme-verify.example/jobs/unready");

        // 1. Terms not accepted -> 409 TERMS_NOT_ACCEPTED
        mvc.perform(post("/api/v1/apply/applications/{id}/universal-account", unreadyApp)
                        .with(csrf()).with(unreadyCand.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":false}"))
                .andExpect(status().isConflict());

        // 2. Managed mailbox not yet active -> 409 MANAGED_MAILBOX_NOT_READY
        MvcResult notReadyRes = mvc.perform(post("/api/v1/apply/applications/{id}/universal-account", unreadyApp)
                        .with(csrf()).with(unreadyCand.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":true}"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(notReadyRes).get("code").asText()).isEqualTo("MANAGED_MAILBOX_NOT_READY");

        // Activate Cloudflare receiving via signed delivery
        Candidate readyCand = createCandidate("cand-ready@personal.example", true);
        assertThat(deliverCloudflare(readyCand.managedAddress(), "Security <security@acme-verify.example>",
                "Welcome", "Active mailbox test",
                "acme-verify.example", "mx.cloudflare.net", true, Instant.now()).getResponse().getStatus()).isEqualTo(200);

        UUID readyApp = createApp(readyCand.profileId(), "https://acme-verify.example/jobs/ready");

        // Missing reviewed CV fails closed with 409 EXECUTION_PACKAGE_BLOCKED without persisting credentials
        MvcResult blockedRes = mvc.perform(post("/api/v1/apply/applications/{id}/universal-account", readyApp)
                        .with(csrf()).with(readyCand.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":true}"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(blockedRes).get("code").asText()).isEqualTo("EXECUTION_PACKAGE_BLOCKED");
        assertThat(jdbc.queryForObject(
                "select count(*) from employer_credentials where profile_id = ?", Integer.class, readyCand.profileId()))
                .isZero();

        seedReadyCvForApplication(readyCand.profileId(), readyApp, "cf-ready-cv");
        MvcResult authRes = mvc.perform(post("/api/v1/apply/applications/{id}/universal-account", readyApp)
                        .with(csrf()).with(readyCand.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":true}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode created = json(authRes);
        assertThat(created.get("status").asText()).isEqualTo("PREPARED");
        assertThat(created.get("planId").asText()).isNotBlank();
        assertThat(created.get("sessionId").asText()).isNotBlank();

        // Plan persisted with explicit accountCreationAuthorized = true and Robin-managed contact email
        String planJson = jdbc.queryForObject(
                "select plan::text from automation_plans where id = ?::uuid", String.class, created.get("planId").asText());
        JsonNode parsedPlan = json.readTree(planJson);
        assertThat(parsedPlan.get("planType").asText()).isEqualTo("UNIVERSAL");
        assertThat(parsedPlan.get("package").get("accountCreationAuthorized").asBoolean()).isTrue();
        assertThat(parsedPlan.get("package").get("profileFacts").get("email").asText()).isEqualTo(readyCand.managedAddress());

        // Duplicate universal-account request refuses with CREDENTIAL_ALREADY_EXISTS
        MvcResult dupRes = mvc.perform(post("/api/v1/apply/applications/{id}/universal-account", readyApp)
                        .with(csrf()).with(readyCand.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":true}"))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(dupRes).get("code").asText()).isEqualTo("CREDENTIAL_ALREADY_EXISTS");

        // IdentityService protects ROBIN_MANAGED email against overwrite in real DB
        assertThatThrownBy(() -> identityService.create(
                readyCand.profileId(), "hacker@gmail.com", "acme-verify.example", "cred-override"))
                .isInstanceOf(IllegalStateException.class);

        // Disabled non-managed identity fails closed instead of falling through
        Candidate disabledCand = createCandidate("cand-disabled@personal.example", true);
        UUID disabledApp = createApp(disabledCand.profileId(), "https://acme-verify.example/jobs/disabled");
        UUID disabledSess = startSession(disabledCand.profileId(), disabledApp, "acme-verify.example");
        jdbc.update("""
                insert into applicant_identities (id, profile_id, domain, applicant_email, provider, account_status)
                values (?, ?, 'acme-verify.example', 'manual@personal.example', 'EXTERNAL', 'DISABLED')
                """, UuidV7.generate(), disabledCand.profileId());
        assertThatThrownBy(() -> registrationIdentities.resolve(
                disabledCand.profileId(), disabledApp, disabledSess, "acme-verify.example"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stage3iPhase5_signupConfirmationAndSecurityBoundariesEndToEnd() throws Exception {
        Candidate cand = createCandidate("cand-p5-lifecycle@personal.example", true);
        assertThat(deliverCloudflare(cand.managedAddress(), "Security <security@acme-verify.example>",
                "Welcome", "Active mailbox test",
                "acme-verify.example", "mx.cloudflare.net", true, Instant.now()).getResponse().getStatus()).isEqualTo(200);

        UUID app = createApp(cand.profileId(), "https://acme-verify.example/jobs/p5");
        seedReadyCvForApplication(cand.profileId(), app, "p5-lifecycle");

        MvcResult authRes = mvc.perform(post("/api/v1/apply/applications/{id}/universal-account", app)
                        .with(csrf()).with(cand.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptTerms\":true}"))
                .andExpect(status().isOk()).andReturn();
        UUID planId = UUID.fromString(json(authRes).get("planId").asText());
        UUID sessId = UUID.fromString(json(authRes).get("sessionId").asText());

        // Before worker claims the plan (status = PREPARED), worker identity/resolve endpoints reject with 409
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app.toString(),
                                "sessionId", sessId.toString(),
                                "employerDomain", "acme-verify.example"))))
                .andExpect(status().isConflict());

        // Worker claims the plan -> status = RUNNING
        mvc.perform(post("/api/v1/automation/plans/{id}/claim", planId).header("Authorization", WORKER_HEADER))
                .andExpect(status().isOk());

        // 1. /registration-identity creates PENDING identity (existing=false) until signup confirmation
        String regBody = json.writeValueAsString(Map.of(
                "applicationId", app.toString(),
                "sessionId", sessId.toString(),
                "employerDomain", "acme-verify.example"));
        JsonNode reg1 = json(mvc.perform(post("/api/v1/mailbox-verification/registration-identity")
                        .header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(reg1.get("existing").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject(
                "select account_status from applicant_identities where profile_id = ? and domain = 'acme-verify.example'",
                String.class, cand.profileId())).isEqualTo("PENDING");

        // Second call while still PENDING never reports existing=true
        JsonNode reg2 = json(mvc.perform(post("/api/v1/mailbox-verification/registration-identity")
                        .header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(reg2.get("existing").asBoolean()).isFalse();

        // 2. Explicit signup confirmation promotes PENDING -> ACTIVE; subsequent calls report existing=true
        JsonNode confReg = json(mvc.perform(post("/api/v1/mailbox-verification/registration-identity/confirm")
                        .header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(confReg.get("promoted").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                "select account_status from applicant_identities where profile_id = ? and domain = 'acme-verify.example'",
                String.class, cand.profileId())).isEqualTo("ACTIVE");

        JsonNode reg3 = json(mvc.perform(post("/api/v1/mailbox-verification/registration-identity")
                        .header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(reg3.get("existing").asBoolean()).isTrue();

        // 3. Domain binding refusal (403) + OTP lease + expired release refusal (409)
        mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app.toString(),
                                "sessionId", sessId.toString(),
                                "employerDomain", "evil-domain.example"))))
                .andExpect(status().isForbidden());

        String otpCode = "814925";
        assertThat(deliverCloudflare(cand.managedAddress(), "Security <security@acme-verify.example>",
                "Verification code", "Your verification code is " + otpCode,
                "mail.acme-verify.example", "mx.cloudflare.net", true, Instant.now()).getResponse().getStatus()).isEqualTo(200);

        JsonNode lease = json(mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON).content(regBody))
                .andExpect(status().isOk()).andReturn());
        assertThat(lease.get("otp").asText()).isEqualTo(otpCode);
        String verId = lease.get("verificationId").asText();
        String tok = lease.get("leaseToken").asText();

        // Expire the lease in DB -> /release returns 409 and never leaks leaseToken in audit_logs
        jdbc.update("update mailbox_verifications set reserved_until = now() - interval '1 minute' where id = ?::uuid", verId);
        mvc.perform(post("/api/v1/mailbox-verification/release").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app.toString(),
                                "verificationId", verId,
                                "leaseToken", tok,
                                "reason", "EXPIRED"))))
                .andExpect(status().isConflict());

        // Closing the session rejects omitting sessionId on /resolve with 409 (no null-session bypass)
        jdbc.update("update account_sessions set state = 'HARD_STOP' where id = ?", sessId);
        mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "applicationId", app.toString(),
                                "employerDomain", "acme-verify.example"))))
                .andExpect(status().isConflict());

        // Candidate EmailController legacy endpoints are retired (403 for candidate, 410 for worker)
        mvc.perform(post("/api/v1/emails/verification/extract").with(csrf()).with(cand.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/emails/verification/extract").header("Authorization", WORKER_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone());

        // Verify audit_logs captured metadata events with zero OTP or leaseToken leakage
        var audits = jdbc.queryForList(
                "select action, after_state::text as after_state from audit_logs where entity_id = ?", app);
        assertThat(audits).isNotEmpty();
        assertThat(audits.stream().map(a -> String.valueOf(a.get("action"))).toList())
                .contains("REGISTRATION_IDENTITY_CONFIRMED", "MAILBOX_VERIFICATION_LEASE_ACQUIRED", "MAILBOX_VERIFICATION_RELEASE_REJECTED");
        for (var entry : audits) {
            assertThat(String.valueOf(entry.get("after_state"))).doesNotContain(otpCode).doesNotContain(tok);
        }
    }

    private void seedReadyCvForApplication(UUID profileId, UUID appId, String tag) throws Exception {
        UUID jobId = jdbc.queryForObject("select job_id from applications where id = ?", UUID.class, appId);
        byte[] pdf = ("%PDF-1.4\n%% " + tag + "\n").getBytes(StandardCharsets.UTF_8);
        String pdfSha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(pdf));
        UUID fileId = UuidV7.generate();
        UUID cvId = UuidV7.generate();
        jdbc.update("insert into files(id, object_key, sha256, content_type, byte_size, purpose, content) values(?,?,?,?,?,?,?)",
                fileId, "cv/" + cvId + ".pdf", pdfSha, "application/pdf", (long) pdf.length, "TAILORED_CV", pdf);
        jdbc.update("""
                insert into cv_versions (id, job_id, application_id, profile_id, kind, title, body_markdown,
                                         claims_validation, approved, profile_revision, profile_snapshot_hash,
                                         content_sha256, immutable, pdf_file_id, created_at)
                values (?, ?, ?, ?, 'TAILORED', 'Tailored CV', 'CV text', '{}', false, 1, 'snap', ?, true, ?, now())
                """, cvId, jobId, appId, profileId, pdfSha, fileId);
        jdbc.update("update applications set cv_version_id = ? where id = ?", cvId, appId);
        jdbc.update("""
                insert into resume_ats_analyses (id, profile_id, job_id, application_id, input_hash, role, domain,
                    required_skills, preferred_skills, normalized_skills, verified_evidence, gaps, ats_report,
                    cv_version_id, profile_revision, profile_snapshot_hash)
                values (?, ?, ?, ?, ?, 'Engineer', 'Tech', '[]'::jsonb, '[]'::jsonb, '{}'::jsonb,
                        '[]'::jsonb, '[]'::jsonb, ?::jsonb, ?, 1, 'snap')
                """, UuidV7.generate(), profileId, jobId, appId, "hash-" + cvId,
                json.writeValueAsString(Map.of("validation", Map.of(
                        "passed", true, "validator_version", DocumentFactValidator.VERSION))), cvId);
        jdbc.update("""
                insert into cv_version_reviews (cv_version_id, profile_id, approved, decided_by, content_sha256)
                values (?, ?, true, 'owner', ?)
                on conflict (cv_version_id) do nothing
                """, cvId, profileId, pdfSha);
    }
}
