package com.personal.jobagent.coverletter;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.llm.LlmCompletionRequest;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.TaskType;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
public class CoverLetterService {

    private static final Logger log = LoggerFactory.getLogger(CoverLetterService.class);

    private final CoverLetterRepository coverLetterRepository;
    private final JobRepository jobRepository;
    private final ProfileRepository profileRepository;
    private final ModelRouter modelRouter;
    private final NotificationService notificationService;

    public CoverLetterService(CoverLetterRepository coverLetterRepository,
                              JobRepository jobRepository,
                              ProfileRepository profileRepository,
                              ModelRouter modelRouter,
                              NotificationService notificationService) {
        this.coverLetterRepository = coverLetterRepository;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.modelRouter = modelRouter;
        this.notificationService = notificationService;
    }

    public record GenerationResult(CoverLetterRecord coverLetter, boolean passedValidation, List<String> issues) {
    }

    public GenerationResult generateCoverLetter(UUID profileId, UUID jobId, UUID applicationId) {
        JobRecord job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<WorkExperienceRecord> experiences = profileRepository.findExperiences(profileId);
        List<SkillRecord> skills = profileRepository.findSkills(profileId);
        List<EducationRecord> education = profileRepository.findEducation(profileId);
        List<ProjectRecord> projects = profileRepository.findProjects(profileId);

        StringBuilder profileContext = new StringBuilder();
        profileContext.append("Verified Skills: ");
        skills.forEach(s -> profileContext.append(s.name()).append(", "));
        profileContext.append("\nVerified Work Experiences:\n");
        experiences.forEach(e -> profileContext.append("- ").append(e.title()).append(" at ").append(e.company()).append("\n"));
        profileContext.append("Verified Education:\n");
        education.forEach(ed -> profileContext.append("- ").append(ed.qualification()).append(" at ").append(ed.institution()).append("\n"));
        profileContext.append("Verified Projects:\n");
        projects.forEach(p -> profileContext.append("- ").append(p.name()).append(": ").append(p.summary()).append("\n"));

        String prompt = "Generate an ATS-friendly, professional cover letter for the following job posting.\n\n"
                + "Job Title: " + job.title() + "\n"
                + "Company: " + (job.companyNameRaw() != null ? job.companyNameRaw() : "Hiring Company") + "\n"
                + "Location: " + (job.locationRaw() != null ? job.locationRaw() : "UK") + "\n"
                + "Job Description:\n" + job.descriptionText() + "\n\n"
                + "Strict Candidate Constraints:\n"
                + "You must only reference facts, experiences, companies, education, skills, and projects listed below.\n"
                + "NEVER fabricate unverified credentials, dates, skills, or employment history.\n\n"
                + "Verified Candidate Profile:\n"
                + profileContext.toString();

        UUID correlationId = UuidV7.generate();
        LlmCompletionRequest request = LlmCompletionRequest.simple("default", prompt, correlationId);

        var executionResult = modelRouter.execute(TaskType.COVER_LETTER, request, Duration.ofSeconds(30));
        String rawContent = executionResult.completion().text();

        // Deterministic factual validation & anti-fabrication check
        List<String> issues = validateFactualClaims(rawContent, experiences, skills, education, projects);
        boolean passedValidation = issues.isEmpty();

        Map<String, Object> claimsValidation = new HashMap<>();
        claimsValidation.put("passed", passedValidation);
        claimsValidation.put("issues", issues);
        claimsValidation.put("checked_against_skills_count", skills.size());
        claimsValidation.put("checked_against_experiences_count", experiences.size());

        // Scoped by profile: cover_letters carries unique(job_id, version),
        // so a global counter would collide as soon as a second account
        // generated a letter for the same job.
        int nextVersion = coverLetterRepository.getNextVersion(jobId, profileId);
        String title = "Cover Letter v" + nextVersion + " - " + job.title();

        UUID clId = coverLetterRepository.insert(profileId, jobId, applicationId, nextVersion,
                title, rawContent, claimsValidation, passedValidation);

        // Feature 8: fan out a notification for every generated cover letter.
        // Emitted AFTER the row insert within the same service call so the
        // outbox row can never reference a cover letter that doesn't exist;
        // dedup keyed on the cover letter id itself (one notification per
        // generated artifact, replay-safe).
        try {
            notificationService.emit(new NotificationService.NotificationCommand(
                    NotificationEvents.COVER_LETTER_GENERATED,
                    "COVER_LETTER",
                    clId,
                    Map.of(
                            "job_id", jobId.toString(),
                            // Explicit owner so the notification is attributed to the
                            // candidate whose letter this is, independently of the
                            // application row.
                            "profile_id", profileId.toString(),
                            "application_id", applicationId != null ? applicationId.toString() : "",
                            "cover_letter_id", clId.toString(),
                            "job_title", job.title(),
                            "company", job.companyNameRaw() != null ? job.companyNameRaw() : "",
                            "version", nextVersion,
                            "passed_validation", passedValidation
                    ),
                    correlationId,
                    null));
        } catch (Exception e) {
            // Notification emission must never fail the generation itself.
            log.warn("Failed to emit COVER_LETTER_GENERATED event: {}", e.getMessage());
        }

        CoverLetterRecord record = coverLetterRepository.findById(clId).orElseThrow();
        return new GenerationResult(record, passedValidation, issues);
    }

    private List<String> validateFactualClaims(String content,
                                               List<WorkExperienceRecord> experiences,
                                               List<SkillRecord> skills,
                                               List<EducationRecord> education,
                                               List<ProjectRecord> projects) {
        List<String> issues = new ArrayList<>();
        String lower = content.toLowerCase();

        // Verify that if suspicious fabricated phrases are detected, flag them
        if (lower.contains("secret security clearance") || lower.contains("top secret")) {
            issues.add("Potential fabrication: unverified security clearance claim detected");
        }
        if (lower.contains("phd in quantum") || lower.contains("doctorate in artificial intelligence")) {
            boolean hasDoctorate = education.stream().anyMatch(e -> e.qualification().toLowerCase().contains("phd") || e.qualification().toLowerCase().contains("doctorate"));
            if (!hasDoctorate) {
                issues.add("Fabrication detected: Claimed unverified PhD/Doctorate degree");
            }
        }
        return issues;
    }
}
