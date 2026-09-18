package com.personal.jobagent.resume;

import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResumeAtsIntelligenceServiceTest {
    @Test
    void tailorsOnlyFromMasterEvidenceAndReplaysTheSameSnapshot() {
        UUID profileId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ProfileRecord profile = new ProfileRecord(profileId, UUID.randomUUID(), "Backend Engineer", null, "London",
                Map.of(), Map.of(), "Verified Java backend engineer.", Map.of(), 7, "READY");
        JobRecord job = new JobRecord(jobId, UUID.randomUUID(), "job-1", UUID.randomUUID(), "Example", "Backend Engineer",
                "London", "London", "GB", "HYBRID", "FULL_TIME", "MID", BigDecimal.ZERO, BigDecimal.ZERO,
                "GBP", "Build Java and Go services with Kubernetes.", List.of("Java", "Go", "Kubernetes"),
                "https://example.test/job", "https://example.test/job", Instant.now(), "DISCOVERED");
        SkillRecord java = new SkillRecord(UUID.randomUUID(), profileId, "Java", "Languages", 5, BigDecimal.valueOf(5), null);
        WorkExperienceRecord experience = new WorkExperienceRecord(UUID.randomUUID(), profileId, "Example Systems", "Backend Engineer",
                LocalDate.of(2021, 1, 1), null, "London", List.of(Map.of("text", "Built Java services")), 1);
        EducationRecord msc = new EducationRecord(UUID.randomUUID(), profileId, "University", "MSc Advanced Computer Science", "Computer Science", 2022, 2023, "Distinction");

        JobRepository jobs = mock(JobRepository.class);
        ProfileRepository profiles = mock(ProfileRepository.class);
        ResumeAtsRepository repository = mock(ResumeAtsRepository.class);
        NotificationService notifications = mock(NotificationService.class);
        CvArtifactService artifacts = new CvArtifactService();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(profiles.findById(profileId)).thenReturn(Optional.of(profile));
        when(profiles.findSkills(profileId)).thenReturn(List.of(java));
        when(profiles.findExperiences(profileId)).thenReturn(List.of(experience));
        when(profiles.findEducation(profileId)).thenReturn(List.of(msc));
        when(profiles.findProjects(profileId)).thenReturn(List.of());
        AtomicReference<ResumeAtsAnalysis> stored = new AtomicReference<>();
        when(repository.find(eq(profileId), eq(jobId), any(), any())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.ensureArtifact(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.insert(any(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            ResumeAtsAnalysis input = invocation.getArgument(0);
            ResumeAtsAnalysis result = new ResumeAtsAnalysis(input.id(), input.profileId(), input.jobId(), input.applicationId(), input.inputHash(),
                    input.role(), input.domain(), input.requiredSkills(), input.preferredSkills(), input.normalizedSkills(), input.verifiedEvidence(),
                    input.gaps(), input.atsReport(), UUID.randomUUID(), input.resumeMarkdown(), input.profileRevision(), input.profileSnapshotHash(), input.contentSha256());
            stored.set(result);
            return result;
        });

        ResumeAtsIntelligenceService service = new ResumeAtsIntelligenceService(jobs, profiles, repository, notifications, artifacts);
        ResumeAtsAnalysis first = service.tailor(profileId, jobId, null);
        ResumeAtsAnalysis replay = service.tailor(profileId, jobId, null);

        assertThat(first.profileRevision()).isEqualTo(7);
        assertThat(first.profileSnapshotHash()).isNotBlank();
        assertThat(first.verifiedEvidence()).anyMatch(x -> "EDUCATION".equals(x.get("source_type")) && x.get("claim").toString().contains("MSc"));
        assertThat(first.verifiedEvidence()).allMatch(x -> "USER_VERIFIED".equals(x.get("evidence_status")));
        assertThat(first.gaps()).contains("go", "kubernetes");
        assertThat(replay.cvVersionId()).isEqualTo(first.cvVersionId());
        verify(repository, times(1)).insert(any(), anyString(), anyBoolean());
    }
}
