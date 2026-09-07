package com.personal.jobagent.profile;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GET/PUT /profile plus CRUD for experiences/education/projects/
 * certifications/skills per docs/contracts/api.md. All endpoints scope to
 * the current session's profile — there is no cross-user access path here
 * (single-user Phase 1, but written so the profileId-ownership check
 * generalizes cleanly if that ever changes).
 */
@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileRepository profileRepository;
    private final AuditLogWriter auditLogWriter;

    public ProfileController(ProfileRepository profileRepository, AuditLogWriter auditLogWriter) {
        this.profileRepository = profileRepository;
        this.auditLogWriter = auditLogWriter;
    }

    public record ProfileUpdateRequest(String headline, String phone, String location,
                                        Map<String, Object> workEligibility, Map<String, Object> careerGoals) {
    }

    @GetMapping
    public ResponseEntity<?> getProfile(HttpServletRequest httpRequest) {
        UUID profileId = currentProfileId();
        return profileRepository.findByUserId(currentUserId())
                .<ResponseEntity<?>>map(profile -> ResponseEntity.ok(Map.of(
                        "profile", profile,
                        "experiences", profileRepository.findExperiences(profileId),
                        "education", profileRepository.findEducation(profileId),
                        "projects", profileRepository.findProjects(profileId),
                        "certifications", profileRepository.findCertifications(profileId),
                        "skills", profileRepository.findSkills(profileId)
                )))
                .orElseGet(() -> notFound(httpRequest, "No profile exists yet for this account."));
    }

    @PutMapping
    public ResponseEntity<?> updateProfile(@RequestBody ProfileUpdateRequest request, HttpServletRequest httpRequest) {
        var existing = profileRepository.findByUserId(currentUserId());
        if (existing.isEmpty()) {
            return notFound(httpRequest, "No profile exists yet for this account.");
        }
        if (request.workEligibility() != null && request.workEligibility().get("visaStatus") != null) {
            try {
                VisaStatus.valueOf(String.valueOf(request.workEligibility().get("visaStatus")));
            } catch (IllegalArgumentException e) {
                ApiError error = ApiError.of(400, "Invalid visaStatus",
                        "visaStatus must be one of " + List.of(VisaStatus.values()),
                        httpRequest.getRequestURI(), correlationId());
                return ResponseEntity.badRequest().body(error);
            }
        }

        ProfileRecord before = existing.get();
        profileRepository.updateProfile(before.id(), request.headline(), request.phone(), request.location(),
                request.workEligibility(), request.careerGoals());

        auditLogWriter.write(new AuditEntry(actorEmail(), "PROFILE_UPDATED", "PROFILE", before.id(),
                Map.of("headline", String.valueOf(before.headline())),
                Map.of("headline", String.valueOf(request.headline())),
                httpRequest.getRemoteAddr(), UuidV7.generate()));

        return ResponseEntity.ok(profileRepository.findByUserId(currentUserId()).orElseThrow());
    }

    // ---- experiences ----

    public record ExperienceRequest(String company, String title, LocalDate startMonth, LocalDate endMonth,
                                     String location, List<Map<String, Object>> bullets, int sortOrder) {
    }

    @GetMapping("/experiences")
    public List<WorkExperienceRecord> listExperiences() {
        return profileRepository.findExperiences(currentProfileId());
    }

    @PostMapping("/experiences")
    public ResponseEntity<?> addExperience(@RequestBody ExperienceRequest request) {
        UUID id = profileRepository.insertExperience(currentProfileId(), request.company(), request.title(),
                request.startMonth(), request.endMonth(), request.location(),
                request.bullets() != null ? request.bullets() : List.of(), request.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @DeleteMapping("/experiences/{id}")
    public ResponseEntity<?> deleteExperience(@PathVariable UUID id) {
        boolean deleted = profileRepository.deleteExperience(id, currentProfileId());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ---- education ----

    public record EducationRequest(String institution, String qualification, String field,
                                    Integer startYear, Integer endYear, String grade) {
    }

    @GetMapping("/education")
    public List<EducationRecord> listEducation() {
        return profileRepository.findEducation(currentProfileId());
    }

    @PostMapping("/education")
    public ResponseEntity<?> addEducation(@RequestBody EducationRequest request) {
        UUID id = profileRepository.insertEducation(currentProfileId(), request.institution(),
                request.qualification(), request.field(), request.startYear(), request.endYear(), request.grade());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @DeleteMapping("/education/{id}")
    public ResponseEntity<?> deleteEducation(@PathVariable UUID id) {
        boolean deleted = profileRepository.deleteEducation(id, currentProfileId());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ---- projects ----

    public record ProjectRequest(String name, String summary, String url,
                                  List<Map<String, Object>> bullets, int sortOrder) {
    }

    @GetMapping("/projects")
    public List<ProjectRecord> listProjects() {
        return profileRepository.findProjects(currentProfileId());
    }

    @PostMapping("/projects")
    public ResponseEntity<?> addProject(@RequestBody ProjectRequest request) {
        UUID id = profileRepository.insertProject(currentProfileId(), request.name(), request.summary(),
                request.url(), request.bullets() != null ? request.bullets() : List.of(), request.sortOrder());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<?> deleteProject(@PathVariable UUID id) {
        boolean deleted = profileRepository.deleteProject(id, currentProfileId());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ---- certifications ----

    public record CertificationRequest(String name, String issuer, LocalDate issuedOn, String credentialId) {
    }

    @GetMapping("/certifications")
    public List<CertificationRecord> listCertifications() {
        return profileRepository.findCertifications(currentProfileId());
    }

    @PostMapping("/certifications")
    public ResponseEntity<?> addCertification(@RequestBody CertificationRequest request) {
        UUID id = profileRepository.insertCertification(currentProfileId(), request.name(), request.issuer(),
                request.issuedOn(), request.credentialId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @DeleteMapping("/certifications/{id}")
    public ResponseEntity<?> deleteCertification(@PathVariable UUID id) {
        boolean deleted = profileRepository.deleteCertification(id, currentProfileId());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ---- skills ----

    public record SkillRequest(String name, String category, Integer mastery, BigDecimal years,
                                UUID evidenceExperienceId) {
    }

    @GetMapping("/skills")
    public List<SkillRecord> listSkills() {
        return profileRepository.findSkills(currentProfileId());
    }

    @PostMapping("/skills")
    public ResponseEntity<?> addSkill(@RequestBody SkillRequest request, HttpServletRequest httpRequest) {
        try {
            UUID id = profileRepository.insertSkill(currentProfileId(), request.name(), request.category(),
                    request.mastery(), request.years(), request.evidenceExperienceId());
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        } catch (ProfileRepository.InvalidMasteryException e) {
            ApiError error = ApiError.of(400, "Invalid mastery", e.getMessage(), httpRequest.getRequestURI(), correlationId());
            return ResponseEntity.badRequest().body(error);
        } catch (ProfileRepository.DuplicateSkillException e) {
            ApiError error = ApiError.of(409, "Duplicate skill", e.getMessage(), httpRequest.getRequestURI(), correlationId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
        }
    }

    @DeleteMapping("/skills/{id}")
    public ResponseEntity<?> deleteSkill(@PathVariable UUID id) {
        boolean deleted = profileRepository.deleteSkill(id, currentProfileId());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ---- shared helpers ----

    private ResponseEntity<?> notFound(HttpServletRequest httpRequest, String detail) {
        ApiError error = ApiError.of(404, "Not found", detail, httpRequest.getRequestURI(), correlationId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
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
