package com.personal.jobagent.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.apply.ApplicationIdentityService;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HardEligibilityAdversarialTest {

    private final HardFilterService hardFilterService = mock(HardFilterService.class);
    private final JobMatchService jobMatchService = mock(JobMatchService.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final PreferenceSetRepository preferenceSets = mock(PreferenceSetRepository.class);
    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationIdentityService identityService = mock(ApplicationIdentityService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

    private ApplicationPipelineService pipeline;

    private static final UUID PROFILE_A = UUID.randomUUID();
    private static final UUID PROFILE_B = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        pipeline = new ApplicationPipelineService(
                hardFilterService,
                jobMatchService,
                jobRepository,
                preferenceSets,
                profileRepository,
                db,
                notifications,
                objectMapper,
                identityService,
                transactionManager
        );
    }

    private JobRecord sampleJob() {
        return new JobRecord(
                JOB_ID, UUID.randomUUID(), "ext-1", UUID.randomUUID(), "Acme Corp",
                "Software Engineer", "London, UK", "London", "UK", "HYBRID",
                "FULL_TIME", "MID", new java.math.BigDecimal("60000"), new java.math.BigDecimal("80000"),
                "GBP", "Software Engineer at Acme", List.of("Java"),
                "http://example.com/job", "http://example.com/job", java.time.Instant.now(), "DISCOVERED"
        );
    }

    @Test
    void createApplicationFromMatchRejectsHardExcludedJobAndNeverInsertsApplication() {
        JobRecord job = sampleJob();
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(preferenceSets.findActiveByProfileId(PROFILE_A)).thenReturn(Optional.of(mock(PreferenceSetRecord.class)));

        // Hard filter rejects (e.g. location or visa mismatch)
        HardFilterResult rejection = HardFilterResult.reject(List.of(
                new HardFilterResult.FilterReason("LOCATION", "London", "Tokyo", "Candidate requested London only")
        ));
        when(hardFilterService.evaluate(eq(job), any())).thenReturn(rejection);

        ApplicationPipelineService.CreatedApplication result = pipeline.createApplicationFromMatch(PROFILE_A, JOB_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.applicationId()).isNull();
        assertThat(result.status()).isEqualTo("EXCLUDED");

        // Verify state is stored per-profile in job_matches with FILTERED_OUT
        verify(db).update(contains("insert into job_matches"), eq(PROFILE_A), eq(JOB_ID), anyString());

        // Verify shared jobs row was NOT mutated
        verify(db, never()).update(startsWith("update jobs set status = 'FILTERED_OUT'"), any(Object[].class));
    }

    @Test
    void onJobIngestedNeverMutatesSharedCatalogueRowWhenOneCandidateIsExcluded() {
        JobRecord job = sampleJob();
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(profileRepository.findAllIds()).thenReturn(List.of(PROFILE_A, PROFILE_B));

        // Candidate A fails hard filter; Candidate B passes hard filter
        HardFilterResult rejectA = HardFilterResult.reject(List.of(
                new HardFilterResult.FilterReason("SALARY", ">= 100000", "80000", "Salary below minimum")
        ));
        HardFilterResult passB = HardFilterResult.pass();

        when(hardFilterService.evaluate(eq(job), any()))
                .thenReturn(rejectA)
                .thenReturn(passB);

        when(jobMatchService.evaluateMatch(eq(JOB_ID), eq(PROFILE_B), any()))
                .thenReturn(new JobMatchService.MatchResult(JOB_ID, 85, "APPLY", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, true));

        pipeline.onJobIngested(JOB_ID, "INSERTED");

        // Profile A gets candidate-scoped rejection in job_matches
        verify(db).update(contains("insert into job_matches"), eq(PROFILE_A), eq(JOB_ID), anyString());

        // Profile B was evaluated and scored
        verify(jobMatchService).evaluateMatch(eq(JOB_ID), eq(PROFILE_B), any());

        // The shared jobs row was NEVER updated with status = 'FILTERED_OUT'
        verify(db, never()).update(startsWith("update jobs set filter_reasons"), any(Object[].class));
    }
}
