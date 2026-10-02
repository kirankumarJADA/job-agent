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
                "discovery", new Rule(10, 60_000L)  // run / ingest / seed per account
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
                || path.equals("/api/v1/discovery/ingest")
                || path.equals("/api/v1/discovery/linkedin/search")
                || path.equals("/api/v1/discovery/maintenance")
                || path.equals("/api/v1/jobs/seed-uk")) return "discovery";
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
