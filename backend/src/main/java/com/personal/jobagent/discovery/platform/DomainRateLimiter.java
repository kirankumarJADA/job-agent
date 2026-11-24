package com.personal.jobagent.discovery.platform;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Proactive, adaptive per-domain token bucket rate limiter for ATS domains.
 *
 * <p>Key capabilities:
 * <ul>
 *   <li><b>Per-domain Token Bucket:</b> Isolates rate limits by canonical ATS domain
 *       (e.g. {@code greenhouse.io}, {@code lever.co}, {@code ashbyhq.com}) across concurrent scans;</li>
 *   <li><b>Burst Capacity:</b> Allows an initial burst of requests up to configured capacity
 *       before imposing proactive rate limiting delays;</li>
 *   <li><b>Configurable Rates:</b> Allows per-domain requests/sec and burst capacity configuration;</li>
 *   <li><b>Adaptive Backoff (AIMD):</b> On HTTP 429 / {@code RATE_LIMITED}, drains available tokens,
 *       halves the request rate, and sets a penalty backoff window; on consecutive successes,
 *       gradually recovers back to the base configured rate;</li>
 *   <li><b>Fail-Closed Deadline Guard:</b> If waiting for a token exceeds the execution deadline,
 *       aborts immediately with a {@link FailureCategory#TIMEOUT} exception rather than hanging;</li>
 *   <li><b>Sleeper Seam:</b> Supports virtual time / recording sleepers for fast, deterministic unit testing.</li>
 * </ul>
 */
public class DomainRateLimiter {

    public record RateLimitConfig(double requestsPerSec, int burstCapacity, double minRequestsPerSec,
                                  double backoffMultiplier, double recoveryStep, int recoveryThreshold,
                                  long baseBackoffMs, long maxBackoffMs) {
        public static RateLimitConfig defaults() {
            return new RateLimitConfig(5.0, 10, 0.5, 0.5, 0.5, 3, 1000L, 15000L);
        }

        public static RateLimitConfig of(double requestsPerSec, int burstCapacity) {
            return new RateLimitConfig(requestsPerSec, burstCapacity, Math.min(0.5, requestsPerSec / 10.0),
                    0.5, 0.5, 3, 1000L, 15000L);
        }
    }

    public static final class TokenBucket {
        private final String domain;
        private volatile double baseRequestsPerSec;
        private volatile int burstCapacity;
        private final double minRequestsPerSec;
        private final double backoffMultiplier;
        private final double recoveryStep;
        private final int recoveryThreshold;
        private final long baseBackoffMs;
        private final long maxBackoffMs;

        private double currentRequestsPerSec;
        private double tokens;
        private long lastRefillNanos;
        private long backoffUntilNanos;
        private int consecutiveSuccesses;
        private int consecutiveRateLimits;

        public TokenBucket(String domain, RateLimitConfig config) {
            this.domain = domain;
            this.baseRequestsPerSec = config.requestsPerSec();
            this.burstCapacity = config.burstCapacity();
            this.minRequestsPerSec = config.minRequestsPerSec();
            this.backoffMultiplier = config.backoffMultiplier();
            this.recoveryStep = config.recoveryStep();
            this.recoveryThreshold = config.recoveryThreshold();
            this.baseBackoffMs = config.baseBackoffMs();
            this.maxBackoffMs = config.maxBackoffMs();

            this.currentRequestsPerSec = this.baseRequestsPerSec;
            this.tokens = this.burstCapacity;
            this.lastRefillNanos = System.nanoTime();
            this.backoffUntilNanos = 0L;
            this.consecutiveSuccesses = 0;
            this.consecutiveRateLimits = 0;
        }

        public synchronized void configure(double requestsPerSec, int burstCapacity) {
            if (requestsPerSec <= 0 || burstCapacity <= 0) {
                throw new IllegalArgumentException("requestsPerSec and burstCapacity must be positive");
            }
            this.baseRequestsPerSec = requestsPerSec;
            this.burstCapacity = burstCapacity;
            this.currentRequestsPerSec = requestsPerSec;
            this.tokens = Math.min(this.tokens, burstCapacity);
        }

        /**
         * Reserves a token and calculates required wait time in nanoseconds.
         * The lock is held only during calculation; actual sleeping is performed outside the lock.
         */
        public synchronized long reserve(long deadlineNanos) {
            long now = System.nanoTime();
            long effectiveStart;

            if (now < backoffUntilNanos) {
                effectiveStart = backoffUntilNanos;
                if (lastRefillNanos < backoffUntilNanos) {
                    lastRefillNanos = backoffUntilNanos;
                }
            } else {
                effectiveStart = now;
            }

            // Refill tokens based on elapsed time up to effectiveStart
            if (effectiveStart > lastRefillNanos) {
                double elapsedSec = (effectiveStart - lastRefillNanos) / 1_000_000_000.0;
                tokens = Math.min(burstCapacity, tokens + (elapsedSec * currentRequestsPerSec));
                lastRefillNanos = effectiveStart;
            }

            long waitNanos;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                waitNanos = Math.max(0L, backoffUntilNanos - now);
            } else {
                double deficit = 1.0 - tokens;
                long delayNanos = (long) Math.ceil((deficit / currentRequestsPerSec) * 1_000_000_000.0);
                tokens = 0.0;
                lastRefillNanos = lastRefillNanos + delayNanos;
                waitNanos = Math.max(0L, lastRefillNanos - now);
            }

            if (deadlineNanos > 0 && now + waitNanos >= deadlineNanos) {
                throw new SafeHttpClient.FetchException(FailureCategory.TIMEOUT,
                        "Domain " + domain + " rate limit delay (" + (waitNanos / 1_000_000) + "ms) exceeds deadline", 0);
            }

            return waitNanos;
        }

        /**
         * Acquires a token, sleeping via the provided sleeper if required.
         */
        public long acquire(long deadlineNanos, SafeHttpClient.Sleeper sleeper) throws InterruptedException {
            long waitNanos = reserve(deadlineNanos);
            if (waitNanos > 0 && sleeper != null) {
                long waitMs = (waitNanos + 999_999L) / 1_000_000L;
                sleeper.sleep(waitMs);
            }
            return waitNanos;
        }

        public synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            if (now < backoffUntilNanos) {
                return false;
            }
            if (now > lastRefillNanos) {
                double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
                tokens = Math.min(burstCapacity, tokens + (elapsedSec * currentRequestsPerSec));
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        /**
         * Adaptive multiplicative decrease on HTTP 429 rate limit response.
         */
        public synchronized void onRateLimited() {
            consecutiveRateLimits++;
            consecutiveSuccesses = 0;
            // Drain remaining burst tokens
            tokens = 0.0;
            // Multiplicative decrease: cut rate in half, bounded by minRequestsPerSec
            currentRequestsPerSec = Math.max(minRequestsPerSec, currentRequestsPerSec * backoffMultiplier);
            // Exponential penalty cooldown backoff
            long backoffMs = Math.min(maxBackoffMs, baseBackoffMs * (1L << Math.min(consecutiveRateLimits - 1, 4)));
            backoffUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMs);
            lastRefillNanos = backoffUntilNanos;
        }

        /**
         * Adaptive additive increase on successful response.
         */
        public synchronized void onSuccess() {
            consecutiveRateLimits = 0;
            consecutiveSuccesses++;
            if (currentRequestsPerSec < baseRequestsPerSec) {
                if (consecutiveSuccesses >= recoveryThreshold) {
                    currentRequestsPerSec = Math.min(baseRequestsPerSec, currentRequestsPerSec + recoveryStep);
                    consecutiveSuccesses = 0;
                }
            }
        }

        public synchronized void reset() {
            this.currentRequestsPerSec = this.baseRequestsPerSec;
            this.tokens = this.burstCapacity;
            this.lastRefillNanos = System.nanoTime();
            this.backoffUntilNanos = 0L;
            this.consecutiveSuccesses = 0;
            this.consecutiveRateLimits = 0;
        }

        public synchronized double getAvailableTokens() {
            long now = System.nanoTime();
            if (now > lastRefillNanos && now >= backoffUntilNanos) {
                double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
                return Math.min(burstCapacity, tokens + (elapsedSec * currentRequestsPerSec));
            }
            return tokens;
        }

        public double getCurrentRequestsPerSec() {
            return currentRequestsPerSec;
        }

        public double getBaseRequestsPerSec() {
            return baseRequestsPerSec;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public synchronized boolean isBackingOff() {
            return System.nanoTime() < backoffUntilNanos;
        }

        public String getDomain() {
            return domain;
        }
    }

    private final ConcurrentHashMap<String, RateLimitConfig> domainConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private volatile RateLimitConfig defaultConfig;
    private volatile SafeHttpClient.Sleeper sleeper;

    public DomainRateLimiter() {
        this(RateLimitConfig.defaults(), Thread::sleep);
    }

    public DomainRateLimiter(RateLimitConfig defaultConfig, SafeHttpClient.Sleeper sleeper) {
        this.defaultConfig = defaultConfig != null ? defaultConfig : RateLimitConfig.defaults();
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;

        // Initialize production ATS domain defaults
        configureDomain("greenhouse.io", 5.0, 10);
        configureDomain("lever.co", 5.0, 10);
        configureDomain("ashbyhq.com", 2.0, 4);
        configureDomain("myworkdayjobs.com", 3.0, 6);
        configureDomain("rippling.com", 4.0, 8);
        configureDomain("smartrecruiters.com", 5.0, 10);
        configureDomain("workable.com", 5.0, 10);
        configureDomain("gem.com", 5.0, 10);
        configureDomain("tsenta.com", 1.5, 3);
        configureDomain("adzuna.com", 10.0, 20);
        configureDomain("arbeitnow.com", 5.0, 10);
        configureDomain("freehire.me", 5.0, 10);
    }

    public void configureDomain(String domainOrHost, double requestsPerSec, int burstCapacity) {
        String canonical = canonicalDomain(domainOrHost);
        RateLimitConfig config = RateLimitConfig.of(requestsPerSec, burstCapacity);
        domainConfigs.put(canonical, config);
        TokenBucket existing = buckets.get(canonical);
        if (existing != null) {
            existing.configure(requestsPerSec, burstCapacity);
        }
    }

    public void configureDomain(PlatformKind kind, double requestsPerSec, int burstCapacity) {
        configureDomain(canonicalDomain(kind), requestsPerSec, burstCapacity);
    }

    public TokenBucket bucket(String domainOrHost) {
        String canonical = canonicalDomain(domainOrHost);
        return buckets.computeIfAbsent(canonical, d -> {
            RateLimitConfig cfg = domainConfigs.getOrDefault(d, defaultConfig);
            return new TokenBucket(d, cfg);
        });
    }

    public TokenBucket bucket(PlatformKind kind) {
        return bucket(canonicalDomain(kind));
    }

    public long acquire(PlatformKind kind, long deadlineNanos) throws InterruptedException {
        return bucket(kind).acquire(deadlineNanos, sleeper);
    }

    public long acquire(String domainOrHost, long deadlineNanos) throws InterruptedException {
        return bucket(domainOrHost).acquire(deadlineNanos, sleeper);
    }

    public void onRateLimited(PlatformKind kind) {
        bucket(kind).onRateLimited();
    }

    public void onRateLimited(String domainOrHost) {
        bucket(domainOrHost).onRateLimited();
    }

    public void onSuccess(PlatformKind kind) {
        bucket(kind).onSuccess();
    }

    public void onSuccess(String domainOrHost) {
        bucket(domainOrHost).onSuccess();
    }

    public void setSleeper(SafeHttpClient.Sleeper sleeper) {
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
    }

    public SafeHttpClient.Sleeper getSleeper() {
        return sleeper;
    }

    public void resetAll() {
        buckets.values().forEach(TokenBucket::reset);
    }

    public static String canonicalDomain(PlatformKind kind) {
        if (kind == null) return "default";
        return switch (kind) {
            case GREENHOUSE -> "greenhouse.io";
            case LEVER -> "lever.co";
            case ASHBY -> "ashbyhq.com";
            case WORKDAY -> "myworkdayjobs.com";
            case RIPPLING -> "rippling.com";
            case SMARTRECRUITERS -> "smartrecruiters.com";
            case WORKABLE -> "workable.com";
            case JAZZHR -> "applytojob.com";
            case JOBVITE -> "jobvite.com";
            case BREEZYHR -> "breezy.hr";
            case ICIMS -> "icims.com";
            case GEM -> "gem.com";
            case ZOHO_RECRUIT -> "zohorecruit.com";
            case ORACLE_CLOUD -> "oraclecloud.com";
            case PAYLOCITY -> "paylocity.com";
            case ULTIPRO -> "ultipro.com";
            case TSENTA -> "tsenta.com";
            case FREEHIRE -> "freehire.me";
            case ARBEITNOW -> "arbeitnow.com";
            case ADZUNA -> "adzuna.com";
            case ADP -> "adp.com";
            case BAMBOOHR -> "bamboohr.com";
            case DOVER -> "dover.com";
            case BRASSRING -> "brassring.com";
            case CAREERPLUG -> "careerplug.com";
            case DAYFORCE -> "dayforce.com";
            case HIREOLOGY -> "hireology.com";
            default -> kind.name().toLowerCase(Locale.ROOT);
        };
    }

    public static String canonicalDomain(String domainOrHost) {
        if (domainOrHost == null || domainOrHost.isBlank()) {
            return "default";
        }
        String clean = domainOrHost.strip().toLowerCase(Locale.ROOT);
        if (clean.startsWith("http://")) clean = clean.substring(7);
        if (clean.startsWith("https://")) clean = clean.substring(8);
        int slash = clean.indexOf('/');
        if (slash >= 0) clean = clean.substring(0, slash);
        int colon = clean.indexOf(':');
        if (colon >= 0) clean = clean.substring(0, colon);

        Optional<PlatformKind> pk = PlatformKind.lookup(clean);
        if (pk.isPresent()) {
            return canonicalDomain(pk.get());
        }

        if (clean.endsWith(".greenhouse.io") || clean.equals("greenhouse.io")) return "greenhouse.io";
        if (clean.endsWith(".lever.co") || clean.equals("lever.co")) return "lever.co";
        if (clean.endsWith(".ashbyhq.com") || clean.equals("ashbyhq.com")) return "ashbyhq.com";
        if (clean.contains("myworkdayjobs.com") || clean.contains("workday")) return "myworkdayjobs.com";
        if (clean.endsWith(".rippling.com") || clean.contains("rippling")) return "rippling.com";
        if (clean.endsWith(".smartrecruiters.com") || clean.contains("smartrecruiters")) return "smartrecruiters.com";
        if (clean.endsWith(".workable.com") || clean.contains("workable")) return "workable.com";
        if (clean.endsWith(".applytojob.com") || clean.contains("jazzhr") || clean.contains("applytojob")) return "applytojob.com";
        if (clean.endsWith(".jobvite.com") || clean.contains("jobvite")) return "jobvite.com";
        if (clean.endsWith(".breezy.hr") || clean.contains("breezy")) return "breezy.hr";
        if (clean.endsWith(".icims.com") || clean.contains("icims")) return "icims.com";
        if (clean.endsWith(".gem.com") || clean.equals("gem.com") || clean.contains("gem")) return "gem.com";
        if (clean.contains("zohorecruit")) return "zohorecruit.com";
        if (clean.contains("oraclecloud")) return "oraclecloud.com";
        if (clean.contains("paylocity")) return "paylocity.com";
        if (clean.contains("ultipro")) return "ultipro.com";
        if (clean.endsWith(".tsenta.com") || clean.equals("tsenta.com")) return "tsenta.com";
        if (clean.endsWith(".freehire.me") || clean.equals("freehire.me")) return "freehire.me";
        if (clean.endsWith(".arbeitnow.com") || clean.equals("arbeitnow.com")) return "arbeitnow.com";
        if (clean.endsWith(".adzuna.com") || clean.equals("adzuna.com")) return "adzuna.com";

        return clean;
    }
}
