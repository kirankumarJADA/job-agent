package com.personal.jobagent.resumeimport;

import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.CorrelationIdFilter;
import com.personal.jobagent.resumeimport.dto.ResumeImportConfirmRequest;
import com.personal.jobagent.resumeimport.dto.ResumeImportConfirmResponse;
import com.personal.jobagent.resumeimport.dto.ResumeImportPreviewResponse;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Master Resume Import — see docs/MASTER_RESUME_IMPORT.md.
 *
 * <p>Ownership is always derived from the authenticated principal, never
 * from anything the browser sends: there is no profileId request
 * parameter anywhere on this controller, by design (per the spec's "the
 * frontend must never be able to force an arbitrary profile ID... into the
 * import").
 *
 * <p>Errors use the same RFC 9457 {@link ApiError} shape as
 * {@code ApiExceptionAdvice}, whose {@code detail} the frontend shows as-is;
 * every detail here is a fixed sentence, never file content or extracted data.
 */
@RestController
@RequestMapping("/api/v1/profile/resume-import")
public class ResumeImportController {

    private static final Logger log = LoggerFactory.getLogger(ResumeImportController.class);

    private final ResumeImportService service;

    public ResumeImportController(ResumeImportService service) {
        this.service = service;
    }

    @PostMapping(value = "/preview", consumes = "multipart/form-data")
    public ResponseEntity<ResumeImportPreviewResponse> preview(@RequestParam("file") MultipartFile file,
                                                                 HttpServletRequest httpRequest) {
        if (file == null || file.isEmpty()) {
            throw new UnprocessableResumeException("No file was uploaded.");
        }
        ResumeImportPreviewResponse response = service.preview(file, userId(), actor(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/confirm")
    public ResponseEntity<ResumeImportConfirmResponse> confirm(@RequestBody ResumeImportConfirmRequest request,
                                                                  HttpServletRequest httpRequest) {
        ResumeImportConfirmResponse response = service.confirm(request, userId(), actor(), httpRequest.getRemoteAddr());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(UnprocessableResumeException.class)
    public ResponseEntity<ApiError> unprocessable(UnprocessableResumeException e, HttpServletRequest request) {
        return error(422, "Unprocessable resume", e.getMessage(), request);
    }

    @ExceptionHandler(AiExtractionFailedException.class)
    public ResponseEntity<ApiError> extractionFailed(AiExtractionFailedException e, HttpServletRequest request) {
        return error(502, "Resume analysis failed", e.getMessage(), request);
    }

    /** confirm() is one transaction, so a database failure leaves the profile exactly as it was. */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> databaseUnavailable(DataAccessException e, HttpServletRequest request) {
        log.warn("Resume import failed on the database: {}", e.getClass().getSimpleName());
        return error(503, "Service Unavailable",
                "Your profile could not be updated right now. Nothing was changed; please try again.", request);
    }

    private ResponseEntity<ApiError> error(int status, String title, String detail, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(status, title, detail, request.getRequestURI(),
                String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY))));
    }

    private UUID userId() {
        return ((AppUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUserId();
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? "UNKNOWN" : authentication.getName();
    }
}
