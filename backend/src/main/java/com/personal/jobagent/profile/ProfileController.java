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
                                        Map<String, Object> workEligibility, Map<String, Object> careerGoals,
                                        String professionalSummary, Map<String, Object> links) {
    }

    public record SetupRequest(String headline, String phone, String location,
                               Map<String, Object> workEligibility, Map<String, Object> careerGoals,
                               String professionalSummary, Map<String, Object> links) {
    }

    @PostMapping("/setup")
    public ResponseEntity<?> setup(@RequestBody SetupRequest request, HttpServletRequest httpRequest) {
        UUID userId = currentUserId();
        UUID profileId = profileRepository.findByUserId(userId)
                .map(ProfileRecord::id)
                .orElseGet(() -> profileRepository.createProfile(userId, request.headline(), request.phone(), request.location(),
                        request.workEligibility(), request.careerGoals(), request.professionalSummary(), request.links()));
        if (profileRepository.findByUserId(userId).isPresent()) {
            profileRepository.updateProfile(profileId, request.headline(), request.phone(), request.location(),
                    request.workEligibility(), request.careerGoals(), request.professionalSummary(), request.links());
        }
        auditLogWriter.write(new AuditEntry(actorEmail(), "MASTER_PROFILE_SETUP_SAVED", "PROFILE", profileId,
                Map.of(), Map.of("profile_id", profileId.toString()), httpRequest.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.ok(profileRepository.findByUserId(userId).orElseThrow());
    }

    @PostMapping("/complete")
    public ResponseEntity<?> completeSetup(HttpServletRequest httpRequest) {
        UUID profileId = currentProfileId();
        List<String> missing = setupMissing(profileId);
        if (!missing.isEmpty()) return ResponseEntity.badRequest().body(Map.of("setupStatus", "INCOMPLETE", "missing", missing));
        profileRepository.markSetupReady(profileId);
        auditLogWriter.write(new AuditEntry(actorEmail(), "MASTER_PROFILE_SETUP_COMPLETED", "PROFILE", profileId,
                Map.of(), Map.of("setup_status", "READY"), httpRequest.getRemoteAddr(), UuidV7.generate()));
        return ResponseEntity.ok(Map.of("setupStatus", "READY", "profile", profileRepository.findByUserId(currentUserId()).orElseThrow()));
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
                        "skills", profileRepository.findSkills(profileId),
                        "evidence", profileRepository.findEvidence(profileId),
                        "masterCv", Map.of("kind", "MASTER", "canonical", true, "profileRevision", profile.masterRevision())
                )))
                .orElseGet(() -> notFound(httpRequest, "No profile exists yet for this account."));
    }

    @GetMapping("/master-cv")
    public ResponseEntity<?> getMasterCv() {
        UUID profileId = currentProfileId();
        ProfileRecord profile = profileRepository.findById(profileId).orElseThrow();
        return ResponseEntity.ok(Map.of("kind", "MASTER", "canonical", true, "profileRevision", profile.masterRevision(),
                "profile", profile, "experiences", profileRepository.findExperiences(profileId),
                "education", profileRepository.findEducation(profileId), "projects", profileRepository.findProjects(profileId),
                "skills", profileRepository.findSkills(profileId), "certifications", profileRepository.findCertifications(profileId),
                "evidence", profileRepository.findEvidence(profileId)));
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
                request.workEligibility(), request.careerGoals(), request.professionalSummary(), request.links());

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

    @PutMapping("/experiences/{id}")
    public ResponseEntity<?> updateExperience(@PathVariable UUID id, @RequestBody ExperienceRequest request) {
        boolean updated = profileRepository.updateExperience(id, currentProfileId(), request.company(), request.title(),
                request.startMonth(), request.endMonth(), request.location(), request.bullets(), request.sortOrder());
        return updated ? ResponseEntity.ok(Map.of("id", id)) : ResponseEntity.notFound().build();
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

    @PutMapping("/education/{id}")
    public ResponseEntity<?> updateEducation(@PathVariable UUID id, @RequestBody EducationRequest request) {
        boolean updated = profileRepository.updateEducation(id, currentProfileId(), request.institution(), request.qualification(),
                request.field(), request.startYear(), request.endYear(), request.grade());
        return updated ? ResponseEntity.ok(Map.of("id", id)) : ResponseEntity.notFound().build();
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

    @PutMapping("/projects/{id}")
    public ResponseEntity<?> updateProject(@PathVariable UUID id, @RequestBody ProjectRequest request) {
        boolean updated = profileRepository.updateProject(id, currentProfileId(), request.name(), request.summary(),
                request.url(), request.bullets(), request.sortOrder());
        return updated ? ResponseEntity.ok(Map.of("id", id)) : ResponseEntity.notFound().build();
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

    @PutMapping("/certifications/{id}")
    public ResponseEntity<?> updateCertification(@PathVariable UUID id, @RequestBody CertificationRequest request) {
        boolean updated = profileRepository.updateCertification(id, currentProfileId(), request.name(), request.issuer(),
                request.issuedOn(), request.credentialId());
        return updated ? ResponseEntity.ok(Map.of("id", id)) : ResponseEntity.notFound().build();
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

    @PutMapping("/skills/{id}")
    public ResponseEntity<?> updateSkill(@PathVariable UUID id, @RequestBody SkillRequest request, HttpServletRequest httpRequest) {
        try {
            boolean updated = profileRepository.updateSkill(id, currentProfileId(), request.name(), request.category(),
                    request.mastery(), request.years(), request.evidenceExperienceId());
            return updated ? ResponseEntity.ok(Map.of("id", id)) : ResponseEntity.notFound().build();
        } catch (ProfileRepository.InvalidMasteryException e) {
            return ResponseEntity.badRequest().body(ApiError.of(400, "Invalid mastery", e.getMessage(), httpRequest.getRequestURI(), correlationId()));
        }
    }

    @DeleteMapping("/skills/{id}")
    public ResponseEntity<?> deleteSkill(@PathVariable UUID id) {
        boolean deleted = profileRepository.deleteSkill(id, currentProfileId());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    // ---- shared helpers ----

    private List<String> setupMissing(UUID profileId) {
        List<String> missing = new java.util.ArrayList<>();
        ProfileRecord profile = profileRepository.findByUserId(currentUserId()).orElseThrow();
        if (profile.headline() == null || profile.headline().isBlank()) missing.add("headline");
        if (profile.professionalSummary() == null || profile.professionalSummary().isBlank()) missing.add("professionalSummary");
        if (profileRepository.findEducation(profileId).isEmpty()) missing.add("education");
        if (profileRepository.findExperiences(profileId).isEmpty()) missing.add("workExperience");
        if (profileRepository.findSkills(profileId).isEmpty()) missing.add("skills");
        return missing;
    }

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
