package com.personal.jobagent.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Robin x Tsenta continuation: proves {@link QuotaService#tryReserve} is
 * actually safe under real concurrency — real threads, real PostgreSQL, real
 * row locking — not just that the SQL text looks right against a mocked
 * {@code JdbcTemplate} (see {@code QuotaServiceTest} for that narrower,
 * mocked coverage). This is the one property a unit test fundamentally
 * cannot prove: that two callers racing for the same profile's last unit of
 * quota can never both win.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"})
@Testcontainers
class QuotaConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;

    @Test
    void concurrentReservationsForTheSameProfileNeverExceedTheConfiguredLimit() throws Exception {
        UUID profile = UUID.randomUUID();
        int limit = 10;
        int attempts = 40; // far more concurrent attempts than the limit allows
        // Package-private test seam, same constructor QuotaServiceTest uses —
        // this test is in the same package deliberately so it can reach it,
        // rather than depending on whatever app.quota.application-daily is
        // configured to in application.yml.
        QuotaService service = new QuotaService(jdbc, 999, 999, limit, true);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> tasks = IntStream.range(0, attempts)
                    .<Callable<Boolean>>mapToObj(i -> () -> service.tryReserve(profile, "application_daily", 1).reserved())
                    .collect(Collectors.toList());
            List<Future<Boolean>> results = pool.invokeAll(tasks);

            long granted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) granted++;
            }

            assertThat(granted).as("exactly the configured limit must be granted, no more, under real concurrent load")
                    .isEqualTo(limit);

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select used, quota_limit from usage_quotas where profile_id = ? and quota_key = 'application_daily'",
                    profile);
            assertThat(rows).hasSize(1);
            assertThat(((Number) rows.get(0).get("used")).intValue())
                    .as("the persisted counter itself must never exceed the limit either")
                    .isEqualTo(limit);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void releaseAfterConcurrentReservationsCorrectlyFreesCapacityForFurtherReservations() throws Exception {
        UUID profile = UUID.randomUUID();
        int limit = 5;
        QuotaService service = new QuotaService(jdbc, 999, 999, limit, true);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            // Exhaust the limit.
            List<Callable<Boolean>> reserveAll = IntStream.range(0, limit)
                    .<Callable<Boolean>>mapToObj(i -> () -> service.tryReserve(profile, "application_daily", 1).reserved())
                    .collect(Collectors.toList());
            for (Future<Boolean> f : pool.invokeAll(reserveAll)) assertThat(f.get()).isTrue();
            assertThat(service.tryReserve(profile, "application_daily", 1).reserved())
                    .as("the limit is now exhausted").isFalse();

            // Release 3 of them concurrently.
            AtomicInteger releases = new AtomicInteger(3);
            List<Callable<Void>> releaseSome = IntStream.range(0, 3)
                    .<Callable<Void>>mapToObj(i -> () -> { service.release(profile, "application_daily", 1, LocalDate.now()); return null; })
                    .collect(Collectors.toList());
            for (Future<Void> f : pool.invokeAll(releaseSome)) f.get();

            // Exactly 3 units of fresh capacity should now be reservable — no
            // more, no less, proving release() and tryReserve() agree on the
            // same row under concurrency too.
            List<Callable<Boolean>> reserveAfterRelease = IntStream.range(0, 10)
                    .<Callable<Boolean>>mapToObj(i -> () -> service.tryReserve(profile, "application_daily", 1).reserved())
                    .collect(Collectors.toList());
            long grantedAfterRelease = pool.invokeAll(reserveAfterRelease).stream()
                    .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
                    .filter(Boolean::booleanValue).count();

            assertThat(grantedAfterRelease).isEqualTo(releases.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
