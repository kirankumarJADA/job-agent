package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.ats.AtsAdapters;
import com.personal.jobagent.identity.EmployerCredentialService;
import com.personal.jobagent.identity.IdentityService;
import com.personal.jobagent.mailbox.MailboxVerificationProvider;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7: the fourth functional ATS path, for Workday career sites.
 *
 * <p><b>Why this is a credential-gated LOGIN CHECK, not a form-filling
 * adapter.</b> Live, read-only inspection (2026-10-20, against
 * {@code nvidia.wd5.myworkdayjobs.com/NVIDIAExternalCareerSite} — the exact
 * tenant the discovery-side {@code WorkdayAdapter} already verifies live
 * postings against) established, directly, without guessing:
 *
 * <ul>
 *   <li>the job-detail page is a server-rendered SEO shell with no
 *       {@code <form>}/{@code <input>} at all — the real application flow is
 *       reached only via an {@code .../apply} URL and is rendered entirely
 *       client-side (confirmed by a real browser render, not a static
 *       fetch — unlike Greenhouse and Lever, a plain HTTP GET cannot inspect
 *       it);</li>
 *   <li>that {@code /apply} URL unconditionally presents a Workday-hosted
 *       sign-in page ("Sign In" / "Create Account") BEFORE any application
 *       question is ever shown — Workday requires an authenticated session
 *       for every tenant, there is no unauthenticated public form the way
 *       Lever's and Greenhouse's are;</li>
 *   <li>the sign-in form itself uses STABLE, Workday-platform-wide
 *       {@code data-automation-id} attributes ({@code email}, {@code
 *       password}, {@code signInSubmitButton}) — reliable across tenants,
 *       unlike Zoho Recruit's per-tenant-generated field names — but also
 *       carries an explicit anti-bot honeypot field
 *       ({@code data-automation-id="beecatcher"}, {@code name="website"})
 *       that must never be filled;</li>
 *   <li>the actual multi-step APPLICATION form that follows a successful
 *       sign-in was never observed: reaching it would require signing in
 *       with a real candidate's real credentials against a real employer's
 *       live tenant, which this investigation deliberately did not do, and
 *       its field structure is known to vary by tenant (requisition
 *       templates, custom questions) in ways Robin has no lawful way to
 *       discover without that same authenticated access.</li>
 * </ul>
 *
 * <p>Given that boundary, this service implements exactly the stage that IS
 * genuinely supported end to end: verifying the candidate's SAVED Workday
 * credential ({@link EmployerCredentialService}, finally given its first
 * real caller) against this specific employer's real sign-in page, through
 * the browser worker, and reporting one of a small set of honest outcomes —
 * credential missing, login succeeded, login failed, or MFA/CAPTCHA/an
 * unrecognised form requiring the human. It NEVER attempts the application
 * form itself, and so never needs to guess a single question, selector, or
 * success signal for it. This is a deliberately smaller, honestly-bounded
 * capability — "Robin confirms your saved Workday password still works
 * (and whether this employer requires MFA) before you go apply yourself" —
 * not a submission or form-filling adapter.
 *
 * <p>Reuses the existing {@code ACCOUNT_RESOLVE_CREDENTIAL} step type exactly
 * as designed (Phase 8.5): the worker asks the backend for this candidate's
 * saved portal password via the existing worker-authenticated resolve
 * endpoint, never baking a secret into the plan's own stored JSON.
 */
@Service
public class WorkdayExecutionPlanService {
    private static final Logger log = LoggerFactory.getLogger(WorkdayExecutionPlanService.class);

    private final AutomationPlanRepository plans;
    private final AtsAdapters.WorkdayAdapter workdayAdapter;
    private final EmployerCredentialService credentials;
    private final RegistrationIdentityService registrationIdentities;
    private final IdentityService identityService;
    private final MailboxVerificationProvider mailbox;
    private final ApplyDocumentSelector documentSelector;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private final int mailboxWindowMinutes;

    public WorkdayExecutionPlanService(AutomationPlanRepository plans, AtsAdapters.WorkdayAdapter workdayAdapter,
                                       EmployerCredentialService credentials, RegistrationIdentityService registrationIdentities,
                                       IdentityService identityService, MailboxVerificationProvider mailbox,
                                       ApplyDocumentSelector documentSelector,
                                       JdbcTemplate db, ObjectMapper json,
                                       @Value("${app.mailbox-verification.window-minutes:30}") int mailboxWindowMinutes) {
        this.plans = plans;
        this.workdayAdapter = workdayAdapter;
        this.credentials = credentials;
        this.registrationIdentities = registrationIdentities;
        this.identityService = identityService;
        this.mailbox = mailbox;
        this.documentSelector = documentSelector;
        this.db = db;
        this.json = json;
        this.mailboxWindowMinutes = Math.max(1, Math.min(mailboxWindowMinutes, 24 * 60));
    }

    public boolean handles(String applicationUrl) {
        return workdayAdapter.matchesUrl(applicationUrl);
    }

    @Transactional
    public Optional<UUID> createExecutionPlan(UUID profileId, UUID applicationId, UUID jobId) {
        if (profileId == null || applicationId == null || jobId == null) {
            throw new IllegalArgumentException("profile, application, and job correlation are required");
        }
        String applicationUrl = applicationUrlOf(profileId, applicationId, jobId);
        if (applicationUrl == null) return Optional.empty();
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "workday:" + applicationId, true);
    }

    /**
     * Phase 19: builds an independent retry plan after an unconfirmed submission.
     */
    @Transactional
    public Optional<UUID> createRetryExecutionPlan(UUID profileId, UUID applicationId, UUID jobId, UUID supersededPlanId) {
        if (profileId == null || applicationId == null || jobId == null || supersededPlanId == null) {
            throw new IllegalArgumentException("profile, application, job and superseded-plan correlation are required");
        }
        String applicationUrl = applicationUrlOf(profileId, applicationId, jobId);
        if (applicationUrl == null) return Optional.empty();
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "workday-retry:" + supersededPlanId, false);
    }

    private String applicationUrlOf(UUID profileId, UUID applicationId, UUID jobId) {
        Map<String, Object> application = db.queryForMap("""
                select a.id, a.profile_id, a.job_id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String applicationUrl = (String) application.get("application_url");
        return handles(applicationUrl) ? applicationUrl : null;
    }

    private Optional<UUID> buildPlan(UUID profileId, UUID applicationId, UUID jobId, String applicationUrl,
                                     String idempotencyKey, boolean allowRequeue) {
        String employerDomain = domainOf(applicationUrl);
        String applyUrl = applyUrlOf(applicationUrl);

        List<AutomationPlan.Step> baseSteps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", applyUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()));
        UUID planId = plans.create(profileId, applicationId, jobId, applyUrl, idempotencyKey, baseSteps);

        AutomationPlanRepository.PlanRow current = plans.findById(planId).orElseThrow();
        if (allowRequeue && ("FAILED".equals(current.status()) || "BLOCKED_ANTI_BOT".equals(current.status()))) {
            if (!plans.requeueFailedPlan(planId)) return Optional.of(planId);
        } else if (!"PREPARED".equals(current.status())) {
            return Optional.of(planId);
        }

        boolean credentialSaved = employerDomain != null && credentials.exists(profileId, employerDomain);
        List<AutomationPlan.Step> steps = new ArrayList<>(baseSteps);
        if (credentialSaved) {
            steps.add(new AutomationPlan.Step("account-resolve-credential", "ACCOUNT_RESOLVE_CREDENTIAL", "AUTO",
                    Map.of("employerDomain", employerDomain)));
        }

        Map<String, Object> candidateRow = db.queryForMap("""
                select u.display_name, u.email::text as email, p.phone::text as phone,
                       coalesce(p.links::text, '{}') as links,
                       p.location::text as location
                from applications a join profiles p on p.id = a.profile_id
                join users u on u.id = p.user_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);

        String displayName = (String) candidateRow.get("display_name");
        String email = registrationIdentities != null
                ? registrationIdentities.resolveApplicationContactEmail(profileId, applyUrl, (String) candidateRow.get("email"))
                : (String) candidateRow.get("email");
        String phone = (String) candidateRow.get("phone");
        String location = (String) candidateRow.get("location");

        Map<String, Object> identity = new LinkedHashMap<>();
        if (displayName != null && !displayName.isBlank()) {
            identity.put("name", displayName.trim());
            String[] parts = displayName.trim().split("\\s+", 2);
            identity.put("firstName", parts[0]);
            if (parts.length > 1) {
                identity.put("lastName", parts[1]);
            }
        }
        if (email != null && !email.isBlank()) {
            identity.put("email", email.trim());
        }
        if (phone != null && !phone.isBlank()) {
            identity.put("phone", phone.trim());
        }
        if (location != null && !location.isBlank()) {
            identity.put("addressLine1", location.trim());
        }
        String linkedIn = linkedInOf(candidateRow.get("links"));
        if (linkedIn != null) {
            identity.put("linkedIn", linkedIn);
        }

        steps.add(new AutomationPlan.Step("workday-apply", "WORKDAY_APPLY", "AUTO", Map.of("identity", identity)));

        ApplyDocumentSelector.DocumentSelection selection = selectDocuments(planId, profileId, applicationId, jobId);
        Map<String, Object> cvArtifact = selectCvArtifact(selection, planId, applicationId, jobId);
        Map<String, Object> coverLetterArtifact = selectCoverLetterArtifact(selection, planId, applicationId, jobId);

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("planId", planId.toString());
        pkg.put("applicationId", applicationId.toString());
        pkg.put("jobId", jobId.toString());
        pkg.put("expectedUrl", applyUrl);
        if (cvArtifact != null) pkg.put("cv", cvArtifact);
        if (coverLetterArtifact != null) pkg.put("coverLetter", coverLetterArtifact);
        List<Map<String, Object>> artifactsList = new ArrayList<>();
        if (cvArtifact != null) artifactsList.add(cvArtifact);
        if (coverLetterArtifact != null) artifactsList.add(coverLetterArtifact);
        pkg.put("artifacts", artifactsList);

        persistWorkerPlan(planId, profileId, applicationId, jobId, applyUrl, steps, employerDomain, credentialSaved,
                false, null, pkg);
        log.info("WORKDAY application plan {} prepared for application {} (credentialSaved={}, identity resolved: {})",
                planId, applicationId, credentialSaved, identity.keySet());
        return Optional.of(planId);
    }

    private ApplyDocumentSelector.DocumentSelection selectDocuments(UUID planId, UUID profileId, UUID applicationId, UUID jobId) {
        try {
            return documentSelector.select(profileId, jobId, applicationId);
        } catch (Exception e) {
            log.warn("Could not select documents for Workday plan {}: {}", planId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> selectCvArtifact(ApplyDocumentSelector.DocumentSelection selection,
                                                  UUID planId, UUID applicationId, UUID jobId) {
        if (selection == null || selection.blocked() || selection.cv() == null) return null;
        var cv = selection.cv();
        String version = cv.versionId().toString();
        if (cv.pdfSha256() == null || cv.byteSize() <= 0) return null;
        String url = "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version;
        Map<String, Object> cvMap = new LinkedHashMap<>();
        cvMap.put("versionId", version);
        cvMap.put("sha256", cv.pdfSha256());
        cvMap.put("jobId", jobId.toString());
        cvMap.put("applicationId", applicationId.toString());
        cvMap.put("fileName", "cv-" + version + ".pdf");
        cvMap.put("url", url);
        cvMap.put("byteSize", cv.byteSize());
        return cvMap;
    }

    private Map<String, Object> selectCoverLetterArtifact(ApplyDocumentSelector.DocumentSelection selection,
                                                           UUID planId, UUID applicationId, UUID jobId) {
        if (selection == null || selection.blocked() || selection.coverLetter() == null) return null;
        var letter = selection.coverLetter();
        if (letter.bodySha256() == null) return null;
        String version = letter.versionId().toString();
        String url = "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter?versionId=" + version;
        Map<String, Object> letterMap = new LinkedHashMap<>();
        letterMap.put("versionId", version);
        letterMap.put("sha256", letter.bodySha256());
        letterMap.put("jobId", jobId.toString());
        letterMap.put("applicationId", applicationId.toString());
        letterMap.put("fileName", "cover-letter-" + version + ".txt");
        letterMap.put("url", url);
        return letterMap;
    }

    @SuppressWarnings("unchecked")
    private String linkedInOf(Object linksJson) {
        Map<String, Object> links;
        try {
            links = json.readValue(String.valueOf(linksJson), Map.class);
        } catch (Exception ignored) {
            return null;
        }
        if (links == null) return null;
        for (var entry : links.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(java.util.Locale.ROOT);
            if (key.contains("linkedin")) {
                Object val = entry.getValue();
                if (val != null && !String.valueOf(val).isBlank()) {
                    return String.valueOf(val).trim();
                }
            }
        }
        return null;
    }

    /** The outcome of {@link #authorizeAccountCreation}. {@code reusedExisting} is true on an idempotent retry. */
    public record AccountCreationResult(UUID planId, UUID sessionId, boolean reusedExisting) {}

    /**
     * An explicit, candidate-authorized request to create a NEW Workday
     * employer account was refused before any plan was built or any
     * credential was touched. {@link #reason()} is one of
     * {@code TERMS_NOT_ACCEPTED} (the request did not carry explicit
     * agreement to the employer's own account-creation terms),
     * {@code NOT_A_WORKDAY_APPLICATION}, {@code CREDENTIAL_ALREADY_EXISTS}
     * (a saved credential already exists for this employer domain — sign in
     * instead), or {@code IDENTITY_ALREADY_REGISTERED} (an employer account
     * for this domain is already registered with an address Robin has no
     * saved credential for — the candidate must resolve this themselves,
     * never by creating a second account).
     */
    public static class AccountCreationRefusedException extends RuntimeException {
        private final String reason;

        public AccountCreationRefusedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * Phase 11: creates and prepares a plan that creates a NEW Workday
     * employer account, strictly after the candidate has explicitly
     * authorized it via {@code acceptWorkdayTerms} — this method never
     * infers or defaults that authorization. Mirrors
     * {@link #createExecutionPlan}'s idempotency shape (a stable key keeps a
     * retry from building a second plan or generating a second password) but
     * additionally: refuses when a credential already exists for this
     * employer domain (use the existing sign-in path instead — never a
     * second account for the same employer), reuses or starts the
     * candidate's own {@code account_sessions} row via the existing
     * {@link IdentityService#startSession}, and resolves the registration
     * identity via the existing {@link RegistrationIdentityService} — the
     * same service the worker-only mailbox-verification boundary already
     * uses, called here in-process from this candidate-owned,
     * ownership-checked entry point, not through that worker-only HTTP
     * boundary.
     *
     * <p>The password filled into Workday's real create-account form is
     * generated here, server-side, with {@link SecureRandom} — never a
     * password reused across sites, and never transmitted by the caller —
     * and is durably saved via {@link EmployerCredentialService#save} before
     * the plan is even built, so a crash between saving the credential and
     * preparing the plan still leaves a usable, re-preparable credential
     * rather than an orphaned one.
     */
    @Transactional
    public AccountCreationResult authorizeAccountCreation(UUID profileId, UUID applicationId, UUID jobId,
                                                           boolean acceptWorkdayTerms) {
        if (profileId == null || applicationId == null || jobId == null) {
            throw new IllegalArgumentException("profile, application, and job correlation are required");
        }
        String idempotencyKey = "workday-create-account:" + applicationId;
        List<UUID> existing = db.queryForList(
                "select id from automation_plans where profile_id = ? and idempotency_key = ?",
                UUID.class, profileId, idempotencyKey);
        if (!existing.isEmpty()) {
            UUID existingSessionId = db.queryForObject(
                    "select account_session_id from applications where id = ? and profile_id = ?",
                    UUID.class, applicationId, profileId);
            return new AccountCreationResult(existing.get(0), existingSessionId, true);
        }
        if (!acceptWorkdayTerms) {
            throw new AccountCreationRefusedException("TERMS_NOT_ACCEPTED",
                    "Creating a Workday account requires explicit acceptance of the employer's own "
                            + "account-creation terms");
        }

        Map<String, Object> application = db.queryForMap("""
                select a.id, a.profile_id, a.job_id, a.account_session_id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String applicationUrl = (String) application.get("application_url");
        if (!handles(applicationUrl)) {
            throw new AccountCreationRefusedException("NOT_A_WORKDAY_APPLICATION",
                    "This application's job posting is not on a Workday career site");
        }
        String employerDomain = domainOf(applicationUrl);
        if (employerDomain == null) {
            throw new AccountCreationRefusedException("NOT_A_WORKDAY_APPLICATION",
                    "No employer domain could be determined for this application");
        }
        if (credentials.exists(profileId, employerDomain)) {
            throw new AccountCreationRefusedException("CREDENTIAL_ALREADY_EXISTS",
                    "A Workday credential is already saved for this employer domain; sign in instead of "
                            + "creating a new account");
        }

        UUID existingSessionId = (UUID) application.get("account_session_id");
        UUID sessionId = existingSessionId != null && !isClosedSession(profileId, existingSessionId)
                ? existingSessionId
                : identityService.startSession(applicationId, employerDomain, profileId)
                        .orElseThrow(() -> new IllegalStateException("Could not start an account session"));

        RegistrationIdentityService.RegistrationIdentity identity =
                registrationIdentities.resolve(profileId, applicationId, sessionId, employerDomain);
        if (identity.existing()) {
            throw new AccountCreationRefusedException("IDENTITY_ALREADY_REGISTERED",
                    "An employer account for this domain is already registered with " + identity.email()
                            + "; sign in instead of creating a new account");
        }

        String password = generatePassword();
        credentials.save(profileId, employerDomain, identity.email(), password);

        String applyUrl = applyUrlOf(applicationUrl);
        List<AutomationPlan.Step> steps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", applyUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()),
                new AutomationPlan.Step("account-resolve-identity", "ACCOUNT_RESOLVE_IDENTITY", "AUTO",
                        Map.of("employerDomain", employerDomain)),
                new AutomationPlan.Step("account-resolve-credential", "ACCOUNT_RESOLVE_CREDENTIAL", "AUTO",
                        Map.of("employerDomain", employerDomain)),
                new AutomationPlan.Step("workday-create-account", "WORKDAY_CREATE_ACCOUNT", "AUTO",
                        Map.of("termsAuthorized", true)));
        UUID planId = plans.create(profileId, applicationId, jobId, applyUrl, idempotencyKey, steps);
        persistWorkerPlan(planId, profileId, applicationId, jobId, applyUrl, steps, employerDomain, false,
                true, sessionId);
        log.info("WORKDAY account-creation plan {} prepared for application {} (sessionId={})",
                planId, applicationId, sessionId);
        return new AccountCreationResult(planId, sessionId, false);
    }

    /** The candidate-visible status of an account-creation + verification attempt. */
    public record VerificationStatus(String status, String provider, Instant receivedAt, String detail) {}

    /**
     * Phase 11A: a read-only status check for the candidate. Never mutates
     * anything and never consumes a verification message — consuming one
     * (marking it resolved so it cannot answer twice) remains exclusively
     * the worker-only {@code MailboxVerificationController#resolve}'s job,
     * so this can never race or double-consume against it. Never returns an
     * OTP or a link; only a coarse status, which provider matched (if any),
     * and a timestamp.
     *
     * <p>Composes two already-real, already-tested facts rather than
     * inventing a third tracking mechanism: (1) the account-creation plan's
     * own, already-persisted {@code WORKDAY_OUTCOME} result (written by
     * {@link #authorizeAccountCreation}'s plan once the worker reports it),
     * and (2) — only once that result is {@code ACCOUNT_CREATED} — a fresh,
     * non-consuming {@link MailboxVerificationProvider#findVerification}
     * probe against the exact mailbox the account was registered with, using
     * the same age window {@code MailboxVerificationController#resolve}
     * itself enforces.
     *
     * <p>Deliberately does not extract, fill, or follow anything: the real
     * Workday post-signup verification mechanism (an OTP field? a link?
     * neither?) was never observed in this investigation, so this reports
     * only "a message was found" or "not yet" — never attempts to act on it.
     * Confirming the account actually works remains the job of a later,
     * separate {@code WORKDAY_LOGIN} plan run, whose own observable sign-in
     * result is the one thing this phase treats as proof of a verified,
     * working account.
     */
    public VerificationStatus checkAccountVerificationStatus(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) {
            throw new IllegalArgumentException("profile and application are required");
        }
        List<Map<String, Object>> applications = db.queryForList(
                "select account_session_id from applications where id = ? and profile_id = ?",
                applicationId, profileId);
        if (applications.isEmpty()) throw new NoSuchElementException("No such application");

        List<UUID> planIds = db.queryForList(
                "select id from automation_plans where profile_id = ? and idempotency_key = ?",
                UUID.class, profileId, "workday-create-account:" + applicationId);
        if (planIds.isEmpty()) {
            return new VerificationStatus("ACCOUNT_CREATION_REQUIRED", null, null,
                    "No Workday account-creation attempt has been authorized for this application yet.");
        }
        AutomationPlanRepository.PlanRow plan = plans.find(profileId, planIds.get(0)).orElseThrow();
        Map<String, Object> outcome = workdayOutcomeOf(plan.result());
        if (outcome == null) {
            return new VerificationStatus("ACCOUNT_CREATION_IN_PROGRESS", null, null,
                    "Account creation was authorized but the worker has not reported a result yet.");
        }
        String loginStatus = String.valueOf(outcome.get("loginStatus"));
        if (!"ACCOUNT_CREATED".equals(loginStatus)) {
            return new VerificationStatus(loginStatus, null, null,
                    outcome.get("reason") == null ? null : String.valueOf(outcome.get("reason")));
        }

        UUID sessionId = (UUID) applications.get(0).get("account_session_id");
        if (sessionId == null) {
            return new VerificationStatus("HUMAN_ASSISTANCE_REQUIRED", null, null,
                    "The account-creation session could not be found for this application.");
        }
        List<Map<String, Object>> sessions = db.queryForList("""
                select s.state, s.hard_stop_reason, s.created_at, i.applicant_email::text as registered_email
                from account_sessions s
                left join applicant_identities i on i.id = s.applicant_identity_id and i.profile_id = s.owner_profile_id
                where s.id = ? and s.owner_profile_id = ?
                """, sessionId, profileId);
        if (sessions.isEmpty()) {
            return new VerificationStatus("HUMAN_ASSISTANCE_REQUIRED", null, null,
                    "The account-creation session could not be found for this application.");
        }
        Map<String, Object> session = sessions.get(0);
        String state = String.valueOf(session.get("state"));
        if ("HARD_STOP".equals(state)) {
            return new VerificationStatus("HUMAN_ASSISTANCE_REQUIRED", null, null,
                    session.get("hard_stop_reason") == null ? null : String.valueOf(session.get("hard_stop_reason")));
        }
        if ("FAILED".equals(state)) {
            return new VerificationStatus("VERIFICATION_FAILED_OR_EXPIRED", null, null,
                    "The account session failed before verification completed.");
        }
        String registeredEmail = session.get("registered_email") == null
                ? null : String.valueOf(session.get("registered_email"));
        if (registeredEmail == null) {
            return new VerificationStatus("HUMAN_ASSISTANCE_REQUIRED", null, null,
                    "No registered e-mail address is bound to this account session.");
        }
        String employerDomain = domainOf(plan.targetUrl());
        Instant sessionStart = ((Timestamp) session.get("created_at")).toInstant();
        Instant after = Instant.now().minusSeconds(mailboxWindowMinutes * 60L);
        if (sessionStart.isAfter(after)) after = sessionStart;

        var hit = mailbox.findVerification(profileId, employerDomain, registeredEmail, after);
        if (hit.isEmpty()) {
            return new VerificationStatus("VERIFICATION_MESSAGE_PENDING", null, null,
                    "No verification message has been found yet in the registered mailbox.");
        }
        return new VerificationStatus("VERIFICATION_MESSAGE_MATCHED", hit.get().providerId(),
                hit.get().receivedAt(), null);
    }

    private static Map<String, Object> workdayOutcomeOf(List<Object> result) {
        if (result == null) return null;
        for (Object entry : result) {
            if (entry instanceof Map<?, ?> map && "WORKDAY_OUTCOME".equals(map.get("type"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                return cast;
            }
        }
        return null;
    }

    private boolean isClosedSession(UUID profileId, UUID sessionId) {
        List<String> states = db.queryForList(
                "select state from account_sessions where id = ? and owner_profile_id = ?",
                String.class, sessionId, profileId);
        return states.isEmpty() || RegistrationIdentityService.CLOSED_SESSION_STATES.contains(states.get(0));
    }

    /**
     * This tenant's exact password-complexity rule was never observed live
     * (no account has actually been created) — mixing in a fixed
     * upper/lower/digit/symbol prefix keeps the generated value acceptable
     * to a typical complexity policy without weakening the random 24-byte
     * core.
     */
    private String generatePassword() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return "Rb9!" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** The host Workday scopes a candidate's saved credential to — the same key {@link EmployerCredentialService} already uses. */
    static String domainOf(String applicationUrl) {
        if (applicationUrl == null) return null;
        try {
            String host = URI.create(applicationUrl).getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Workday's own job-detail page carries no form at all (verified live —
     * see this class's own module comment); the real application entry point
     * is the same URL with {@code /apply} appended, exactly as observed on
     * the live NVIDIA posting's own "Apply" control.
     */
    static String applyUrlOf(String applicationUrl) {
        if (applicationUrl == null) return null;
        String trimmed = applicationUrl.replaceAll("/+$", "");
        return trimmed.endsWith("/apply") ? trimmed : trimmed + "/apply";
    }

    private void persistWorkerPlan(UUID planId, UUID profileId, UUID applicationId, UUID jobId, String targetUrl,
                                   List<AutomationPlan.Step> steps, String employerDomain, boolean credentialSaved,
                                   boolean accountCreationAttempted, UUID sessionId) {
        persistWorkerPlan(planId, profileId, applicationId, jobId, targetUrl, steps, employerDomain, credentialSaved,
                accountCreationAttempted, sessionId, null);
    }

    private void persistWorkerPlan(UUID planId, UUID profileId, UUID applicationId, UUID jobId, String targetUrl,
                                   List<AutomationPlan.Step> steps, String employerDomain, boolean credentialSaved,
                                   boolean accountCreationAttempted, UUID sessionId, Map<String, Object> pkg) {
        try {
            Map<String, Object> correlation = new LinkedHashMap<>();
            correlation.put("jobId", jobId.toString());
            correlation.put("applicationId", applicationId.toString());
            correlation.put("employerDomain", employerDomain == null ? "" : employerDomain);
            if (sessionId != null) correlation.put("sessionId", sessionId.toString());
            Map<String, Object> workerPlan = new LinkedHashMap<>();
            workerPlan.put("planType", "WORKDAY");
            workerPlan.put("version", 1);
            workerPlan.put("correlation", correlation);
            workerPlan.put("targetUrl", targetUrl);
            workerPlan.put("steps", steps.stream().map(step -> Map.of(
                    "id", step.id(), "type", step.type(), "policy", step.policy(), "params", step.params())).toList());
            if (pkg != null && !pkg.isEmpty()) {
                workerPlan.put("package", pkg);
            }
            workerPlan.put("credentialSaved", credentialSaved);
            workerPlan.put("accountCreationAttempted", accountCreationAttempted);
            workerPlan.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);
            String body = json.writeValueAsString(workerPlan);
            int updated = db.update("update automation_plans set plan = ?::jsonb "
                    + "where id = ? and profile_id = ? and application_id = ? and status = 'PREPARED'",
                    body, planId, profileId, applicationId);
            if (updated != 1) throw new IllegalStateException("WORKDAY_PLAN_NOT_PREPARED");
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Could not persist complete Workday plan payload", e);
        }
    }
}
