package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import com.personal.jobagent.security.UserRecord;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Robin-managed email end to end on real PostgreSQL 16 (Phase 8.5).
 *
 * <p>Mailgun is simulated by POSTing the documented inbound-route fields to the
 * webhook, signed with the configured test signing key exactly as Mailgun signs
 * them. No real domain, DNS record, provider account or mailbox is involved:
 * this proves Robin's side of the contract, not that a real domain receives
 * mail (that end-to-end check is an operator step, documented in
 * docs/ROBIN_BUILD_STATUS.md). No Gmail connection exists anywhere in this test.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false", "app.rate-limit.enabled=false",
        "app.events.dispatch-interval-ms=3600000",
        "app.worker-event-token=managed-mail-it-token",
        "app.managed-mail.domain=" + ManagedMailIT.DOMAIN,
        "app.managed-mail.mailgun.webhook-signing-key=" + ManagedMailIT.SIGNING_KEY,
        "app.managed-mail.mailgun.max-age-seconds=900",
        "app.managed-mail.retention-sweep-initial-delay-ms=3600000",
        "app.secrets.encryption-key=6M5mXo2C0eP1Ztq0QzZ0n7cYk1o0r8sB3m6Gq9v2T8U="})
@AutoConfigureMockMvc
@Testcontainers
class ManagedMailIT {

    static final String DOMAIN = "mail.aplivenosi-test.net";
    static final String SIGNING_KEY = "it-webhook-signing-key-7f3a9c2d";
    static final String INBOUND = "/api/v1/managed-mail/inbound/mailgun";
    private static final String WORKER = "Bearer managed-mail-it-token";
    /** Default employer for tests that only need one; identity-bearing tests use their own domain. */
    private static final String EMPLOYER = "employer-it.example";
    private static final String EMPLOYER_FROM = employerFrom(EMPLOYER);
    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-00000000f850");
    private static final SecureRandom RANDOM = new SecureRandom();

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
    @Autowired private ManagedMailRetentionJob retention;
    @Autowired private ManagedMailboxService mailboxes;
    @Autowired private com.personal.jobagent.identity.IdentityService identities;
    @Autowired private com.personal.jobagent.mailbox.RegistrationIdentityService registrationIdentities;

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
                values (?, 'GREENHOUSE', 'managed-mail-fixture', 'Managed Mail Fixture Board', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);
        // Every test states the receiving evidence it needs; none inherits it.
        jdbc.update("delete from managed_mail_domains");
        jdbc.update("delete from managed_inbound_messages");
    }

    // ── readiness and ownership ──────────────────────────────────────────

    @Test
    void anAddressIsNeverPresentedAsWorkingUntilASignedDeliveryProvesReceiving() throws Exception {
        JsonNode before = json(mvc.perform(get("/api/v1/managed-mail/address").session(session))
                .andExpect(status().isOk()).andReturn());
        assertThat(before.get("readiness").asText()).isEqualTo("PENDING_RECEIVING_VERIFICATION");
        assertThat(before.get("usableForRegistration").asBoolean()).isFalse();

        String alias = reserve(session);
        JsonNode reserved = json(mvc.perform(get("/api/v1/managed-mail/address").session(session)).andReturn());
        assertThat(reserved.get("address").asText()).isEqualTo(alias);
        assertThat(reserved.get("readiness").asText()).isEqualTo("PENDING_RECEIVING_VERIFICATION");
        assertThat(reserved.get("usableForRegistration").asBoolean()).isFalse();
        assertThat(reserved.get("receivingVerifiedAt").isNull()).isTrue();

        // Registration refuses to use an address that has not been proven to receive mail.
        UUID app = application(devProfile, "pending");
        MvcResult refused = mvc.perform(post("/api/v1/mailbox-verification/registration-identity")
                        .header("Authorization", WORKER).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("applicationId", app.toString(), "employerDomain", EMPLOYER))))
                .andExpect(status().isConflict()).andReturn();
        assertThat(json(refused).get("reason").asText()).isEqualTo("MANAGED_MAILBOX_NOT_READY");
        assertThat(json(refused).get("managedMailbox").asText()).isEqualTo("PENDING_RECEIVING_VERIFICATION");
        assertThat(count("select count(*) from applicant_identities where profile_id = ? and domain = ?", devProfile, EMPLOYER))
                .isZero();

        // The first signed delivery is the evidence.
        deliver(Delivery.to(alias).from("Someone <someone@sender-it.example>").subject("Hello").body("Testing receiving"))
                .andExpect(status().isOk());
        JsonNode active = json(mvc.perform(get("/api/v1/managed-mail/address").session(session)).andReturn());
        assertThat(active.get("readiness").asText()).isEqualTo("ACTIVE");
        assertThat(active.get("usableForRegistration").asBoolean()).isTrue();
        assertThat(active.get("receivingVerifiedAt").isNull()).isFalse();
        assertThat(active.get("address").asText()).isEqualTo(alias);
    }

    @Test
    void aliasesAreOpaqueUniquePerDomainAndOwnedByExactlyOneProfile() throws Exception {
        String mine = reserve(session);
        assertThat(reserve(session)).isEqualTo(mine);                       // idempotent
        String theirs = reserve(asOtherCandidate());
        assertThat(mine).matches("^[a-z][a-z2-9]{15}@" + DOMAIN.replace(".", "\\.") + "$");
        assertThat(theirs).isNotEqualTo(mine);
        assertThat(mine).doesNotContain("dev").doesNotContain("example.local");

        // The database itself refuses a second alias for one profile, and one alias for two profiles.
        assertThatThrownBy(() -> jdbc.update("insert into managed_mail_addresses (id, profile_id, local_part, domain) "
                + "values (?, ?, 'zzzzzzzzzzzzzzzz', ?)", UuidV7.generate(), devProfile, DOMAIN))
                .isInstanceOf(DataIntegrityViolationException.class);
        UUID third = newProfile("third@managed-mail.test");
        assertThatThrownBy(() -> jdbc.update("insert into managed_mail_addresses (id, profile_id, local_part, domain) "
                + "values (?, ?, ?, ?)", UuidV7.generate(), third, MailAddresses.localPartOf(mine), DOMAIN))
                .isInstanceOf(DataIntegrityViolationException.class);
        // A message can only be filed under the profile that owns its address.
        UUID myAddress = jdbc.queryForObject("select id from managed_mail_addresses where profile_id = ?", UUID.class,
                devProfile);
        assertThatThrownBy(() -> jdbc.update("""
                insert into managed_inbound_messages (id, profile_id, address_id, provider_id, content_sha256,
                    envelope_recipient, content_purged_at, signed_at)
                values (?, ?, ?, 'mailgun', ?, ?, now(), now())
                """, UuidV7.generate(), third, myAddress, "a".repeat(64), mine))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── webhook authentication and replay ─────────────────────────────────

    @Test
    void unsignedForgedStaleAndMalformedDeliveriesAreRefusedAndNothingIsStored() throws Exception {
        String alias = reserve(session);
        Delivery code = Delivery.to(alias).from(EMPLOYER_FROM).subject("Your code").body("Your verification code is 482913");

        mvc.perform(code.unsigned()).andExpect(status().isUnauthorized());
        mvc.perform(code.signedWith("a-different-signing-key", Instant.now())).andExpect(status().isUnauthorized());
        mvc.perform(code.signed(Instant.now()).param("signature", "0".repeat(64))).andExpect(status().isUnauthorized());
        // Authentic but outside the window: refused without retry.
        deliver(code, Instant.now().minus(Duration.ofMinutes(16))).andExpect(status().isNotAcceptable());
        deliver(code, Instant.now().plus(Duration.ofMinutes(10))).andExpect(status().isNotAcceptable());
        // Authentic but malformed.
        deliver(Delivery.to("not-an-address").from(EMPLOYER_FROM).body("x")).andExpect(status().isNotAcceptable());
        deliver(code.headers("{not json")).andExpect(status().isNotAcceptable());
        deliver(Delivery.to("someone@" + DOMAIN.replace("mail.", "other.")).from(EMPLOYER_FROM).body("x"))
                .andExpect(status().isNotAcceptable());
        // A well-formed alias nobody owns.
        deliver(Delivery.to("unknownalias2345@" + DOMAIN).from(EMPLOYER_FROM).body("x")).andExpect(status().isNotAcceptable());

        assertThat(count("select count(*) from managed_inbound_messages")).isZero();
        // The unknown-alias delivery was still a signed delivery for the domain: receiving works.
        assertThat(count("select count(*) from managed_mail_domains where domain = ?", DOMAIN)).isEqualTo(1);
    }

    @Test
    void providerRetriesAndDuplicateDeliveriesAreIdempotentAndReplaysAreRefused() throws Exception {
        String alias = reserve(session);
        Delivery message = Delivery.to(alias).from(EMPLOYER_FROM).subject("Welcome").body("Thanks for registering")
                .messageId("<welcome-1@careers.employer-it.example>");
        String token = Delivery.newToken();
        Instant signedAt = Instant.now();

        assertThat(outcome(mvc.perform(message.signed(signedAt, token)).andExpect(status().isOk()).andReturn()))
                .isEqualTo("stored");
        // Mailgun re-sending the same request (same token, same payload): accepted, not stored twice.
        assertThat(outcome(mvc.perform(message.signed(signedAt, token)).andExpect(status().isOk()).andReturn()))
                .isEqualTo("duplicate");
        // The same token with different content is a replay: refused.
        mvc.perform(message.body("Your verification code is 111111").signed(signedAt, token))
                .andExpect(status().isNotAcceptable());
        // The same message re-delivered with a fresh token: accepted, still one row.
        assertThat(outcome(deliver(message).andExpect(status().isOk()).andReturn())).isEqualTo("duplicate");
        assertThat(count("select count(*) from managed_inbound_messages where envelope_recipient = ?", alias)).isEqualTo(1);
        // A message without a Message-Id is de-duplicated by content.
        Delivery noId = Delivery.to(alias).from(EMPLOYER_FROM).subject("Reminder").body("Finish your profile");
        deliver(noId).andExpect(status().isOk());
        deliver(noId).andExpect(status().isOk());
        assertThat(count("select count(*) from managed_inbound_messages where envelope_recipient = ?", alias)).isEqualTo(2);
        // Only token hashes are kept.
        assertThat(count("select count(*) from managed_mail_webhook_tokens where token_sha256 = ?", token)).isZero();
    }

    @Test
    void multipartDeliveriesWithAttachmentsAreAcceptedAndAttachmentsIgnored() throws Exception {
        String alias = reserve(session);
        Delivery message = Delivery.to(alias).from(employerFrom("multipart-employer.example"))
                .subject("Your documents").body("See attached");
        var request = message.multipartSigned(Instant.now());
        request.file(new MockMultipartFile("attachment-1", "offer.pdf", "application/pdf", new byte[]{1, 2, 3}));
        request.param("attachment-count", "1");
        assertThat(outcome(mvc.perform(request).andExpect(status().isOk()).andReturn())).isEqualTo("stored");
        assertThat(count("select count(*) from managed_inbound_messages where envelope_recipient = ?", alias)).isEqualTo(1);
    }

    // ── routing ──────────────────────────────────────────────────────────

    @Test
    void routingFollowsTheEnvelopeRecipientNeverTheVisibleToHeader() throws Exception {
        String mine = reserve(session);
        String theirs = reserve(asOtherCandidate());
        UUID other = otherProfile();

        // Envelope says mine, To header claims theirs: it is mine, and only mine.
        deliver(Delivery.to(mine).toHeader(theirs).from(EMPLOYER_FROM).subject("For me").body("x")
                .messageId("<route-1@careers.employer-it.example>")).andExpect(status().isOk());
        // Envelope says theirs, To header claims mine: it is theirs.
        deliver(Delivery.to(theirs).toHeader(mine).from(EMPLOYER_FROM).subject("For them").body("y")
                .messageId("<route-2@careers.employer-it.example>")).andExpect(status().isOk());

        assertThat(count("select count(*) from managed_inbound_messages where profile_id = ?", devProfile)).isEqualTo(1);
        assertThat(count("select count(*) from managed_inbound_messages where profile_id = ?", other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select subject_redacted from managed_inbound_messages where profile_id = ?",
                String.class, devProfile)).isEqualTo("For me");

        // Each owner sees only their own deliveries.
        String myList = mvc.perform(get("/api/v1/managed-mail/messages").session(session)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        String theirList = mvc.perform(get("/api/v1/managed-mail/messages").with(asOtherCandidate())).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(myList).contains("For me").doesNotContain("For them");
        assertThat(theirList).contains("For them").doesNotContain("For me");
    }

    // ── signup → verification → login ────────────────────────────────────

    @Test
    void signupVerificationAndLoginUseTheManagedAddressWithoutAnyGmailConnection() throws Exception {
        ListAppender<ILoggingEvent> logs = captureLogs();
        String employer = "lifecycle-employer.example";
        String employerFrom = employerFrom(employer);
        String alias = reserve(session);
        proveReceiving();
        assertThat(count("select count(*) from gmail_connections where profile_id = ?", devProfile)).isZero();
        UUID app = application(devProfile, "lifecycle");
        UUID accountSession = accountSession(app, employer);

        // 1. Sign-up: the worker asks which address to register with — the managed one.
        JsonNode identity = registrationIdentity(app, accountSession, employer);
        assertThat(identity.get("email").asText()).isEqualTo(alias);
        assertThat(identity.get("mailbox").asText()).isEqualTo("managed");
        assertThat(identity.get("existing").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select provider from applicant_identities where profile_id = ? and domain = ?",
                String.class, devProfile, employer)).isEqualTo("ROBIN_MANAGED");
        assertThat(jdbc.queryForObject("select applicant_identity_id from account_sessions where id = ?", UUID.class,
                accountSession)).isEqualTo(UUID.fromString(identity.get("identityId").asText()));
        assertThat(sessionState(accountSession)).isEqualTo("SIGNUP");

        // 2. The employer's verification e-mail arrives at the managed address.
        deliver(Delivery.to(alias).from(employerFrom).subject("Verify your Employer account")
                .body("Hi,\n\nYour verification code is 482913. It expires in 10 minutes.\n\n© 2026 Employer")
                .messageId("<verify-1@careers.employer-it.example>")).andExpect(status().isOk());

        // 3. The worker resolves it: no mailbox address needed, the session says which mailbox.
        JsonNode resolved = resolve(app, accountSession, employer, null, 200);
        assertThat(resolved.get("otp").asText()).isEqualTo("482913");
        assertThat(resolved.get("provider").asText()).isEqualTo("managed");
        assertThat(sessionState(accountSession)).isEqualTo("VERIFY_EMAIL");

        // One-time: recorded without the code, content erased, never released again.
        Map<String, Object> audit = jdbc.queryForMap("""
                select provider_id, source_managed_message_id, source_email_id, otp_value_ref, link_url, account_session_id
                from mailbox_verifications where application_id = ?""", app);
        assertThat(audit.get("provider_id")).isEqualTo("managed");
        assertThat(audit.get("source_managed_message_id")).isNotNull();
        assertThat(audit.get("source_email_id")).isNull();
        assertThat(audit.get("otp_value_ref")).isNull();
        assertThat(audit.get("link_url")).isNull();
        assertThat(audit.get("account_session_id")).isEqualTo(accountSession);
        Map<String, Object> source = jdbc.queryForMap(
                "select consumed_at, content_encrypted, content_purged_at from managed_inbound_messages where id = ?",
                audit.get("source_managed_message_id"));
        assertThat(source.get("consumed_at")).isNotNull();
        assertThat(source.get("content_encrypted")).isNull();
        assertThat(source.get("content_purged_at")).isNotNull();
        resolve(app, accountSession, employer, null, 404);

        // 4. Login: the same identity, now an existing account.
        JsonNode login = registrationIdentity(app, accountSession, employer);
        assertThat(login.get("email").asText()).isEqualTo(alias);
        assertThat(login.get("existing").asBoolean()).isTrue();
        assertThat(count("select count(*) from applicant_identities where profile_id = ? and domain = ?", devProfile, employer))
                .isEqualTo(1);

        // Neither the code nor the alias ever reached a log line (any logger, any level that was emitted).
        releaseLogs(logs);
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent event : logs.list) {
            String line = event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                    : " " + event.getThrowableProxy().getMessage());
            assertThat(line).doesNotContain("482913").doesNotContain(MailAddresses.localPartOf(alias));
        }
    }

    @Test
    void theVerificationBoundaryIsWorkerOnlyAndBoundToTheSessionsEmployerAndOwner() throws Exception {
        String employer = "boundary-employer.example";
        String employerFrom = employerFrom(employer);
        reserve(session);
        proveReceiving();
        UUID app = application(devProfile, "boundary");
        UUID accountSession = accountSession(app, employer);
        String body = json.writeValueAsString(Map.of("applicationId", app.toString(), "sessionId", accountSession.toString(),
                "employerDomain", employer));

        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", "Bearer wrong")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());

        // The session's employer domain must match the request.
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", WORKER)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "applicationId", app.toString(), "sessionId", accountSession.toString(),
                        "employerDomain", "another-employer.example")))).andExpect(status().isConflict());
        resolve(app, accountSession, "another-employer.example", null, 409);
        // Another candidate's session is not reachable through my application.
        UUID theirApp = application(otherProfile(), "boundary-other");
        UUID theirSession = accountSession(theirApp, employer, asOtherCandidate());
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", WORKER)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "applicationId", app.toString(), "sessionId", theirSession.toString(),
                        "employerDomain", employer)))).andExpect(status().isNotFound());
        resolve(app, theirSession, employer, null, 404);
        // A mailbox other than the one the account was registered with is refused.
        registrationIdentity(app, accountSession, employer);
        resolve(app, accountSession, employer, "someone-else@" + DOMAIN, 409);

        // A hard-stopped session never receives another identity or code.
        mvc.perform(post("/api/v1/identity/sessions/{id}/hard-stop", accountSession).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"CAPTCHA\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", WORKER)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        resolve(app, accountSession, employer, null, 409);

        // The owner endpoints need a session, and writes need the CSRF token.
        mvc.perform(get("/api/v1/managed-mail/address")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/managed-mail/address").session(session)).andExpect(status().isForbidden());
    }

    @Test
    void ambiguousForeignUnauthenticatedStaleAndPreSessionMessagesAreNeverUsed() throws Exception {
        String employer = "ambiguity-employer.example";
        String employerFrom = employerFrom(employer);
        String alias = reserve(session);
        proveReceiving();
        UUID app = application(devProfile, "ambiguity");
        UUID accountSession = accountSession(app, employer);
        registrationIdentity(app, accountSession, employer);

        // Another domain claiming the code.
        deliver(Delivery.to(alias).from("Security <security@" + employer + ".attacker.example>")
                .subject("Your verification code").body("Your verification code is 482913")).andExpect(status().isOk());
        resolve(app, accountSession, employer, null, 404);
        clearMessages();

        // The provider reported a failed sender check: kept as metadata only, never used.
        deliver(Delivery.to(alias).from(employerFrom).subject("Your verification code").body("Your verification code is 482913")
                .headers("[[\"X-Mailgun-Spf\", \"Fail\"], [\"X-Mailgun-Spf\", \"Pass\"]]")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select content_encrypted is null from managed_inbound_messages where envelope_recipient = ?",
                Boolean.class, alias)).isTrue();
        resolve(app, accountSession, employer, null, 404);
        clearMessages();

        // Two different codes in one message.
        deliver(Delivery.to(alias).from(employerFrom).subject("Codes")
                .body("Your verification code is 482913. Or use code 551200.")).andExpect(status().isOk());
        resolve(app, accountSession, employer, null, 404);
        clearMessages();

        // Two messages, each with its own code.
        deliver(Delivery.to(alias).from(employerFrom).subject("Code one").body("Your verification code is 482913")
                .messageId("<one@careers.employer-it.example>")).andExpect(status().isOk());
        deliver(Delivery.to(alias).from(employerFrom).subject("Code two").body("Your verification code is 551200")
                .messageId("<two@careers.employer-it.example>")).andExpect(status().isOk());
        resolve(app, accountSession, employer, null, 404);
        clearMessages();

        // A message older than the verification window.
        deliver(Delivery.to(alias).from(employerFrom).subject("Old code").body("Your verification code is 482913"))
                .andExpect(status().isOk());
        jdbc.update("update managed_inbound_messages set received_at = now() - interval '31 minutes' where envelope_recipient = ?",
                alias);
        jdbc.update("update account_sessions set created_at = now() - interval '2 hours' where id = ?", accountSession);
        resolve(app, accountSession, employer, null, 404);
        clearMessages();

        // A message that arrived before this account session started cannot belong to it.
        deliver(Delivery.to(alias).from(employerFrom).subject("Earlier code").body("Your verification code is 482913"))
                .andExpect(status().isOk());
        jdbc.update("update account_sessions set created_at = now() where id = ?", accountSession);
        jdbc.update("update managed_inbound_messages set received_at = now() - interval '5 minutes' where envelope_recipient = ?",
                alias);
        resolve(app, accountSession, employer, null, 404);
        // ... while the same message is fresh enough for a resolution that is not bound to a session.
        assertThat(resolve(app, null, employer, alias, 200).get("otp").asText()).isEqualTo("482913");
        clearMessages();

        // A code addressed (To header) to me but delivered to someone else's alias never reaches my mailbox.
        String theirs = reserve(asOtherCandidate());
        deliver(Delivery.to(theirs).toHeader(alias).from(employerFrom).subject("Code").body("Your verification code is 482913"))
                .andExpect(status().isOk());
        jdbc.update("update account_sessions set created_at = now() - interval '1 minute' where id = ?", accountSession);
        resolve(app, accountSession, employer, null, 404);
    }

    // ── minimisation and retention ───────────────────────────────────────

    @Test
    void contentIsEncryptedTheSubjectRedactedAndBothErasedOnSchedule() throws Exception {
        String alias = reserve(session);
        proveReceiving();
        deliver(Delivery.to(alias).from(EMPLOYER_FROM).subject("482913 is your verification code")
                .body("Your verification code is 482913. Open https://careers.employer-it.example/verify?token=secret-token")
                .messageId("<retention@careers.employer-it.example>")).andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "select subject_redacted, content_encrypted, from_address, sender_domain from managed_inbound_messages "
                        + "where envelope_recipient = ?", alias);
        assertThat(String.valueOf(row.get("subject_redacted"))).isEqualTo("[number] is your verification code");
        assertThat(String.valueOf(row.get("content_encrypted"))).doesNotContain("482913").doesNotContain("verification")
                .doesNotContain("secret-token");
        assertThat(row.get("sender_domain")).isEqualTo("careers.employer-it.example");
        String listed = mvc.perform(get("/api/v1/managed-mail/messages").session(session)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(listed).contains("[number] is your verification code").doesNotContain("482913").doesNotContain("secret-token");

        // Inside every retention period nothing is touched.
        var early = retention.purge(Instant.now().plus(Duration.ofMinutes(30)));
        assertThat(early.contentErased()).isZero();
        assertThat(jdbc.queryForObject("select content_encrypted is not null from managed_inbound_messages "
                + "where envelope_recipient = ?", Boolean.class, alias)).isTrue();
        assertThat(count("select count(*) from managed_mail_webhook_tokens")).isPositive();
        // Content goes after the content retention (2 h); accepted tokens once no request carrying them
        // could pass the timestamp check any more (2 × (15 + 5) min); metadata after 30 days.
        retention.purge(Instant.now().plus(Duration.ofHours(3)));
        assertThat(jdbc.queryForObject("select content_encrypted is null and content_purged_at is not null "
                + "from managed_inbound_messages where envelope_recipient = ?", Boolean.class, alias)).isTrue();
        assertThat(count("select count(*) from managed_mail_webhook_tokens")).isZero();
        assertThat(count("select count(*) from managed_inbound_messages where envelope_recipient = ?", alias)).isEqualTo(1);
        retention.purge(Instant.now().plus(Duration.ofDays(31)));
        assertThat(count("select count(*) from managed_inbound_messages where envelope_recipient = ?", alias)).isZero();
    }

    @Test
    void aSafeEmployerVerificationLinkIsReturnedWhenThereIsNoCode() throws Exception {
        String employer = "link-employer.example";
        String employerFrom = employerFrom(employer);
        String alias = reserve(session);
        proveReceiving();
        UUID app = application(devProfile, "link");
        deliver(Delivery.to(alias).from(employerFrom).subject("Confirm your email")
                .body("Confirm your address: https://careers." + employer + "/account/confirm?token=abc123\n"
                        + "Privacy: https://" + employer + "/privacy\nPhishing: https://attacker.example/verify?token=x"))
                .andExpect(status().isOk());
        JsonNode resolved = resolve(app, null, employer, alias, 200);
        assertThat(resolved.get("otp").asText()).isEmpty();
        assertThat(resolved.get("link").asText()).isEqualTo("https://careers." + employer + "/account/confirm?token=abc123");
    }

    @Test
    void endToEndCandidateJourneyFromApplicationToRecruiterTracking() throws Exception {
        // 1. Candidate registers & reserves managed address
        String alias = reserve(session);
        proveReceiving();
        assertThat(alias).endsWith("@" + DOMAIN);

        // 2. Candidate has two applications at the same employer
        String employerDomain = "careers.employer-it.example";
        String jobUrl1 = "https://boards.greenhouse.io/employerit/jobs/8001";
        String jobUrl2 = "https://boards.greenhouse.io/employerit/jobs/8002";
        UUID app1 = applicationWithJob(devProfile, "Employer IT", "Senior Systems Engineer", jobUrl1, "REQ-8001");
        UUID app2 = applicationWithJob(devProfile, "Employer IT", "Lead Cloud Architect", jobUrl2, "REQ-8002");

        // 3. Applications resolve and use the candidate's managed address, while personal login email remains intact
        String contactEmail1 = registrationIdentities.resolveApplicationContactEmail(devProfile, jobUrl1, "dev@example.local");
        String contactEmail2 = registrationIdentities.resolveApplicationContactEmail(devProfile, jobUrl2, "dev@example.local");
        assertThat(contactEmail1).isEqualTo(alias);
        assertThat(contactEmail2).isEqualTo(alias);

        String loginEmail = jdbc.queryForObject(
                "select u.email from profiles p join users u on u.id = p.user_id where p.id = ?", String.class, devProfile);
        assertThat(loginEmail).isEqualTo("dev@example.local");

        // 4. Inbound message 1 arrives for App 1 with specific job link
        Delivery msg1 = Delivery.to(alias)
                .from("Employer IT Recruiting <recruiting@" + employerDomain + ">")
                .subject("Application Received: Senior Systems Engineer")
                .body("Thank you for applying to Senior Systems Engineer at Employer IT! View your application: " + jobUrl1)
                .messageId("<app1-ack@careers.employer-it.example>");
        deliver(msg1).andExpect(status().isOk());

        // Verify message was stored and mapped to app1
        Map<String, Object> emailRow1 = jdbc.queryForMap(
                "select application_id, classification from emails where message_id = '<app1-ack@careers.employer-it.example>' and profile_id = ?",
                devProfile);
        assertThat(emailRow1.get("application_id")).isEqualTo(app1);
        assertThat(emailRow1.get("classification")).isEqualTo("APPLICATION_ACKNOWLEDGEMENT");

        // Verify event recorded on app1 timeline
        assertThat(count("select count(*) from application_events where application_id = ? and profile_id = ?", app1, devProfile))
                .isGreaterThanOrEqualTo(1);

        // 5. Inbound message 2 is a duplicate replay of message 1 (same token and payload, or same message ID)
        assertThat(outcome(deliver(msg1).andExpect(status().isOk()).andReturn())).isEqualTo("duplicate");
        // No duplicate row inserted
        assertThat(count("select count(*) from emails where message_id = '<app1-ack@careers.employer-it.example>' and profile_id = ?", devProfile))
                .isEqualTo(1);

        // 6. Inbound message 3 is ambiguous between App 1 and App 2 (only company name mentioned, no requisition or URL)
        Delivery ambiguousMsg = Delivery.to(alias)
                .from("Employer IT Talent <talent@" + employerDomain + ">")
                .subject("Update on your candidacy at Employer IT")
                .body("Hello, we are actively reviewing your background for opportunities at Employer IT.")
                .messageId("<ambiguous-update@careers.employer-it.example>");
        deliver(ambiguousMsg).andExpect(status().isOk());

        // Verify ambiguous message is NOT blindly assigned to either application
        Map<String, Object> ambRow = jdbc.queryForMap(
                "select application_id from emails where message_id = '<ambiguous-update@careers.employer-it.example>' and profile_id = ?",
                devProfile);
        assertThat(ambRow.get("application_id")).isNull();

        // Verify it was routed to track review for candidate review
        assertThat(count("select count(*) from email_application_links where profile_id = ? and status = 'PENDING_REVIEW'", devProfile))
                .isGreaterThanOrEqualTo(1);

        // 7. Inbound delivery to unknown recipient is safely rejected with 406
        Delivery unknownRecipientMsg = Delivery.to("nonexistentalias@" + DOMAIN)
                .from("Recruiter <recruiter@random-employer.example>")
                .subject("Job Offer")
                .body("Offer details")
                .messageId("<offer-unknown@random-employer.example>");
        deliver(unknownRecipientMsg).andExpect(status().isNotAcceptable());
        assertThat(count("select count(*) from emails where message_id = '<offer-unknown@random-employer.example>'")).isZero();

        // 8. Cross-candidate isolation: other candidate cannot see dev candidate's messages or events
        String otherMessagesJson = mvc.perform(get("/api/v1/managed-mail/messages").with(asOtherCandidate()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(otherMessagesJson).doesNotContain("<app1-ack@careers.employer-it.example>")
                .doesNotContain("<ambiguous-update@careers.employer-it.example>");

        assertThat(count("select count(*) from emails where profile_id = ?", otherProfile())).isZero();
    }

    // ── delete my data ───────────────────────────────────────────────────

    @Test
    void deletingAProfileRemovesItsManagedMailIdentitiesAndSessions() throws Exception {
        String employer = "purge-employer.example";
        UUID profile = newProfile("purge@managed-mail.test");
        String alias = mailboxes.ensureAddress(profile).address();
        proveReceiving();
        UUID app = application(profile, "purge");
        UUID accountSession = identities.startSession(app, employer, profile).orElseThrow();
        registrationIdentities.resolve(profile, app, accountSession, employer);
        deliver(Delivery.to(alias).from(employerFrom(employer)).subject("Verify").body("Your verification code is 482913"))
                .andExpect(status().isOk());
        assertThat(resolve(app, accountSession, employer, null, 200).get("otp").asText()).isEqualTo("482913");

        // What PurgeService does: one delete of the profile row; everything owned must go with it.
        jdbc.update("delete from profiles where id = ?", profile);
        assertThat(count("select count(*) from managed_mail_addresses where profile_id = ?", profile)).isZero();
        assertThat(count("select count(*) from managed_inbound_messages where profile_id = ?", profile)).isZero();
        assertThat(count("select count(*) from applicant_identities where profile_id = ?", profile)).isZero();
        assertThat(count("select count(*) from account_sessions where owner_profile_id = ?", profile)).isZero();
        assertThat(count("select count(*) from mailbox_verifications where application_id = ?", app)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** A Mailgun inbound-route POST, built field by field the way Mailgun sends it. */
    static final class Delivery {
        private final Map<String, String> fields = new LinkedHashMap<>();
        private String toHeader;
        private String messageId;
        private String headersOverride;

        static Delivery to(String recipient) {
            Delivery d = new Delivery();
            d.fields.put("recipient", recipient);
            d.fields.put("sender", "bounces+it@esp-it.example");
            d.fields.put("subject", "");
            d.fields.put("body-plain", "");
            return d;
        }

        private Delivery copy() {
            Delivery d = new Delivery();
            d.fields.putAll(fields);
            d.toHeader = toHeader;
            d.messageId = messageId;
            d.headersOverride = headersOverride;
            return d;
        }

        Delivery from(String from) { Delivery d = copy(); d.fields.put("from", from); return d; }
        Delivery subject(String subject) { Delivery d = copy(); d.fields.put("subject", subject); return d; }
        Delivery body(String body) { Delivery d = copy(); d.fields.put("body-plain", body); return d; }
        Delivery toHeader(String to) { Delivery d = copy(); d.toHeader = to; return d; }
        Delivery messageId(String id) { Delivery d = copy(); d.messageId = id; return d; }
        Delivery headers(String raw) { Delivery d = copy(); d.headersOverride = raw; return d; }

        private String messageHeaders() {
            if (headersOverride != null) return headersOverride;
            StringBuilder h = new StringBuilder("[");
            h.append("[\"From\", ").append(quote(fields.getOrDefault("from", ""))).append("]");
            h.append(", [\"To\", ").append(quote(toHeader == null ? fields.get("recipient") : toHeader)).append("]");
            h.append(", [\"Subject\", ").append(quote(fields.get("subject"))).append("]");
            if (messageId != null) h.append(", [\"Message-Id\", ").append(quote(messageId)).append("]");
            h.append(", [\"X-Mailgun-Spf\", \"Pass\"], [\"X-Mailgun-Sflag\", \"No\"]]");
            return h.toString();
        }

        private static String quote(String value) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }

        private <B extends MockHttpServletRequestBuilder> B fill(B request) {
            fields.forEach((name, value) -> request.param(name, value));
            request.param("message-headers", messageHeaders());
            return request;
        }

        private <B extends MockHttpServletRequestBuilder> B sign(B request, String key, Instant at, String token) {
            String timestamp = Long.toString(at.getEpochSecond());
            request.param("timestamp", timestamp);
            request.param("token", token);
            request.param("signature", hmac(key, timestamp + token));
            return request;
        }

        MockHttpServletRequestBuilder unsigned() {
            return fill(post(INBOUND).contentType(MediaType.APPLICATION_FORM_URLENCODED));
        }

        MockHttpServletRequestBuilder signed(Instant at) {
            return signed(at, newToken());
        }

        MockHttpServletRequestBuilder signed(Instant at, String token) {
            return sign(unsigned(), SIGNING_KEY, at, token);
        }

        MockHttpServletRequestBuilder signedWith(String key, Instant at) {
            return sign(unsigned(), key, at, newToken());
        }

        MockMultipartHttpServletRequestBuilder multipartSigned(Instant at) {
            return sign(fill(multipart(INBOUND)), SIGNING_KEY, at, newToken());
        }

        static String newToken() {
            String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
            StringBuilder token = new StringBuilder();
            for (int i = 0; i < 50; i++) token.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            return token.toString();
        }

        private static String hmac(String key, String data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String employerFrom(String employerDomain) {
        return "Employer Careers <no-reply@careers." + employerDomain + ">";
    }

    private org.springframework.test.web.servlet.ResultActions deliver(Delivery delivery) throws Exception {
        return deliver(delivery, Instant.now());
    }

    private org.springframework.test.web.servlet.ResultActions deliver(Delivery delivery, Instant signedAt) throws Exception {
        return mvc.perform(delivery.signed(signedAt));
    }

    /** Every log event from every logger while attached (the application logs through Logback). */
    private static ListAppender<ILoggingEvent> captureLogs() {
        Logger root = ((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory())
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    private static void releaseLogs(ListAppender<ILoggingEvent> appender) {
        ((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory())
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        appender.stop();
    }

    /** One signed delivery for the domain: the evidence that receiving works. */
    private void proveReceiving() throws Exception {
        deliver(Delivery.to("receivingprobe234@" + DOMAIN).from("Operator <operator@sender-it.example>")
                .subject("Receiving probe").body("probe")).andExpect(status().isNotAcceptable());
    }

    private void clearMessages() {
        jdbc.update("delete from managed_inbound_messages");
    }

    private String reserve(Object who) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/managed-mail/address").with(csrf());
        request = who instanceof MockHttpSession s ? request.session(s) : request.with((RequestPostProcessor) who);
        JsonNode body = json(mvc.perform(request).andExpect(status().isOk()).andReturn());
        return body.get("address").asText();
    }

    private JsonNode registrationIdentity(UUID app, UUID accountSession, String employer) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        if (accountSession != null) body.put("sessionId", accountSession.toString());
        body.put("employerDomain", employer);
        return json(mvc.perform(post("/api/v1/mailbox-verification/registration-identity").header("Authorization", WORKER)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode resolve(UUID app, UUID accountSession, String employer, String mailbox, int expected) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", app.toString());
        if (accountSession != null) body.put("sessionId", accountSession.toString());
        body.put("employerDomain", employer);
        if (mailbox != null) body.put("mailboxAddress", mailbox);
        MvcResult result = mvc.perform(post("/api/v1/mailbox-verification/resolve").header("Authorization", WORKER)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn();
        return json(result);
    }

    private UUID accountSession(UUID app, String domain) throws Exception {
        return accountSession(app, domain, null);
    }

    private UUID accountSession(UUID app, String domain, RequestPostProcessor who) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/identity/sessions").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("applicationId", app.toString(), "domain", domain)));
        request = who == null ? request.session(session) : request.with(who);
        return UUID.fromString(json(mvc.perform(request).andExpect(status().isOk()).andReturn()).get("sessionId").asText());
    }

    private UUID application(UUID profileId, String tag) {
        UUID jobId = UuidV7.generate();
        String externalId = "managed-mail-" + tag + "-" + jobId;
        String url = "https://boards.greenhouse.io/managedmailfixture/jobs/" + Math.abs(jobId.hashCode());
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, ?, ?, 'Employer IT', 'Platform Engineer', 'Build services.', '{}', 'SCORED', ?, ?, ?)
                """, jobId, SOURCE, externalId, "dedup-" + externalId, "hash-" + externalId, url, url);
        return pipeline.createApplicationFromMatch(profileId, jobId).applicationId();
    }

    private UUID applicationWithJob(UUID profileId, String company, String title, String url, String req) {
        UUID jobId = UuidV7.generate();
        String externalId = "managed-mail-job-" + jobId;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, skills_extracted, status, content_hash, application_url, canonical_url)
                values (?, ?, ?, ?, ?, ?, 'Role desc', '{}', 'SCORED', ?, ?, ?)
                """, jobId, SOURCE, externalId, "dedup-" + externalId, company, title, "hash-" + externalId, url, url);
        UUID appId = pipeline.createApplicationFromMatch(profileId, jobId).applicationId();
        for (com.personal.jobagent.discovery.platform.JobIdentity.Key k :
                com.personal.jobagent.discovery.platform.JobIdentity.keys(SOURCE, externalId, url, url, company, req)) {
            jdbc.update("insert into job_identity_keys (key, job_id, key_type, source_id) values (?, ?, ?, ?) on conflict do nothing",
                    k.asString(), jobId, k.type().name(), SOURCE);
        }
        return appId;
    }

    private UUID otherUserId() {
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, 'other@managed-mail.test', null, 'Other Candidate', 'FIREBASE') on conflict (email) do nothing
                """, UuidV7.generate());
        return jdbc.queryForObject("select id from users where email = 'other@managed-mail.test'", UUID.class);
    }

    private UUID newProfile(String email) {
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, null, 'Another Candidate', 'FIREBASE') on conflict (email) do nothing
                """, UuidV7.generate(), email);
        UUID user = jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
        return profiles.findByUserId(user).map(p -> p.id())
                .orElseGet(() -> profiles.createProfile(user, null, null, null, null, null, null, null));
    }

    private UUID otherProfile() {
        UUID user = otherUserId();
        return profiles.findByUserId(user).map(p -> p.id())
                .orElseGet(() -> profiles.createProfile(user, null, null, null, null, null, null, null));
    }

    private RequestPostProcessor asOtherCandidate() {
        UUID user = otherUserId();
        otherProfile();
        AppUserDetails principal = new AppUserDetails(new UserRecord(user, "other@managed-mail.test", null,
                "Other Candidate", null, "FIREBASE"));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? json.createObjectNode() : json.readTree(body);
    }

    private String outcome(MvcResult result) throws Exception {
        return json(result).get("status").asText();
    }

    private String sessionState(UUID accountSession) {
        return jdbc.queryForObject("select state from account_sessions where id = ?", String.class, accountSession);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

}
