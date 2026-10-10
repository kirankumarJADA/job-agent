package com.personal.jobagent.resume;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.profile.ProfileRecord;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resume-intelligence")
public class ResumeAtsController {
    /** Exposed through CORS so the browser client can verify downloaded bytes. */
    public static final String CONTENT_SHA256_HEADER = "X-Content-SHA256";

    private final ResumeAtsIntelligenceService service;
    private final ProfileRepository profiles;
    private final ResumeAtsRepository repository;
    private final CvComparisonService comparison;
    private final AuditLogWriter audit;

    public ResumeAtsController(ResumeAtsIntelligenceService service, ProfileRepository profiles,
                               ResumeAtsRepository repository, CvComparisonService comparison, AuditLogWriter audit) {
        this.service = service;
        this.profiles = profiles;
        this.repository = repository;
        this.comparison = comparison;
        this.audit = audit;
    }

    public record Request(UUID jobId, UUID applicationId) {}
    public record ReviewRequest(Boolean approved) {}

    @PostMapping("/tailor")
    public ResumeAtsAnalysis tailor(@RequestBody Request request, HttpServletRequest httpRequest) {
        if (request.jobId() == null) throw new IllegalArgumentException("jobId is required");
        UUID profileId = profileId();
        ResumeAtsAnalysis result = service.tailor(profileId, request.jobId(), request.applicationId());
        audit.write(new AuditEntry(actor(), "RESUME_ATS_TAILORED", "CV", result.cvVersionId(), Map.of(),
                Map.of("job_id", request.jobId().toString(), "input_hash", result.inputHash(),
                        "profile_revision", result.profileRevision(), "content_sha256", result.contentSha256()),
                httpRequest.getRemoteAddr(), UuidV7.generate()));
        return result;
    }

    /**
     * The caller's latest tailored CV for a job (optionally for one of their
     * applications), with its review state and artifact integrity. Returns
     * {@code {"cv": null}} when none exists, so "no CV yet" is distinguishable
     * from a failed request.
     */
    @GetMapping("/job/{jobId}")
    public ResponseEntity<?> latest(@PathVariable UUID jobId, @RequestParam(required = false) UUID applicationId) {
        Optional<UUID> profileId = profileIdOrEmpty();
        Map<String, Object> body = new LinkedHashMap<>();
        if (profileId.isEmpty()) {
            body.put("cv", null);
            return ResponseEntity.ok(body);
        }
        Optional<ResumeAtsAnalysis> latest = repository.findLatest(profileId.get(), jobId, applicationId);
        body.put("cv", latest.map(cv -> describe(profileId.get(), cv)).orElse(null));
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> describe(UUID profileId, ResumeAtsAnalysis cv) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analysis", cv);
        var check = repository.checkedArtifact(profileId, cv.cvVersionId());
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("present", check.map(c -> c.bytes() != null).orElse(false));
        artifact.put("intact", check.map(ResumeAtsRepository.ArtifactCheck::intact).orElse(false));
        artifact.put("sha256", check.map(ResumeAtsRepository.ArtifactCheck::actualSha256).orElse(null));
        artifact.put("byteSize", check.map(c -> c.bytes() == null ? 0 : c.bytes().length).orElse(0));
        out.put("artifact", artifact);
        out.put("review", repository.review(profileId, cv.cvVersionId()).orElse(null));
        return out;
    }

    @GetMapping("/cv/{cvVersionId}/comparison")
    public ResponseEntity<?> comparison(@PathVariable UUID cvVersionId) {
        Optional<UUID> profileId = profileIdOrEmpty();
        if (profileId.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Tailored CV not found"));
        return comparison.compare(profileId.get(), cvVersionId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Tailored CV not found")));
    }

    /**
     * Owner review of one immutable CV version. Approval is refused while the
     * version has blocking validation findings, was generated before the
     * current checks existed, or its stored PDF fails the integrity check.
     * Withdrawing an approval is always allowed.
     */
    @PutMapping("/cv/{cvVersionId}/review")
    public ResponseEntity<?> review(@PathVariable UUID cvVersionId, @RequestBody ReviewRequest body, HttpServletRequest request) {
        if (body == null || body.approved() == null) return ResponseEntity.badRequest().body(Map.of("error", "approved is required"));
        Optional<UUID> owner = profileIdOrEmpty();
        if (owner.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Tailored CV not found"));
        UUID profileId = owner.get();
        Optional<ResumeAtsAnalysis> cv = repository.findByCvVersion(profileId, cvVersionId);
        if (cv.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Tailored CV not found"));
        var check = repository.checkedArtifact(profileId, cvVersionId);
        if (body.approved()) {
            Object validation = cv.get().atsReport() == null ? null : cv.get().atsReport().get("validation");
            if (!(validation instanceof Map<?, ?> v)) {
                return ResponseEntity.status(409).body(Map.of("error",
                        "This CV was generated before the current validation existed. Regenerate it before approving."));
            }
            if (!Boolean.TRUE.equals(v.get("passed"))) {
                return ResponseEntity.status(409).body(Map.of("error",
                        "This CV has blocking validation findings. Correct your profile and regenerate before approving."));
            }
            if (check.isEmpty() || !check.get().intact()) {
                return ResponseEntity.status(409).body(Map.of("error",
                        "The stored PDF for this CV failed its integrity check. Regenerate before approving."));
            }
        }
        String digest = check.map(ResumeAtsRepository.ArtifactCheck::actualSha256).orElse("");
        repository.saveReview(profileId, cvVersionId, body.approved(), actor(), digest == null ? "" : digest);
        audit.write(new AuditEntry(actor(), body.approved() ? "TAILORED_CV_APPROVED" : "TAILORED_CV_APPROVAL_WITHDRAWN",
                "CV", cvVersionId, Map.of(), Map.of("approved", body.approved(), "content_sha256", digest == null ? "" : digest),
                request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.ok(describe(profileId, cv.get()));
    }

    @GetMapping("/cv/{cvVersionId}/artifact")
    public ResponseEntity<?> artifact(@PathVariable UUID cvVersionId, HttpServletRequest request) {
        Optional<UUID> owner = profileIdOrEmpty();
        if (owner.isEmpty()) return ResponseEntity.status(404).body(Map.of("error", "Tailored CV artifact not found"));
        var check = repository.checkedArtifact(owner.get(), cvVersionId);
        if (check.isEmpty() || check.get().bytes() == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Tailored CV artifact not found"));
        }
        if (!check.get().intact()) {
            audit.write(new AuditEntry(actor(), "TAILORED_CV_ARTIFACT_INTEGRITY_FAILED", "CV", cvVersionId, Map.of(),
                    Map.of("recorded_sha256", String.valueOf(check.get().recordedSha256()),
                            "actual_sha256", String.valueOf(check.get().actualSha256())),
                    request.getRemoteAddr(), UuidV7.generate()));
            return ResponseEntity.status(409).body(Map.of("error",
                    "The stored PDF does not match its recorded checksum and was not served. Regenerate the CV."));
        }
        byte[] bytes = check.get().bytes();
        audit.write(new AuditEntry(actor(), "TAILORED_CV_ARTIFACT_DOWNLOADED", "CV", cvVersionId,
                Map.of(), Map.of("content_type", "application/pdf", "byte_size", bytes.length,
                        "content_sha256", check.get().actualSha256()),
                request.getRemoteAddr(), UuidV7.generate()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename("tailored-cv-" + cvVersionId + ".pdf").build());
        headers.setContentLength(bytes.length);
        headers.set(CONTENT_SHA256_HEADER, check.get().actualSha256());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    // Consistent with ApplicationController: invalid input (including
    // cross-job application/CV mismatches) is a client error with an explicit
    // message, not an opaque 500.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }

    private UUID profileId() {
        return profileIdOrEmpty().orElseThrow(() -> new IllegalStateException("No profile exists for the current user"));
    }

    private Optional<UUID> profileIdOrEmpty() {
        return profiles.findByUserId(userId()).map(ProfileRecord::id);
    }

    private UUID userId() {
        return ((AppUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUserId();
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "UNKNOWN" : authentication.getName();
    }
}
