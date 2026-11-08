package com.personal.jobagent.events;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.JobDiscoveryService;
import org.junit.jupiter.api.Test;
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
 * The first start on Oracle, with the migration switches set as planned
 * (plan §10.6, §10.7): the full application context runs, the schedulers fire
 * every 200 ms, and nothing in the outbox moves: no event is published, retried
 * or dead-lettered, no consumer runs, and no stale job is archived.
 */
@SpringBootTest(properties = {
        "app.discovery.scheduler-enabled=false",
        "app.events.dispatch-enabled=false",
        "app.events.dispatch-interval-ms=200",
        "app.events.reconcile-interval-ms=200",
        "app.discovery.maintenance-enabled=false"})
@Testcontainers
class OutboxDispatchPausedIT {

    private static final UUID SOURCE = UUID.fromString("00000000-0000-7000-8000-0000000d0a01");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private OutboxWriter writer;
    @Autowired private OutboxProcessor processor;
    @Autowired private JobDiscoveryService discovery;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void withDispatchPausedNothingInTheOutboxMoves() throws Exception {
        assertThat(processor.isDispatchEnabled()).isFalse();
        UUID aggregate = UuidV7.generate();
        UUID pending = writer.append("JOB", aggregate, "job.discovered",
                Map.of("job_id", aggregate.toString(), "action", "TOUCHED", "source_id", ""), UuidV7.generate(), null);
        UUID deadLetter = writer.append("JOB", aggregate, "job.discovered",
                Map.of("job_id", aggregate.toString(), "action", "TOUCHED", "source_id", ""), UuidV7.generate(), null);
        jdbc.update("update outbox_events set attempt_count = 5, last_error = 'earlier failure' where id = ?", deadLetter);
        List<Map<String, Object>> before = rows(pending, deadLetter);

        Thread.sleep(2500); // about a dozen ticks of both schedulers
        processor.dispatchPending();
        processor.reconcileDlq();

        assertThat(rows(pending, deadLetter)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where published_at is not null", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consumed_events where event_id in (?, ?)", Long.class,
                pending, deadLetter)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where dedup_key = ?", Long.class,
                "outbox-dlq:" + deadLetter)).isZero();
    }

    @Test
    void withMaintenancePausedAStaleJobIsNotArchived() {
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'paused-it', 'Paused IT', '{}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, SOURCE);
        UUID job = discovery.ingestJob(new JobDiscoveryService.IngestJobCommand(
                SOURCE, "paused-1", null, "Paused Corp", "Platform Engineer", "London", "London", "GB", "HYBRID",
                "FULL_TIME", "MID", 60000, 80000, "GBP", "Build services.", List.of("Java"),
                "https://boards.greenhouse.io/paused/jobs/1", "https://boards.greenhouse.io/paused/jobs/1", null)).jobId();
        jdbc.update("update jobs set last_seen_at = now() - interval '40 days', status = 'DISCOVERED' where id = ?", job);

        discovery.scheduledDiscoveryMaintenance();

        assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, job)).isEqualTo("DISCOVERED");
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_events
                 where aggregate_id = ? and event_type = 'job.discovered' and published_at is null
                """, Long.class, job)).as("its job.discovered event stays pending").isEqualTo(1L);
    }

    private List<Map<String, Object>> rows(UUID a, UUID b) {
        return jdbc.queryForList("""
                select id, sequence_id, published_at, attempt_count, last_error
                  from outbox_events where id in (?, ?) order by sequence_id
                """, a, b);
    }
}
