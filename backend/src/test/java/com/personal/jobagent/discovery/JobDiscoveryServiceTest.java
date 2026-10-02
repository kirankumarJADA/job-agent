package com.personal.jobagent.discovery;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.events.OutboxWriter;
import com.personal.jobagent.notifications.NotificationEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

class JobDiscoveryServiceTest {

    private JdbcTemplate jdbcTemplate;
    private OutboxWriter outboxWriter;
    private JobDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        outboxWriter = Mockito.mock(OutboxWriter.class);
        discoveryService = new JobDiscoveryService(jdbcTemplate, outboxWriter);
    }

    @Test
    void ingestsNewJobSuccessfully() {
        UUID sourceId = UuidV7.generate();
        UUID companyId = UuidV7.generate();

        doReturn(List.of()).when(jdbcTemplate).queryForList(anyString(), Mockito.<Object>any(), Mockito.<Object>any(), Mockito.<Object>any());

        JobDiscoveryService.IngestJobCommand cmd = new JobDiscoveryService.IngestJobCommand(
                sourceId,
                "gh-101",
                companyId,
                "Monzo",
                "Backend Engineer",
                "London",
                "London",
                "GB",
                "HYBRID",
                "FULL_TIME",
                "MID",
                70000,
                85000,
                "GBP",
                "Building resilient microservices.",
                List.of("Go", "Kubernetes"),
                "https://boards.greenhouse.io/monzo/jobs/101",
                "https://boards.greenhouse.io/monzo/jobs/101"
        );

        JobDiscoveryService.IngestResult result = discoveryService.ingestJob(cmd);

        assertThat(result.action()).isEqualTo("INSERTED");
        assertThat(result.dedupKey()).isNotEmpty();
        assertThat(result.contentHash()).isNotEmpty();
        // The pipeline trigger is atomically bound to the ingest:
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq("JOB"), eq(result.jobId()), eq(NotificationEvents.JOB_DISCOVERED),
                payload.capture(), any(UUID.class), eq(null));
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) payload.getValue();
        assertThat(payloadMap).containsEntry("action", "INSERTED");
    }

    @Test
    void updatesExistingJobOnContentChange() {
        UUID sourceId = UuidV7.generate();
        UUID existingId = UuidV7.generate();

        doReturn(List.of(
                Map.of("id", existingId, "content_hash", "old-hash-value", "repost_count", 0)
        )).when(jdbcTemplate).queryForList(anyString(), Mockito.<Object>any(), Mockito.<Object>any(), Mockito.<Object>any());

        JobDiscoveryService.IngestJobCommand cmd = new JobDiscoveryService.IngestJobCommand(
                sourceId,
                "gh-101",
                null,
                "Monzo",
                "Backend Engineer",
                "London",
                "London",
                "GB",
                "HYBRID",
                "FULL_TIME",
                "MID",
                75000,
                90000,
                "GBP",
                "Updated description with new requirements.",
                List.of("Go"),
                "https://boards.greenhouse.io/monzo/jobs/101",
                "https://boards.greenhouse.io/monzo/jobs/101"
        );

        JobDiscoveryService.IngestResult result = discoveryService.ingestJob(cmd);

        assertThat(result.action()).isEqualTo("UPDATED");
        assertThat(result.jobId()).isEqualTo(existingId);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq("JOB"), eq(existingId), eq(NotificationEvents.JOB_DISCOVERED),
                payload.capture(), any(UUID.class), eq(null));
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) payload.getValue();
        assertThat(payloadMap).containsEntry("action", "UPDATED");
    }
}