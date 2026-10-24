package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE, opt-in (mirrors {@link PlatformLiveIT}'s own convention:
 * {@code mvn verify -Dlive.discovery=true -Dit.test=FreeHireLiveIngestionIT})
 * proof that a bounded real FreeHire discovery run reaches REAL PostgreSQL
 * through the exact same path the scheduler and {@code POST /discovery/run}
 * use — {@link BoardDiscoveryService#run(UUID, BoardDiscoveryService.Mode)} —
 * not a mocked fixture and not a disconnected script.
 *
 * <p>{@code app.discovery.max-pages=1} caps the run to exactly one real HTTP
 * page (the task's own "smallest useful page budget, initially one page"); no
 * full-catalogue attempt is made. The row run here is the real
 * {@code V044__freehire_discovery_source.sql} seed for the {@code backend}
 * category, not a duplicate test-only source.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "live.discovery", matches = "true",
        disabledReason = "Live FreeHire ingestion runs only with -Dlive.discovery=true")
@SpringBootTest(properties = {"app.discovery.scheduler-enabled=false", "app.discovery.max-pages=1"})
@Testcontainers
class FreeHireLiveIngestionIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BoardDiscoveryService boards;

    private UUID seededBackendSourceId() {
        return jdbc.queryForObject(
                "select id from job_sources where kind = 'FREEHIRE' and org_identifier = 'backend'",
                UUID.class);
    }

    @Test
    void aBoundedRealRunPersistsGenuineFreeHireRecordsThroughTheRealPipelineWithoutDuplicationOnRepeat() {
        UUID sourceId = seededBackendSourceId();
        assertThat(sourceId).as("V044 seed row for FREEHIRE/backend must exist").isNotNull();

        BoardDiscoveryService.SourceRunReport first = boards.run(sourceId, BoardDiscoveryService.Mode.MANUAL);

        assertThat(first.ok()).as("first live run failed: " + first.failureCategory() + " " + first.failureDetail()).isTrue();
        assertThat(first.complete()).as("FreeHire listings must never be reported complete").isFalse();
        int listed = (Integer) first.counts().get("listed");
        int created = (Integer) first.counts().get("created");
        assertThat(listed).as("one real page returned no records").isPositive();
        assertThat(created).as("no new rows were persisted from a real page").isPositive();
        assertThat((Integer) first.counts().get("pages")).isEqualTo(1);
        assertThat((Integer) first.counts().get("skipped_no_employer_url"))
                .as("every FreeHire record in this page lacked an application URL").isLessThan(listed);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select external_id, title, company_name_raw, application_url, canonical_url, description_text, source_id
                from jobs where source_id = ? and deleted_at is null
                """, sourceId);
        assertThat(rows).as("persisted rows must match what was created this run").hasSizeGreaterThanOrEqualTo(created);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("title")).as("title must be real, never fabricated").isNotNull();
            assertThat(String.valueOf(row.get("title"))).isNotBlank();
            assertThat(row.get("application_url")).as("the employer's own URL must be preserved, not dropped").isNotNull();
            assertThat(String.valueOf(row.get("application_url"))).startsWith("https://");
            // description is never fabricated when absent, but /agent/jobs/search
            // always returns the full body, so real rows should carry real text.
            assertThat(String.valueOf(row.get("description_text"))).isNotBlank();
        }

        int countAfterFirst = rows.size();
        Integer distinctExternalIdsAfterFirst = jdbc.queryForObject(
                "select count(distinct external_id) from jobs where source_id = ? and deleted_at is null",
                Integer.class, sourceId);
        assertThat(distinctExternalIdsAfterFirst).as("no duplicate external_id rows after the first run")
                .isEqualTo(countAfterFirst);

        // Repeat discovery of the SAME category/page: must not create duplicate
        // records, and a healthy repeat run must not incorrectly close anything
        // (FreeHire listings are always `partial`, so BoardDiscoveryService's own
        // "only a complete listing may markUnlisted" gate never fires for this
        // source at all — proven structurally by FreeHireAdapter always returning
        // ListingResult.partial(...), and exercised generically for every adapter
        // kind by CrossSourceDiscoveryIT#onlyACompleteListingRemovesAndOnlyWhenNoSourceStillListsTheJob).
        BoardDiscoveryService.SourceRunReport second = boards.run(sourceId, BoardDiscoveryService.Mode.MANUAL);
        assertThat(second.ok()).as("second live run failed: " + second.failureCategory()).isTrue();

        int countAfterSecond = jdbc.queryForObject(
                "select count(*) from jobs where source_id = ? and deleted_at is null", Integer.class, sourceId);
        Integer distinctExternalIdsAfterSecond = jdbc.queryForObject(
                "select count(distinct external_id) from jobs where source_id = ? and deleted_at is null",
                Integer.class, sourceId);
        assertThat(distinctExternalIdsAfterSecond).as("no duplicate external_id rows after the repeat run")
                .isEqualTo(countAfterSecond);
        // The second run's own page may have shifted (newest-first, real postings
        // arrive between calls), so growth is allowed, but every row from the
        // first run's set must still be present — nothing was dropped or
        // incorrectly closed by a second, independently-successful run.
        Integer stillPresentFromFirstRun = jdbc.queryForObject("""
                select count(*) from jobs where source_id = ? and deleted_at is null
                  and external_id in (%s)
                """.formatted(String.join(",", rows.stream().map(r -> "'" + r.get("external_id") + "'").toList())),
                Integer.class, sourceId);
        assertThat(stillPresentFromFirstRun).isEqualTo(countAfterFirst);

        System.out.println("LIVE FreeHire ingestion " + Map.of(
                "sourceId", sourceId, "firstRun", first.counts(), "secondRun", second.counts(),
                "rowsPersisted", countAfterSecond));
    }
}
