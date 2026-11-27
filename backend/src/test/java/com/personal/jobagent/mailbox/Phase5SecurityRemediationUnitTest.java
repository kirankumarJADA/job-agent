package com.personal.jobagent.mailbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.LogScrubber;
import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.common.SchedulerDatabaseGuard;
import com.personal.jobagent.common.ScrubbingJsonGeneratorDecorator;
import com.personal.jobagent.common.ScrubbingMessageConverter;
import com.personal.jobagent.common.ScrubbingThrowableConverter;
import com.personal.jobagent.discovery.JobDiscoveryService;
import com.personal.jobagent.email.EmailController;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.events.OutboxWriter;
import com.personal.jobagent.identity.CredentialVault;
import com.personal.jobagent.identity.EmployerCredentialService;
import com.personal.jobagent.mailbox.managed.ManagedMailVerificationProvider;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.track.TrackEvidenceRecorder;
import com.personal.jobagent.track.TrackRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Stage 3I Phase 5 regression unit tests covering P1-3, P1-4, P1-6, P2-1, P2-2, and P2-3.
 */
class Phase5SecurityRemediationUnitTest {

    @Test
    void emailControllerRetiresVerificationExtractionForCandidatesAndWorkers() {
        EmailIntelligenceService intel = mock(EmailIntelligenceService.class);
        OwnerContext owner = mock(OwnerContext.class);
        EmailController controller = new EmailController(intel, owner);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/emails/verification/extract");

        when(owner.isWorkerRequest()).thenReturn(false);
        assertThat(controller.extract(new EmailController.VerifyRequest(UUID.randomUUID(), UUID.randomUUID(), "acme.example"), req)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(controller.resolve(UUID.randomUUID(), req).getStatusCode().value()).isEqualTo(403);

        when(owner.isWorkerRequest()).thenReturn(true);
        assertThat(controller.extract(null, req).getStatusCode().value()).isEqualTo(410);
        assertThat(controller.resolve(UUID.randomUUID(), req).getStatusCode().value()).isEqualTo(410);
        verifyNoInteractions(intel);
    }

    @Test
    void logScrubberAndLogbackConvertersScrubNumericOtpsBearerTokensHeadersAndThrowables() throws Exception {
        String raw = "https://alice:hunter2@evil.example/cb?activation_code=act-88&leaseToken=lt-123&sig=abc999 "
                + "{\"otp\": 482913, \"pin\": 9012, \"code\": 771122} "
                + "Authorization: Bearer eyJabc.def.ghi\nCookie: sid=sess-secret-1";
        String scrubbed = LogScrubber.scrub(raw);
        for (String leak : List.of("hunter2", "act-88", "lt-123", "abc999", "482913", "9012", "771122", "eyJabc.def.ghi", "sess-secret-1")) {
            assertThat(scrubbed).doesNotContain(leak);
        }

        LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = lc.getLogger("phase5.test");

        ScrubbingMessageConverter msgConverter = new ScrubbingMessageConverter();
        msgConverter.setContext(lc);
        msgConverter.start();
        LoggingEvent msgEvent = new LoggingEvent("fqcn", logger, Level.INFO, "OTP {\"otp\": 994411} ?sig=xyz123", null, null);
        String renderedMsg = msgConverter.convert(msgEvent);
        assertThat(renderedMsg).doesNotContain("994411").doesNotContain("xyz123").contains("***REDACTED***");

        ScrubbingThrowableConverter throwConverter = new ScrubbingThrowableConverter();
        throwConverter.setContext(lc);
        throwConverter.start();
        LoggingEvent throwEvent = new LoggingEvent("fqcn", logger, Level.ERROR, "failed",
                new IllegalStateException("Auth failed Bearer secret-tok-99 ?lease_token=lt-777"), null);
        String renderedThrow = throwConverter.convert(throwEvent);
        assertThat(renderedThrow).doesNotContain("secret-tok-99").doesNotContain("lt-777").contains("***REDACTED***");

        StringWriter sw = new StringWriter();
        JsonGenerator decorated = new ScrubbingJsonGeneratorDecorator().decorate(new JsonFactory().createGenerator(sw));
        decorated.writeStartObject();
        decorated.writeStringField("message", "Callback https://u:pw@host.example/x?verification_code=556677");
        decorated.writeEndObject();
        decorated.close();
        assertThat(sw.toString()).doesNotContain("pw").doesNotContain("556677").contains("***REDACTED***");
    }

    @Test
    void mailboxConfirmOmittingSessionIdCannotBypassClosedSessionAndWritesMetadataOnlyAudit() throws Exception {
        MailboxVerificationProvider provider = mock(MailboxVerificationProvider.class);
        ManagedMailVerificationProvider managedProvider = mock(ManagedMailVerificationProvider.class);
        RegistrationIdentityService regIds = mock(RegistrationIdentityService.class);
        OwnerContext owner = mock(OwnerContext.class);
        JdbcTemplate db = mock(JdbcTemplate.class);
        PlatformTransactionManager tx = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return mock(TransactionStatus.class);
            }
            @Override
            public void commit(TransactionStatus status) {}
            @Override
            public void rollback(TransactionStatus status) {}
        };
        EmployerCredentialService credentials = mock(EmployerCredentialService.class);
        AuditLogWriter auditLog = mock(AuditLogWriter.class);

        MailboxVerificationController controller = new MailboxVerificationController(
                provider, regIds, owner, db, tx, managedProvider, credentials, auditLog, 30, 180);

        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID verId = UUID.randomUUID();
        String leaseToken = "lease-secret-token-value";
        String tokenSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(leaseToken.getBytes(StandardCharsets.UTF_8)));

        when(owner.isWorkerRequest()).thenReturn(true);
        when(owner.ownerOfApplication(appId)).thenReturn(java.util.Optional.of(profileId));
        when(credentials.bindingFor(profileId, appId))
                .thenReturn(new EmployerCredentialService.ApplicationBinding(true, Set.of("acme.example")));
        when(db.queryForObject("select now()", Timestamp.class)).thenReturn(Timestamp.from(Instant.now()));
        when(db.queryForList("select account_session_id from applications where id = ? and profile_id = ?", appId, profileId))
                .thenReturn(List.of(Map.of("account_session_id", sessionId)));
        when(db.queryForObject(
                "select count(*) from account_sessions where id = ? and owner_profile_id = ? and application_id = ?",
                Integer.class, sessionId, profileId, appId))
                .thenReturn(1);
        when(db.queryForList(contains("from account_sessions"), eq(sessionId), eq(profileId), eq(appId)))
                .thenReturn(List.of(Map.of("state", "HARD_STOP", "expected_domain", "acme.example")));
        when(db.queryForList(contains("from mailbox_verifications"), eq(verId), eq(appId)))
                .thenReturn(List.of(Map.of(
                        "id", verId,
                        "account_session_id", sessionId,
                        "employer_domain", "acme.example",
                        "provider_id", "managed",
                        "source_managed_message_id", UUID.randomUUID(),
                        "status", "RESERVED",
                        "reserved_until", Timestamp.from(Instant.now().plusSeconds(120)),
                        "reservation_token_sha256", tokenSha)));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/mailbox-verification/confirm");
        // sessionId intentionally omitted (null) while application holds HARD_STOP session
        ResponseEntity<?> res = controller.confirm(
                new MailboxVerificationController.LeaseActionRequest(appId, null, verId, leaseToken, null), req);
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        verify(db, never()).update(contains("status = 'CONSUMED'"), any(), any());

        // Non-running plan on release -> 409 and audit written without secrets
        when(db.queryForList(contains("from account_sessions"), eq(sessionId), eq(profileId), eq(appId)))
                .thenReturn(List.of(Map.of("state", "VERIFY_EMAIL", "expected_domain", "acme.example")));
        when(credentials.bindingFor(profileId, appId))
                .thenReturn(new EmployerCredentialService.ApplicationBinding(false, Set.of("acme.example")));
        ResponseEntity<?> releaseRes = controller.release(
                new MailboxVerificationController.LeaseActionRequest(appId, sessionId, verId, leaseToken, "OTP_REJECTED"), req);
        assertThat(releaseRes.getStatusCode().value()).isEqualTo(409);
        ArgumentCaptor<AuditEntry> auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLog).write(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo("MAILBOX_VERIFICATION_RELEASE_REJECTED");
        assertThat(String.valueOf(auditCaptor.getValue().afterState())).doesNotContain(leaseToken);

        // Expired lease on release -> 409 + expires stale row
        when(credentials.bindingFor(profileId, appId))
                .thenReturn(new EmployerCredentialService.ApplicationBinding(true, Set.of("acme.example")));
        when(db.queryForList(contains("from mailbox_verifications"), eq(verId), eq(appId)))
                .thenReturn(List.of(Map.of(
                        "id", verId,
                        "account_session_id", sessionId,
                        "employer_domain", "acme.example",
                        "source_managed_message_id", UUID.randomUUID(),
                        "status", "RESERVED",
                        "reserved_until", Timestamp.from(Instant.now().minusSeconds(30)),
                        "reservation_token_sha256", tokenSha)));
        ResponseEntity<?> expiredRelease = controller.release(
                new MailboxVerificationController.LeaseActionRequest(appId, sessionId, verId, leaseToken, "EXPIRED"), req);
        assertThat(expiredRelease.getStatusCode().value()).isEqualTo(409);
        verify(db).update(contains("set status = 'EXPIRED'"), eq(verId));

        // Explicit /registration-identity/confirm promotes PENDING -> ACTIVE and audits
        when(regIds.confirmRegistration(profileId, "acme.example")).thenReturn(true);
        MockHttpServletRequest confirmRegReq = new MockHttpServletRequest("POST", "/api/v1/mailbox-verification/registration-identity/confirm");
        ResponseEntity<?> regConfirmRes = controller.confirmRegistration(
                new MailboxVerificationController.IdentityRequest(appId, sessionId, "acme.example"), confirmRegReq);
        assertThat(regConfirmRes.getStatusCode().value()).isEqualTo(200);
        verify(regIds).confirmRegistration(profileId, "acme.example");
    }

    @Test
    void schedulerDatabaseGuardIgnoresNonMatchingContextCloseAndDiscoveryMaintenanceSkipsBeforeTransaction() {
        ApplicationContext rootCtx = mock(ApplicationContext.class);
        ApplicationContext unrelatedChildCtx = mock(ApplicationContext.class);
        SchedulerDatabaseGuard guard = new SchedulerDatabaseGuard();
        guard.setApplicationContext(rootCtx);

        guard.onApplicationEvent(new ContextClosedEvent(unrelatedChildCtx));
        assertThat(guard.isReady()).isTrue();

        guard.onApplicationEvent(new ContextClosedEvent(rootCtx));
        assertThat(guard.isReady()).isFalse();

        JdbcTemplate db = mock(JdbcTemplate.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        JobDiscoveryService discovery = new JobDiscoveryService(db, mock(OutboxWriter.class), new MaintenanceModeService(false));
        ReflectionTestUtils.setField(discovery, "databaseGuard", guard);
        ReflectionTestUtils.setField(discovery, "transactionManager", txManager);

        discovery.scheduledDiscoveryMaintenance();
        verifyNoInteractions(txManager, db);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emailIntelligenceServiceRedactsOtpAndUrlSecretsFromVerificationSubjects() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TrackRepository track = new TrackRepository(db, mapper);
        TrackEvidenceRecorder recorder = new TrackEvidenceRecorder(track, mock(AuditLogWriter.class));
        EmailIntelligenceService svc = new EmailIntelligenceService(
                db, new CredentialVault(), mock(NotificationService.class),
                mock(OwnerContext.class), track, recorder, mapper);

        UUID profileId = UUID.randomUUID();
        UUID emailId = UUID.randomUUID();
        when(db.query(anyString(), any(RowMapper.class), eq("<otp-subj@acme.example>"), eq(profileId)))
                .thenReturn(List.of());
        when(db.query(anyString(), any(RowMapper.class), eq(profileId)))
                .thenReturn(List.of());
        ArgumentCaptor<Object> sqlArgs = ArgumentCaptor.forClass(Object.class);
        when(db.query(
                contains("insert into emails"),
                any(RowMapper.class),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture()))
                .thenReturn(List.of(emailId));

        svc.ingestFromMailboxConnector(
                profileId,
                new EmailIntelligenceService.ConnectorMessage(
                        "cloudflare", "<otp-subj@acme.example>", "security@acme.example",
                        "robin+abc123@mail.example.com",
                        "482913 is your verification code https://acme.example/verify?token=topsecret99",
                        "Use code 482913", Instant.now(),
                        "robin+abc123@mail.example.com", null, List.of()));

        String storedSubject = String.valueOf(sqlArgs.getAllValues().get(5));
        String storedBody = String.valueOf(sqlArgs.getAllValues().get(6));
        assertThat(storedSubject).doesNotContain("482913").doesNotContain("topsecret99").contains("[number]").contains("[link]");
        assertThat(storedBody).isEmpty();
    }
}
