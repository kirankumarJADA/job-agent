package com.personal.jobagent.coverletter;

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

class CoverLetterServiceTest {

    private CoverLetterRepository coverLetterRepository;
    private JobRepository jobRepository;
    private ProfileRepository profileRepository;
    private ModelRouter modelRouter;
    private com.personal.jobagent.notifications.NotificationService notificationService;
    private CoverLetterService coverLetterService;

    @BeforeEach
    void setUp() {
        coverLetterRepository = Mockito.mock(CoverLetterRepository.class);
        jobRepository = Mockito.mock(JobRepository.class);
        profileRepository = Mockito.mock(ProfileRepository.class);
        modelRouter = Mockito.mock(ModelRouter.class);
        notificationService = Mockito.mock(com.personal.jobagent.notifications.NotificationService.class);

        coverLetterService = new CoverLetterService(
                coverLetterRepository,
                jobRepository,
                profileRepository,
                modelRouter,
                notificationService
        );
    }

    @Test
    void generatesCoverLetterSuccessfullyWithValidationPassing() {
        UUID profileId = UuidV7.generate();
        UUID jobId = UuidV7.generate();
        UUID coverLetterId = UuidV7.generate();

        JobRecord mockJob = new JobRecord(
                jobId, UuidV7.generate(), "ext-1", UuidV7.generate(),
                "Monzo", "Senior Backend Engineer", "London, UK", "London", "UK",
                "HYBRID", "FULL_TIME", "SENIOR", BigDecimal.valueOf(90000), BigDecimal.valueOf(110000),
                "GBP", "Looking for a Go/Java engineer to build distributed payment pipelines.",
                List.of("Java", "Go", "Distributed Systems"), "https://monzo.com/jobs/1",
                "https://monzo.com/jobs/1", Instant.now(), "DISCOVERED"
        );
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(mockJob));

        when(profileRepository.findExperiences(profileId)).thenReturn(List.of(
                new WorkExperienceRecord(UuidV7.generate(), profileId, "Deliveroo", "Software Engineer",
                        LocalDate.of(2021, 1, 1), LocalDate.of(2023, 12, 31), "London", List.of(), 1)
        ));
        when(profileRepository.findSkills(profileId)).thenReturn(List.of(
                new SkillRecord(UuidV7.generate(), profileId, "Java", "Languages", 5, BigDecimal.valueOf(5), null)
        ));
        when(profileRepository.findEducation(profileId)).thenReturn(List.of(
                new EducationRecord(UuidV7.generate(), profileId, "Imperial College", "BSc", "Computer Science", 2017, 2020, "1st")
        ));
        when(profileRepository.findProjects(profileId)).thenReturn(List.of(
                new ProjectRecord(UuidV7.generate(), profileId, "Payment Gateway", "A toy high throughput gateway", "https://github.com", List.of(), 1)
        ));

        when(coverLetterRepository.getNextVersion(eq(jobId), eq(profileId))).thenReturn(1);
        when(coverLetterRepository.insert(eq(profileId), eq(jobId), any(), eq(1), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(coverLetterId);

        String generatedText = "Dear Monzo Team,\n\nI am thrilled to apply for the Senior Backend Engineer role. At Deliveroo, I built resilient backend systems using Java.\n\nBest regards,\nCandidate";
        LlmCompletion completion = new LlmCompletion(generatedText, new LlmCompletion.TokenUsage(100, 150), 400, LlmCompletion.FinishReason.STOP);
        when(modelRouter.execute(eq(TaskType.COVER_LETTER), any(), any(Duration.class)))
                .thenReturn(new ModelRouter.ExecutionResult(completion, new RoutingTrace(TaskType.COVER_LETTER, "simulated", "PRIMARY", List.of())));

        CoverLetterRecord stored = new CoverLetterRecord(
                coverLetterId, profileId, jobId, null, 1, "Cover Letter v1 - Senior Backend Engineer",
                generatedText, Map.of("passed", true), true, Instant.now(), Instant.now()
        );
        when(coverLetterRepository.findById(coverLetterId)).thenReturn(Optional.of(stored));

        CoverLetterService.GenerationResult result = coverLetterService.generateCoverLetter(profileId, jobId, null);

        assertThat(result.passedValidation()).isTrue();
        assertThat(result.issues()).isEmpty();
        assertThat(result.coverLetter().title()).contains("Senior Backend Engineer");
        assertThat(result.coverLetter().bodyMarkdown()).contains("Dear Monzo Team");
    }

    @Test
    void detectsFabricationWhenUnverifiedClaimsPresent() {
        UUID profileId = UuidV7.generate();
        UUID jobId = UuidV7.generate();
        UUID coverLetterId = UuidV7.generate();

        JobRecord mockJob = new JobRecord(
                jobId, UuidV7.generate(), "ext-2", UuidV7.generate(),
                "Defense Org", "Lead Engineer", "London, UK", "London", "UK",
                "ONSITE", "FULL_TIME", "LEAD", BigDecimal.valueOf(100000), BigDecimal.valueOf(120000),
                "GBP", "Top secret clearance required.",
                List.of("Security"), "https://defense.org/jobs/2",
                "https://defense.org/jobs/2", Instant.now(), "DISCOVERED"
        );
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(mockJob));

        when(profileRepository.findExperiences(profileId)).thenReturn(List.of());
        when(profileRepository.findSkills(profileId)).thenReturn(List.of());
        when(profileRepository.findEducation(profileId)).thenReturn(List.of());
        when(profileRepository.findProjects(profileId)).thenReturn(List.of());

        when(coverLetterRepository.getNextVersion(eq(jobId), eq(profileId))).thenReturn(1);
        when(coverLetterRepository.insert(eq(profileId), eq(jobId), any(), eq(1), anyString(), anyString(), anyMap(), anyBoolean()))
                .thenReturn(coverLetterId);

        String hallucinatedText = "I have active top secret security clearance and a PhD in Quantum Computing.";
        LlmCompletion completion = new LlmCompletion(hallucinatedText, new LlmCompletion.TokenUsage(100, 150), 400, LlmCompletion.FinishReason.STOP);
        when(modelRouter.execute(eq(TaskType.COVER_LETTER), any(), any(Duration.class)))
                .thenReturn(new ModelRouter.ExecutionResult(completion, new RoutingTrace(TaskType.COVER_LETTER, "simulated", "PRIMARY", List.of())));

        CoverLetterRecord stored = new CoverLetterRecord(
                coverLetterId, profileId, jobId, null, 1, "Cover Letter v1",
                hallucinatedText, Map.of("passed", false), false, Instant.now(), Instant.now()
        );
        when(coverLetterRepository.findById(coverLetterId)).thenReturn(Optional.of(stored));

        CoverLetterService.GenerationResult result = coverLetterService.generateCoverLetter(profileId, jobId, null);

        assertThat(result.passedValidation()).isFalse();
        assertThat(result.issues()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(result.issues().toString()).contains("clearance");
    }
}
