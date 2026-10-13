package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The limiter must stop hammering, not normal use: generous windows, strict
 * per-key isolation, and clean reset when a window rolls over.
 */
class RateLimitServiceTest {

    @Test
    void allowsUpToTheLimitThenRejectsWithARetryHint() {
        RateLimitService limiter = new RateLimitService(true, Map.of(
                "auth", new RateLimitService.Rule(3, 60_000L)));

        assertThat(limiter.checkAndRecord("auth", "ip:1.2.3.4")).isEqualTo(-1);
        assertThat(limiter.checkAndRecord("auth", "ip:1.2.3.4")).isEqualTo(-1);
        assertThat(limiter.checkAndRecord("auth", "ip:1.2.3.4")).isEqualTo(-1);
        long retryAfter = limiter.checkAndRecord("auth", "ip:1.2.3.4");
        assertThat(retryAfter).isBetween(1L, 60L);
    }

    @Test
    void keysAreIsolatedFromEachOther() {
        RateLimitService limiter = new RateLimitService(true, Map.of(
                "auth", new RateLimitService.Rule(1, 60_000L)));

        assertThat(limiter.checkAndRecord("auth", "ip:1.1.1.1")).isEqualTo(-1);
        assertThat(limiter.checkAndRecord("auth", "ip:1.1.1.1")).isPositive();
        // A different client is untouched by the first client's quota:
        assertThat(limiter.checkAndRecord("auth", "ip:2.2.2.2")).isEqualTo(-1);
        // So is a different bucket for the same client:
        assertThat(limiter.checkAndRecord("llm", "ip:1.1.1.1")).isEqualTo(-1);
    }

    @Test
    void aNewWindowResetsTheCount() {
        RateLimitService limiter = new RateLimitService(true, Map.of(
                "auth", new RateLimitService.Rule(1, 5L))); // 5 ms window

        assertThat(limiter.checkAndRecord("auth", "ip:1.1.1.1")).isEqualTo(-1);
        assertThat(limiter.checkAndRecord("auth", "ip:1.1.1.1")).isPositive();
        // Wait past the window boundary, then the client is allowed again.
        try { Thread.sleep(15); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        assertThat(limiter.checkAndRecord("auth", "ip:1.1.1.1")).isEqualTo(-1);
    }

    @Test
    void aDisabledLimiterNeverRejects() {
        RateLimitService limiter = new RateLimitService(false, Map.of(
                "auth", new RateLimitService.Rule(1, 60_000L)));

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.checkAndRecord("auth", "ip:1.1.1.1")).isEqualTo(-1);
        }
    }

    @Test
    void bucketSelectionCoversTheSensitiveRoutesAndNothingElse() {
        RateLimitService limiter = new RateLimitService(true, Map.of());

        assertThat(limiter.bucketFor("POST", "/api/v1/auth/login")).isEqualTo("auth");
        assertThat(limiter.bucketFor("POST", "/api/v1/auth/firebase/session")).isEqualTo("auth");
        assertThat(limiter.bucketFor("POST", "/api/v1/cover-letters/generate")).isEqualTo("llm");
        assertThat(limiter.bucketFor("POST", "/api/v1/applications/abc/re-prepare")).isEqualTo("llm");
        assertThat(limiter.bucketFor("POST", "/api/v1/discovery/run")).isEqualTo("discovery");
        assertThat(limiter.bucketFor("POST", "/api/v1/jobs/seed-uk")).isEqualTo("discovery");
        // The health check runs a live board fetch, so it shares the discovery quota:
        assertThat(limiter.bucketFor("POST", "/api/v1/sources/0b7e-id/health-check")).isEqualTo("discovery");
        assertThat(limiter.bucketFor("GET", "/api/v1/sources")).isNull();
        assertThat(limiter.bucketFor("POST", "/api/v1/sources/a/b/health-check")).isNull();
        // Normal reads and worker traffic stay unthrottled:
        assertThat(limiter.bucketFor("GET", "/api/v1/applications")).isNull();
        assertThat(limiter.bucketFor("POST", "/api/v1/automation/plans/claim-next")).isNull();
        assertThat(limiter.bucketFor("POST", "/api/v1/cover-letters/generate/extra")).isNull();
    }

    @Test
    void trackEvidenceWritesAndMessageIngestionShareTheTrackBucket() {
        RateLimitService limiter = new RateLimitService(true, Map.of());

        assertThat(limiter.bucketFor("POST", "/api/v1/emails/ingest")).isEqualTo("track");
        assertThat(limiter.bucketFor("POST", "/api/v1/applications/abc/track/reports")).isEqualTo("track");
        assertThat(limiter.bucketFor("POST", "/api/v1/applications/abc/track/receipts/def/review")).isEqualTo("track");
        assertThat(limiter.bucketFor("POST", "/api/v1/track/emails/abc/review")).isEqualTo("track");
        // Reads stay unthrottled, and nothing outside TRACK moved bucket.
        assertThat(limiter.bucketFor("GET", "/api/v1/applications/abc/track")).isNull();
        assertThat(limiter.bucketFor("GET", "/api/v1/track/reviews")).isNull();
        assertThat(limiter.bucketFor("POST", "/api/v1/applications/abc/status")).isNull();
        assertThat(limiter.bucketFor("POST", "/api/v1/applications/abc/track/reports/extra")).isNull();
    }

    @Test
    void theManagedMailWebhookIsBoundedPerClientWithoutAffectingOtherRoutes() {
        RateLimitService limiter = new RateLimitService(true);
        assertThat(limiter.bucketFor("POST", "/api/v1/managed-mail/inbound/mailgun")).isEqualTo("mail-inbound");
        assertThat(limiter.bucketFor("GET", "/api/v1/managed-mail/inbound/mailgun")).isNull();
        assertThat(limiter.bucketFor("POST", "/api/v1/managed-mail/address")).isNull();
        for (int i = 0; i < 120; i++) {
            assertThat(limiter.checkAndRecord("mail-inbound", "ip:198.51.100.7")).isEqualTo(-1);
        }
        assertThat(limiter.checkAndRecord("mail-inbound", "ip:198.51.100.7")).isPositive();
        assertThat(limiter.checkAndRecord("mail-inbound", "ip:198.51.100.8")).isEqualTo(-1);
    }

    @Test
    void theProductionGmailAuthorizationRuleBoundsOAuthStateCreation() {
        RateLimitService limiter = new RateLimitService(true);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.checkAndRecord("gmail-authorize", "profile-1")).isEqualTo(-1);
        }
        assertThat(limiter.checkAndRecord("gmail-authorize", "profile-1")).isPositive();
        assertThat(limiter.checkAndRecord("gmail-authorize", "profile-2")).isEqualTo(-1);
    }

    @Test
    void theProductionTrackRuleAllowsNormalUseAndStopsFlooding() {
        RateLimitService limiter = new RateLimitService(true);
        for (int i = 0; i < 30; i++) {
            assertThat(limiter.checkAndRecord("track", "profile-1")).isEqualTo(-1);
        }
        assertThat(limiter.checkAndRecord("track", "profile-1")).isPositive();
        // Per account: another candidate is unaffected.
        assertThat(limiter.checkAndRecord("track", "profile-2")).isEqualTo(-1);
    }
}
