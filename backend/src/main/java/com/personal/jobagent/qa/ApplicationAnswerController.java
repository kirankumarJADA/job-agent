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

    @GetMapping("/job/{jobId}")
    public List<ApplicationAnswerRecord> listByJob(@PathVariable UUID jobId) {
        return answerRepository.findByJobId(jobId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getAnswer(@PathVariable UUID id, HttpServletRequest request) {
        return answerRepository.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiError.of(404, "Not Found", "Answer not found", request.getRequestURI(), correlationId())));
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

    @PutMapping("/{id}")
    public ResponseEntity<?> updateAnswer(@PathVariable UUID id, @RequestBody UpdateAnswerRequest body, HttpServletRequest request) {
        var existing = answerRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiError.of(404, "Not Found", "Answer not found", request.getRequestURI(), correlationId()));
        }

        answerRepository.updateStatus(id, body.status() != null ? body.status() : existing.get().status(), body.answerText());

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

        return ResponseEntity.ok(answerRepository.findById(id).orElseThrow());
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