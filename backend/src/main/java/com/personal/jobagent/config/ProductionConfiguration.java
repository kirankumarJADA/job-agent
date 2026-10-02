package com.personal.jobagent.config;

import com.personal.jobagent.security.WorkerEventTokenFilter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Production fail-closed guard.
 *
 * <p>The worker endpoints (claim-next, complete, heartbeat, recover-stale,
 * package and artifact reads) intentionally fall back to session-based local
 * development access when {@code WORKER_EVENT_TOKEN} is blank — that keeps the
 * no-worker local mode usable. That fallback is ONLY acceptable on a
 * developer machine: in production it would let any signed-in account act as
 * the worker on any plan. The prod profile therefore refuses to start without
 * a worker token, instead of silently degrading to insecure behaviour.
 */
@Configuration
@Profile("prod")
public class ProductionConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfiguration.class);

    private final String workerToken;
    private final boolean requireInviteCode;

    public ProductionConfiguration(@Value("${app.worker-event-token:}") String workerToken,
                                   @Value("${app.auth.require-invite-code:true}") boolean requireInviteCode) {
        this.workerToken = workerToken == null ? "" : workerToken;
        this.requireInviteCode = requireInviteCode;
    }

    @PostConstruct
    public void verifyProductionInvariants() {
        requireWorkerToken();
        requireInviteGate();
    }

    /** Visible for testing: throws with the exact remediation, or returns quietly. */
    public void requireWorkerToken() {
        if (workerToken.isBlank()) {
            throw new IllegalStateException("WORKER_EVENT_TOKEN must be configured in the prod profile — "
                    + "without it worker endpoints (" + WorkerEventTokenFilter.EVENTS_PATH
                    + ", /plans/claim-next, /plans/recover-stale) would fall back to local-dev "
                    + "session authentication and any signed-in account could act as the worker");
        }
        if (workerToken.length() < 32) {
            throw new IllegalStateException("WORKER_EVENT_TOKEN must be at least 32 characters in the prod profile");
        }
        log.info("Production invariant verified: worker event token is configured ({} chars)", workerToken.length());
    }

    private void requireInviteGate() {
        if (!requireInviteCode) {
            throw new IllegalStateException("APP_REQUIRE_INVITE_CODE must not be disabled in the prod profile — "
                    + "signups would be open to anyone with the deployment URL");
        }
        log.info("Production invariant verified: registration invite gate is enforced");
    }
}
