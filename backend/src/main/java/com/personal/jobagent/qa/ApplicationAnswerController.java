package com.personal.jobagent.qa;

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
@RequestMapping("/api/v1/application-answers")
public class ApplicationAnswerController {

    private final ApplicationAnswerService answerService;
    private final ApplicationAnswerRepository answerRepository;
    private final ProfileRepository profileRepository;
    private final AuditLogWriter auditLogWriter;

    public ApplicationAnswerController(ApplicationAnswerService answerService,
                                     ApplicationAnswerRepository answerRepository,
                                     ProfileRepository profileRepository,
                                     AuditLogWriter auditLogWriter) {
        this.answerService = answerService;
        this.answerRepository = answerRepository;
        this.profileRepository = profileRepository;
        this.auditLogWriter = auditLogWriter;
    }

    public record DraftAnswerRequest(UUID jobId, UUID applicationId, String questionText) {}
    public record UpdateAnswerRequest(String status, String answerText) {}

    /**
     * Lists the caller's own drafted answers for a job.
     *
     * <p>Scoped by the caller's profile: previously every account's answers for
     * the job were returned together. An account with no profile has no answers,
     * so it gets an empty list rather than a server error.
     */
    @GetMapping("/job/{jobId}")
    public List<ApplicationAnswerRecord> listByJob(@PathVariable UUID jobId) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return List.of();
        }
        return answerRepository.findByJobIdForProfile(jobId, profileId);
    }

    /**
     * Reads one answer, only if the caller owns it. Another account's answer is
     * indistinguishable from a missing one (404).
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getAnswer(@PathVariable UUID id, HttpServletRequest request) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(request, "Answer not found");
        }
        return answerRepository.findByIdForProfile(id, profileId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> notFound(request, "Answer not found"));
    }

    @PostMapping("/draft")
    public ResponseEntity<?> draftAnswer(@RequestBody DraftAnswerRequest body, HttpServletRequest request) {
        if (body.jobId() == null || body.questionText() == null || body.questionText().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ApiError.of(400, "Bad Request", "jobId and questionText are required", request.getRequestURI(), correlationId()));
        }

        UUID profileId = currentProfileId();
        ApplicationAnswerService.AnswerResult result = answerService.draftAnswer(profileId, body.jobId(), body.applicationId(), body.questionText());

        auditLogWriter.write(new AuditEntry(
                actorEmail(),
                "APPLICATION_ANSWER_DRAFTED",
                "APPLICATION_ANSWER",
                result.record().id(),
                Map.of(),
                Map.of("status", result.outcome(), "jobId", body.jobId().toString()),
                request.getRemoteAddr(),
                UuidV7.generate()
        ));

        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * Updates one of the caller's own answers. Lookup and write are both scoped
     * by the caller's profile, so another account's answer cannot be read or
     * modified here.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateAnswer(@PathVariable UUID id, @RequestBody UpdateAnswerRequest body, HttpServletRequest request) {
        UUID profileId = currentProfileIdOrNull();
        if (profileId == null) {
            return notFound(request, "Answer not found");
        }

        var existing = answerRepository.findByIdForProfile(id, profileId);
        if (existing.isEmpty()) {
            return notFound(request, "Answer not found");
        }

        answerRepository.updateStatusForProfile(
                id, body.status() != null ? body.status() : existing.get().status(), body.answerText(), profileId);

        auditLogWriter.write(new AuditEntry(
                actorEmail(),
                "APPLICATION_ANSWER_UPDATED",
                "APPLICATION_ANSWER",
                id,
                Map.of("status", existing.get().status()),
                Map.of("status", body.status() != null ? body.status() : existing.get().status()),
                request.getRemoteAddr(),
                UuidV7.generate()
        ));

        return ResponseEntity.ok(answerRepository.findByIdForProfile(id, profileId).orElseThrow());
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
     * profile yet — so authorization failures are answered with a clean 404
     * instead of surface as a 500.
     */
    private UUID currentProfileIdOrNull() {
        return profileRepository.findByUserId(currentUserId())
                .map(ProfileRecord::id)
                .orElse(null);
    }
}