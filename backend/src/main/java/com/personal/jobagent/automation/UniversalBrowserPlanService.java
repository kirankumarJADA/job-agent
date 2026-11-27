package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.identity.EmployerCredentialService;
import com.personal.jobagent.identity.IdentityService;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
import com.personal.jobagent.mailbox.managed.MailAddresses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds controlled {@code planType = "UNIVERSAL"} browser execution plans
 * for ordinary employer websites that have no dedicated ATS integration.
 *
 * <p>Enforces SSRF validation via {@link AddressGuard} (allowing loopback
 * test fixtures only when {@code app.sandbox.allow-local-test-hosts=true}),
 * fail-closed document selection via {@link ApplyDocumentSelector}, verified
 * candidate facts + confirmed-only answers via {@link UniversalFieldMapper},
 * and zero submit steps or stored credentials on initial preparation.
 */
@Service
public class UniversalBrowserPlanService {

    private static final Logger log = LoggerFactory.getLogger(UniversalBrowserPlanService.class);

    private static final Set<String> DEDICATED_ATS_HOST_MARKERS = Set.of(
            "greenhouse.io",
            "zohorecruit.com",
            "zohorecruit.in",
            "jobs.lever.co",
            "jobs.eu.lever.co",
            "myworkdayjobs.com",
            "jobs.ashbyhq.com"
    );

    public record UrlValidationResult(boolean allowed, String reason) {
        public static UrlValidationResult ok() { return new UrlValidationResult(true, "OK"); }
        public static UrlValidationResult rejected(String reason) { return new UrlValidationResult(false, reason); }
    }

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

    public record AccountCreationResult(UUID planId, UUID sessionId, boolean reusedExisting) {}

    private final AutomationPlanRepository plans;
    private final ApplyDocumentSelector documentSelector;
    private final com.personal.jobagent.qa.ApplicationAnswerRepository answerRepository;
    private final RegistrationIdentityService registrationIdentities;
    private final EmployerCredentialService credentials;
    private final IdentityService identityService;
    private final AddressGuard addressGuard;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean allowLocalTestHosts;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public UniversalBrowserPlanService(AutomationPlanRepository plans,
                                       ApplyDocumentSelector documentSelector,
                                       com.personal.jobagent.qa.ApplicationAnswerRepository answerRepository,
                                       @Autowired(required = false) RegistrationIdentityService registrationIdentities,
                                       @Autowired(required = false) AddressGuard addressGuard,
                                       JdbcTemplate db,
                                       ObjectMapper json,
                                       @Value("${app.sandbox.allow-local-test-hosts:false}") boolean allowLocalTestHosts,
                                       @Autowired(required = false) EmployerCredentialService credentials,
                                       @Autowired(required = false) IdentityService identityService) {
        this.plans = plans;
        this.documentSelector = documentSelector;
        this.answerRepository = answerRepository;
        this.registrationIdentities = registrationIdentities;
        this.credentials = credentials;
        this.identityService = identityService;
        this.addressGuard = addressGuard != null ? addressGuard : new AddressGuard();
        this.db = db;
        this.json = json;
        this.allowLocalTestHosts = allowLocalTestHosts;
    }

    public UniversalBrowserPlanService(AutomationPlanRepository plans,
                                       ApplyDocumentSelector documentSelector,
                                       com.personal.jobagent.qa.ApplicationAnswerRepository answerRepository,
                                       RegistrationIdentityService registrationIdentities,
                                       AddressGuard addressGuard,
                                       JdbcTemplate db,
                                       ObjectMapper json,
                                       boolean allowLocalTestHosts) {
        this(plans, documentSelector, answerRepository, registrationIdentities, addressGuard,
                db, json, allowLocalTestHosts, null, null);
    }

    public UniversalBrowserPlanService(AutomationPlanRepository plans,
                                       ApplyDocumentSelector documentSelector,
                                       com.personal.jobagent.qa.ApplicationAnswerRepository answerRepository,
                                       RegistrationIdentityService registrationIdentities,
                                       AddressGuard addressGuard,
                                       JdbcTemplate db,
                                       ObjectMapper json) {
        this(plans, documentSelector, answerRepository, registrationIdentities, addressGuard, db, json, false, null, null);
    }

    public boolean handles(String applicationUrl) {
        if (applicationUrl == null || applicationUrl.isBlank()) {
            return false;
        }
        if (isDedicatedAtsUrl(applicationUrl)) {
            return false;
        }
        return validateTargetUrl(applicationUrl).allowed();
    }

    /** Validates ordinary target URLs against SSRF, unsafe schemes, userinfo, and private networks. */
    public UrlValidationResult validateTargetUrl(String url) {
        if (url == null || url.isBlank()) {
            return UrlValidationResult.rejected("EMPTY_URL");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return UrlValidationResult.rejected("INVALID_URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return UrlValidationResult.rejected("MISSING_SCHEME");
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!"https".equals(lowerScheme) && !"http".equals(lowerScheme)) {
            return UrlValidationResult.rejected("UNSUPPORTED_SCHEME");
        }
        if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isEmpty()) {
            return UrlValidationResult.rejected("USERINFO_FORBIDDEN");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return UrlValidationResult.rejected("MISSING_HOST");
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (allowLocalTestHosts && isExplicitLoopbackHost(lowerHost)) {
            try {
                InetAddress addr = InetAddress.getByName(lowerHost);
                if (addr.isLoopbackAddress() && !addr.isLinkLocalAddress() && !addr.isSiteLocalAddress()) {
                    return UrlValidationResult.ok();
                }
            } catch (Exception e) {
                return UrlValidationResult.rejected("UNRESOLVED_LOOPBACK_HOST");
            }
        }
        try {
            addressGuard.check(uri);
            return UrlValidationResult.ok();
        } catch (AddressGuard.UnsafeAddressException e) {
            return UrlValidationResult.rejected(e.getMessage());
        }
    }

    @Transactional
    public Optional<UUID> createExecutionPlan(UUID profileId, UUID applicationId, UUID jobId) {
        if (profileId == null || applicationId == null || jobId == null) {
            throw new IllegalArgumentException("profile, application, and job correlation are required");
        }
        String applicationUrl = applicationUrlOf(profileId, applicationId, jobId);
        if (applicationUrl == null) {
            return Optional.empty();
        }
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "universal:" + applicationId, true, false);
    }

    @Transactional
    public Optional<UUID> createRetryExecutionPlan(UUID profileId, UUID applicationId, UUID jobId, UUID supersededPlanId) {
        if (profileId == null || applicationId == null || jobId == null || supersededPlanId == null) {
            throw new IllegalArgumentException("profile, application, job and superseded-plan correlation are required");
        }
        String applicationUrl = applicationUrlOf(profileId, applicationId, jobId);
        if (applicationUrl == null) {
            return Optional.empty();
        }
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "universal-retry:" + supersededPlanId, false, false);
    }

    /**
     * Explicit, candidate-authorized creation of a new Universal employer account
     * using the candidate's Robin-managed email and a freshly generated random password.
     * Refuses if terms were not explicitly accepted, a credential already exists, or the
     * managed mailbox is not ready.
     */
    @Transactional
    public AccountCreationResult authorizeAccountCreation(UUID profileId, UUID applicationId, UUID jobId,
                                                          boolean acceptTerms) {
        if (profileId == null || applicationId == null || jobId == null) {
            throw new IllegalArgumentException("profile, application, and job correlation are required");
        }
        if (!acceptTerms) {
            throw new AccountCreationRefusedException("TERMS_NOT_ACCEPTED",
                    "Creating an employer account requires explicit candidate authorization");
        }
        Map<String, Object> app = db.queryForMap("""
                select a.id, a.account_session_id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String applicationUrl = (String) app.get("application_url");
        if (!handles(applicationUrl)) {
            throw new AccountCreationRefusedException("NOT_A_UNIVERSAL_APPLICATION",
                    "This application is not handled by the Universal browser engine");
        }
        String employerDomain = employerDomainOf(applicationUrl);
        if (employerDomain == null) {
            throw new AccountCreationRefusedException("EMPLOYER_DOMAIN_INVALID",
                    "No valid employer domain could be determined for this application");
        }
        if (hasSavedCredential(profileId, applicationUrl)) {
            throw new AccountCreationRefusedException("CREDENTIAL_ALREADY_EXISTS",
                    "A saved employer credential already exists for " + employerDomain + "; sign in instead of creating a duplicate account");
        }
        if (credentials == null || identityService == null || registrationIdentities == null) {
            throw new IllegalStateException("Universal account provisioning services are unavailable");
        }

        UUID existingSessionId = (UUID) app.get("account_session_id");
        UUID sessionId = existingSessionId != null && isOpenSession(profileId, existingSessionId)
                ? existingSessionId
                : identityService.startSession(applicationId, employerDomain, profileId)
                        .orElseThrow(() -> new IllegalStateException("Could not start an account session"));

        RegistrationIdentityService.RegistrationIdentity identity =
                registrationIdentities.resolve(profileId, applicationId, sessionId, employerDomain);
        if (identity.existing()) {
            throw new AccountCreationRefusedException("IDENTITY_ALREADY_REGISTERED",
                    "An employer account for this domain is already registered with " + identity.email()
                            + "; sign in instead of creating a duplicate account");
        }

        String password = generatePassword();
        credentials.save(profileId, employerDomain, identity.email(), password);

        Optional<AutomationPlanRepository.PlanRow> existingPlan = findLatestUniversalPlan(profileId, applicationId);
        if (existingPlan.isPresent()) {
            AutomationPlanRepository.PlanRow row = existingPlan.get();
            if ("AWAITING_APPROVAL".equals(row.status()) && !row.submitApproved()
                    && !plans.hasPreSubmitAttemptCheckpoint(row.id())) {
                List<AutomationPlan.Step> steps = buildUniversalSteps(
                        profileId, applicationId, applicationUrl, employerDomain, sessionId, true);
                Map<String, Object> workerPlan = assembleWorkerPlanPayload(
                        row.id(), profileId, applicationId, jobId, applicationUrl, steps, employerDomain, sessionId, true);
                if (plans.resumeUniversalPlan(row.id(), workerPlan)) {
                    log.info("UNIVERSAL account creation authorized and paused plan {} resumed for application {} (sessionId={})",
                            row.id(), applicationId, sessionId);
                    return new AccountCreationResult(row.id(), sessionId, true);
                }
            }
        }

        UUID planId = buildPlan(profileId, applicationId, jobId, applicationUrl,
                "universal:" + applicationId, true, true).orElseThrow();
        log.info("UNIVERSAL account creation authorized on plan {} for application {} (sessionId={})",
                planId, applicationId, sessionId);
        return new AccountCreationResult(planId, sessionId, false);
    }

    /**
     * Rebuilds the verified candidate package for an existing UNIVERSAL plan before resuming
     * after candidate input, re-validating SSRF rules and active reviewed document hashes.
     */
    public Map<String, Object> rebuildForResume(UUID profileId, UUID planId) {
        AutomationPlanRepository.PlanRow row = plans.find(profileId, planId)
                .orElseThrow(() -> new IllegalArgumentException("UNIVERSAL_PLAN_NOT_FOUND"));
        Map<String, Object> stored = row.plan() == null ? Map.of() : row.plan();
        if (!"UNIVERSAL".equals(stored.get("planType"))) {
            throw new IllegalStateException("PLAN_IS_NOT_UNIVERSAL");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> correlation = stored.get("correlation") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> storedPkg = stored.get("package") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        UUID jobId = UUID.fromString(String.valueOf(correlation.get("jobId")));
        UUID applicationId = row.applicationId();
        String targetUrl = row.targetUrl();
        UrlValidationResult urlCheck = validateTargetUrl(targetUrl);
        if (!urlCheck.allowed() || isDedicatedAtsUrl(targetUrl)) {
            throw new IllegalStateException("UNSAFE_APPLICATION_URL:" + urlCheck.reason());
        }
        String employerDomain = employerDomainOf(targetUrl);
        UUID sessionId = openSessionId(profileId, applicationId).orElse(null);
        boolean accountCreationAuthorized = Boolean.TRUE.equals(storedPkg.get("accountCreationAuthorized"));
        List<AutomationPlan.Step> steps = buildUniversalSteps(
                profileId, applicationId, targetUrl, employerDomain, sessionId, accountCreationAuthorized);
        return assembleWorkerPlanPayload(planId, profileId, applicationId, jobId, targetUrl, steps,
                employerDomain, sessionId, accountCreationAuthorized);
    }

    public int countUnresolvedAnswers(UUID profileId, UUID jobId, UUID applicationId) {
        if (answerRepository == null) return 0;
        return (int) answerRepository.findByJobAndProfileScoped(profileId, jobId, applicationId).stream()
                .filter(a -> "NEEDS_USER_INPUT".equals(a.status()) || "HARD_STOP".equals(a.status()))
                .count();
    }

    private Optional<UUID> buildPlan(UUID profileId, UUID applicationId, UUID jobId, String applicationUrl,
                                     String idempotencyKey, boolean allowRequeue, boolean accountCreationAuthorized) {
        String employerDomain = employerDomainOf(applicationUrl);
        UUID sessionId = openSessionId(profileId, applicationId).orElse(null);
        List<AutomationPlan.Step> steps = buildUniversalSteps(
                profileId, applicationId, applicationUrl, employerDomain, sessionId, accountCreationAuthorized);
        UUID planId = plans.create(profileId, applicationId, jobId, applicationUrl, idempotencyKey, steps);
        AutomationPlanRepository.PlanRow current = plans.findById(planId).orElseThrow();
        if (allowRequeue && ("FAILED".equals(current.status()) || "BLOCKED_ANTI_BOT".equals(current.status()))) {
            if (!plans.requeueFailedPlan(planId)) {
                return Optional.of(planId);
            }
        } else if (!"PREPARED".equals(current.status())) {
            return Optional.of(planId);
        }

        Map<String, Object> workerPlan = assembleWorkerPlanPayload(planId, profileId, applicationId, jobId,
                applicationUrl, steps, employerDomain, sessionId, accountCreationAuthorized);
        try {
            String body = json.writeValueAsString(workerPlan);
            int updated = db.update("""
                    update automation_plans set plan = ?::jsonb
                    where id = ? and profile_id = ? and application_id = ? and status = 'PREPARED'
                    """, body, planId, profileId, applicationId);
            if (updated != 1) {
                throw new IllegalStateException("UNIVERSAL_PLAN_NOT_PREPARED");
            }
            log.info("UNIVERSAL plan {} prepared for application {} ({})", planId, applicationId,
                    com.personal.jobagent.common.SafeUrls.redactForLog(applicationUrl));
            return Optional.of(planId);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Could not persist UNIVERSAL plan payload", e);
        }
    }

    private List<AutomationPlan.Step> buildUniversalSteps(UUID profileId, UUID applicationId, String targetUrl,
                                                          String employerDomain, UUID sessionId,
                                                          boolean accountCreationAuthorized) {
        Map<String, Object> applyParams = new LinkedHashMap<>();
        applyParams.put("expectedUrl", targetUrl);
        applyParams.put("hasSavedCredential", hasSavedCredential(profileId, targetUrl));
        applyParams.put("accountCreationAuthorized", accountCreationAuthorized);
        if (employerDomain != null) applyParams.put("employerDomain", employerDomain);
        if (sessionId != null) applyParams.put("sessionId", sessionId.toString());
        return List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", targetUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()),
                new AutomationPlan.Step("universal-apply", "UNIVERSAL_APPLY", "AUTO", Map.copyOf(applyParams))
        );
    }

    private Map<String, Object> assembleWorkerPlanPayload(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                                          String targetUrl, List<AutomationPlan.Step> steps,
                                                          String employerDomain, UUID sessionId,
                                                          boolean accountCreationAuthorized) {
        ApplyDocumentSelector.DocumentSelection selection = documentSelector.select(profileId, jobId, applicationId);
        if (selection.blocked()) {
            throw new ExecutionPackageBlockedException(
                    "EXECUTION_PACKAGE_BLOCKED: the exact document versions for this application cannot be selected",
                    selection.blockers());
        }

        Map<String, Object> candidateRow = db.queryForMap("""
                select u.email::text as email, u.display_name, p.phone::text as phone,
                       p.location::text as location, coalesce(p.links::text, '{}') as links
                from applications a
                join profiles p on p.id = a.profile_id
                join users u on u.id = p.user_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);

        String rawEmail = (String) candidateRow.get("email");
        String effectiveEmail = registrationIdentities == null
                ? rawEmail
                : registrationIdentities.resolveApplicationContactEmail(profileId, targetUrl, rawEmail);

        List<UniversalFieldMapper.VerifiedAnswer> scoped = answerRepository == null ? List.of()
                : answerRepository.findByJobAndProfileScoped(profileId, jobId, applicationId).stream()
                        .map(r -> toVerifiedAnswer(r, false)).toList();
        List<UniversalFieldMapper.VerifiedAnswer> reusable = answerRepository == null ? List.of()
                : answerRepository.findReusableConfirmedAnswers(profileId).stream()
                        .map(r -> toVerifiedAnswer(r, true)).toList();

        List<Map<String, Object>> artifacts = new ArrayList<>();
        Map<String, Object> cv = null;
        if (selection.cv() != null) {
            var selected = selection.cv();
            String version = selected.versionId().toString();
            String sha = selected.pdfSha256();
            if (sha == null || selected.byteSize() <= 0) {
                throw new IllegalStateException("CV_ARTIFACT_METADATA_MISSING");
            }
            artifacts.add(Map.of(
                    "kind", "cv",
                    "url", "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version,
                    "versionId", version,
                    "sha256", sha,
                    "fileName", "cv-" + version + ".pdf",
                    "byteSize", selected.byteSize()));
            cv = new LinkedHashMap<>();
            cv.put("versionId", version);
            cv.put("sha256", sha);
            cv.put("jobId", jobId.toString());
            cv.put("applicationId", applicationId.toString());
            cv.put("fileName", "cv-" + version + ".pdf");
            cv.put("url", "/api/v1/automation/plans/" + planId + "/artifacts/cv?versionId=" + version);
            cv.put("byteSize", selected.byteSize());
        }

        Map<String, Object> coverLetter = null;
        String coverLetterMarkdown = null;
        if (selection.coverLetter() != null) {
            var selectedCl = selection.coverLetter();
            coverLetterMarkdown = db.queryForObject(
                    "select body_markdown from cover_letters where id = ? and profile_id = ? and job_id = ? and application_id = ?",
                    String.class, selectedCl.versionId(), profileId, jobId, applicationId);
            // Stage 3F (Workstream F): prefer the letter's own immutable, candidate-approved PDF
            // (rendered by CoverLetterService, checksum recorded at render time) so PDF-only
            // upload fields can accept it. Without a stored, intact PDF the plain-text export
            // below is kept, and the worker refuses it against a PDF-only field and asks the
            // candidate — it never uploads a mismatched format silently.
            byte[] storedPdf = selectedCl.pdfStored() && selectedCl.pdfSha256() != null
                    ? storedCoverLetterPdf(selectedCl.versionId(), profileId, jobId, applicationId, selectedCl.pdfSha256())
                    : null;
            if (storedPdf != null) {
                String version = selectedCl.versionId().toString();
                String url = "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter-pdf?versionId=" + version;
                artifacts.add(Map.of(
                        "kind", "coverLetter",
                        "url", url,
                        "versionId", version,
                        "sha256", selectedCl.pdfSha256(),
                        "fileName", "cover-letter-" + version + ".pdf",
                        "contentType", "application/pdf",
                        "byteSize", (long) storedPdf.length));
                coverLetter = new LinkedHashMap<>();
                coverLetter.put("versionId", version);
                coverLetter.put("sha256", selectedCl.pdfSha256());
                coverLetter.put("jobId", jobId.toString());
                coverLetter.put("applicationId", applicationId.toString());
                coverLetter.put("fileName", "cover-letter-" + version + ".pdf");
                coverLetter.put("contentType", "application/pdf");
                coverLetter.put("url", url);
                coverLetter.put("byteSize", (long) storedPdf.length);
                coverLetter.put("bodySha256", selectedCl.bodySha256());
            } else if (coverLetterMarkdown != null) {
                byte[] bytes = coverLetterMarkdown.getBytes(StandardCharsets.UTF_8);
                String sha = sha256Hex(bytes);
                String version = selectedCl.versionId().toString();
                String url = "/api/v1/automation/plans/" + planId + "/artifacts/cover-letter?versionId=" + version;
                artifacts.add(Map.of(
                        "kind", "coverLetter",
                        "url", url,
                        "versionId", version,
                        "sha256", sha,
                        "fileName", "cover-letter-" + version + ".txt",
                        "contentType", "text/plain; charset=utf-8",
                        "byteSize", (long) bytes.length));
                coverLetter = new LinkedHashMap<>();
                coverLetter.put("versionId", version);
                coverLetter.put("sha256", sha);
                coverLetter.put("jobId", jobId.toString());
                coverLetter.put("applicationId", applicationId.toString());
                coverLetter.put("fileName", "cover-letter-" + version + ".txt");
                coverLetter.put("contentType", "text/plain; charset=utf-8");
                coverLetter.put("url", url);
                coverLetter.put("byteSize", (long) bytes.length);
            }
        }

        var snapshot = new UniversalFieldMapper.CandidateFactSnapshot(
                effectiveEmail,
                (String) candidateRow.get("display_name"),
                (String) candidateRow.get("phone"),
                (String) candidateRow.get("location"),
                readJson(candidateRow.get("links")),
                cv != null,
                selection.cv() == null ? null : selection.cv().versionId(),
                selection.cv() == null ? null : selection.cv().pdfSha256(),
                cv == null ? null : String.valueOf(cv.get("fileName")),
                coverLetter != null,
                selection.coverLetter() == null ? null : selection.coverLetter().versionId(),
                coverLetterMarkdown,
                scoped,
                reusable
        );

        boolean hasCredential = hasSavedCredential(profileId, targetUrl);
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("planId", planId.toString());
        pkg.put("applicationId", applicationId.toString());
        pkg.put("jobId", jobId.toString());
        pkg.put("expectedUrl", targetUrl);
        if (employerDomain != null) pkg.put("employerDomain", employerDomain);
        if (sessionId != null) pkg.put("sessionId", sessionId.toString());
        pkg.put("hasSavedCredential", hasCredential);
        pkg.put("accountCreationAuthorized", accountCreationAuthorized);
        pkg.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);
        pkg.put("allowLocalTestHosts", allowLocalTestHosts);
        pkg.put("profileFacts", UniversalFieldMapper.buildProfileFactsPayload(snapshot));
        pkg.put("confirmedAnswers", UniversalFieldMapper.buildConfirmedAnswersPayload(snapshot));
        pkg.put("cv", cv);
        pkg.put("coverLetter", coverLetter);
        pkg.put("artifacts", artifacts);
        pkg.put("documentSelection", Map.of(
                "cv", cv == null ? Map.of() : cv,
                "coverLetterRequirement", selection.coverLetterRequirement().name(),
                "warnings", selection.warnings()));

        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("jobId", jobId.toString());
        correlation.put("applicationId", applicationId.toString());
        if (employerDomain != null) correlation.put("employerDomain", employerDomain);
        if (sessionId != null) correlation.put("sessionId", sessionId.toString());

        Map<String, Object> workerPlan = new LinkedHashMap<>();
        workerPlan.put("planType", "UNIVERSAL");
        workerPlan.put("version", 1);
        workerPlan.put("correlation", correlation);
        workerPlan.put("targetUrl", targetUrl);
        workerPlan.put("safetyContract", AutomationPlan.SAFETY_CONTRACT);
        workerPlan.put("steps", steps.stream().map(s -> Map.of(
                "id", s.id(), "type", s.type(), "policy", s.policy(), "params", s.params())).toList());
        workerPlan.put("package", pkg);
        return workerPlan;
    }

    private UniversalFieldMapper.VerifiedAnswer toVerifiedAnswer(com.personal.jobagent.qa.ApplicationAnswerRecord row,
                                                                   boolean defaultReusable) {
        boolean reusable = defaultReusable || (row.validationNotes() != null
                && Boolean.parseBoolean(String.valueOf(row.validationNotes().getOrDefault("reusable", "false"))));
        return new UniversalFieldMapper.VerifiedAnswer(
                row.id(),
                row.applicationId(),
                row.questionText(),
                row.questionType(),
                row.answerText(),
                row.status(),
                row.humanConfirmed(),
                row.answerOrigin(),
                reusable
        );
    }

    private String applicationUrlOf(UUID profileId, UUID applicationId, UUID jobId) {
        Map<String, Object> app = db.queryForMap("""
                select a.id, j.application_url
                from applications a join jobs j on j.id = a.job_id
                where a.id = ? and a.profile_id = ? and a.job_id = ?
                """, applicationId, profileId, jobId);
        String url = (String) app.get("application_url");
        return handles(url) ? url : null;
    }

    private boolean hasSavedCredential(UUID profileId, String applicationUrl) {
        try {
            String domain = employerDomainOf(applicationUrl);
            if (domain == null) return false;
            if (credentials != null && credentials.exists(profileId, domain)) return true;
            Integer count = db.queryForObject("""
                    select count(*) from employer_credentials
                    where profile_id = ? and employer_domain = ? and status = 'ACTIVE'
                    """, Integer.class, profileId, domain);
            if (count != null && count > 0) return true;
            String rawHost = URI.create(applicationUrl.trim()).getHost();
            if (rawHost != null && !rawHost.equalsIgnoreCase(domain)) {
                Integer rawCount = db.queryForObject("""
                        select count(*) from employer_credentials
                        where profile_id = ? and employer_domain = ? and status = 'ACTIVE'
                        """, Integer.class, profileId, rawHost.toLowerCase(Locale.ROOT));
                return rawCount != null && rawCount > 0;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    static String employerDomainOf(String applicationUrl) {
        if (applicationUrl == null || applicationUrl.isBlank()) return null;
        try {
            String host = URI.create(applicationUrl.trim()).getHost();
            if (host == null || host.isBlank()) return null;
            return MailAddresses.employerDomain(host).orElse(host.toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private Optional<UUID> openSessionId(UUID profileId, UUID applicationId) {
        List<UUID> ids = db.queryForList("""
                select s.id
                from applications a
                join account_sessions s on s.id = a.account_session_id
                where a.id = ? and a.profile_id = ? and s.owner_profile_id = ?
                  and s.state not in ('HARD_STOP', 'FAILED', 'COMPLETED')
                """, UUID.class, applicationId, profileId, profileId);
        return ids.stream().findFirst();
    }

    private boolean isOpenSession(UUID profileId, UUID sessionId) {
        List<String> states = db.queryForList(
                "select state from account_sessions where id = ? and owner_profile_id = ?",
                String.class, sessionId, profileId);
        return !states.isEmpty() && !RegistrationIdentityService.CLOSED_SESSION_STATES.contains(states.get(0));
    }

    private Optional<AutomationPlanRepository.PlanRow> findLatestUniversalPlan(UUID profileId, UUID applicationId) {
        List<UUID> ids = db.queryForList("""
                select id from automation_plans
                where profile_id = ? and application_id = ?
                  and coalesce(plan->>'planType', '') = 'UNIVERSAL'
                order by created_at desc
                limit 1
                """, UUID.class, profileId, applicationId);
        if (ids.isEmpty()) return Optional.empty();
        return plans.find(profileId, ids.get(0));
    }

    private String generatePassword() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return "Rb9!" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * Stage 3F: canonical host-boundary match (never a substring of the URL text, so
     * {@code ?ref=greenhouse.io} or {@code greenhouse.io.evil.example} do not count).
     */
    static boolean isDedicatedAtsUrl(String url) {
        String host = com.personal.jobagent.common.SafeUrls.canonicalHost(url).orElse(null);
        if (host == null) return false;
        for (String marker : DEDICATED_ATS_HOST_MARKERS) {
            if (com.personal.jobagent.common.SafeUrls.hostIsOrIsSubdomainOf(host, marker)) return true;
        }
        return false;
    }

    /** The letter's stored PDF bytes, only when they still match the recorded checksum and fit the cap. */
    private byte[] storedCoverLetterPdf(UUID letterId, UUID profileId, UUID jobId, UUID applicationId, String expectedSha) {
        List<Map<String, Object>> rows = db.queryForList("""
                select f.content from cover_letters c join files f on f.id = c.pdf_file_id
                where c.id = ? and c.profile_id = ? and c.job_id = ? and c.application_id = ? and f.content is not null
                """, letterId, profileId, jobId, applicationId);
        if (rows.isEmpty()) return null;
        byte[] content = (byte[]) rows.getFirst().get("content");
        if (content == null || content.length == 0 || content.length > ExecutionPackageService.MAX_ARTIFACT_BYTES) return null;
        if (!expectedSha.equals(sha256Hex(content))) return null;
        if (content.length < 5 || content[0] != '%' || content[1] != 'P' || content[2] != 'D' || content[3] != 'F' || content[4] != '-') return null;
        return content;
    }

    private static boolean isExplicitLoopbackHost(String lowerHost) {
        return "localhost".equals(lowerHost) || "127.0.0.1".equals(lowerHost) || "[::1]".equals(lowerHost) || "::1".equals(lowerHost);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(Object value) {
        try {
            return value == null ? Map.of() : json.readValue(String.valueOf(value), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
