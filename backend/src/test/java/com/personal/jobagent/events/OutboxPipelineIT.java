package com.personal.jobagent.events;

import com.personal.jobagent.common.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * NOTE ON VERIFICATION STATUS: the raw SQL patterns underlying every step
 * here (outbox insert, pending-row select, publish, mark-published,
 * attempt-count increment, consumed_events idempotent upsert) were
 * independently verified with a plain JDBC program against live Postgres
 * during implementation. This Spring-wired end-to-end version has NOT been
 * executed (no Maven Central access in the authoring environment) — run
 * via `mvn verify`.
 *
 * Uses a synthetic test event type and a test-only EventHandler bean
 * (registered via @TestConfiguration) rather than a real business handler,
 * consistent with the architecture's own suggestion that P1-c "can be
 * validated with a synthetic/test event type" since the first real event
 * types aren't emitted until P1-g.
 */
@SpringBootTest
@Testcontainers
class OutboxPipelineIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Fast polling for the test so we don't wait 2s+ real time per assertion
        registry.add("app.events.dispatch-interval-ms", () -> "200");
    }

    @TestConfiguration
    static class TestHandlerConfig {
        @Bean
        RecordingTestHandler recordingTestHandler() {
            return new RecordingTestHandler();
        }
    }

    static class RecordingTestHandler implements EventHandler {
        final CopyOnWriteArrayList<Envelope> received = new CopyOnWriteArrayList<>();

        @Override
        public String consumerName() {
            return "test-recorder";
        }

        @Override
        public boolean supports(String eventType) {
            return "test.synthetic_event".equals(eventType);
        }

        @Override
        public void handle(Envelope envelope) {
            received.add(envelope);
        }
    }

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private RecordingTestHandler recordingTestHandler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void writtenEventIsDispatchedExactlyOnceAndMarkedPublished() {
        UUID aggregateId = UuidV7.generate();
        UUID correlationId = UuidV7.generate();

        // Simulates the real usage pattern: OutboxWriter is called from
        // within an existing @Transactional business method. Using
        // TransactionTemplate here to make that explicit rather than
        // relying on a repository method's own @Transactional annotation.
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        UUID eventId = txTemplate.execute(status -> outboxWriter.append(
                "TEST_AGGREGATE", aggregateId, "test.synthetic_event",
                Map.of("hello", "world"), correlationId, null));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(recordingTestHandler.received).hasSize(1);
            assertThat(recordingTestHandler.received.get(0).id()).isEqualTo(eventId);
            assertThat(recordingTestHandler.received.get(0).aggregateId()).isEqualTo(aggregateId);

            Boolean published = jdbcTemplate.queryForObject(
                    "select published_at is not null from outbox_events where id = ?",
                    Boolean.class, eventId);
            assertThat(published).isTrue();
        });

        // Give the dispatcher more cycles to prove it does NOT redeliver
        // an already-published/consumed event.
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        assertThat(recordingTestHandler.received).hasSize(1);
    }
}
