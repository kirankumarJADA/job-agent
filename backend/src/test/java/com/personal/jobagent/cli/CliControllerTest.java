package com.personal.jobagent.cli;

import com.personal.jobagent.ats.AtsAdapter;
import com.personal.jobagent.ats.AtsAdapterRegistry;
import com.personal.jobagent.ats.AtsKind;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.qa.ApplicationAnswerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class CliControllerTest {

    private CliController controller;
    private JobRepository jobRepository;
    private CoverLetterService coverLetterService;
    private ApplicationAnswerService answerService;
    private AtsAdapterRegistry atsAdapterRegistry;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        coverLetterService = Mockito.mock(CoverLetterService.class);
        answerService = Mockito.mock(ApplicationAnswerService.class);
        atsAdapterRegistry = Mockito.mock(AtsAdapterRegistry.class);
        controller = new CliController(jobRepository, coverLetterService, answerService, atsAdapterRegistry);
    }

    @Test
    void statusReturnsUp() {
        JobRepository.Page mockPage = new JobRepository.Page(List.of(), null);
        when(jobRepository.findJobs(any(), any(), any(Integer.class), any())).thenReturn(mockPage);
        when(atsAdapterRegistry.getAdapters()).thenReturn(List.of());

        ResponseEntity<?> response = controller.status();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("status")).isEqualTo("UP");
        assertThat(body).containsKey("mcp_endpoint");
    }

    @Test
    void listJobsReturnsEmptyWhenNoneFound() {
        JobRepository.Page mockPage = new JobRepository.Page(List.of(), null);
        when(jobRepository.findJobs(any(), any(), any(Integer.class), any())).thenReturn(mockPage);

        ResponseEntity<?> response = controller.listJobs(null, null, 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("count")).isEqualTo(0);
    }

    @Test
    void showJobReturns404WhenNotFound() {
        when(jobRepository.findById(any(UUID.class))).thenReturn(java.util.Optional.empty());

        ResponseEntity<?> response = controller.showJob(UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listAtsAdaptersReturnsKindNames() {
        AtsAdapter mockAdapter = Mockito.mock(AtsAdapter.class);
        when(mockAdapter.kind()).thenReturn(AtsKind.LEVER);
        when(atsAdapterRegistry.getAdapters()).thenReturn(List.of(mockAdapter));

        ResponseEntity<?> response = controller.listAtsAdapters();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> adapters = (List<Map<String, Object>>) body.get("adapters");
        assertThat(adapters.get(0).get("kind")).isEqualTo("LEVER");
    }
}