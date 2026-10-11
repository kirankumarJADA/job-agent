package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.JobDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8.2.5 on real PostgreSQL: cross-source identity, concurrent
 * ingestion, provenance, completeness-gated removal and the candidate/operator
 * API surfaces.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class CrossSourceDiscoveryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private JobDiscoveryService ingestion;
    @Autowired private BoardDiscoveryService boards;
    @Autowired private MockMvc mockMvc;

    private UUID ats;
    private UUID ats2;
    private UUID aggregator;
    private MockHttpSession session;

    @BeforeEach
    void setUp() throws Exception {
        ats = source("GREENHOUSE", "xs-acme-" + UUID.randomUUID().toString().substring(0, 8));
        ats2 = source("LEVER", "xs-acme-" + UUID.randomUUID().toString().substring(0, 8));
        aggregator = source("TSENTA", "jobs-" + UUID.randomUUID().toString().substring(0, 8));
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
    }

    private UUID source(String kind, String org) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, ?, ?, ?, '{"discovery": true}', 'DISCOVERY_ONLY')
                """, id, kind, org, org + " (" + kind + ")");
        return id;
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private JobDiscoveryService.IngestResult ingest(UUID source, String externalId, String title, String description,
                                                    String applicationUrl, String canonicalUrl) {
        return ingestion.ingestJob(new JobDiscoveryService.IngestJobCommand(source, externalId, null, "Acme Ltd", title,
                "London", null, null, null, null, null, null, null, null, description, List.of(),
                applicationUrl, canonicalUrl, null, null, null));
    }

    @Test
    void anAggregatorListingAndTheAtsPostingBecomeOneJobWithBothProvenances() {
        String n = unique();
        String gh = "https://boards.greenhouse.io/acme" + n + "/jobs/4" + n.hashCode();
        var first = ingest(ats, "gh-" + n, "Platform Engineer", "ATS text", gh, gh);
        var second = ingest(aggregator, "ts-" + n, "Platform Engineer", "Aggregator rewording", gh,
                "https://tsenta.com/jobs/acme-platform-engineer-" + UUID.randomUUID());

        assertThat(second.jobId()).isEqualTo(first.jobId());
        assertThat(second.crossSource()).isTrue();
        assertThat(second.matchedBy()).isEqualTo("ATS");
        Map<String, Object> job = jdbc.queryForMap(
                "select source_id, application_url, canonical_url, description_text, repost_count from jobs where id = ?", first.jobId());
        assertThat(job.get("source_id")).isEqualTo(ats);
        assertThat(job.get("application_url")).isEqualTo(gh);
        assertThat(String.valueOf(job.get("canonical_url"))).doesNotContain("tsenta");
        assertThat(job.get("description_text")).isEqualTo("ATS text");
        assertThat(job.get("repost_count")).isEqualTo(0);
        // Provenance per route is kept.
        assertThat(jdbc.queryForObject("select count(distinct source_id) from job_source_observations where job_id = ?",
                Integer.class, first.jobId())).isEqualTo(2);
    }

    @Test
    void aJobFirstSeenThroughTheAggregatorIsPromotedToItsAtsSource() {
        String n = unique();
        String gh = "https://boards.greenhouse.io/acme" + n + "/jobs/7" + Math.abs(n.hashCode());
        var viaAggregator = ingest(aggregator, "ts-" + n, "Data Engineer", "Aggregator text", gh,
                "https://tsenta.com/jobs/acme-data-engineer-" + UUID.randomUUID());
        var viaAts = ingest(ats, "gh-" + n, "Data Engineer", "Employer's own text", gh, gh);

        assertThat(viaAts.jobId()).isEqualTo(viaAggregator.jobId());
        Map<String, Object> job = jdbc.queryForMap(
                "select source_id, external_id, description_text, canonical_url from jobs where id = ?", viaAts.jobId());
        assertThat(job.get("source_id")).isEqualTo(ats);
        assertThat(job.get("external_id")).isEqualTo("gh-" + n);
        assertThat(job.get("description_text")).isEqualTo("Employer's own text");
        assertThat(job.get("canonical_url")).isEqualTo(gh);
    }

    @Test
    void twoDifferentRolesWithTheSameEmployerTitleAndLocationStaySeparate() {
        String n = unique();
        var a = ingest(ats, "gh-a-" + n, "Software Engineer", "Payments team",
                "https://boards.greenhouse.io/acme" + n + "/jobs/1", "https://boards.greenhouse.io/acme" + n + "/jobs/1");
        var b = ingest(ats2, "lv-b-" + n, "Software Engineer", "Identity team",
                "https://jobs.lever.co/acme" + n + "/" + UUID.randomUUID(), null);
        assertThat(a.jobId()).isNotEqualTo(b.jobId());
        assertThat(a.dedupKey()).isEqualTo(b.dedupKey()); // same old title key — and still two jobs
    }

    @Test
    void reIngestingIsIdempotent() {
        String n = unique();
        String url = "https://jobs.lever.co/acme" + n + "/" + UUID.randomUUID();
        var first = ingest(ats2, "lv-" + n, "Analyst", "Body", url, url);
        var again = ingest(ats2, "lv-" + n, "Analyst", "Body", url, url);
        assertThat(again.jobId()).isEqualTo(first.jobId());
        assertThat(again.action()).isEqualTo("TOUCHED");
        assertThat(jdbc.queryForObject("select count(*) from jobs where source_id = ? and external_id = ?",
                Integer.class, ats2, "lv-" + n)).isEqualTo(1);
    }

    @Test
    void concurrentIngestionOfTheSamePostingFromTwoSourcesCreatesExactlyOneJob() throws Exception {
        String n = unique();
        String gh = "https://boards.greenhouse.io/acme" + n + "/jobs/9" + Math.abs(n.hashCode());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> results = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            final int k = i;
            results.add(pool.submit(() -> {
                start.await();
                UUID src = k % 2 == 0 ? ats : aggregator;
                String ext = k % 2 == 0 ? "gh-" + n : "ts-" + n;
                return ingest(src, ext, "Concurrent Role", "Body", gh, k % 2 == 0 ? gh
                        : "https://tsenta.com/jobs/concurrent-" + n).jobId();
            }));
        }
        start.countDown();
        Set<UUID> ids = new java.util.HashSet<>();
        for (Future<UUID> f : results) ids.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(ids).hasSize(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from jobs where application_url = ?
                """, Integer.class, gh)).isEqualTo(1);
    }

    /** Scripted adapter so removal rules can be driven deterministically against the real database. */
    private static final class Scripted implements BoardAdapter {
        BoardAdapter.ListingResult listing;
        @Override public PlatformKind kind() { return PlatformKind.GEM; }
        @Override public Optional<BoardRef> recognize(URI url) { return Optional.empty(); }
        @Override public BoardRef parseIdentifier(String id) { return new BoardRef(kind(), id, Map.of()); }
        @Override public ListingResult list(BoardRef board, CrawlContext context) { return listing; }
        @Override public NormalizedJob detail(BoardRef b, ListingEntry e, CrawlContext c) { throw new UnsupportedOperationException(); }
    }

    private static BoardAdapter.ListingEntry posting(String board, String id) {
        String url = "https://jobs.gem.com/" + board + "/" + id;
        return BoardAdapter.ListingEntry.complete(NormalizedJob.builder(id, "Role " + id).description("Body " + id)
                .applicationUrl(url).sourceUrl(url).build());
    }

    private Object deletedAt(UUID gemSource, String externalId) {
        return jdbc.queryForObject("select deleted_at from jobs where source_id = ? and external_id = ?",
                Object.class, gemSource, externalId);
    }

    @Test
    void onlyACompleteListingRemovesAndOnlyWhenNoSourceStillListsTheJob() {
        String board = "xs" + unique();
        UUID gem = source("GEM", board);
        Scripted a = new Scripted();

        a.listing = BoardAdapter.ListingResult.complete(List.of(posting(board, "1"), posting(board, "2")), 1, null);
        boards.run(gem, a, board, "Acme", BoardDiscoveryService.Mode.MANUAL);
        assertThat(deletedAt(gem, "1")).isNull();
        assertThat(deletedAt(gem, "2")).isNull();

        // A FAILED partial listing without posting 2 removes nothing.
        a.listing = BoardAdapter.ListingResult.partial(List.of(posting(board, "1")), 1, FailureCategory.UPSTREAM_ERROR, "HTTP 503", null);
        boards.run(gem, a, board, "Acme", BoardDiscoveryService.Mode.MANUAL);
        assertThat(deletedAt(gem, "2")).isNull();

        // A complete listing without posting 2: the only source no longer lists it → removed.
        a.listing = BoardAdapter.ListingResult.complete(List.of(posting(board, "1")), 1, null);
        var report = boards.run(gem, a, board, "Acme", BoardDiscoveryService.Mode.MANUAL);
        assertThat(report.counts().get("removed")).isEqualTo(1);
        assertThat(deletedAt(gem, "2")).isNotNull();
        assertThat(deletedAt(gem, "1")).isNull();
        assertThat(jdbc.queryForObject("""
                select source_status from job_source_observations where source_id = ? and external_job_id = '2'
                """, String.class, gem)).isEqualTo("MISSING");

        // Listed again → restored automatically.
        a.listing = BoardAdapter.ListingResult.complete(List.of(posting(board, "1"), posting(board, "2")), 1, null);
        boards.run(gem, a, board, "Acme", BoardDiscoveryService.Mode.MANUAL);
        assertThat(deletedAt(gem, "2")).isNull();
    }

    @Test
    void aJobStillListedByAnotherSourceIsNotRemoved() {
        String board = "xs" + unique();
        UUID gem = source("GEM", board);
        Scripted a = new Scripted();
        a.listing = BoardAdapter.ListingResult.complete(List.of(posting(board, "keep")), 1, null);
        boards.run(gem, a, board, "Acme", BoardDiscoveryService.Mode.MANUAL);
        // The same posting is also listed by the aggregator.
        String url = "https://jobs.gem.com/" + board + "/keep";
        ingest(aggregator, "ts-keep-" + board, "Role keep", "Body", url, "https://tsenta.com/jobs/keep-" + board);

        a.listing = BoardAdapter.ListingResult.complete(List.of(), 1, null);
        boards.run(gem, a, board, "Acme", BoardDiscoveryService.Mode.MANUAL);
        assertThat(deletedAt(gem, "keep")).isNull();
    }

    @Test
    void runMetadataAndHealthAreRecordedOnTheSource() {
        String board = "xs" + unique();
        UUID gem = source("GEM", board);
        Scripted a = new Scripted();
        a.listing = BoardAdapter.ListingResult.partial(List.of(), 0, FailureCategory.TIMEOUT, "timed out", null);
        boards.run(gem, a, board, null, BoardDiscoveryService.Mode.SCHEDULED);
        Map<String, Object> row = jdbc.queryForMap("""
                select failure_streak, last_attempted_at, last_success_at, last_run::text as last_run, health::text as health
                from job_sources where id = ?
                """, gem);
        assertThat(row.get("failure_streak")).isEqualTo(1);
        assertThat(row.get("last_attempted_at")).isNotNull();
        assertThat(row.get("last_success_at")).isNull();
        assertThat(String.valueOf(row.get("last_run"))).contains("TIMEOUT", "duration_ms");
        assertThat(String.valueOf(row.get("health"))).contains("\"status\": \"failed\"");

        a.listing = BoardAdapter.ListingResult.complete(List.of(posting(board, "x")), 1, null);
        boards.run(gem, a, board, null, BoardDiscoveryService.Mode.SCHEDULED);
        Map<String, Object> ok = jdbc.queryForMap("select failure_streak, last_success_at from job_sources where id = ?", gem);
        assertThat(ok.get("failure_streak")).isEqualTo(0);
        assertThat(ok.get("last_success_at")).isNotNull();
    }

    @Test
    void theCandidateFeedNeverRevealsTheDiscoverySource() throws Exception {
        String n = unique();
        String term = "Quillfeather" + n;
        String employerUrl = "https://acme" + n + ".example.com/careers/" + n;
        ingest(aggregator, "ts-" + n, term + " Engineer", "Body", employerUrl, "https://tsenta.com/jobs/" + n);
        String body = mockMvc.perform(get("/api/v1/jobs?q=" + term).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains(term, "Acme Ltd", employerUrl)
                .doesNotContainIgnoringCase("tsenta").doesNotContain("source_name", "source_kind", "canonical_url", "external_id");
    }

    @Test
    void operatorEndpointsExposeCoverageAndImportIsSsrfSafe() throws Exception {
        String coverage = mockMvc.perform(get("/api/v1/sources/coverage").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(coverage).contains("\"WORKDAY\"", "\"TSENTA\"", "\"BLOCKED\"", "\"UNVERIFIED\"");
        mockMvc.perform(post("/api/v1/jobs/import-url").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/jobs/import-url").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://localhost/admin\"}"))
                .andExpect(status().isBadRequest());
        String brass = mockMvc.perform(post("/api/v1/jobs/import-url").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://sjobs.brassring.com/TGnewUI/Search/Home/Home?partnerid=25526&siteid=5032\"}"))
                .andReturn().getResponse().getContentAsString();
        // brassring.com may or may not resolve in CI; either it is explained as blocked or refused as unreachable.
        assertThat(brass).containsAnyOf("UNSUPPORTED", "cannot be fetched");
    }
}
