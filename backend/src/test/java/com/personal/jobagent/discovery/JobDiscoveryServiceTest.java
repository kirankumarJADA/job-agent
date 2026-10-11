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

    /**
     * Phase 8.2.5: an existing job is found through its identity keys (here the
     * source-scoped external id), not through company|title|location. The mock
     * answers the identity-key lookup the way PostgreSQL would.
     */
    @Test
    void updatesExistingJobOnContentChange() {
        UUID sourceId = UuidV7.generate();
        UUID existingId = UuidV7.generate();
        identityLookupReturns("src:" + sourceId + ":gh-101", existingId);
        jobRowIs(existingId, "old-hash-value", sourceId, "https://boards.greenhouse.io/monzo/jobs/101", null);

        JobDiscoveryService.IngestResult result = discoveryService.ingestJob(command(sourceId, "gh-101", "Monzo",
                "Backend Engineer", "Updated description with new requirements.",
                "https://boards.greenhouse.io/monzo/jobs/101"));

        assertThat(result.action()).isEqualTo("UPDATED");
        assertThat(result.jobId()).isEqualTo(existingId);
        assertThat(result.matchedBy()).isEqualTo("SRC");
        assertThat(result.crossSource()).isFalse();
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq("JOB"), eq(existingId), eq(NotificationEvents.JOB_DISCOVERED),
                payload.capture(), any(UUID.class), eq(null));
        @SuppressWarnings("unchecked")
        Map<String, Object> payloadMap = (Map<String, Object>) payload.getValue();
        assertThat(payloadMap).containsEntry("action", "UPDATED");
    }

    @Test
    void sameTitleAndEmployerWithoutStrongEvidenceIsANewJobNotAMerge() {
        UUID sourceId = UuidV7.generate();
        // No identity key matches (a different role that happens to share
        // company, title and location with an existing posting).
        JobDiscoveryService.IngestResult result = discoveryService.ingestJob(command(sourceId, "gh-202", "Monzo",
                "Backend Engineer", "A different team, a different role.", "https://boards.greenhouse.io/monzo/jobs/202"));

        assertThat(result.action()).isEqualTo("INSERTED");
        assertThat(result.matchedBy()).isNull();
        // The title-based dedup_key is never used as a lookup predicate any more.
        verify(jdbcTemplate, Mockito.never()).queryForList(contains("dedup_key = ?"), any(Object[].class));
    }

    @Test
    void aCrossSourceMatchOnThePlatformJobIdOnlyRefreshesAndNeverRewritesContent() {
        UUID atsSource = UuidV7.generate();
        UUID aggregatorSource = UuidV7.generate();
        UUID existingId = UuidV7.generate();
        identityLookupReturns("ats:greenhouse:monzo:101", existingId);
        jobRowIs(existingId, "ats-hash", atsSource, "https://boards.greenhouse.io/monzo/jobs/101", null);
        doReturn(List.of(Map.of("kind", "GREENHOUSE"))).when(jdbcTemplate)
                .queryForList(contains("select kind from job_sources"), eq(atsSource));

        JobDiscoveryService.IngestResult result = discoveryService.ingestJob(new JobDiscoveryService.IngestJobCommand(
                aggregatorSource, "tsenta-uuid", null, "Monzo", "Backend Engineer", "London", null, null, null, null,
                null, null, null, null, "The aggregator's rewording.", List.of(),
                "https://boards.greenhouse.io/monzo/jobs/101", "https://tsenta.com/jobs/monzo-backend-engineer-1",
                null, null, null));

        assertThat(result.jobId()).isEqualTo(existingId);
        assertThat(result.action()).isEqualTo("TOUCHED");
        assertThat(result.crossSource()).isTrue();
        assertThat(result.matchedBy()).isEqualTo("ATS");
        verify(jdbcTemplate, Mockito.never()).update(contains("repost_count = repost_count + 1"), any(Object[].class));
        verify(jdbcTemplate, Mockito.never()).update(contains("insert into jobs"), any(Object[].class));
    }

    @Test
    void anAggregatorUrlIsNeverStoredAsTheApplicationUrl() {
        UUID sourceId = UuidV7.generate();
        discoveryService.ingestJob(command(sourceId, "t-1", "Acme", "Analyst", "Body",
                "https://dashboard.tsenta.com/dashboard?apply=8d7f73a6-5a94-4d7a-84c3-4db0ce114498"));

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(contains("insert into jobs"), args.capture());
        // Parameter 19 is application_url (see the insert column list); the
        // aggregator URL may only survive as the provenance canonical_url (20).
        assertThat(args.getValue()[19]).isNull();
        assertThat(args.getValue()[20]).isEqualTo(
                "https://dashboard.tsenta.com/dashboard?apply=8d7f73a6-5a94-4d7a-84c3-4db0ce114498");
    }

    @Test
    void identityKeysAreLockedInAFixedOrderBeforeLookup() {
        UUID sourceId = UuidV7.generate();
        discoveryService.ingestJob(command(sourceId, "gh-9", "Monzo", "Engineer", "Body",
                "https://boards.greenhouse.io/monzo/jobs/9"));

        ArgumentCaptor<Object> locked = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate, Mockito.atLeast(2)).queryForList(contains("pg_advisory_xact_lock"), locked.capture());
        List<String> keys = locked.getAllValues().stream().map(String::valueOf).toList();
        assertThat(keys).isSorted().contains("ats:greenhouse:monzo:9", "src:" + sourceId + ":gh-9");
    }

    @Test
    void aBetterApplicationUrlFromAnotherSourceReplacesAWeakerOne() {
        UUID first = UuidV7.generate();
        UUID second = UuidV7.generate();
        UUID existingId = UuidV7.generate();
        identityLookupReturns("url:https://careers.acme.com/jobs/42", existingId);
        jobRowIs(existingId, "h", first, "https://careers.acme.com/jobs/42", "https://careers.acme.com/jobs/42");
        doReturn(List.of(Map.of("kind", "COMPANY_SITE"))).when(jdbcTemplate)
                .queryForList(contains("select kind from job_sources"), eq(first));

        discoveryService.ingestJob(new JobDiscoveryService.IngestJobCommand(second, "gh-42", null, "Acme", "Analyst",
                null, null, null, null, null, null, null, null, null, "Body", List.of(),
                "https://boards.greenhouse.io/acme/jobs/42", "https://careers.acme.com/jobs/42", null, null, null));

        verify(jdbcTemplate).update(contains("set application_url = ?"), eq("https://boards.greenhouse.io/acme/jobs/42"), eq(existingId));
    }

    private void identityLookupReturns(String key, UUID jobId) {
        doReturn(List.of(Map.of("key", key, "job_id", jobId))).when(jdbcTemplate)
                .queryForList(contains("from job_identity_keys where key in"), any(Object[].class));
    }

    private void jobRowIs(UUID id, String hash, UUID sourceId, String applicationUrl, String canonicalUrl) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("content_hash", hash);
        row.put("source_id", sourceId);
        row.put("application_url", applicationUrl);
        row.put("canonical_url", canonicalUrl);
        doReturn(List.of(row)).when(jdbcTemplate)
                .queryForList(contains("select content_hash, source_id, application_url, canonical_url from jobs"), eq(id));
    }

    private static JobDiscoveryService.IngestJobCommand command(UUID sourceId, String externalId, String company,
                                                               String title, String description, String url) {
        return new JobDiscoveryService.IngestJobCommand(sourceId, externalId, null, company, title, "London",
                "London", "GB", "HYBRID", "FULL_TIME", "MID", 75000, 90000, "GBP", description, List.of("Go"),
                url, url);
    }
}
