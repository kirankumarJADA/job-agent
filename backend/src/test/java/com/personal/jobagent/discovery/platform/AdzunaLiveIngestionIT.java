package com.personal.jobagent.discovery.platform;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE, opt-in (mirrors {@link ArbeitnowLiveIngestionIT}'s and
 * {@link FreeHireLiveIngestionIT}'s own convention:
 * {@code mvn verify -Dlive.discovery=true -Dit.test=AdzunaLiveIngestionIT
 * -Dapp.discovery.adzuna.app-id=... -Dapp.discovery.adzuna.app-key=...})
 * proof that a bounded real Adzuna discovery run reaches REAL PostgreSQL
 * through the exact same path the scheduler and {@code POST /discovery/run}
 * use — {@link BoardDiscoveryService#run(UUID, BoardDiscoveryService.Mode)} —
 * not a mocked fixture.
 *
 * <p>This environment has no registered Adzuna {@code app_id}/{@code
 * app_key} (see {@code AdzunaAdapter}'s own javadoc on why its fixture tests
 * are built from documentation, not a live capture). Rather than silently
 * skip only via {@code @EnabledIfSystemProperty} (which this class also
 * uses, for the same baseline opt-in as every other live-network test here),
 * this test additionally asserts — with {@link Assumptions#assumeTrue} — that
 * real credentials are actually configured before it runs, so a bare
 * {@code -Dlive.discovery=true} without credentials produces an honest
 * "assumption not met" skip rather than a confusing CONFIGURATION failure
 * that looks like a broken integration.
 */
@Tag("live")
@EnabledIfSystemProperty(named = "live.discovery", matches = "true",
        disabledReason = "Live Adzuna ingestion runs only with -Dlive.discovery=true")
@SpringBootTest(properties = {"app.discovery.scheduler-enabled=false", "app.discovery.max-pages=1"})
@Testcontainers
class AdzunaLiveIngestionIT {

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
    @Value("${app.discovery.adzuna.app-id:}") private String appId;
    @Value("${app.discovery.adzuna.app-key:}") private String appKey;

    private UUID seededSourceId() {
        return jdbc.queryForObject(
                "select id from job_sources where kind = 'ADZUNA' and org_identifier = 'gb'",
                UUID.class);
    }

    @Test
    void aBoundedRealRunPersistsGenuineAdzunaRecordsThroughTheRealPipelineWithoutDuplicationOnRepeat() {
        Assumptions.assumeTrue(!appId.isBlank() && !appKey.isBlank(),
                "no Adzuna app_id/app_key configured — set app.discovery.adzuna.app-id/app-key to run this test live");

        UUID sourceId = seededSourceId();
        assertThat(sourceId).as("V048 seed row for ADZUNA/gb must exist").isNotNull();

        BoardDiscoveryService.SourceRunReport first = boards.run(sourceId, BoardDiscoveryService.Mode.MANUAL);

        assertThat(first.ok()).as("first live run failed: " + first.failureCategory() + " " + first.failureDetail()).isTrue();
        assertThat(first.complete()).as("Adzuna listings must never be reported complete").isFalse();
        int listed = (Integer) first.counts().get("listed");
        int created = (Integer) first.counts().get("created");
        assertThat(listed).as("one real page returned no records").isPositive();
        assertThat(created).as("no new rows were persisted from a real page").isPositive();

        java.util.List<Map<String, Object>> rows = jdbc.queryForList("""
                select external_id, title, application_url, description_text from jobs
                where source_id = ? and deleted_at is null
                """, sourceId);
        assertThat(rows).as("persisted rows must match what was created this run").hasSizeGreaterThanOrEqualTo(created);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("title")).as("title must be real, never fabricated").isNotNull();
            assertThat(row.get("application_url")).as("Adzuna's own redirect URL must be preserved, not dropped").isNotNull();
            assertThat(String.valueOf(row.get("application_url"))).matches("https://www\\.adzuna\\.[a-z.]+/.*");
        }

        BoardDiscoveryService.SourceRunReport second = boards.run(sourceId, BoardDiscoveryService.Mode.MANUAL);
        assertThat(second.ok()).as("second live run failed: " + second.failureCategory()).isTrue();

        Integer countAfterSecond = jdbc.queryForObject(
                "select count(*) from jobs where source_id = ? and deleted_at is null", Integer.class, sourceId);
        Integer distinctExternalIdsAfterSecond = jdbc.queryForObject(
                "select count(distinct external_id) from jobs where source_id = ? and deleted_at is null",
                Integer.class, sourceId);
        assertThat(distinctExternalIdsAfterSecond).as("no duplicate external_id rows after the repeat run")
                .isEqualTo(countAfterSecond);

        System.out.println("LIVE Adzuna ingestion " + Map.of(
                "sourceId", sourceId, "firstRun", first.counts(), "secondRun", second.counts(),
                "rowsPersisted", countAfterSecond));
    }
}
