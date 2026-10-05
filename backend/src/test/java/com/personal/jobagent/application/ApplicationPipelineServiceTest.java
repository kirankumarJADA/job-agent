package com.personal.jobagent.application;

import com.personal.jobagent.jobs.HardFilterResult;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.notifications.NotificationEvents;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit contract for the pipeline's matching + creation stage: every profile
 * gets an idempotent match, APPLY decisions create exactly one application
 * (through the event handler consuming the outbox event), and failures in the
 * pipeline never propagate into the ingestion that already committed.
 */
class ApplicationPipelineServiceTest {

    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PROFILE_A = UUID.randomUUID();
    private static final UUID PROFILE_B = UUID.randomUUID();
    private static final UUID EXISTING_APP = UUID.randomUUID();

    private final HardFilterService hardFilterService = mock(HardFilterService.class);
    private final JobMatchService matchService = mock(JobMatchService.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PreferenceSetRepository preferenceSets = mock(PreferenceSetRepository.class);
    private final ProfileRepository profileRepository = mock(ProfileRepository.class);
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private ApplicationPipelineService service;

    @BeforeEach
    void setUp() {
        service = new ApplicationPipelineService(hardFilterService, matchService, jobRepository,
                preferenceSets, profileRepository, db, notifications, objectMapper, transactionManager);
        when(profileRepository.findAllIds()).thenReturn(List.of(PROFILE_A, PROFILE_B));
        when(preferenceSets.findActiveByProfileId(PROFILE_A)).thenReturn(Optional.of(preferences("CONTROLLED_AUTO", 60000L)));
        when(preferenceSets.findActiveByProfileId(PROFILE_B)).thenReturn(Optional.empty());

        // Provide a default job and make hard filters pass by default
        JobRecord defaultJob = new JobRecord(JOB, UUID.randomUUID(), "ext-1", null, "Acme", "Engineer",
                "London", null, null, null, null, null,
                null, null, null, "A role.", List.of(), "https://example.com", "https://example.com",
                java.time.Instant.now(), "DISCOVERED");
        when(jobRepository.findById(JOB)).thenReturn(Optional.of(defaultJob));
        when(hardFilterService.evaluate(any(JobRecord.class), any())).thenReturn(HardFilterResult.pass());
    }

    private static PreferenceSetRecord preferences(String mode, Long salaryMinGbp) {
        return new PreferenceSetRecord(UUID.randomUUID(), PROFILE_A, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                salaryMinGbp, "SHOW_ALL", List.of(), List.of(), mode, Map.of(), Map.of(), true);
    }

    private static JobMatchService.MatchResult match(int score, String recommendation) {
        return new JobMatchService.MatchResult(JOB, score, recommendation, 1.0, 0.5, 0.5, false);
    }

    @Test
    void matchesEveryProfileWithPreferenceDerivedConstraintsAndNoRemoteConstraint() {
        when(matchService.evaluateMatch(eq(JOB), eq(PROFILE_A), eq(60000L), isNull()))
                .thenReturn(match(80, "APPLY"));
        when(matchService.evaluateMatch(eq(JOB), eq(PROFILE_B), isNull(), isNull()))
                .thenReturn(match(20, "SKIP"));

        service.onJobIngested(JOB, "INSERTED");

        verify(matchService).evaluateMatch(JOB, PROFILE_A, 60000L, null);
        verify(matchService).evaluateMatch(JOB, PROFILE_B, null, null);
    }

    @Test
    void matchingFailureForOneProfileDoesNotBlockTheOthersOrThrow() {
        when(matchService.evaluateMatch(eq(JOB), eq(PROFILE_A), any(), any()))
                .thenThrow(new IllegalStateException("scorer down"));
        when(matchService.evaluateMatch(eq(JOB), eq(PROFILE_B), any(), any()))
                .thenReturn(match(20, "SKIP"));

        assertThatCode(() -> service.onJobIngested(JOB, "TOUCHED")).doesNotThrowAnyException();
        verify(matchService).evaluateMatch(JOB, PROFILE_B, null, null);
    }

    @Test
    void creationInsertsWithThePreferredModeAndEmitsTheCreationEventTransactionally() {
        when(db.query(contains("applications"), any(org.springframework.jdbc.core.RowMapper.class), eq(PROFILE_A), eq(JOB)))
                .thenReturn(List.of()); // no live application
        when(db.update(contains("insert into applications"), any(UUID.class), eq(JOB), eq(PROFILE_A), eq("CONTROLLED_AUTO")))
                .thenReturn(1);
        when(db.queryForList(contains("from jobs"), eq(JOB)))
                .thenReturn(List.of(Map.of("title", "Java Engineer", "company", "Monzo")));

        var created = service.createApplicationFromMatch(PROFILE_A, JOB);

        assertThat(created.created()).isTrue();
        assertThat(created.status()).isEqualTo("READY_TO_APPLY");
        // The creation timeline row embeds type/actor in the statement and
        // binds id, application_id and the payload.
        verify(db).update(contains("application_events"), any(UUID.class), eq(created.applicationId()),
                contains("auto-match"));
        ArgumentCaptor<NotificationService.NotificationCommand> event =
                ArgumentCaptor.forClass(NotificationService.NotificationCommand.class);
        verify(notifications).emit(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(NotificationEvents.APPLICATION_CREATED);
        assertThat(event.getValue().aggregateType()).isEqualTo("APPLICATION");
        assertThat(String.valueOf(event.getValue().payload())).contains(PROFILE_A.toString());
    }

    @Test
    void anExistingLiveApplicationIsReturnedWithoutRecreating() {
        when(db.query(contains("applications"), any(org.springframework.jdbc.core.RowMapper.class), eq(PROFILE_A), eq(JOB)))
                .thenReturn(List.of(EXISTING_APP));
        when(db.queryForObject(contains("select status"), eq(String.class), eq(EXISTING_APP)))
                .thenReturn("APPLICATION_STARTED");

        var created = service.createApplicationFromMatch(PROFILE_A, JOB);

        assertThat(created.applicationId()).isEqualTo(EXISTING_APP);
        assertThat(created.created()).isFalse();
        assertThat(created.status()).isEqualTo("APPLICATION_STARTED");
        verify(db, never()).update(contains("insert into applications"), any(), any(), any(), any());
        verify(notifications, never()).emit(any());
    }

    @Test
    void aMissingPreferenceFallsBackToTheAssistedMode() {
        when(db.query(contains("applications"), any(org.springframework.jdbc.core.RowMapper.class), eq(PROFILE_B), eq(JOB)))
                .thenReturn(List.of());
        when(db.update(contains("insert into applications"), any(UUID.class), eq(JOB), eq(PROFILE_B), eq("ASSISTED")))
                .thenReturn(1);
        when(db.queryForList(contains("from jobs"), eq(JOB)))
                .thenReturn(List.of(Map.of("title", "Java Engineer", "company", "Monzo")));

        service.createApplicationFromMatch(PROFILE_B, JOB);

        verify(db).update(contains("insert into applications"), any(UUID.class), eq(JOB), eq(PROFILE_B), eq("ASSISTED"));
    }

    @Test
    void aConcurrentCreationConflictResolvesToTheWinningRow() {
        when(db.query(contains("applications"), any(org.springframework.jdbc.core.RowMapper.class), eq(PROFILE_A), eq(JOB)))
                .thenReturn(List.of())            // fast path: nothing live yet
                .thenReturn(List.of(EXISTING_APP)); // post-conflict re-select
        when(db.update(contains("insert into applications"), any(UUID.class), eq(JOB), eq(PROFILE_A), eq("CONTROLLED_AUTO")))
                .thenReturn(0); // the other writer won
        when(db.queryForObject(contains("select status"), eq(String.class), eq(EXISTING_APP)))
                .thenReturn("READY_TO_APPLY");

        var created = service.createApplicationFromMatch(PROFILE_A, JOB);

        assertThat(created.applicationId()).isEqualTo(EXISTING_APP);
        assertThat(created.created()).isFalse();
        verify(notifications, never()).emit(any());
    }

    @Test
    void touchActionStillTriggersMatchingSoThePipelineSelfHeals() {
        when(matchService.evaluateMatch(any(), any(), anyDouble(), isNull()))
                .thenReturn(match(80, "APPLY"));

        service.onJobIngested(JOB, "TOUCHED");

        verify(matchService, times(2)).evaluateMatch(eq(JOB), any(), any(), any());
    }
}
