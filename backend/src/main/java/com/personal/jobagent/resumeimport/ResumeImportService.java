package com.personal.jobagent.resumeimport;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.profile.*;
import com.personal.jobagent.resumeimport.ProfileComparisonService.MatchResult;
import com.personal.jobagent.resumeimport.dto.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Orchestrates the two-stage resume import: {@link #preview} reads and
 * analyses the upload without writing anything to the Master Profile;
 * {@link #confirm} applies only what the caller explicitly selected,
 * re-checking ownership and duplicates against the current database state.
 */
@Service
public class ResumeImportService {

    private static final Set<String> PERSONAL_FIELDS = Set.of("headline", "phone", "location", "linkedinUrl", "websiteUrl");
    private static final Set<String> URL_FIELDS = Set.of("linkedinUrl", "websiteUrl");
    private static final Pattern HAS_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
    private static final Pattern WEB_SCHEME = Pattern.compile("^(?i)https?://");

    private final ResumeTextExtractor textExtractor;
    private final ResumeProfileExtractor profileExtractor;
    private final ProfileComparisonService comparison;
    private final ProfileRepository profiles;
    private final ResumeImportPreviewRepository previews;
    private final AuditLogWriter audit;

    public ResumeImportService(ResumeTextExtractor textExtractor, ResumeProfileExtractor profileExtractor,
                                ProfileComparisonService comparison, ProfileRepository profiles,
                                ResumeImportPreviewRepository previews, AuditLogWriter audit) {
        this.textExtractor = textExtractor;
        this.profileExtractor = profileExtractor;
        this.comparison = comparison;
        this.profiles = profiles;
        this.previews = previews;
        this.audit = audit;
    }

    // ---- preview ----

    public ResumeImportPreviewResponse preview(MultipartFile file, UUID userId, String actor, String ip) {
        ProfileRecord profile = profiles.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("No profile exists for the current user"));

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UnprocessableResumeException("The uploaded file could not be read.");
        }
        ResumeFileValidator.DetectedType type = ResumeFileValidator.detect(bytes, file.getOriginalFilename());
        ResumeTextExtractor.Extracted extracted = textExtractor.extract(bytes, type);

        UUID correlationId = UuidV7.generate();
        ExtractedProfile extractedProfile = profileExtractor.extract(extracted.text(), correlationId);

        List<WorkExperienceRecord> existingExperiences = profiles.findExperiences(profile.id());
        List<EducationRecord> existingEducation = profiles.findEducation(profile.id());
        List<ProjectRecord> existingProjects = profiles.findProjects(profile.id());
        List<SkillRecord> existingSkills = profiles.findSkills(profile.id());
        List<CertificationRecord> existingCertifications = profiles.findCertifications(profile.id());

        List<ExperienceCandidate> experiences = new ArrayList<>();
        int i = 0;
        for (ExtractedExperience e : extractedProfile.experiences()) {
            MatchResult match = comparison.compareExperience(e, existingExperiences);
            experiences.add(new ExperienceCandidate("exp-" + (i++), e, match.status(), match.matchedExistingId()));
        }
        List<EducationCandidate> education = new ArrayList<>();
        i = 0;
        for (ExtractedEducation e : extractedProfile.education()) {
            MatchResult match = comparison.compareEducation(e, existingEducation);
            education.add(new EducationCandidate("edu-" + (i++), e, match.status(), match.matchedExistingId()));
        }
        List<ProjectCandidate> projects = new ArrayList<>();
        i = 0;
        for (ExtractedProject p : extractedProfile.projects()) {
            MatchResult match = comparison.compareProject(p, existingProjects);
            projects.add(new ProjectCandidate("proj-" + (i++), p, match.status(), match.matchedExistingId()));
        }
        List<SkillCandidate> skills = new ArrayList<>();
        i = 0;
        for (ExtractedSkill s : extractedProfile.skills()) {
            MatchResult match = comparison.compareSkill(s, existingSkills);
            skills.add(new SkillCandidate("skill-" + (i++), s, match.status(), match.matchedExistingId()));
        }
        List<CertificationCandidate> certifications = new ArrayList<>();
        i = 0;
        for (ExtractedCertification c : extractedProfile.certifications()) {
            MatchResult match = comparison.compareCertification(c, existingCertifications);
            certifications.add(new CertificationCandidate("cert-" + (i++), c, match.status(), match.matchedExistingId()));
        }

        List<PersonalFieldCandidate> personal = buildPersonalFieldCandidates(extractedProfile.personal(), profile);

        ResumeImportPreviewResponse.Counts counts = countStatuses(experiences, education, projects, skills, certifications);

        ResumeImportPreviewRepository.Created token = previews.create(userId, profile.id());
        audit.write(new AuditEntry(actor, "PROFILE_RESUME_IMPORT_PREVIEWED", "PROFILE", profile.id(), Map.of(),
                Map.of("preview_token", token.id().toString(), "detected_type", type.name(),
                        "offline_fallback", extractedProfile.offlineFallback(),
                        "experience_count", experiences.size(), "education_count", education.size(),
                        "project_count", projects.size(), "skill_count", skills.size(),
                        "certification_count", certifications.size()),
                ip, correlationId));

        return new ResumeImportPreviewResponse(token.id(), token.expiresAt(), extractedProfile.offlineFallback(),
                personal, extractedProfile.summary().professionalSummary(), extractedProfile.summary().careerFocus(),
                extractedProfile.summary().specializations(), extractedProfile.summary().sourceVerified(),
                experiences, education, projects, skills, certifications, counts);
    }

    private List<PersonalFieldCandidate> buildPersonalFieldCandidates(ExtractedPersonal personal, ProfileRecord profile) {
        List<PersonalFieldCandidate> out = new ArrayList<>();
        out.add(new PersonalFieldCandidate("headline", personal.headline(), profile.headline(), personal.isFieldVerified("headline")));
        out.add(new PersonalFieldCandidate("phone", personal.phone(), profile.phone(), personal.isFieldVerified("phone")));
        out.add(new PersonalFieldCandidate("location", personal.location(), profile.location(), personal.isFieldVerified("location")));
        Object existingLinkedin = profile.links() == null ? null : profile.links().get("linkedin");
        Object existingWebsite = profile.links() == null ? null : profile.links().get("website");
        out.add(new PersonalFieldCandidate("linkedinUrl", personal.linkedinUrl(),
                existingLinkedin == null ? null : String.valueOf(existingLinkedin), personal.isFieldVerified("linkedinUrl")));
        out.add(new PersonalFieldCandidate("websiteUrl", personal.websiteUrl(),
                existingWebsite == null ? null : String.valueOf(existingWebsite), personal.isFieldVerified("websiteUrl")));
        // fullName/email are shown nowhere as an import target: the account's
        // authenticated identity/email is never overwritten by this feature.
        return out;
    }

    private ResumeImportPreviewResponse.Counts countStatuses(List<ExperienceCandidate> experiences, List<EducationCandidate> education,
                                                               List<ProjectCandidate> projects, List<SkillCandidate> skills,
                                                               List<CertificationCandidate> certifications) {
        List<MatchStatus> all = new ArrayList<>();
        experiences.forEach(c -> all.add(c.matchStatus()));
        education.forEach(c -> all.add(c.matchStatus()));
        projects.forEach(c -> all.add(c.matchStatus()));
        skills.forEach(c -> all.add(c.matchStatus()));
        certifications.forEach(c -> all.add(c.matchStatus()));
        return new ResumeImportPreviewResponse.Counts(
                (int) all.stream().filter(s -> s == MatchStatus.NEW).count(),
                (int) all.stream().filter(s -> s == MatchStatus.EXISTING).count(),
                (int) all.stream().filter(s -> s == MatchStatus.POSSIBLE_DUPLICATE).count(),
                (int) all.stream().filter(s -> s == MatchStatus.CONFLICT).count(),
                (int) all.stream().filter(s -> s == MatchStatus.NEEDS_REVIEW).count());
    }

    // ---- confirm ----

    /**
     * One transaction: the token is claimed first, so a failure anywhere below
     * rolls back every change AND the claim (the same preview can be retried),
     * while a concurrent second confirm of the same token waits on the claim's
     * row lock and then finds it already used.
     *
     * <p>No statement here may fail on a constraint and be carried on past: in
     * PostgreSQL that aborts the whole transaction. Duplicates are therefore
     * detected up front, against the current records plus everything added
     * earlier in this same request, instead of being caught after the fact.
     */
    @Transactional
    public ResumeImportConfirmResponse confirm(ResumeImportConfirmRequest request, UUID userId, String actor, String ip) {
        if (request.previewToken() == null) {
            throw new IllegalArgumentException("previewToken is required");
        }
        int itemCount = nullToEmpty(request.experiences()).size() + nullToEmpty(request.education()).size()
                + nullToEmpty(request.projects()).size() + nullToEmpty(request.skills()).size()
                + nullToEmpty(request.certifications()).size();
        if (itemCount > ResumeImportLimits.MAX_ITEMS_PER_CONFIRM) {
            throw new IllegalArgumentException("Too many items in one import.");
        }
        ResumeImportPreviewRepository.Claimed token = previews.claim(request.previewToken(), userId)
                .orElseThrow(() -> new IllegalStateException(
                        "This preview has expired or was already used. Upload the resume again to re-import."));

        // Ownership comes from the authenticated user, and must also be the profile the preview was issued for.
        ProfileRecord profile = profiles.findByUserId(userId)
                .filter(p -> p.id().equals(token.profileId()))
                .orElseThrow(() -> new IllegalStateException(
                        "This preview no longer matches your profile. Upload the resume again to re-import."));

        Tally tally = new Tally();
        applyPersonalUpdates(request, profile, tally.warnings);
        applyExperiences(nullToEmpty(request.experiences()), profile.id(), tally);
        applyEducation(nullToEmpty(request.education()), profile.id(), tally);
        applyProjects(nullToEmpty(request.projects()), profile.id(), tally);
        applySkills(nullToEmpty(request.skills()), profile.id(), tally);
        applyCertifications(nullToEmpty(request.certifications()), profile.id(), tally);

        audit.write(new AuditEntry(actor, "PROFILE_RESUME_IMPORT_CONFIRMED", "PROFILE", profile.id(), Map.of(),
                Map.of("added", tally.added, "replaced", tally.replaced, "skipped", tally.skipped,
                        "warning_count", tally.warnings.size()),
                ip, UuidV7.generate()));

        return new ResumeImportConfirmResponse(tally.added, tally.replaced, tally.skipped, tally.warnings);
    }

    private static final class Tally {
        int added;
        int replaced;
        int skipped;
        final List<String> warnings = new ArrayList<>();

        void skip(String warning) {
            skipped++;
            if (warning != null) warnings.add(warning);
        }
    }

    private void applyExperiences(List<ExperienceDecision> decisions, UUID profileId, Tally tally) {
        List<WorkExperienceRecord> current = new ArrayList<>(profiles.findExperiences(profileId));
        int sortOrder = current.size();
        for (ExperienceDecision d : decisions) {
            if (d.action() == null || d.action() == ImportAction.SKIP) { tally.skip(null); continue; }
            LocalDate start = toStorageDate(d.startMonth());
            LocalDate end = toStorageDate(d.endMonth());
            String startPrecision = datePrecision(d.startMonth());
            String endPrecision = d.endMonth() == null ? null : datePrecision(d.endMonth());
            if (isBlank(d.company()) || isBlank(d.title()) || start == null) {
                tally.skip("Skipped a work experience entry: company, title and a start date are required.");
                continue;
            }
            List<Map<String, Object>> bullets = toBullets(d.responsibilities(), d.achievements());
            if (d.action() == ImportAction.REPLACE) {
                Optional<WorkExperienceRecord> target = current.stream()
                        .filter(e -> e.id().equals(d.matchedExistingId())).findFirst();
                if (target.isEmpty()) {
                    tally.skip("Could not replace \"" + d.title() + "\" at \"" + d.company() + "\": the matched record no longer exists.");
                    continue;
                }
                profiles.updateExperience(d.matchedExistingId(), profileId, d.company(), d.title(),
                        start, end, d.location(), bullets, target.get().sortOrder(), startPrecision, endPrecision);
                tally.replaced++;
                continue;
            }
            ExtractedExperience asExtracted = new ExtractedExperience(d.company(), d.title(), d.location(),
                    d.startMonth(), d.endMonth(), false, List.of(), List.of(), List.of(), true, false);
            if (comparison.compareExperience(asExtracted, current).status() == MatchStatus.EXISTING) {
                tally.skip("\"" + d.title() + "\" at \"" + d.company() + "\" is already in your profile, so it was not added again.");
                continue;
            }
            UUID id = profiles.insertExperience(profileId, d.company(), d.title(), start, end,
                    d.location(), bullets, sortOrder, startPrecision, endPrecision);
            current.add(new WorkExperienceRecord(id, profileId, d.company(), d.title(), start, end,
                    d.location(), bullets, sortOrder++, startPrecision, endPrecision));
            tally.added++;
        }
    }

    private void applyEducation(List<EducationDecision> decisions, UUID profileId, Tally tally) {
        List<EducationRecord> current = new ArrayList<>(profiles.findEducation(profileId));
        for (EducationDecision d : decisions) {
            if (d.action() == null || d.action() == ImportAction.SKIP) { tally.skip(null); continue; }
            if (isBlank(d.institution()) || isBlank(d.qualification())) {
                tally.skip("Skipped an education entry: institution and qualification are required.");
                continue;
            }
            if (d.action() == ImportAction.REPLACE) {
                if (current.stream().noneMatch(e -> e.id().equals(d.matchedExistingId()))) {
                    tally.skip("Could not replace \"" + d.qualification() + "\" at \"" + d.institution() + "\": the matched record no longer exists.");
                    continue;
                }
                profiles.updateEducation(d.matchedExistingId(), profileId, d.institution(), d.qualification(),
                        d.field(), d.startYear(), d.endYear(), d.grade());
                tally.replaced++;
                continue;
            }
            ExtractedEducation asExtracted = new ExtractedEducation(d.institution(), d.qualification(), d.field(),
                    d.startYear(), d.endYear(), d.grade(), true, false);
            if (comparison.compareEducation(asExtracted, current).status() == MatchStatus.EXISTING) {
                tally.skip("\"" + d.qualification() + "\" at \"" + d.institution() + "\" is already in your profile, so it was not added again.");
                continue;
            }
            UUID id = profiles.insertEducation(profileId, d.institution(), d.qualification(), d.field(),
                    d.startYear(), d.endYear(), d.grade());
            current.add(new EducationRecord(id, profileId, d.institution(), d.qualification(), d.field(),
                    d.startYear(), d.endYear(), d.grade()));
            tally.added++;
        }
    }

    private void applyProjects(List<ProjectDecision> decisions, UUID profileId, Tally tally) {
        List<ProjectRecord> current = new ArrayList<>(profiles.findProjects(profileId));
        int sortOrder = current.size();
        for (ProjectDecision d : decisions) {
            if (d.action() == null || d.action() == ImportAction.SKIP) { tally.skip(null); continue; }
            if (isBlank(d.name())) {
                tally.skip("Skipped a project entry: a name is required.");
                continue;
            }
            List<Map<String, Object>> bullets = toBullets(d.bullets(), List.of());
            if (d.action() == ImportAction.REPLACE) {
                Optional<ProjectRecord> target = current.stream()
                        .filter(p -> p.id().equals(d.matchedExistingId())).findFirst();
                if (target.isEmpty()) {
                    tally.skip("Could not replace project \"" + d.name() + "\": the matched record no longer exists.");
                    continue;
                }
                profiles.updateProject(d.matchedExistingId(), profileId, d.name(), d.summary(), d.url(), bullets,
                        target.get().sortOrder());
                tally.replaced++;
                continue;
            }
            ExtractedProject asExtracted = new ExtractedProject(d.name(), d.summary(), d.url(), List.of(), List.of(), true, false);
            if (comparison.compareProject(asExtracted, current).status() == MatchStatus.EXISTING) {
                tally.skip("Project \"" + d.name() + "\" is already in your profile, so it was not added again.");
                continue;
            }
            UUID id = profiles.insertProject(profileId, d.name(), d.summary(), d.url(), bullets, sortOrder);
            current.add(new ProjectRecord(id, profileId, d.name(), d.summary(), d.url(), bullets, sortOrder++));
            tally.added++;
        }
    }

    private void applySkills(List<SkillDecision> decisions, UUID profileId, Tally tally) {
        List<SkillRecord> current = new ArrayList<>(profiles.findSkills(profileId));
        for (SkillDecision d : decisions) {
            if (d.action() == null || d.action() == ImportAction.SKIP) { tally.skip(null); continue; }
            if (isBlank(d.name())) {
                tally.skip("Skipped a skill entry: a name is required.");
                continue;
            }
            String name = d.name().strip();
            // Mirrors the database's case-insensitive (citext) unique(profile_id, name).
            Optional<SkillRecord> sameName = current.stream()
                    .filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
            if (d.action() == ImportAction.REPLACE) {
                Optional<SkillRecord> target = current.stream()
                        .filter(s -> s.id().equals(d.matchedExistingId())).findFirst();
                if (target.isEmpty()) {
                    tally.skip("Could not replace skill \"" + name + "\": the matched record no longer exists.");
                    continue;
                }
                if (sameName.isPresent() && !sameName.get().id().equals(target.get().id())) {
                    tally.skip("Could not rename a skill to \"" + name + "\": another skill already has that name.");
                    continue;
                }
                // Only name and category come from a resume; mastery, years and the
                // evidence link are the user's own and are kept as they were.
                SkillRecord existing = target.get();
                profiles.updateSkill(existing.id(), profileId, name, d.category(), existing.mastery(), existing.years(),
                        existing.evidenceExperienceId());
                current.remove(existing);
                current.add(new SkillRecord(existing.id(), profileId, name, d.category(), existing.mastery(),
                        existing.years(), existing.evidenceExperienceId()));
                tally.replaced++;
                continue;
            }
            if (sameName.isPresent()) {
                tally.skip("Skill \"" + name + "\" is already in your profile, so it was not added again.");
                continue;
            }
            UUID id;
            try {
                id = profiles.insertSkill(profileId, name, d.category(), null, null, null);
            } catch (ProfileRepository.DuplicateSkillException e) {
                // Only reachable if another request added the same skill between the
                // check above and this insert. The failed statement has aborted the
                // transaction, so the whole import is rolled back rather than half-applied.
                throw new IllegalStateException(
                        "Your profile changed while this import was being saved. Nothing was changed; please try again.");
            }
            current.add(new SkillRecord(id, profileId, name, d.category(), null, null, null));
            tally.added++;
        }
    }

    private void applyCertifications(List<CertificationDecision> decisions, UUID profileId, Tally tally) {
        List<CertificationRecord> current = new ArrayList<>(profiles.findCertifications(profileId));
        for (CertificationDecision d : decisions) {
            if (d.action() == null || d.action() == ImportAction.SKIP) { tally.skip(null); continue; }
            if (isBlank(d.name())) {
                tally.skip("Skipped a certification entry: a name is required.");
                continue;
            }
            LocalDate issuedOnDate = toStorageDate(d.issuedOn());
            String issuedPrecision = d.issuedOn() == null ? null : datePrecision(d.issuedOn());
            if (d.action() == ImportAction.REPLACE) {
                if (current.stream().noneMatch(c -> c.id().equals(d.matchedExistingId()))) {
                    tally.skip("Could not replace certification \"" + d.name() + "\": the matched record no longer exists.");
                    continue;
                }
                profiles.updateCertification(d.matchedExistingId(), profileId, d.name(), d.issuer(), issuedOnDate, d.credentialId(), issuedPrecision);
                tally.replaced++;
                continue;
            }
            ExtractedCertification asExtracted = new ExtractedCertification(d.name(), d.issuer(), d.issuedOn(), null,
                    d.credentialId(), null, true, false);
            if (comparison.compareCertification(asExtracted, current).status() == MatchStatus.EXISTING) {
                tally.skip("Certification \"" + d.name() + "\" is already in your profile, so it was not added again.");
                continue;
            }
            UUID id = profiles.insertCertification(profileId, d.name(), d.issuer(), issuedOnDate, d.credentialId(), issuedPrecision);
            current.add(new CertificationRecord(id, profileId, d.name(), d.issuer(), issuedOnDate, d.credentialId(), issuedPrecision));
            tally.added++;
        }
    }

    static LocalDate toStorageDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String s = dateStr.strip();
        if (s.matches("\\d{4}")) {
            return LocalDate.of(Integer.parseInt(s), 1, 1);
        }
        if (s.matches("\\d{4}-\\d{2}")) {
            return LocalDate.parse(s + "-01");
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return LocalDate.parse(s);
        }
        return null;
    }

    static String datePrecision(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) return null;
        String s = dateStr.strip();
        if (s.matches("\\d{4}")) {
            return "YEAR";
        }
        return "MONTH";
    }

    /**
     * Applies only the personal-info fields the caller explicitly included. Keys
     * outside {@link #PERSONAL_FIELDS} (e.g. "email") are ignored, and a link must
     * be a plain address or http(s) — never another scheme such as javascript:.
     */
    private void applyPersonalUpdates(ResumeImportConfirmRequest request, ProfileRecord profile, List<String> warnings) {
        Map<String, String> updates = new HashMap<>();
        if (request.personalFieldUpdates() != null) {
            request.personalFieldUpdates().forEach((field, value) -> {
                if (!PERSONAL_FIELDS.contains(field) || value == null) return;
                String trimmed = value.strip();
                if (URL_FIELDS.contains(field) && HAS_SCHEME.matcher(trimmed).find() && !WEB_SCHEME.matcher(trimmed).find()) {
                    warnings.add("Ignored " + field + ": only web (http/https) links can be imported.");
                    return;
                }
                updates.put(field, trimmed);
            });
        }
        String summaryUpdate = request.professionalSummaryUpdate();
        boolean summaryChanged = summaryUpdate != null && !summaryUpdate.isBlank();
        if (updates.isEmpty() && !summaryChanged) return;

        Map<String, Object> links = new LinkedHashMap<>(profile.links() == null ? Map.of() : profile.links());
        if (updates.containsKey("linkedinUrl")) links.put("linkedin", updates.get("linkedinUrl"));
        if (updates.containsKey("websiteUrl")) links.put("website", updates.get("websiteUrl"));

        // updateProfile bumps master_revision itself.
        profiles.updateProfile(profile.id(),
                updates.getOrDefault("headline", profile.headline()),
                updates.getOrDefault("phone", profile.phone()),
                updates.getOrDefault("location", profile.location()),
                profile.workEligibility(), profile.careerGoals(),
                summaryChanged ? summaryUpdate.strip() : profile.professionalSummary(),
                links);
    }

    private List<Map<String, Object>> toBullets(List<String> first, List<String> second) {
        List<Map<String, Object>> bullets = new ArrayList<>();
        for (String text : nullToEmpty(first)) if (text != null && !text.isBlank()) bullets.add(Map.of("text", text));
        for (String text : nullToEmpty(second)) if (text != null && !text.isBlank()) bullets.add(Map.of("text", text));
        return bullets;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
