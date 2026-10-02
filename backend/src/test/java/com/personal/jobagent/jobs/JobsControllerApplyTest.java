package com.personal.jobagent.jobs;

import com.personal.jobagent.application.ApplicationPipelineService;
import com.personal.jobagent.common.ApiError;
import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The explicit human decision on a REVIEW match: one owned application per
 * (profile, job), idempotent under double clicks and replays, and a foreign
 * or profile-less caller never reaches the pipeline.
 */
class JobsControllerApplyTest {

    private static final UUID JOB = UUID.randomUUID();
    private static final UUID PROFILE = UUID.randomUUID();
    private static final UUID APPLICATION = UUID.randomUUID();

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final OwnerContext ownerContext = mock(OwnerContext.class);
    private final ApplicationPipelineService pipeline = mock(ApplicationPipelineService.class);
    private JobsController controller;

    @BeforeEach
    void setUp() {
        controller = new JobsController(jobRepository, null, ownerContext, pipeline);
        when(ownerContext.profileIdOrNull()).thenReturn(PROFILE);
    }

    @Test
    void anExplicitApplyCreatesOneOwnedApplication() {
        when(pipeline.createApplicationFromMatch(PROFILE, JOB)).thenReturn(
                new ApplicationPipelineService.CreatedApplication(APPLICATION, true, "READY_TO_APPLY"));

        ResponseEntity<?> response = controller.applyToJob(JOB);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of(
                "application_id", APPLICATION.toString(), "created", true, "status", "READY_TO_APPLY"));
    }

    @Test
    void aSecondApplyReplaysTheExistingApplicationInsteadOfDuplicating() {
        when(pipeline.createApplicationFromMatch(PROFILE, JOB)).thenReturn(
                new ApplicationPipelineService.CreatedApplication(APPLICATION, false, "READY_TO_APPLY"));

        ResponseEntity<?> response = controller.applyToJob(JOB);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((java.util.Map<?, ?>) response.getBody()).get("created")).isEqualTo(false);
    }

    @Test
    void aCallerWithoutAProfileIsRejectedBeforeTouchingThePipeline() {
        when(ownerContext.profileIdOrNull()).thenReturn(null);

        assertThat(controller.applyToJob(JOB).getStatusCode().value()).isEqualTo(400);
        org.mockito.Mockito.verifyNoInteractions(pipeline);
    }

    @Test
    void anUnknownJobIsReportedAsNotFound() {
        when(pipeline.createApplicationFromMatch(eq(PROFILE), any()))
                .thenThrow(new IllegalArgumentException("Job not found: " + JOB));

        assertThat(controller.applyToJob(JOB).getStatusCode().value()).isEqualTo(404);
    }
}
