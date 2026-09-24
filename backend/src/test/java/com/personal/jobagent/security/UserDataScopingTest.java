package com.personal.jobagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationStatusService;
import com.personal.jobagent.audit.AuditController;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.automation.AutomationPlanRepository;
import com.personal.jobagent.email.EmailIntelligenceService;
import com.personal.jobagent.identity.CredentialVault;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationRepository;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.ProfileRecord;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ownership predicates for the tables that used to be globally scoped —
 * the per-candidate job match data, applications, notifications, audit_logs,
 * emails and the automation tables.
 *
 * <p>This class exists because "authenticated" is not "isolated": every one of
 * these tables was readable and writable by any signed-in account before V022.
 * Each assertion is deliberately about the <em>statement that reaches the
 * database</em>, not the Java call signature — a scoped-looking method that
 * builds unscoped SQL is exactly the failure mode being guarded against, and it
 * is invisible to a test that only checks the method name.
 *
 * <p>The SQL is inspected through the mock's recorded invocations rather than
 * {@code ArgumentCaptor} plus a fixed argument list: {@code JdbcTemplate} is
 * overloaded with varargs, so a captor that expects the wrong arity silently
 * matches nothing and the test passes for the wrong reason.
 *
 * <p>Companions: {@code UserDataIsolationTest} (controller-level refusals for
 * cover letters, answers and identities) and {@code UserDataIsolationIT} (the
 * same guarantees end-to-end against real PostgreSQL, plus the unique indexes and
 * foreign keys a mocked template cannot see).
 */
class UserDataScopingTest {

    private static final UUID PROFILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String ACTOR = "scoping@example.com";

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── notifications ────────────────────────────────────────────────────

    @Test
    void listingNotificationsIsScopedByOwner() {
        JdbcTemplate jdbc = readOnlyJdbc();
        new NotificationRepository(jdbc, new ObjectMapper()).findRecent(PROFILE_ID, 50);

        assertThat(issuedSql(jdbc)).anySatisfy(sql ->
                assertThat(sql).contains("from notifications where profile_id = ?"));
    }

    @Test
    void markingEveryNotificationReadCannotCrossAccounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new NotificationRepository(jdbc, new ObjectMapper()).markAllRead(PROFILE_ID);

        assertThat(issuedSql(jdbc)).singleElement().asString().contains("where profile_id = ? and read_at is null");
    }

    @Test
    void markingOneNotificationReadScopesByOwnerInTheUpdateItself() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        new NotificationRepository(jdbc, new ObjectMapper()).markRead(PROFILE_ID, UUID.randomUUID());

        // The owner predicate is part of the UPDATE, so there is no
        // check-then-write window for another account's row to slip through.
        assertThat(issuedSql(jdbc)).singleElement().asString()
                .contains("where id = ? and profile_id = ? and read_at is null");
    }

    @Test
    void notificationReadsWithoutAProfileIssueNoStatementAtAll() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NotificationRepository repository = new NotificationRepository(jdbc, new ObjectMapper());

        assertThat(repository.markAllRead(null)).isZero();
        assertThat(repository.findRecent(null, 10)).isEmpty();
        assertThat(repository.findRecentUnread(null, 10)).isEmpty();
        assertThat(repository.countUnread(null)).isZero();
        assertThat(repository.findById(null, UUID.randomUUID())).isEmpty();

        // Not one statement issued: there is no unscoped fallback path to reach.
        Mockito.verifyNoInteractions(jdbc);
    }

    // ── audit ────────────────────────────────────────────────────────────

    @Test
    void theAuditReadIsScopedToTheCallerByOwnerOrActor() {
        JdbcTemplate jdbc = readOnlyJdbc();
        AuditController controller = new AuditController(jdbc, ownerContextWithProfile(jdbc));
        authenticate();

        controller.listAuditLogs(50);

        assertThat(issuedSql(jdbc)).anySatisfy(sql -> assertThat(sql)
                .contains("from audit_logs")
                // Modern rows carry the owner...
                .contains("profile_id = ?")
                // ...and legacy rows, which the append-only table cannot have
                // backfilled, are attributed by actor email instead.
                .contains("lower(actor) = lower(?)"));
    }

    @Test
    void anUnauthenticatedAuditReadReturnsNothingRatherThanEverything() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditController controller = new AuditController(jdbc, ownerContextWithProfile(jdbc));

        assertThat(controller.listAuditLogs(50)).isEmpty();
        Mockito.verifyNoInteractions(jdbc);
    }

    // ── emails ───────────────────────────────────────────────────────────

    @Test
    void emailReplayDetectionIsScopedByOwner() {
        JdbcTemplate jdbc = emailJdbc();
        emailService(jdbc).ingest(PROFILE_ID, "msg-1", "r@example.com", "me@example.com", "s", "b", Instant.now());

        // A global message_id lookup is what used to return another account's
        // email row — classification and all — instead of storing the caller's own.
        assertThat(issuedSql(jdbc)).anySatisfy(sql -> assertThat(sql)
                .contains("from emails where message_id=? and profile_id=?"));
    }

    @Test
    void emailApplicationCorrelationIsRestrictedToTheOwnersApplications() {
        JdbcTemplate jdbc = emailJdbc();
        emailService(jdbc).ingest(PROFILE_ID, "msg-2", "r@example.com", "me@example.com", "s", "b", Instant.now());

        assertThat(issuedSql(jdbc)).anySatisfy(sql -> assertThat(sql)
                .contains("from applications a")
                .contains("a.profile_id = ?"));
    }

    @Test
    void ingestingAnEmailWithoutAnOwnerIsRefusedBeforeAnyStatementRuns() {
        JdbcTemplate jdbc = emailJdbc();

        assertThatThrownBy(() -> emailService(jdbc).ingest(null, "msg-3", "r@example.com", "me@example.com",
                "s", "b", Instant.now()))
                .isInstanceOf(NullPointerException.class);

        // An unowned email is either invisible to its owner or visible to
        // everyone, so it is refused rather than stored.
        Mockito.verifyNoInteractions(jdbc);
    }

    // ── applications ─────────────────────────────────────────────────────

    @Test
    void theApplicationTransitionLockRequiresOwnership() {
        JdbcTemplate jdbc = readOnlyJdbc();
        UUID applicationId = UUID.randomUUID();
        new ApplicationStatusService(jdbc, new ObjectMapper(), mock(AuditLogWriter.class),
                mock(NotificationService.class))
                .existsForOwner(PROFILE_ID, applicationId);

        assertThat(issuedSql(jdbc)).anySatisfy(sql ->
                assertThat(sql).contains("from applications where id=? and profile_id=?"));
    }

    @Test
    void anApplicationTransitionWithoutAnOwnerIsRefusedBeforeAnyStatementRuns() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ApplicationStatusService service = new ApplicationStatusService(jdbc, new ObjectMapper(),
                mock(AuditLogWriter.class), mock(NotificationService.class));

        assertThatThrownBy(() -> service.apply(null, UUID.randomUUID(),
                "APPLICATION_STARTED", "k", ACTOR, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void aTransitionOnAForeignApplicationNeverWrites() {
        // The event_key replay probe finds nothing, and the ownership-scoped
        // locking read finds no row either — both are "no rows" by default.
        JdbcTemplate jdbc = readOnlyJdbc();
        ApplicationStatusService service = new ApplicationStatusService(jdbc, new ObjectMapper(),
                mock(AuditLogWriter.class), mock(NotificationService.class));

        assertThatThrownBy(() -> service.apply(PROFILE_ID, UUID.randomUUID(),
                "APPLICATION_STARTED", "k", ACTOR, Map.of()))
                .isInstanceOf(java.util.NoSuchElementException.class);

        assertThat(issuedSql(jdbc)).anySatisfy(sql -> assertThat(sql)
                .contains("for update")
                .contains("profile_id=?"));
        assertThat(issuedSql(jdbc)).noneSatisfy(sql -> assertThat(sql).startsWith("update applications"));
    }

    // ── automation ───────────────────────────────────────────────────────

    @Test
    void automationPlanReadsAreScopedByOwner() {
        JdbcTemplate jdbc = readOnlyJdbc();
        new AutomationPlanRepository(jdbc, new ObjectMapper()).find(PROFILE_ID, UUID.randomUUID());

        assertThat(issuedSql(jdbc)).anySatisfy(sql ->
                assertThat(sql).contains("from automation_plans where id=? and profile_id=?"));
    }

    @Test
    void automationPlanOwnershipProbeIsScopedByOwner() {
        JdbcTemplate jdbc = readOnlyJdbc();
        AutomationPlanRepository repository = new AutomationPlanRepository(jdbc, new ObjectMapper());

        assertThat(repository.owns(PROFILE_ID, UUID.randomUUID())).isFalse();

        assertThat(issuedSql(jdbc)).anySatisfy(sql ->
                assertThat(sql).contains("from automation_plans where id=? and profile_id=?"));
    }

    @Test
    void aPlanCreateWithoutAnOwnerWouldNotBeScopedSoItIsAlwaysGivenOne() {
        JdbcTemplate jdbc = readOnlyJdbc();
        new AutomationPlanRepository(jdbc, new ObjectMapper()).create(PROFILE_ID, UUID.randomUUID(),
                UUID.randomUUID(), "https://employer.example/apply", "key-1", List.of());

        // The insert carries the owner, and idempotency is resolved per owner —
        // a shared key must not return another candidate's plan id.
        assertThat(issuedSql(jdbc)).anySatisfy(sql -> assertThat(sql)
                .contains("insert into automation_plans")
                .contains("profile_id")
                .contains("on conflict (profile_id, idempotency_key) do nothing"));
        assertThat(issuedSql(jdbc)).anySatisfy(sql -> assertThat(sql)
                .contains("where idempotency_key=? and profile_id=?"));
    }

    // ── the shared job catalogue and its per-candidate match data ────────

    @Test
    void theJobSelectNeverAsksForAnyPerCandidateColumn() {
        JdbcTemplate jdbc = readOnlyJdbc();
        new JobRepository(jdbc, new ObjectMapper()).findById(UUID.randomUUID());

        String sql = issuedSql(jdbc).get(0);
        // These three were the per-candidate match decision sitting on the row every
        // authenticated user can select; V022 moved them to job_matches.
        assertThat(sql).doesNotContain("match_score")
                .doesNotContain("match_recommendation")
                .doesNotContain("match_breakdown")
                // ...and the select is an explicit column list rather than select *,
                // so a future per-candidate column cannot leak in by accident.
                .doesNotContain("select *");
    }

    @Test
    void matchResultsAreReadFromTheOwnerScopedTable() {
        JdbcTemplate jdbc = readOnlyJdbc();
        JobRepository repository = new JobRepository(jdbc, new ObjectMapper());

        assertThat(repository.findMatch(PROFILE_ID, UUID.randomUUID())).isEmpty();

        assertThat(issuedSql(jdbc)).anySatisfy(sql ->
                assertThat(sql).contains("from job_matches where profile_id = ? and job_id = ?"));
    }

    @Test
    void askingForAMatchWithoutAProfileIsNotAnUnscopedQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        assertThat(new JobRepository(jdbc, new ObjectMapper()).findMatch(null, UUID.randomUUID())).isEmpty();

        Mockito.verifyNoInteractions(jdbc);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Every SQL string the mock was ever asked to execute, in call order. */
    private static List<String> issuedSql(JdbcTemplate jdbc) {
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .map(Invocation::getArguments)
                .filter(arguments -> arguments.length > 0 && arguments[0] instanceof String)
                .map(arguments -> (String) arguments[0])
                .toList();
    }

    /**
     * A plain mock. No stubbing is needed or wanted: Mockito returns an empty
     * collection for collection-returning methods and zero for numeric ones, which
     * is exactly "no rows matched" — the behaviour under test. Stubbing would also
     * be awkward here, because {@code queryForList} is overloaded such that
     * {@code any()} matchers are ambiguous for the compiler.
     */
    private static JdbcTemplate readOnlyJdbc() {
        return mock(JdbcTemplate.class);
    }

    /** Alias kept for readability at the email call sites. */
    private static JdbcTemplate emailJdbc() {
        return readOnlyJdbc();
    }

    private EmailIntelligenceService emailService(JdbcTemplate jdbc) {
        return new EmailIntelligenceService(jdbc, new CredentialVault(),
                mock(NotificationService.class), mock(ApplicationStatusService.class), ownerContextWithProfile(jdbc));
    }

    private OwnerContext ownerContextWithProfile(JdbcTemplate jdbc) {
        ProfileRepository profiles = mock(ProfileRepository.class);
        when(profiles.findByUserId(USER_ID)).thenReturn(Optional.of(new ProfileRecord(
                PROFILE_ID, USER_ID, null, null, null, Map.of(), Map.of(), null, Map.of(), 1L, "READY")));
        return new OwnerContext(profiles, jdbc);
    }

    private void authenticate() {
        UserRecord user = new UserRecord(USER_ID, ACTOR, null, "Scoping", "uid-1", "FIREBASE");
        AppUserDetails principal = new AppUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
