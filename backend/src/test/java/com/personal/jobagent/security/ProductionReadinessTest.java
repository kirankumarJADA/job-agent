package com.personal.jobagent.security;

import java.util.Map;

import com.personal.jobagent.config.ProductionConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Production fail-closed invariants: the prod profile refuses to start
 * without a worker token (the blank-token local-dev fallback would let any
 * signed-in account act as the worker), refuses weak tokens, and refuses an
 * open registration gate.
 */
class ProductionConfigurationTest {

    @Test
    void aBlankWorkerTokenRefusesStartup() {
        ProductionConfiguration configuration = new ProductionConfiguration("   ", true);
        assertThatThrownBy(configuration::requireWorkerToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WORKER_EVENT_TOKEN must be configured")
                .hasMessageContaining("local-dev");
    }

    @Test
    void aShortWorkerTokenRefusesStartup() {
        ProductionConfiguration configuration = new ProductionConfiguration("too-short", true);
        assertThatThrownBy(configuration::requireWorkerToken)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 characters");
    }

    @Test
    void aStrongWorkerTokenPasses() {
        ProductionConfiguration configuration =
                new ProductionConfiguration("0123456789abcdef0123456789abcdef", true);
        configuration.requireWorkerToken(); // no exception
    }

    @Test
    void aDisabledInviteGateRefusesStartup() {
        ProductionConfiguration configuration = new ProductionConfiguration("0123456789abcdef0123456789abcdef", false);
        assertThatThrownBy(configuration::verifyProductionInvariants)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_REQUIRE_INVITE_CODE");
    }

    @Test
    void theRateLimiterReturnsProblemDetail429sWhenExhausted() throws Exception {
        RateLimitService limiter = new RateLimitService(true, Map.of(
                "auth", new RateLimitService.Rule(1, 60_000L)));
        RateLimitInterceptor interceptor = new RateLimitInterceptor(
                limiter, new com.fasterxml.jackson.databind.ObjectMapper());

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        first.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(first, response, new Object())).isTrue();

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        second.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(second, blocked, new Object())).isFalse();
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isNotBlank();
        assertThat(blocked.getContentType()).contains("problem+json");
        assertThat(blocked.getContentAsString())
                .contains("Too Many Requests")
                .contains("Too many requests")
                .contains("correlationId");
    }

    @Test
    void theMetricsComponentCountsOnlyWhatIsAsked() {
        // AutomationMetrics smoke: counters appear under their names and tags.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        com.personal.jobagent.common.AutomationMetrics metrics =
                new com.personal.jobagent.common.AutomationMetrics(registry);
        metrics.planOutcome("HUMAN_REQUIRED");
        metrics.planOutcome("HUMAN_REQUIRED");
        metrics.planOutcome("FAILED");
        metrics.submitApproved();
        assertThat(registry.get("robin_plan_outcomes_total").tag("outcome", "HUMAN_REQUIRED").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("robin_plan_outcomes_total").tag("outcome", "FAILED").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("robin_submit_approvals_total").counter().count()).isEqualTo(1.0);
    }
}
