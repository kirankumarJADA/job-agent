package com.personal.jobagent.resume;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resume-intelligence")
public class ResumeAtsController {
    private final ResumeAtsIntelligenceService service;
    private final ProfileRepository profiles;
    private final ResumeAtsRepository repository;
    private final AuditLogWriter audit;

    public ResumeAtsController(ResumeAtsIntelligenceService service, ProfileRepository profiles,
                               ResumeAtsRepository repository, AuditLogWriter audit) {
        this.service = service;
        this.profiles = profiles;
        this.repository = repository;
        this.audit = audit;
    }

    public record Request(UUID jobId, UUID applicationId) {}

    @PostMapping("/tailor")
    public ResumeAtsAnalysis tailor(@RequestBody Request request, HttpServletRequest httpRequest) {
        if (request.jobId() == null) throw new IllegalArgumentException("jobId is required");
        UUID profileId = profiles.findByUserId(userId()).orElseThrow().id();
        ResumeAtsAnalysis result = service.tailor(profileId, request.jobId(), request.applicationId());
        audit.write(new AuditEntry(actor(), "RESUME_ATS_TAILORED", "CV", result.cvVersionId(), Map.of(),
                Map.of("job_id", request.jobId().toString(), "input_hash", result.inputHash(),
                        "profile_revision", result.profileRevision(), "content_sha256", result.contentSha256()),
                httpRequest.getRemoteAddr(), UuidV7.generate()));
        return result;
    }

    @GetMapping("/cv/{cvVersionId}/artifact")
    public ResponseEntity<byte[]> artifact(@PathVariable UUID cvVersionId, HttpServletRequest request) {
        UUID profileId = profiles.findByUserId(userId()).orElseThrow().id();
        byte[] bytes = repository.artifact(profileId, cvVersionId)
                .orElseThrow(() -> new IllegalArgumentException("Tailored CV artifact not found"));
        audit.write(new AuditEntry(actor(), "TAILORED_CV_ARTIFACT_DOWNLOADED", "CV", cvVersionId,
                Map.of(), Map.of("content_type", "application/pdf", "byte_size", bytes.length),
                request.getRemoteAddr(), UuidV7.generate()));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename("tailored-cv-" + cvVersionId + ".pdf").build());
        headers.setContentLength(bytes.length);
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

    private UUID userId() {
        return ((AppUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUserId();
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "UNKNOWN" : authentication.getName();
    }
}
