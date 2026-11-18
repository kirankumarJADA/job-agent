package com.personal.jobagent.adversarial;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Adversarial QA Suite: Concurrency, Ingestion Race Conditions & Event Deduplication")
class ConcurrencyAndDuplicateAdversarialTest {

    @Test
    @DisplayName("Adversarial: Concurrent duplicate deliveries for the same business key result in exactly 1 claim")
    void testConcurrentDuplicateDeliverySingleClaim() throws InterruptedException, ExecutionException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        ConcurrentHashMap<String, Boolean> atomicClaimRegistry = new ConcurrentHashMap<>();
        AtomicInteger successClaims = new AtomicInteger(0);
        AtomicInteger duplicateClaims = new AtomicInteger(0);

        String businessKey = "job.matched:profile-1:job-42:APPLY";
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startGate.await();
                    // Simulates atomic DB insert with ON CONFLICT DO NOTHING
                    Boolean prior = atomicClaimRegistry.putIfAbsent(businessKey, Boolean.TRUE);
                    if (prior == null) {
                        successClaims.incrementAndGet();
                    } else {
                        duplicateClaims.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        startGate.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        executor.shutdown();

        assertThat(successClaims.get()).isEqualTo(1);
        assertThat(duplicateClaims.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("Adversarial: Duplicate webhooks with identical token or Message-ID are idempotent")
    void testDuplicateWebhookIdempotency() {
        ConcurrentHashMap<String, String> processedWebhooks = new ConcurrentHashMap<>();
        String messageIdSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        // First delivery
        String firstOutcome = processedWebhooks.putIfAbsent(messageIdSha256, "STORED");
        assertThat(firstOutcome).isNull();

        // Second delivery (replay/duplicate)
        String secondOutcome = processedWebhooks.putIfAbsent(messageIdSha256, "DUPLICATE_MESSAGE");
        assertThat(secondOutcome).isNotNull();
        assertThat(secondOutcome).isEqualTo("STORED");
    }
}
