package com.personal.jobagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory fixed-window rate limiter for the endpoints where abuse is
 * expensive or dangerous: credential brute force (login), and the LLM-backed
 * or discovery-controlling actions a single account could otherwise hammer.
 *
 * <p>This is deliberately simple and JVM-local, matching the deployment
 * reality (one Render backend instance, in-JVM sessions). It is NOT a
 * distributed limiter — if the backend ever scales horizontally, each
 * instance enforces its own window, which still bounds per-instance abuse but
 * must be revisited alongside external session storage.
 *
 * <p>Windows are generous by design: a normal candidate issues single-digit
 * requests per minute on every bucket. The limiter exists to stop hammering,
 * not to shape normal use.
 */
@Component
public class RateLimitService {

    /** One named bucket: max requests per window. */
    public record Rule(int maxRequests, long windowMillis) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final Map<String, Rule> rules;

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitService(@Value("${app.rate-limit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
        this.rules = Map.of(
                "auth", new Rule(20, 60_000L),      // login / firebase session per IP
                "llm", new Rule(30, 60_000L),       // generation + re-prepare per account
                "discovery", new Rule(10, 60_000L), // run / ingest / seed per account
                "track", new Rule(30, 60_000L),     // TRACK evidence writes and message ingestion per account
                "gmail-sync", new Rule(6, 3_600_000L), // manual Gmail mailbox sync per account (hourly window)
                "gmail-authorize", new Rule(10, 3_600_000L), // OAuth initiation per account (hourly window)
                "mail-inbound", new Rule(120, 60_000L) // Mailgun inbound webhook per client address
        );
    }

    /** Test seam: isolated limiter with chosen rules. */
    RateLimitService(boolean enabled, Map<String, Rule> rules) {
        this.enabled = enabled;
        this.rules = Map.copyOf(rules);
        this.windows.clear();
    }

    /**
     * Records one request against {@code bucket:key} and returns the seconds
     * after which the caller may retry, or -1 when the request is allowed.
     */
    public long checkAndRecord(String bucket, String key) {
        if (!enabled) return -1;
        Rule rule = rules.get(bucket);
        if (rule == null) return -1;
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(bucket + ":" + key,
                k -> new Window(now / rule.windowMillis(), new AtomicLong(0)));
        long currentWindow = now / rule.windowMillis();
        if (!window.resetIfStale(currentWindow)) {
            long retryAfterSeconds = ((currentWindow + 1) * rule.windowMillis() - now + 999) / 1000;
            return Math.max(1, retryAfterSeconds);
        }
        long used = window.count.incrementAndGet();
        if (used > rule.maxRequests()) {
            long retryAfterSeconds = ((currentWindow + 1) * rule.windowMillis() - now + 999) / 1000;
            return Math.max(1, retryAfterSeconds);
        }
        return -1;
    }

    /** Chooses the bucket for a request path, or null when unthrottled. */
    public String bucketFor(String method, String path) {
        if (!"POST".equals(method)) return null;
        if (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/firebase/session")) return "auth";
        if (path.equals("/api/v1/cover-letters/generate")
                || path.equals("/api/v1/resume-intelligence/tailor")
                || path.equals("/api/v1/application-answers/draft")) return "llm";
        if (path.matches("/api/v1/applications/[^/]+/re-prepare")) return "llm";
        if (path.equals("/api/v1/discovery/run")
                || path.equals("/api/v1/discovery/run-for-me")
                || path.equals("/api/v1/discovery/ingest")
                || path.equals("/api/v1/discovery/linkedin/search")
                || path.equals("/api/v1/discovery/maintenance")
                || path.equals("/api/v1/jobs/seed-uk")
                // URL import may resolve a page and run a board fetch (Phase 8.2.5).
                || path.equals("/api/v1/jobs/import-url")) return "discovery";
        // A source health check performs a live board fetch and ingestion for
        // Greenhouse/Ashby sources (SourcesController), and the FIND page uses
        // it as its Discover action, so it shares the discovery quota rather
        // than being an unthrottled way to hit external boards.
        if (path.matches("/api/v1/sources/[^/]+/health-check")) return "discovery";
        // Phase 8.3: employer-message ingestion and TRACK writes (reports, evidence
        // and message reviews) are cheap but write durable, audited history, so a
        // single account cannot flood them.
        if (path.equals("/api/v1/emails/ingest")
                || path.matches("/api/v1/applications/[^/]+/track/reports")
                || path.matches("/api/v1/applications/[^/]+/track/receipts/[^/]+/review")
                || path.matches("/api/v1/track/emails/[^/]+/review")) return "track";
        // Phase 8.4: manual Gmail mailbox sync performs provider API calls with
        // the stored grant, so a single account cannot hammer Google or Robin.
        if (path.equals("/api/v1/integrations/gmail/sync")) return "gmail-sync";
        // Phase 8.5: the managed-mail webhook is public (signature-authenticated),
        // so unauthenticated floods are bounded per client address. A 429 is
        // retried by Mailgun, so a burst of genuine mail is delayed, not lost.
        if (path.startsWith("/api/v1/managed-mail/inbound/")) return "mail-inbound";
        return null;
    }

    private static final class Window {
        private final AtomicLong windowId = new AtomicLong();
        private final AtomicLong count;

        private Window(long initialWindow, AtomicLong count) {
            this.windowId.set(initialWindow);
            this.count = count;
        }

        /** Returns true when the request belongs in this window; false when it is over quota. */
        private boolean resetIfStale(long currentWindow) {
            long observed = windowId.get();
            if (observed == currentWindow) return true;
            // One winner resets; the loser sees the reset and counts afresh.
            if (windowId.compareAndSet(observed, currentWindow)) {
                count.set(0);
                return true;
            }
            return windowId.get() == currentWindow;
        }
    }
}
