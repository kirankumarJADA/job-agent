package com.personal.jobagent.qa;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.llm.*;
import com.personal.jobagent.profile.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class ApplicationAnswerServiceTest {

    private ApplicationAnswerRepository answerRepository;
    private JobRepository jobRepository;
    private ProfileRepository profileRepository;
    private ModelRouter modelRouter;
    private ApplicationAnswerService answerService;

    @BeforeEach
    void setUp() {
        answerRepository = Mockito.mock(ApplicationAnswerRepository.class);
        jobRepository = Mockito.mock(JobRepository.class);
        profileRepository = Mockito.mock(ProfileRepository.class);
        modelRouter = Mockito.mock(ModelRouter.class);

        answerService = new ApplicationAnswerService(
                answerRepository,
                jobRepository,
                profileRepository,
                modelRouter
        );
    }

    @Test
    void answersWhyRoleQuestionWithVerifiedFacts() {
        UUID profileId = UuidV7.generate();
        UUID jobId = UuidV7.generate();
        UUID answerId = UuidV7.generate();

        JobRecord mockJob = new JobRecord(
                jobId, UuidV7.generate(), "ext-1", UuidV7.generate(),
                "Skyscanner", "Senior Platform Engineer", "Edinburgh, UK", "Edinburgh", "UK",
                "REMOTE", "FULL_TIME", "SENIOR", BigDecimal.valueOf(85000), BigDecimal.valueOf(95000),
                "GBP", "Scaling our global travel search platform.",
                List.of("Go", "Kubernetes", "AWS"), "https://skyscanner.net/jobs/1",
                "https://skyscanner.net/jobs/1", Instant.now(), "DISCOVERED"
        );
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(mockJob));

        when(profileRepository.findExperiences(profileId)).thenReturn(List.of(
                new WorkExperienceRecord(UuidV7.generate(), profileId, "Starling Bank", "Site Reliability Engineer",
                        LocalDate.of(2022, 1, 1), LocalDate.of(2024, 1, 1), "London", List.of(), 1)
        ));
        when(profileRepository.findSkills(profileId)).thenReturn(List.of(
                new SkillRecord(UuidV7.generate(), profileId, "Kubernetes", "DevOps", 5, BigDecimal.valueOf(4), null)
        ));
        when(profileRepository.findEducation(profileId)).thenReturn(List.of());
        when(profileRepository.findProjects(profileId)).thenReturn(List.of());

        when(answerRepository.insert(eq(profileId), eq(jobId), any(), anyString(), anyString(), anyString(), any(), anyString(), anyMap()))
                .thenReturn(answerId);

        String drafted = "I want to join Skyscanner because my background at Starling Bank managing high-scale Kubernetes clusters directly mirrors your platform goals.";
        LlmCompletion completion = new LlmCompletion(drafted, new LlmCompletion.TokenUsage(50, 80), 200, LlmCompletion.FinishReason.STOP);
        when(modelRouter.execute(eq(TaskType.APPLICATION_QA), any(), any(Duration.class)))
                .thenReturn(new ModelRouter.ExecutionResult(completion, new RoutingTrace(TaskType.APPLICATION_QA, "simulated", "PRIMARY", List.of())));

        ApplicationAnswerRecord record = new ApplicationAnswerRecord(
                answerId, profileId, jobId, null, "Why this role?", "WHY_ROLE",
                drafted, BigDecimal.valueOf(0.95), "ANSWERED", Map.of(), Instant.now(), Instant.now()
        );
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(record));

        ApplicationAnswerService.AnswerResult result = answerService.draftAnswer(profileId, jobId, null, "Why are you interested in this role?");

        assertThat(result.outcome()).isEqualTo("ANSWERED");
        assertThat(result.issues()).isEmpty();
        assertThat(result.record().answerText()).contains("Starling Bank");
    }

    @Test
    void flagsNeedsUserInputOnSalaryQuestion() {
        UUID profileId = UuidV7.generate();
        UUID jobId = UuidV7.generate();
        UUID answerId = UuidV7.generate();

        JobRecord mockJob = new JobRecord(
                jobId, UuidV7.generate(), "ext-1", UuidV7.generate(),
                "Revolut", "Staff Engineer", "London, UK", "London", "UK",
                "HYBRID", "FULL_TIME", "STAFF", BigDecimal.valueOf(120000), BigDecimal.valueOf(140000),
                "GBP", "Core payments engine.",
                List.of("Java"), "https://revolut.com/jobs/1",
                "https://revolut.com/jobs/1", Instant.now(), "DISCOVERED"
        );
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(mockJob));

        when(answerRepository.insert(eq(profileId), eq(jobId), any(), anyString(), anyString(), anyString(), any(), anyString(), anyMap()))
                .thenReturn(answerId);

        LlmCompletion completion = new LlmCompletion("My expected compensation is negotiable.", new LlmCompletion.TokenUsage(20, 20), 100, LlmCompletion.FinishReason.STOP);
        when(modelRouter.execute(eq(TaskType.APPLICATION_QA), any(), any(Duration.class)))
                .thenReturn(new ModelRouter.ExecutionResult(completion, new RoutingTrace(TaskType.APPLICATION_QA, "simulated", "PRIMARY", List.of())));

        ApplicationAnswerRecord record = new ApplicationAnswerRecord(
                answerId, profileId, jobId, null, "What is your expected salary?", "SALARY_QUESTION",
                "My expected compensation is negotiable.", BigDecimal.valueOf(0.50), "NEEDS_USER_INPUT", Map.of(), Instant.now(), Instant.now()
        );
        when(answerRepository.findById(answerId)).thenReturn(Optional.of(record));

        ApplicationAnswerService.AnswerResult result = answerService.draftAnswer(profileId, jobId, null, "What is your expected salary?");

        assertThat(result.outcome()).isEqualTo("NEEDS_USER_INPUT");
    }
}