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
        CoverLetterService.GenerationResult result = coverLetterService.generateCoverLetter(profileId, body.jobId(), body.applicationId());

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
