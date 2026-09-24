package com.personal.jobagent.security;

import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.audit.AuditController;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlan;
import com.personal.jobagent.automation.AutomationPlanRepository;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationRepository;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-account isolation, against a real PostgreSQL with the real repositories
 * and services.
 *
 * <p>Why a database is required: the whole point of V022 is that ownership is
 * enforced <em>in SQL</em> — as a predicate on every statement, as a foreign key,
 * and as per-owner unique indexes. A mocked {@code JdbcTemplate} can assert that
 * the SQL string contains {@code profile_id = ?} (see
 * {@code UserDataIsolationTest}) but it cannot show that Postgres then refuses to
 * hand one account another account's rows, that two candidates can both hold a
 * live application for the same shared posting, or that one candidate's match
 * result no longer overwrites another's. Those are the guarantees the end user
 * actually depends on.
 *
 * <h2>Running it</h2>
 * Disabled unless a PostgreSQL URL is supplied, because a build machine without a
 * database must not fail on it:
 *
 * <pre>
 * mvn -f backend/pom.xml verify -Dit.postgres.url=jdbc:postgresql://127.0.0.1:5432/jobagent
 * </pre>
 *
 * Optional: {@code -Dit.postgres.username} (default {@code jobagent}),
 * {@code -Dit.postgres.password} (default empty). The schema is expected to be
 * migrated already — Flyway runs on context startup, so pointing this at an empty
 * database also verifies the migrations themselves.
 *
 * <p>{@code @Testcontainers} is deliberately not used: the CI path for this
 * project already supplies a database URL, and Testcontainers would make the
 * class unrunnable precisely where a real PostgreSQL is available.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "it.postgres.url", matches = ".+",
        disabledReason = "Set -Dit.postgres.url to run the PostgreSQL isolation tests")
class UserDataIsolationIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("it.postgres.url"));
        registry.add("spring.datasource.username",
                () -> System.getProperty("it.postgres.username", "jobagent"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("it.postgres.password", ""));
        // Background sweeps are irrelevant here and would add noise.
        registry.add("app.events.dispatch-interval-ms", () -> "600000");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private NotificationRepository notifications;
    @Autowired private ApplicationStatusService applications;
    @Autowired private EmailIntelligenceService emails;
    @Autowired private AutomationPlanRepository automationPlans;
    @Autowired private JobRepository jobs;
    @Autowired private AuditController auditController;
    @Autowired private AuditLogWriter auditLogWriter;
    @Autowired private OwnerContext ownerContext;
    @Autowired private com.personal.jobagent.identity.IdentityService identityService;
    @Autowired private com.personal.jobagent.resume.ResumeAtsRepository resumeAtsRepository;

    /** Account A (the "caller" in most assertions) and account B (the "stranger"). */
    private UUID userA, userB, profileA, profileB;
    private UUID jobId;

    @BeforeEach
    void seedTwoAccounts() {
        userA = createAccount("alice@isolation.test");
        userB = createAccount("bob@isolation.test");
        profileA = profileFor(userA);
        profileB = profileFor(userB);
        jobId = seedJob();

        as(userA);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── applications ─────────────────────────────────────────────────

    @Test
    void twoCandidatesCanBothHoldALiveApplicationForTheSameSharedPosting() {
        UUID applicationA = seedApplication(profileA, jobId);
        // The pre-V022 global unique index (applications_one_per_job) made this
        // impossible: a second candidate could never apply to a posting someone
        // else had already applied to.
        UUID applicationB = seedApplication(profileB, jobId);

        assertThat(applicationA).isNotEqualTo(applicationB);
        assertThat(applications.existsForOwner(profileA, applicationA)).isTrue();
        assertThat(applications.existsForOwner(profileB, applicationB)).isTrue();
    }

    @Test
    void readingAnotherAccountsApplicationFindsNothing() {
        UUID applicationB = seedApplication(profileB, jobId);

        assertThat(applications.find(profileA, applicationB)).isEmpty();
        assertThat(applications.existsForOwner(profileA, applicationB)).isFalse();
        assertThat(applications.timeline(profileA, applicationB)).isEmpty();
    }

    @Test
    void transitioningAnotherAccountsApplicationIsRefusedAndChangesNothing() {
        UUID applicationB = seedApplication(profileB, jobId);

        assertThatThrownBy(() -> applications.apply(profileA, applicationB, "APPLICATION_STARTED",
                "attack-1", "alice@isolation.test", Map.of()))
                .isInstanceOf(java.util.NoSuchElementException.class);

        // The status is untouched — the ownership predicate is part of the
        // locking read, so the refusal happens before any write.
        assertThat(statusOf(applicationB)).isEqualTo("READY_TO_APPLY");
        assertThat(jdbc.queryForObject("select count(*) from application_events where application_id = ?",
                Integer.class, applicationB)).isZero();
    }

    @Test
    void aLegacyUnattributedApplicationIsInvisibleToEveryone() {
        // No profile_id at all, which is exactly what V022 leaves behind for a
        // legacy row it could not safely attribute.
        UUID orphan = UuidV7.generate();
        jdbc.update("""
                insert into applications (id, job_id, status, mode) values (?, ?, 'READY_TO_APPLY', 'ASSISTED')
                """, orphan, jobId);

        // Fail closed: not visible to A, not visible to B.
        assertThat(applications.find(profileA, orphan)).isEmpty();
        assertThat(applications.find(profileB, orphan)).isEmpty();
    }

    // ── notifications ────────────────────────────────────────────────

    @Test
    void notificationsAreOnlyEverVisibleToTheirOwner() {
        UUID notificationB = seedNotification(profileB, "b-only");
        UUID notificationA = seedNotification(profileA, "a-only");

        assertThat(notifications.findRecent(profileA, 200))
                .extracting(r -> r.id())
                .contains(notificationA)
                .doesNotContain(notificationB);
        assertThat(notifications.findById(profileA, notificationB)).isEmpty();
        // The write path is filtered too: A cannot even mark B's notification read.
        // (The predicate is part of the UPDATE, so there is no read-then-write window.)
        assertThat(notifications.markRead(profileA, notificationB)).isFalse();
        assertThat(unread(notificationB)).isTrue();
    }

    @Test
    void markingAllReadCannotReachAnotherAccountsNotifications() {
        UUID notificationB = seedNotification(profileB, "b-unread");
        UUID notificationA = seedNotification(profileA, "a-unread");

        notifications.markAllRead(profileA);

        // A's sweep is scoped to A; B's notification is untouched.
        assertThat(unread(notificationA)).isFalse();
        assertThat(unread(notificationB)).isTrue();

        notifications.markAllRead(profileB);
        assertThat(unread(notificationB)).isFalse();
    }

    @Test
    void anUnattributedSystemNotificationIsInvisibleToUsers() {
        UUID systemNotification = UuidV7.generate();
        jdbc.update("""
                insert into notifications (id, severity, category, title, dedup_key)
                values (?, 'ERROR', 'OUTBOX_DLQ', 'system notice', ?)
                """, systemNotification, "outbox-dlq:" + systemNotification);

        assertThat(notifications.findRecent(profileA, 200))
                .extracting(r -> r.id())
                .doesNotContain(systemNotification);
        assertThat(notifications.findById(profileA, systemNotification)).isEmpty();
    }

    // ── emails and verification codes ────────────────────────────────

    @Test
    void theSameMessageIdIsStoredPerOwnerRatherThanReplayedAcrossAccounts() {
        // Unique per run so the test is re-runnable against a persistent database.
        String messageId = "shared-" + UUID.randomUUID() + "@example.com";

        var first = emails.ingest(profileA, messageId, "r@example.com", "alice@isolation.test",
                "Interview invitation", "Can you attend?", Instant.now());
        var second = emails.ingest(profileB, messageId, "r@example.com", "bob@isolation.test",
                "Interview invitation", "Can you attend?", Instant.now());

        // Before V022 message_id was globally unique, so B's ingest returned A's row
        // (class and all) instead of storing B's own copy.
        assertThat(second.replayed()).isFalse();
        assertThat(second.emailId()).isNotEqualTo(first.emailId());
        assertThat(jdbc.queryForObject("select count(*) from emails where message_id = ?",
                Integer.class, messageId)).isEqualTo(2);

        // A genuine replay for the SAME owner is still detected, so per-owner
        // idempotency is intact.
        assertThat(emails.ingest(profileA, messageId, "r@example.com", "alice@isolation.test",
                "Interview invitation", "Can you attend?", Instant.now()).replayed()).isTrue();
    }

    @Test
    void emailCorrelationNeverMatchesAnotherAccountsApplication() {
        // A company unique to this run. The test database is reused between
        // methods and re-runs, so earlier scenarios legitimately leave
        // applications for A behind — and A's own application matching A's
        // email is correct behaviour, not a leak. A fresh company guarantees
        // the only application mentioning it belongs to B.
        String company = "Unique Ltd " + UUID.randomUUID();
        UUID uniqueJobId = seedJobWithCompany(company);
        UUID applicationB = seedApplication(profileB, uniqueJobId);

        // A's message mentions B's company; B has the only application for it.
        var ingested = emails.ingest(profileA, "corr-" + UUID.randomUUID() + "@example.com", "r@example.com",
                "alice@isolation.test", "Regarding " + company, "Body", Instant.now());

        // A must NOT be correlated onto B's application.
        assertThat(ingested.applicationId()).isNull();
        assertThat(ingested.classification()).isNotNull();
        assertThat(applicationB).isNotNull();
    }

    @Test
    void aVerificationCodeCannotBeResolvedByAnotherAccount() {
        UUID applicationB = seedApplication(profileB, jobId);
        UUID sessionB = UuidV7.generate();
        jdbc.update("""
                insert into account_sessions (id, application_id, expected_domain, state, owner_profile_id)
                values (?, ?, 'employer.example', 'WAITING_FOR_EMAIL', ?)
                """, sessionB, applicationB, profileB);
        // Unique per run: a reused message id would replay the earlier run's email,
        // whose code has already been consumed.
        var ingested = emails.ingest(profileB, "otp-" + UUID.randomUUID() + "@example.com",
                "noreply@employer.example", "bob@isolation.test", "Your code",
                "Use 123456 to continue", Instant.now());

        // B can extract and resolve its own code.
        var extraction = emails.extractVerification(profileB, ingested.emailId(), sessionB, "employer.example");
        assertThat(extraction).isPresent();
        UUID extractionId = (UUID) extraction.get().get("extractionId");

        // A cannot, even knowing the extraction id.
        assertThatThrownBy(() -> emails.resolve(profileA, extractionId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(emails.extractVerification(profileA, ingested.emailId(), sessionB, "employer.example")).isEmpty();
        // Still unused, because A's attempt did not consume it.
        assertThat(emails.resolve(profileB, extractionId)).isEqualTo("123456");
    }

    // ── automation ───────────────────────────────────────────────────

    @Test
    void automationPlansAreOwnerScopedEvenWithTheSameIdempotencyKey() {
        UUID applicationA = seedApplication(profileA, jobId);
        UUID applicationB = seedApplication(profileB, jobId);
        String sharedKey = "idem-" + jobId;

        UUID planA = automationPlans.create(profileA, applicationA, jobId, "https://employer.example/apply",
                sharedKey, List.<AutomationPlan.Step>of());
        UUID planB = automationPlans.create(profileB, applicationB, jobId, "https://employer.example/apply",
                sharedKey, List.<AutomationPlan.Step>of());

        // A shared idempotency key must not collapse two candidates' plans into one
        // row, and create() must not read back the other account's plan id.
        assertThat(planA).isNotEqualTo(planB);
        assertThat(automationPlans.find(profileB, planA)).isEmpty();
        assertThat(automationPlans.owns(profileA, planB)).isFalse();
        assertThat(automationPlans.find(profileA, planA)).isPresent();
    }

    @Test
    void startingAnAutomationSessionOnAnotherAccountsApplicationIsRefused() {
        // The application id arrives from the request body. Before the
        // ownership check existed, A could overwrite account_session_id on
        // B's application — driving B's application with A's session.
        UUID applicationB = seedApplication(profileB, jobId);

        assertThat(identityService.startSession(applicationB, "employer.example", profileA)).isEmpty();

        // Nothing was written anywhere: no session row, no back-reference.
        assertThat(jdbc.queryForObject(
                "select count(*) from account_sessions where application_id = ?",
                Integer.class, applicationB)).isZero();
        assertThat(jdbc.queryForObject(
                "select account_session_id from applications where id = ?",
                UUID.class, applicationB)).isNull();

        // The caller's own application still starts a session normally.
        UUID applicationA = seedApplication(profileA, jobId);
        assertThat(identityService.startSession(applicationA, "employer.example", profileA)).isPresent();
    }

    // ── resume / ATS tailoring ───────────────────────────────────────

    @Test
    void tailoringACvAgainstAnotherAccountsApplicationIsRefused() {
        UUID applicationB = seedApplication(profileB, jobId);

        var analysis = new com.personal.jobagent.resume.ResumeAtsAnalysis(UuidV7.generate(), profileA, jobId, applicationB,
                "hash-" + UUID.randomUUID(), "Backend Engineer", "Test", List.of(), List.of(), Map.of(),
                List.of(), List.of(), Map.of(), null, "# Tailored CV", 0L, null, null);

        // A foreign application id takes the same "not found" signal a
        // missing id gets, and the refusal happens before any artifact work.
        // Called through the Spring proxy the repository's own
        // IllegalArgumentException arrives wrapped by transaction
        // translation, so assert on the signal, not the wrapper type.
        assertThatThrownBy(() -> resumeAtsRepository.insert(analysis, "Tailored", false))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Application not found");

        assertThat(jdbc.queryForObject(
                "select cv_version_id from applications where id = ?",
                UUID.class, applicationB)).isNull();
    }

    // ── job match results (the data that used to live on the shared job row) ──

    @Test
    void oneCandidatesMatchResultIsNotAnotherCandidates() {
        jdbc.update("""
                insert into job_matches (profile_id, job_id, score, recommendation, breakdown)
                values (?, ?, 91, 'APPLY', '{"skill_overlap":90}'::jsonb)
                """, profileA, jobId);

        assertThat(jobs.findMatch(profileA, jobId)).isPresent();
        assertThat(jobs.findMatch(profileA, jobId).orElseThrow().score()).isEqualTo(91);
        // B has not scored this posting: no match, not A's match.
        assertThat(jobs.findMatch(profileB, jobId)).isEmpty();
        // Listing shared catalogue jobs never carries anyone's match data.
        assertThat(jobs.findJobs(null, null, 50, null).items()).isNotNull();
    }

    @Test
    void theSharedJobsRowNoLongerCarriesAnyPerCandidateColumns() {
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'jobs' and column_name like 'match%'
                """, String.class);

        // V022 dropped these; they were the per-candidate match decision sitting on
        // a row every authenticated user can select.
        assertThat(columns).isEmpty();
    }

    // ── audit ────────────────────────────────────────────────────────

    @Test
    void theAuditTrailIsScopedToTheCaller() {
        String actorA = "alice@isolation.test";
        String actorB = "bob@isolation.test";
        as(userA);
        auditLogWriter.write(AuditEntry.simple(actorA, "LOGIN_SUCCESS", "203.0.113.1", UuidV7.generate()));
        as(userB);
        auditLogWriter.write(AuditEntry.simple(actorB, "LOGIN_SUCCESS", "203.0.113.2", UuidV7.generate()));

        as(userA);
        List<Map<String, Object>> visibleToA = auditController.listAuditLogs(100);

        assertThat(visibleToA).isNotEmpty();
        assertThat(visibleToA).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo(actorA));
    }

    @Test
    void aLegacyAuditRowIsAttributedByActorEmailBecauseTheTableCannotBeBackfilled() {
        String actorA = "alice@isolation.test";
        UUID legacy = UuidV7.generate();
        // audit_logs is append-only (V008), so V022 could not set profile_id on
        // pre-existing rows; attribution is derived at read time from `actor`.
        jdbc.update("""
                insert into audit_logs (id, actor, action, entity_type) values (?, ?, 'LEGACY_ACTION', 'USER')
                """, legacy, actorA);

        as(userA);
        assertThat(auditController.listAuditLogs(100))
                .extracting(row -> row.get("id"))
                .contains(legacy);

        as(userB);
        assertThat(auditController.listAuditLogs(100))
                .extracting(row -> row.get("id"))
                .doesNotContain(legacy);
    }

    @Test
    void systemAuditRowsAreNotHandedToUsers() {
        UUID systemRow = UuidV7.generate();
        jdbc.update("""
                insert into audit_logs (id, actor, action, entity_type) values (?, 'SYSTEM', 'OUTBOX_DLQ', 'outbox')
                """, systemRow);

        as(userA);
        assertThat(auditController.listAuditLogs(100))
                .extracting(row -> row.get("id"))
                .doesNotContain(systemRow);
    }

    // ── the ownership context itself ─────────────────────────────────

    @Test
    void theCallersProfileIsResolvedFromThePrincipalAndNeverFromInput() {
        as(userA);
        assertThat(ownerContext.profileIdOrNull()).isEqualTo(profileA);
        assertThat(ownerContext.ownerOfApplication(null)).isEmpty();

        as(userB);
        assertThat(ownerContext.profileIdOrNull()).isEqualTo(profileB);

        SecurityContextHolder.clearContext();
        assertThat(ownerContext.profileIdOrNull()).isNull();
        assertThat(ownerContext.isWorkerRequest()).isFalse();
    }

    // ── plumbing ─────────────────────────────────────────────────────

    private void as(UUID userId) {
        UserRecord record = new UserRecord(userId, emailOf(userId), "x", "Test User", "uid-" + userId, "FIREBASE");
        AppUserDetails principal = new AppUserDetails(record);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private UUID createAccount(String email) {
        UUID userId = UuidV7.generate();
        // Inserted directly and idempotently: the test may be re-run against the
        // same database without resetting it.
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, null, 'Isolation Test', 'FIREBASE')
                on conflict (email) do nothing
                """, userId, email);
        return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
    }

    private UUID profileFor(UUID userId) {
        var existing = profileRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        return profileRepository.createProfile(userId, null, null, null, null, null, null, null);
    }

    private String emailOf(UUID userId) {
        return jdbc.queryForObject("select email::text from users where id = ?", String.class, userId);
    }

    private UUID seedJob() {
        return seedJobWithCompany("Isolation Ltd");
    }

    private UUID seedJobWithCompany(String company) {
        UUID sourceId = UuidV7.generate();
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities)
                values (?, 'MANUAL_IMPORT', ?, ?, '{}')
                on conflict (kind, org_identifier) do nothing
                """, sourceId, "isolation-org", "Isolation source");
        UUID resolvedSource = jdbc.queryForObject(
                "select id from job_sources where org_identifier = 'isolation-org'", UUID.class);
        UUID id = UuidV7.generate();
        String external = "isolation-job-" + id;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title,
                                  description_text, content_hash, status)
                values (?, ?, ?, ?, ?, 'Backend Engineer', 'desc', ?, 'DISCOVERED')
                """, id, resolvedSource, external, "dedup-" + external, company, "hash-" + external);
        return id;
    }

    private UUID seedApplication(UUID profileId, UUID jobId) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into applications (id, job_id, status, mode, profile_id)
                values (?, ?, 'READY_TO_APPLY', 'ASSISTED', ?)
                """, id, jobId, profileId);
        return id;
    }

    private UUID seedNotification(UUID profileId, String key) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into notifications (id, severity, category, title, dedup_key, profile_id)
                values (?, 'INFO', 'TEST', 'test', ?, ?)
                """, id, key + ":" + id, profileId);
        return id;
    }

    private boolean unread(UUID notificationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select read_at is null from notifications where id = ?", Boolean.class, notificationId));
    }

    private String statusOf(UUID applicationId) {
        return jdbc.queryForObject("select status from applications where id = ?", String.class, applicationId);
    }
}
