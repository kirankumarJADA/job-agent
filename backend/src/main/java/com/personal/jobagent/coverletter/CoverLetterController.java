package com.personal.jobagent.coverletter;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.profile.ProfileRecord;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cover-letters")
public class CoverLetterController {

    private final CoverLetterService coverLetterService;
    private final CoverLetterRepository coverLetterRepository;
    private final ProfileRepository profileRepository;
    private final AuditLogWriter auditLogWriter;

    public CoverLetterController(CoverLetterService coverLetterService,
                                 CoverLetterRepository coverLetterRepository,
                                 ProfileRepository profileRepository,
                                 AuditLogWriter auditLogWriter) {
        this.coverLetterService = coverLetterService;
        this.coverLetterRepository = coverLetterRepository;
        this.profileRepository = profileRepository;
        this.auditLogWriter = auditLogWriter;
    }

    public record GenerateRequest(UUID jobId, UUID applicationId) {}
    public record ApprovalRequest(boolean approved) {}

    /**
     * Lists the caller's own cover letters for a job.
     *
     * <p>Scoped by the caller's profile: previously this returned every
     * account's letters for the job, which leaked other users' tailored resume
     * content. An account with no profile yet has no letters, so it gets an
     * empty list rather than a server error.
     */
    @GetMapping("/job/{jobId}")
    public List<CoverLetterRecord> getCoverLettersByJob(@PathVariable UUID jobId) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return List.of();
        }
        return coverLetterRepository.findByJobIdForProfile(jobId, profileId);
    }

    /**
     * Reads one cover letter, only if the caller owns it. A letter belonging to
     * another account is indistinguishable from a missing one (404), so this
     * does not confirm that an id exists.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCoverLetter(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(request, "Cover letter not found");
        }
        return coverLetterRepository.findByIdForProfile(id, profileId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(request, "Cover letter not found"));
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody GenerateRequest body, HttpServletRequest request) {
        if (body.jobId() == null) {
            return ResponseEntity.badRequest()
                    .body(ApiError.of(400, "Bad Request", "jobId is required", request.getRequestURI(), correlationId()));
        }

        UUID profileId = currentProfileId();
        CoverLetterService.GenerationResult result;
        try {
            result = coverLetterService.generateCoverLetter(profileId, body.jobId(), body.applicationId());
        } catch (IllegalArgumentException e) {
            return notFound(request, e.getMessage());
        }

        auditLogWriter.write(new AuditEntry(
                actorEmail(),
                "COVER_LETTER_GENERATED",
                "COVER_LETTER",
                result.coverLetter().id(),
                Map.of(),
                Map.of("version", result.coverLetter().version(), "jobId", body.jobId().toString()),
                request.getRemoteAddr(),
                UuidV7.generate()
        ));

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Approves or unapproves one of the caller's own cover letters. The lookup
     * and the update are both scoped by the caller's profile, so another
     * account's letter can neither be read nor modified here.
     */
    @PutMapping("/{id}/approval")
    public ResponseEntity<?> setApproval(@PathVariable UUID id, @RequestBody ApprovalRequest body, HttpServletRequest request) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(request, "Cover letter not found");
        }

        var existing = coverLetterRepository.findByIdForProfile(id, profileId);
        if (existing.isEmpty()) {
            return notFound(request, "Cover letter not found");
        }

        if (body.approved()) {
            // Approval is refused for content that is not provably the
            // generated/corrected text, or that fails the CURRENT checks
            // (re-run now, so letters validated by older, weaker checks are
            // not approved on that basis). Withdrawing approval is always allowed.
            if (!CoverLetterService.bodyIntact(existing.get())) {
                return conflict(request, "COVER_LETTER_CHECKSUM_MISMATCH: the stored letter no longer matches its recorded digest.");
            }
            var report = coverLetterService.revalidate(profileId, existing.get());
            if (!report.passed()) {
                auditLogWriter.write(new AuditEntry(actorEmail(), "COVER_LETTER_APPROVAL_REFUSED", "COVER_LETTER", id,
                        Map.of("approved", existing.get().isApproved()),
                        Map.of("blockers", report.blockers()), request.getRemoteAddr(), UuidV7.generate()));
                return conflict(request, "This letter has " + report.blockers()
                        + " blocking validation finding(s). Submit a correction (a new version) before approving.");
            }
        }

        coverLetterRepository.setApprovedForProfile(id, body.approved(), profileId);

        auditLogWriter.write(new AuditEntry(
                actorEmail(),
                "COVER_LETTER_APPROVAL_UPDATED",
                "COVER_LETTER",
                id,
                Map.of("approved", existing.get().isApproved()),
                Map.of("approved", body.approved()),
                request.getRemoteAddr(),
                UuidV7.generate()
        ));

        return ResponseEntity.ok(coverLetterRepository.findByIdForProfile(id, profileId).orElseThrow());
    }

    public record CorrectionRequest(String bodyMarkdown) {}

    /**
     * Owner correction of a letter. Creates a NEW version (origin
     * USER_CORRECTED, parent = the corrected version), revalidated and
     * unapproved. The original version is left exactly as it was.
     */
    @PostMapping("/{id}/corrections")
    public ResponseEntity<?> correct(@PathVariable UUID id, @RequestBody CorrectionRequest body, HttpServletRequest request) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) return notFound(request, "Cover letter not found");
        CoverLetterService.GenerationResult result;
        try {
            result = coverLetterService.correct(profileId, id, body == null ? null : body.bodyMarkdown());
        } catch (java.util.NoSuchElementException e) {
            return notFound(request, "Cover letter not found");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiError.of(400, "Bad Request", e.getMessage(), request.getRequestURI(), correlationId()));
        }
        auditLogWriter.write(new AuditEntry(actorEmail(), "COVER_LETTER_CORRECTED", "COVER_LETTER",
                result.coverLetter().id(), Map.of("corrects", id.toString()),
                Map.of("version", result.coverLetter().version(), "passed_validation", result.passedValidation()),
                request.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Authenticated, owner-scoped PDF of one letter version. The bytes are
     * served only when they still match their stored digest; the digest is
     * returned in X-Content-SHA256 so the client can verify what it received.
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<?> pdf(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) return notFound(request, "Cover letter not found");
        var pdf = coverLetterService.pdf(profileId, id);
        if (pdf.isEmpty() || pdf.get().bytes() == null) return notFound(request, "Cover letter not found");
        if (!pdf.get().intact()) {
            auditLogWriter.write(new AuditEntry(actorEmail(), "COVER_LETTER_PDF_INTEGRITY_FAILED", "COVER_LETTER", id,
                    Map.of(), Map.of("recorded_sha256", String.valueOf(pdf.get().recordedSha256())),
                    request.getRemoteAddr(), UuidV7.generate()));
            return conflict(request, "The stored PDF does not match its recorded checksum and was not served.");
        }
        byte[] bytes = pdf.get().bytes();
        auditLogWriter.write(new AuditEntry(actorEmail(), "COVER_LETTER_PDF_DOWNLOADED", "COVER_LETTER", id,
                Map.of(), Map.of("byte_size", bytes.length, "content_sha256", pdf.get().actualSha256()),
                request.getRemoteAddr(), UuidV7.generate()));
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                .filename("cover-letter-" + id + ".pdf").build());
        headers.setContentLength(bytes.length);
        headers.set("X-Content-SHA256", pdf.get().actualSha256());
        headers.setCacheControl("no-store");
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private ResponseEntity<?> conflict(HttpServletRequest request, String detail) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "Conflict", detail, request.getRequestURI(), correlationId()));
    }

    private ResponseEntity<?> notFound(HttpServletRequest request, String detail) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "Not Found", detail, request.getRequestURI(), correlationId()));
    }

    private String correlationId() {
        return String.valueOf(org.slf4j.MDC.get("correlation_id"));
    }

    private String actorEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "UNKNOWN";
    }

    private UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        AppUserDetails principal = (AppUserDetails) authentication.getPrincipal();
        return principal.getUserId();
    }

    private UUID currentProfileId() {
        return profileRepository.findByUserId(currentUserId())
                .map(ProfileRecord::id)
                .orElseThrow(() -> new IllegalStateException("No profile exists for the current user"));
    }

    /**
     * Profile id for the authenticated caller, or null when the account has no
     * profile yet. Read/authorization paths use this so an account without a
     * profile is answered with a clean 404/empty result instead of a 500.
     */
    private UUID currentProfileIdOrNull() {
        return profileRepository.findByUserId(currentUserId())
                .map(ProfileRecord::id)
                .orElse(null);
    }
}
