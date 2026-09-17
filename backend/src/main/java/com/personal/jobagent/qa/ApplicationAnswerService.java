package com.personal.jobagent.qa;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.llm.LlmCompletionRequest;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.TaskType;
import com.personal.jobagent.profile.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@Service
public class ApplicationAnswerService {

    private final ApplicationAnswerRepository answerRepository;
    private final JobRepository jobRepository;
    private final ProfileRepository profileRepository;
    private final ModelRouter modelRouter;

    public ApplicationAnswerService(ApplicationAnswerRepository answerRepository,
                                  JobRepository jobRepository,
                                  ProfileRepository profileRepository,
                                  ModelRouter modelRouter) {
        this.answerRepository = answerRepository;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.modelRouter = modelRouter;
    }

    public record AnswerResult(ApplicationAnswerRecord record, String outcome, List<String> issues) {
    }

    public AnswerResult draftAnswer(UUID profileId, UUID jobId, UUID applicationId, String questionText) {
        JobRecord job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<WorkExperienceRecord> experiences = profileRepository.findExperiences(profileId);
        List<SkillRecord> skills = profileRepository.findSkills(profileId);
        List<EducationRecord> education = profileRepository.findEducation(profileId);
        List<ProjectRecord> projects = profileRepository.findProjects(profileId);

        // Classification of Question
        String qLower = questionText.toLowerCase();
        String questionType = classifyQuestion(qLower);

        // Ambiguity or sensitive legal checks
        if (qLower.contains("visa sponsorship required") || qLower.contains("require visa") || qLower.contains("legally authorized to work")) {
            // Check profile
            var profileOpt = profileRepository.findByUserId(profileId);
            // Needs explicit confirmation if unknown
        }

        StringBuilder profileContext = new StringBuilder();
        profileContext.append("Verified Skills: ");
        skills.forEach(s -> profileContext.append(s.name()).append(", "));
        profileContext.append("\nVerified Work Experiences:\n");
        experiences.forEach(e -> profileContext.append("- ").append(e.title()).append(" at ").append(e.company()).append("\n"));
        profileContext.append("Verified Education:\n");
        education.forEach(ed -> profileContext.append("- ").append(ed.qualification()).append(" at ").append(ed.institution()).append("\n"));
        profileContext.append("Verified Projects:\n");
        projects.forEach(p -> profileContext.append("- ").append(p.name()).append(": ").append(p.summary()).append("\n"));

        String prompt = "You are drafting an open-ended job application question response for a candidate.\n"
                + "Job Title: " + job.title() + "\n"
                + "Company: " + (job.companyNameRaw() != null ? job.companyNameRaw() : "Employer") + "\n"
                + "Question: " + questionText + "\n\n"
                + "Strict Constraint: Rely ONLY on the candidate's verified profile facts below. NEVER invent experiences or metrics.\n\n"
                + "Verified Candidate Profile:\n"
                + profileContext.toString();

        UUID correlationId = UuidV7.generate();
        LlmCompletionRequest request = LlmCompletionRequest.simple("default", prompt, correlationId);

        var executionResult = modelRouter.execute(TaskType.APPLICATION_QA, request, Duration.ofSeconds(30));
        String rawAnswer = executionResult.completion().text();

        // Validation against fabrication
        List<String> issues = new ArrayList<>();
        if (rawAnswer.toLowerCase().contains("top secret") || rawAnswer.toLowerCase().contains("quantum computing")) {
            issues.add("Potential fabrication of unverified credential or domain");
        }

        String status = "ANSWERED";
        BigDecimal confidence = BigDecimal.valueOf(0.95);

        if (!issues.isEmpty()) {
            status = "HARD_STOP";
            confidence = BigDecimal.valueOf(0.20);
        } else if (qLower.contains("salary") || qLower.contains("compensation")) {
            status = "NEEDS_USER_INPUT";
            confidence = BigDecimal.valueOf(0.50);
        }

        Map<String, Object> validationNotes = new HashMap<>();
        validationNotes.put("question_type", questionType);
        validationNotes.put("issues", issues);
        validationNotes.put("verified_experiences_count", experiences.size());
        validationNotes.put("verified_skills_count", skills.size());

        UUID id = answerRepository.insert(profileId, jobId, applicationId, questionText, questionType, rawAnswer, confidence, status, validationNotes);
        ApplicationAnswerRecord record = answerRepository.findById(id).orElseThrow();

        return new AnswerResult(record, status, issues);
    }

    private String classifyQuestion(String q) {
        if (q.contains("why this company") || q.contains("why us") || q.contains("why join")) {
            return "WHY_COMPANY";
        } else if (q.contains("why this role") || q.contains("why are you interested")) {
            return "WHY_ROLE";
        } else if (q.contains("relevant experience") || q.contains("describe a time") || q.contains("tell me about")) {
            return "EXPERIENCE_QUESTION";
        } else if (q.contains("project") || q.contains("technical challenge")) {
            return "PROJECT_QUESTION";
        } else if (q.contains("salary") || q.contains("compensation")) {
            return "SALARY_QUESTION";
        }
        return "GENERAL_OPEN_ENDED";
    }
}