package com.personal.jobagent.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.mailbox.RegistrationIdentityService;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final AutomationPlanRepository plans;
    private final ApplyDocumentSelector documentSelector;
    private final com.personal.jobagent.qa.ApplicationAnswerRepository answerRepository;
    private final RegistrationIdentityService registrationIdentities;
    private final AddressGuard addressGuard;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final boolean allowLocalTestHosts;

    @Autowired
    public UniversalBrowserPlanService(AutomationPlanRepository plans,
                                       ApplyDocumentSelector documentSelector,
                                       com.personal.jobagent.qa.ApplicationAnswerRepository answerRepository,
                                       @Autowired(required = false) RegistrationIdentityService registrationIdentities,
                                       @Autowired(required = false) AddressGuard addressGuard,
                                       JdbcTemplate db,
                                       ObjectMapper json,
                                       @Value("${app.sandbox.allow-local-test-hosts:false}") boolean allowLocalTestHosts) {
        this.plans = plans;
        this.documentSelector = documentSelector;
        this.answerRepository = answerRepository;
        this.registrationIdentities = registrationIdentities;
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
                                       ObjectMapper json) {
        this(plans, documentSelector, answerRepository, registrationIdentities, addressGuard, db, json, false);
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
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "universal:" + applicationId, true);
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
        return buildPlan(profileId, applicationId, jobId, applicationUrl, "universal-retry:" + supersededPlanId, false);
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
        UUID jobId = UUID.fromString(String.valueOf(correlation.get("jobId")));
        UUID applicationId = row.applicationId();
        String targetUrl = row.targetUrl();
        UrlValidationResult urlCheck = validateTargetUrl(targetUrl);
        if (!urlCheck.allowed() || isDedicatedAtsUrl(targetUrl)) {
            throw new IllegalStateException("UNSAFE_APPLICATION_URL:" + urlCheck.reason());
        }
        List<AutomationPlan.Step> steps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", targetUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()),
                new AutomationPlan.Step("universal-apply", "UNIVERSAL_APPLY", "AUTO",
                        Map.of("expectedUrl", targetUrl, "hasSavedCredential", hasSavedCredential(profileId, targetUrl)))
        );
        return assembleWorkerPlanPayload(planId, profileId, applicationId, jobId, targetUrl, steps);
    }

    public int countUnresolvedAnswers(UUID profileId, UUID jobId, UUID applicationId) {
        if (answerRepository == null) return 0;
        return (int) answerRepository.findByJobAndProfileScoped(profileId, jobId, applicationId).stream()
                .filter(a -> "NEEDS_USER_INPUT".equals(a.status()) || "HARD_STOP".equals(a.status()))
                .count();
    }

    private Optional<UUID> buildPlan(UUID profileId, UUID applicationId, UUID jobId, String applicationUrl,
                                     String idempotencyKey, boolean allowRequeue) {
        List<AutomationPlan.Step> steps = List.of(
                new AutomationPlan.Step("navigate-application", "NAVIGATE", "AUTO", Map.of("url", applicationUrl)),
                new AutomationPlan.Step("screenshot-landing", "SCREENSHOT", "AUTO", Map.of()),
                new AutomationPlan.Step("universal-apply", "UNIVERSAL_APPLY", "AUTO",
                        Map.of("expectedUrl", applicationUrl, "hasSavedCredential", hasSavedCredential(profileId, applicationUrl)))
        );
        UUID planId = plans.create(profileId, applicationId, jobId, applicationUrl, idempotencyKey, steps);
        AutomationPlanRepository.PlanRow current = plans.findById(planId).orElseThrow();
        if (allowRequeue && ("FAILED".equals(current.status()) || "BLOCKED_ANTI_BOT".equals(current.status()))) {
            if (!plans.requeueFailedPlan(planId)) {
                return Optional.of(planId);
            }
        } else if (!"PREPARED".equals(current.status())) {
            return Optional.of(planId);
        }

        Map<String, Object> workerPlan = assembleWorkerPlanPayload(planId, profileId, applicationId, jobId, applicationUrl, steps);
        try {
            String body = json.writeValueAsString(workerPlan);
            int updated = db.update("""
                    update automation_plans set plan = ?::jsonb
                    where id = ? and profile_id = ? and application_id = ? and status = 'PREPARED'
                    """, body, planId, profileId, applicationId);
            if (updated != 1) {
                throw new IllegalStateException("UNIVERSAL_PLAN_NOT_PREPARED");
            }
            log.info("UNIVERSAL plan {} prepared for application {} ({})", planId, applicationId, applicationUrl);
            return Optional.of(planId);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new IllegalStateException("Could not persist UNIVERSAL plan payload", e);
        }
    }

    private Map<String, Object> assembleWorkerPlanPayload(UUID planId, UUID profileId, UUID applicationId, UUID jobId,
                                                          String targetUrl, List<AutomationPlan.Step> steps) {
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
            if (coverLetterMarkdown != null) {
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
                        "byteSize", (long) bytes.length));
                coverLetter = new LinkedHashMap<>();
                coverLetter.put("versionId", version);
                coverLetter.put("sha256", sha);
                coverLetter.put("jobId", jobId.toString());
                coverLetter.put("applicationId", applicationId.toString());
                coverLetter.put("fileName", "cover-letter-" + version + ".txt");
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

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("planId", planId.toString());
        pkg.put("applicationId", applicationId.toString());
        pkg.put("jobId", jobId.toString());
        pkg.put("expectedUrl", targetUrl);
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

        Map<String, Object> workerPlan = new LinkedHashMap<>();
        workerPlan.put("planType", "UNIVERSAL");
        workerPlan.put("version", 1);
        workerPlan.put("correlation", Map.of("jobId", jobId.toString(), "applicationId", applicationId.toString()));
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
            String host = URI.create(applicationUrl.trim()).getHost();
            if (host == null || host.isBlank()) return false;
            Integer count = db.queryForObject("""
                    select count(*) from employer_credentials
                    where profile_id = ? and employer_domain = ? and status = 'ACTIVE'
                    """, Integer.class, profileId, host.toLowerCase(Locale.ROOT));
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDedicatedAtsUrl(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String marker : DEDICATED_ATS_HOST_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
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
