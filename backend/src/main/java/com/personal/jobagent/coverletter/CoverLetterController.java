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

    @GetMapping("/job/{jobId}")
    public List<CoverLetterRecord> getCoverLettersByJob(@PathVariable UUID jobId) {
        return coverLetterRepository.findByJobId(jobId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getCoverLetter(@PathVariable UUID id, HttpServletRequest request) {
        return coverLetterRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiError.of(404, "Not Found", "Cover letter not found", request.getRequestURI(), correlationId())));
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

    @PutMapping("/{id}/approval")
    public ResponseEntity<?> setApproval(@PathVariable UUID id, @RequestBody ApprovalRequest body, HttpServletRequest request) {
        var existing = coverLetterRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiError.of(404, "Not Found", "Cover letter not found", request.getRequestURI(), correlationId()));
        }

        coverLetterRepository.setApproved(id, body.approved());

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

        return ResponseEntity.ok(coverLetterRepository.findById(id).orElseThrow());
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
}
