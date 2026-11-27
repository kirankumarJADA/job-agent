package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.apply.ApplyController;
import com.personal.jobagent.apply.ApplyReadinessService;
import com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.ApplyDocumentSelector;
import com.personal.jobagent.automation.ApplyDocumentSelector.DocumentSelection;
import com.personal.jobagent.automation.AshbyExecutionPlanService;
import com.personal.jobagent.automation.AutomationPlanRepository;
import com.personal.jobagent.automation.GreenhouseExecutionPlanService;
import com.personal.jobagent.automation.InspectionPlanService;
import com.personal.jobagent.automation.LeverExecutionPlanService;
import com.personal.jobagent.automation.UniversalBrowserPlanService;
import com.personal.jobagent.automation.WorkdayExecutionPlanService;
import com.personal.jobagent.automation.ZohoRecruitExecutionPlanService;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.identity.CredentialVault;
import com.personal.jobagent.identity.EmployerCredentialService;
import com.personal.jobagent.identity.IdentityService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.mailbox.MailboxVerificationProvider.VerificationHit;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import com.personal.jobagent.mailbox.RegistrationIdentityService.ManagedMailboxNotReadyException;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.qa.ApplicationAnswerRepository;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.security.SecretCipher;
import com.personal.jobagent.track.TrackEvidenceRecorder;
import com.personal.jobagent.track.TrackRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 3I Phase 3 unit tests covering settings validation, Cloudflare auth trust boundaries,
 * separated readiness reporting, pagination & alignment in ManagedMailVerificationProvider,
 * Managed Mail retention lease protection, IdentityService overwrite protection,
 * ApplyController fail-closed handling, UniversalBrowserPlanService explicit account authorization,
 * and EmailIntelligenceService Managed Mail non-retention.
 */
class Stage3IPhase3UnitTest {

    private static final SecretCipher CIPHER =
            new SecretCipher("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void managedMailSettingsValidatesAddressPrefixAndVerificationMissing() {
        ManagedMailSettings invalidPrefix = new ManagedMailSettings(
                "mail.example.com", "cloudflare", "", 900,
                "secret-value", 900, "mx.cloudflare.net",
                "1bad-prefix", 120, 30, CIPHER);
        assertThat(invalidPrefix.configured()).isFalse();
        assertThat(invalidPrefix.missing()).contains("APP_MANAGED_MAIL_ADDRESS_PREFIX");

        ManagedMailSettings unconfiguredAuthServ = new ManagedMailSettings(
                "mail.example.com", "cloudflare", "", 900,
                "secret-value", 900, "",
                "robin+", 120, 30, CIPHER);
        assertThat(unconfiguredAuthServ.configured()).isTrue();
        assertThat(unconfiguredAuthServ.verificationMissing())
                .containsExactly("APP_MANAGED_MAIL_CLOUDFLARE_TRUSTED_AUTHSERV_IDS");

        ManagedMailSettings fullCloudflare = new ManagedMailSettings(
                "mail.example.com", "cloudflare", "", 900,
                "secret-value", 900, "mx.cloudflare.net, alt.cloudflare.net",
                "robin+", 120, 30, CIPHER);
        assertThat(fullCloudflare.configured()).isTrue();
        assertThat(fullCloudflare.verificationMissing()).isEmpty();
        assertThat(fullCloudflare.cloudflareTrustedAuthServIds())
                .containsExactlyInAnyOrder("mx.cloudflare.net", "alt.cloudflare.net");
    }

    @Test
    void cloudflareInboundParserDropsUntrustedSpfAndNeverFallsBackOnAmbiguousFrom() throws Exception {
        Map<String, Object> ambiguousMap = new LinkedHashMap<>();
        ambiguousMap.put("envelopeRecipient", "robin+abc@mail.example.com");
        ambiguousMap.put("envelopeSender", "bounces@spoofed.example");
        ambiguousMap.put("from", "Alice <a@one.example>, Bob <b@two.example>");
        ambiguousMap.put("to", "robin+abc@mail.example.com");
        ambiguousMap.put("subject", "Verification");
        ambiguousMap.put("text", "Code: 482913");
        ambiguousMap.put("spf", "pass");
        ambiguousMap.put("dkim", "pass");
        ambiguousMap.put("dkimDomain", "one.example");
        ambiguousMap.put("authServId", "mx.cloudflare.net");
        ambiguousMap.put("authTrusted", false);
        String ambiguousUntrusted = JSON.writeValueAsString(ambiguousMap);

        CloudflareInboundParser.Result r1 = CloudflareInboundParser.parse(ambiguousUntrusted, JSON);
        assertThat(r1.accepted()).isTrue();
        assertThat(r1.message().fromAddress()).isNull();
        assertThat(r1.message().senderDomain()).isNull();
        assertThat(r1.message().spfResult()).isNull();
        assertThat(r1.message().dkimDomain()).isNull();
        assertThat(r1.message().dkimResult()).isNull();

        String conflictPayload = JSON.writeValueAsString(Map.of(
                "envelopeRecipient", "robin+abc@mail.example.com",
                "from", "Security <security@acme.example>",
                "subject", "Code",
                "text", "Your code is 111222",
                "spf", "pass",
                "dkim", "pass",
                "dkimDomain", "acme.example",
                "authServId", "mx.cloudflare.net",
                "authTrusted", true,
                "authConflict", true
        ));
        CloudflareInboundParser.Result r2 = CloudflareInboundParser.parse(conflictPayload, JSON);
        assertThat(r2.accepted()).isTrue();
        assertThat(r2.message().spfResult()).isEqualTo("fail");
        assertThat(r2.message().dkimDomain()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void managedMailboxServiceSeparatesTransportVsAutomatedVerificationReadiness() {
        UUID profileId = UUID.randomUUID();
        ManagedMailSettings settingsNoAuthServ = new ManagedMailSettings(
                "mail.example.com", "cloudflare", "", 900,
                "secret-value", 900, "",
                "robin+", 120, 30, CIPHER);

        JdbcTemplate db = mock(JdbcTemplate.class);
        UUID addrId = UUID.randomUUID();
        ManagedMailboxService.ManagedAddress managedAddr = new ManagedMailboxService.ManagedAddress(
                addrId, profileId, "abc123", "mail.example.com", "ACTIVE");
        doReturn(List.of(managedAddr)).when(db).query(
                contains("from managed_mail_addresses"), any(RowMapper.class), eq(profileId), eq("mail.example.com"));
        Timestamp verifiedAt = Timestamp.from(Instant.now().minusSeconds(60));
        when(db.queryForList(contains("from managed_mail_domains"), eq("mail.example.com"), eq("cloudflare")))
                .thenReturn(List.of(Map.of(
                        "receiving_verified_at", verifiedAt,
                        "last_inbound_at", verifiedAt
                )));
        when(db.queryForList(contains("from managed_inbound_messages"), eq("mail.example.com"), eq("cloudflare")))
                .thenReturn(List.of());

        ManagedMailboxService svcNoAuthServ = new ManagedMailboxService(db, settingsNoAuthServ);
        ManagedMailboxService.Status s1 = svcNoAuthServ.status(profileId);
        assertThat(s1.readiness()).isEqualTo(ManagedMailboxService.Readiness.ACTIVE);
        assertThat(s1.transportReadiness()).isEqualTo(ManagedMailboxService.Readiness.ACTIVE);
        assertThat(s1.usableForRegistration()).isTrue();
        assertThat(s1.senderAuthenticationReadiness()).isEqualTo("UNCONFIGURED");
        assertThat(s1.employerVerificationReadiness()).isEqualTo("TRUSTED_AUTHSERV_REQUIRED");
        assertThat(s1.usableForAutomatedVerification()).isFalse();
        assertThat(s1.verificationMissing()).containsExactly("APP_MANAGED_MAIL_CLOUDFLARE_TRUSTED_AUTHSERV_IDS");

        ManagedMailboxService.ManagedAddress addr = s1.address();
        assertThat(svcNoAuthServ.matchesAddress(addr, "robin+abc123@mail.example.com")).isTrue();
        assertThat(svcNoAuthServ.matchesAddress(addr, "abc123@mail.example.com")).isTrue();
        assertThat(svcNoAuthServ.matchesAddress(addr, "robin+wrong@mail.example.com")).isFalse();

        ManagedMailSettings settingsWithAuthServ = new ManagedMailSettings(
                "mail.example.com", "cloudflare", "", 900,
                "secret-value", 900, "mx.cloudflare.net",
                "robin+", 120, 30, CIPHER);
        ManagedMailboxService svcConfigured = new ManagedMailboxService(db, settingsWithAuthServ);
        ManagedMailboxService.Status s2 = svcConfigured.status(profileId);
        assertThat(s2.senderAuthenticationReadiness()).isEqualTo("CONFIGURED");
        assertThat(s2.employerVerificationReadiness()).isEqualTo("ACTIVE");
        assertThat(s2.usableForAutomatedVerification()).isTrue();
        assertThat(s2.verificationMissing()).isEmpty();

        when(db.queryForList(contains("from managed_inbound_messages"), eq("mail.example.com"), eq("cloudflare")))
                .thenReturn(List.of(Map.of("1", 1)));
        assertThat(svcConfigured.status(profileId).senderAuthenticationReadiness()).isEqualTo("VERIFIED");
    }

    @Test
    void managedMailVerificationProviderRequiresCloudflareTrustAlignmentAndPaginatesPast25Rows() {
        UUID profileId = UUID.randomUUID();
        UUID addrId = UUID.randomUUID();
        ManagedMailboxService mailboxes = mock(ManagedMailboxService.class);
        when(mailboxes.usableAddress(profileId)).thenReturn(Optional.of(
                new ManagedMailboxService.ManagedAddress(addrId, profileId, "abc123", "mail.example.com", "ACTIVE")));

        ManagedMessageContent content = new ManagedMessageContent(CIPHER, JSON);
        String encryptedOtp = content.encrypt(new InboundMailMessage(
                "cloudflare", "robin+abc123@mail.example.com", "bounces@acme.example",
                "Security <security@acme.example>", "security@acme.example",
                "Verify email", "Your verification code is 918273", List.of(),
                "<otp@acme.example>", null, List.of("pass"), List.of("pass"), List.of(),
                List.of(), "mail.acme.example", "pass", "mx.cloudflare.net", false));
        String encryptedFiller = content.encrypt(new InboundMailMessage(
                "cloudflare", "robin+abc123@mail.example.com", "bounces@other.example",
                "Info <info@other.example>", "info@other.example",
                "Newsletter", "Welcome 123456", List.of(),
                "<other@other.example>", null, List.of("pass"), List.of("pass"), List.of(),
                List.of(), "other.example", "pass", "mx.cloudflare.net", false));

        // 1. Untrusted Cloudflare message (auth_trusted=false) is rejected even if content has matching sender & OTP
        JdbcTemplate dbUntrusted = mock(JdbcTemplate.class);
        Instant now = Instant.now();
        when(dbUntrusted.queryForList(anyString(), eq(profileId), eq(addrId), any(Timestamp.class), any(Timestamp.class), anyInt()))
                .thenReturn(List.of(row(UUID.randomUUID(), "cloudflare", "robin+abc123@mail.example.com",
                        "acme.example", "pass", "mail.acme.example", "pass", false, encryptedOtp, now)));
        ManagedMailVerificationProvider providerUntrusted = new ManagedMailVerificationProvider(dbUntrusted, mailboxes, content);
        assertThat(providerUntrusted.findVerification(profileId, "acme.example", null, now.minusSeconds(60))).isEmpty();

        // 2. Unaligned DKIM domain is rejected even when auth_trusted=true
        JdbcTemplate dbUnaligned = mock(JdbcTemplate.class);
        when(dbUnaligned.queryForList(anyString(), eq(profileId), eq(addrId), any(Timestamp.class), any(Timestamp.class), anyInt()))
                .thenReturn(List.of(row(UUID.randomUUID(), "cloudflare", "robin+abc123@mail.example.com",
                        "acme.example", "pass", "spoofed-dkim.example", "pass", true, encryptedOtp, now)));
        ManagedMailVerificationProvider providerUnaligned = new ManagedMailVerificationProvider(dbUnaligned, mailboxes, content);
        assertThat(providerUnaligned.findVerification(profileId, "acme.example", null, now.minusSeconds(60))).isEmpty();

        // 3. Keyset pagination scans past 25 non-matching rows to find row #26 with aligned DKIM & auth_trusted=true
        List<Map<String, Object>> page1 = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            page1.add(row(UUID.randomUUID(), "cloudflare", "robin+abc123@mail.example.com",
                    "other.example", "pass", "other.example", "pass", true, encryptedFiller, now.minusSeconds(i + 1)));
        }
        UUID matchId = UUID.randomUUID();
        List<Map<String, Object>> page2 = List.of(row(
                matchId, "cloudflare", "robin+abc123@mail.example.com",
                "acme.example", "pass", "mail.acme.example", "pass", true, encryptedOtp, now.minusSeconds(30)));

        JdbcTemplate dbPaged = mock(JdbcTemplate.class);
        when(dbPaged.queryForList(anyString(), eq(profileId), eq(addrId), any(Timestamp.class), any(Timestamp.class), eq(25)))
                .thenReturn(page1);
        when(dbPaged.queryForList(anyString(), eq(profileId), eq(addrId), any(Timestamp.class), any(Timestamp.class),
                any(Timestamp.class), any(Timestamp.class), any(UUID.class), eq(25)))
                .thenReturn(page2);

        ManagedMailVerificationProvider providerPaged = new ManagedMailVerificationProvider(dbPaged, mailboxes, content);
        Optional<VerificationHit> hit = providerPaged.findVerification(profileId, "acme.example", null, now.minusSeconds(120));
        assertThat(hit).isPresent();
        assertThat(hit.get().otp()).isEqualTo("918273");
        assertThat(hit.get().sourceManagedMessageId()).isEqualTo(matchId);
    }

    @Test
    void identityServiceProtectsRobinManagedEmailAgainstOverwrite() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        IdentityService identities = new IdentityService(db);
        UUID profileId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID();

        when(db.queryForList(anyString(), eq(profileId), eq("acme.example"))).thenReturn(List.of(Map.of(
                "id", existingId,
                "applicant_email", "robin+abc123@mail.example.com",
                "provider", "ROBIN_MANAGED"
        )));

        assertThatThrownBy(() -> identities.create(profileId, "different@gmail.com", "acme.example", "cred-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Robin-managed");

        UUID returned = identities.create(profileId, "ROBIN+ABC123@mail.example.com", "acme.example", "cred-2");
        assertThat(returned).isEqualTo(existingId);
        verify(db).update(anyString(), eq("cred-2"), eq(existingId), eq(profileId));
    }

    @Test
    void applyControllerHandlesManagedMailboxNotReadyOnPackageAndUniversalAccountAuth() {
        OwnerContext owner = mock(OwnerContext.class);
        ApplyReadinessService readiness = mock(ApplyReadinessService.class);
        GreenhouseExecutionPlanService gh = mock(GreenhouseExecutionPlanService.class);
        ZohoRecruitExecutionPlanService zoho = mock(ZohoRecruitExecutionPlanService.class);
        LeverExecutionPlanService lever = mock(LeverExecutionPlanService.class);
        WorkdayExecutionPlanService wd = mock(WorkdayExecutionPlanService.class);
        AshbyExecutionPlanService ashby = mock(AshbyExecutionPlanService.class);
        InspectionPlanService insp = mock(InspectionPlanService.class);
        JobRepository jobs = mock(JobRepository.class);
        AuditLogWriter audit = mock(AuditLogWriter.class);
        UniversalBrowserPlanService universal = mock(UniversalBrowserPlanService.class);

        ApplyController controller = new ApplyController(
                owner, readiness, gh, zoho, lever, wd, ashby, insp, jobs, audit, universal, true);

        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String url = "https://careers.acme.example/jobs/1";
        when(owner.profileIdOrNull()).thenReturn(profileId);
        when(owner.actorOr("user")).thenReturn("cand");
        when(readiness.evaluate(profileId, appId)).thenReturn(Map.of(
                "packageReady", true,
                "application", Map.of("jobId", jobId.toString(), "applicationUrl", url)
        ));
        JobRecord job = mock(JobRecord.class);
        when(job.applicationUrl()).thenReturn(url);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));

        // 1. createPackage returns 409 MANAGED_MAILBOX_NOT_READY when universal plan creation throws ManagedMailboxNotReadyException
        when(universal.createExecutionPlan(profileId, appId, jobId))
                .thenThrow(new ManagedMailboxNotReadyException("PENDING_RECEIVING_VERIFICATION"));
        ResponseEntity<?> pkgRes = controller.createPackage(appId, null);
        assertThat(pkgRes.getStatusCode().value()).isEqualTo(409);
        assertThat(pkgRes.getBody().toString()).contains("MANAGED_MAILBOX_NOT_READY");
        verify(audit, times(1)).write(any());

        // 2. createUniversalAccount refuses acceptTerms=false via AccountCreationRefusedException
        when(universal.authorizeAccountCreation(profileId, appId, jobId, false))
                .thenThrow(new UniversalBrowserPlanService.AccountCreationRefusedException("TERMS_NOT_ACCEPTED", "must accept"));
        ResponseEntity<?> refusedRes = controller.createUniversalAccount(appId, new ApplyController.UniversalAccountRequest(false));
        assertThat(refusedRes.getStatusCode().value()).isEqualTo(409);
        assertThat(refusedRes.getBody().toString()).contains("TERMS_NOT_ACCEPTED");

        // 3. createUniversalAccount succeeds cleanly on acceptTerms=true
        UUID planId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(universal.authorizeAccountCreation(profileId, appId, jobId, true))
                .thenReturn(new UniversalBrowserPlanService.AccountCreationResult(planId, sessionId, false));
        ResponseEntity<?> okRes = controller.createUniversalAccount(appId, new ApplyController.UniversalAccountRequest(true));
        assertThat(okRes.getStatusCode().value()).isEqualTo(200);
        assertThat(okRes.getBody().toString()).contains(planId.toString()).contains("PREPARED");
    }

    @Test
    void universalBrowserPlanServiceDoesNotAutoAuthorizeAccountCreationAndRefusesDuplicateCreds() {
        AutomationPlanRepository plans = mock(AutomationPlanRepository.class);
        ApplyDocumentSelector docSelector = mock(ApplyDocumentSelector.class);
        ApplicationAnswerRepository answers = mock(ApplicationAnswerRepository.class);
        RegistrationIdentityService regIds = mock(RegistrationIdentityService.class);
        EmployerCredentialService creds = mock(EmployerCredentialService.class);
        IdentityService identities = mock(IdentityService.class);
        AddressGuard guard = new AddressGuard(host -> new InetAddress[]{InetAddress.getByName("93.184.216.34")});
        JdbcTemplate db = mock(JdbcTemplate.class);

        UniversalBrowserPlanService svc = new UniversalBrowserPlanService(
                plans, docSelector, answers, regIds, guard, db, JSON, false, creds, identities);

        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        String url = "https://acme-verify.example/apply";

        when(db.queryForMap(contains("select a.id, j.application_url"), eq(appId), eq(profileId), eq(jobId)))
                .thenReturn(Map.of("id", appId, "application_url", url));
        when(db.queryForList(anyString(), eq(appId), eq(profileId)))
                .thenReturn(List.of());
        when(creds.exists(profileId, "acme-verify.example")).thenReturn(false);
        when(plans.create(eq(profileId), eq(appId), eq(jobId), eq(url), eq("universal:" + appId), any()))
                .thenReturn(planId);
        when(plans.findById(planId)).thenReturn(Optional.of(
                new AutomationPlanRepository.PlanRow(planId, appId, url, "PREPARED", Map.of(), null, false, Instant.now(), Instant.now())));
        when(docSelector.select(profileId, jobId, appId))
                .thenReturn(new DocumentSelection(null, null, CoverLetterRequirement.OPTIONAL, List.of(), List.of()));
        when(db.queryForMap(contains("u.email::text as email"), eq(appId), eq(profileId), eq(jobId))).thenReturn(Map.of(
                "email", "personal@gmail.com",
                "display_name", "Dev Candidate",
                "phone", "+447700900111",
                "location", "London",
                "links", "{}"
        ));
        when(regIds.resolveApplicationContactEmail(profileId, url, "personal@gmail.com"))
                .thenReturn("robin+abc123@mail.example.com");
        when(db.update(anyString(), anyString(), eq(planId), eq(profileId), eq(appId))).thenReturn(1);

        Optional<UUID> created = svc.createExecutionPlan(profileId, appId, jobId);
        assertThat(created).contains(planId);
        verify(creds, never()).save(any(), anyString(), anyString(), anyString());

        ArgumentCaptor<String> planJsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(db).update(anyString(), planJsonCaptor.capture(), eq(planId), eq(profileId), eq(appId));
        assertThat(planJsonCaptor.getValue())
                .contains("\"accountCreationAuthorized\":false")
                .contains("\"employerDomain\":\"acme-verify.example\"")
                .contains("robin+abc123@mail.example.com");

        assertThatThrownBy(() -> svc.authorizeAccountCreation(profileId, appId, jobId, false))
                .isInstanceOf(UniversalBrowserPlanService.AccountCreationRefusedException.class)
                .hasMessageContaining("authorization");

        when(db.queryForMap(contains("a.account_session_id"), eq(appId), eq(profileId), eq(jobId))).thenReturn(Map.of(
                "id", appId,
                "application_url", url
        ));
        when(creds.exists(profileId, "acme-verify.example")).thenReturn(true);
        assertThatThrownBy(() -> svc.authorizeAccountCreation(profileId, appId, jobId, true))
                .isInstanceOf(UniversalBrowserPlanService.AccountCreationRefusedException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @SuppressWarnings("unchecked")
    void emailIntelligenceServiceNeverRetainsBodyTextForManagedMailOtp() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        TrackRepository track = new TrackRepository(db, JSON);
        TrackEvidenceRecorder recorder = new TrackEvidenceRecorder(track, mock(AuditLogWriter.class));
        EmailIntelligenceService svc = new EmailIntelligenceService(
                db, new CredentialVault(), mock(NotificationService.class),
                mock(OwnerContext.class), track, recorder, JSON);

        UUID profileId = UUID.randomUUID();
        UUID insertedEmailId = UUID.randomUUID();
        doReturn(List.of()).when(db).query(anyString(), any(RowMapper.class), eq("<otp-cf-1@acme.example>"), eq(profileId));
        doReturn(List.of()).when(db).query(anyString(), any(RowMapper.class), eq(profileId));
        ArgumentCaptor<Object> sqlArgs = ArgumentCaptor.forClass(Object.class);
        doReturn(List.of(insertedEmailId)).when(db).query(
                contains("insert into emails"),
                any(RowMapper.class),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(),
                sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture(), sqlArgs.capture());

        EmailIntelligenceService.IngestResult result = svc.ingestFromMailboxConnector(
                profileId,
                new EmailIntelligenceService.ConnectorMessage(
                        "cloudflare", "<otp-cf-1@acme.example>", "security@acme.example",
                        "robin+abc123@mail.example.com", "Your verification code",
                        "Your one-time verification code is 482913", Instant.now(),
                        "robin+abc123@mail.example.com", null, List.of()));

        assertThat(result.classification()).isEqualTo("VERIFICATION_CODE");
        List<Object> captured = sqlArgs.getAllValues();
        // emails insert columns: (0) id, (1) message_id, (2) thread_id, (3) from_address, (4) to_address,
        // (5) subject, (6) body_text, (7) received_at, ..., (15) body_retained
        assertThat(captured.get(6)).isEqualTo("");
        assertThat(captured.get(15)).isEqualTo(Boolean.FALSE);
    }

    @Test
    void managedMailRetentionJobExpiresOverdueLeasesAndProtectsActiveLeases() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        ManagedMailSettings settings = new ManagedMailSettings(
                "mail.example.com", "cloudflare", "", 900,
                "secret-value", 900, "mx.cloudflare.net",
                "robin+", 120, 30, CIPHER);
        ManagedMailRetentionJob job = new ManagedMailRetentionJob(db, settings);

        when(db.update(anyString(), any(), anyInt())).thenReturn(0);
        when(db.update(anyString(), any(), any(), any(), anyInt())).thenReturn(1);

        ManagedMailRetentionJob.Result res = job.purge(Instant.now());
        assertThat(res.contentErased()).isEqualTo(1);
        verify(db).update(contains("update mailbox_verifications"), any(Timestamp.class), anyInt());
        verify(db).update(contains("reservation_expires_at is null or reservation_expires_at <= ?"),
                any(Timestamp.class), any(Timestamp.class), any(Timestamp.class), anyInt());
    }

    private static Map<String, Object> row(UUID id, String providerId, String recipient, String senderDomain,
                                           String spfResult, String dkimDomain, String dmarcResult,
                                           boolean authTrusted, String encrypted, Instant receivedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("provider_id", providerId);
        row.put("envelope_recipient", recipient);
        row.put("sender_domain", senderDomain);
        row.put("spf_result", spfResult);
        row.put("dkim_domain", dkimDomain);
        row.put("dmarc_result", dmarcResult);
        row.put("auth_trusted", authTrusted);
        row.put("content_encrypted", encrypted);
        row.put("received_at", Timestamp.from(receivedAt));
        return row;
    }
}
