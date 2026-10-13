package com.personal.jobagent.application;

import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7 / 7.3: REST API for per-user auto-approval rules.
 *
 * <p>The whole point of the 7.3 contract is that the three availability states
 * are distinguishable, and that an unreadable rule is never reported as a
 * successfully loaded unconfigured one. Every operation is owner-scoped via
 * OwnerContext; the controller validates input, delegates to
 * ApplicationDecisionService, and writes an audit trail on mutations.
 */
class ApprovalRulesControllerTest {

    private static final UUID PROFILE = UUID.randomUUID();

    private final ApplicationDecisionService decisions = mock(ApplicationDecisionService.class);
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final OwnerContext ownerContext = mock(OwnerContext.class);
    private final HttpServletRequest httpRequest = mock(HttpServletRequest.class);

    private ApprovalRulesController controller;

    @BeforeEach
    void setUp() {
        when(ownerContext.profileIdOrNull()).thenReturn(PROFILE);
        when(ownerContext.actorOr("user")).thenReturn("test@example.com");
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(decisions.effectiveApplicationMode(PROFILE)).thenReturn("ASSISTED");
        controller = new ApprovalRulesController(decisions, audit, ownerContext);
    }

    private void ruleStateIs(ApplicationDecisionService.RuleAvailability availability,
                             ApplicationDecisionService.UserApprovalRule rule,
                             ApplicationDecisionService.RuleUnavailableCause cause) {
        when(decisions.ruleState(PROFILE))
                .thenReturn(new ApplicationDecisionService.RuleState(availability, rule, cause));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bodyOf(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @Nested
    class GetRule {

        @Test
        void reportsConfiguredRule() {
            ruleStateIs(ApplicationDecisionService.RuleAvailability.CONFIGURED,
                    new ApplicationDecisionService.UserApprovalRule(true, 72), null);

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response))
                    .containsEntry("availability", "CONFIGURED")
                    .containsEntry("configured", true)
                    .containsEntry("autoApproveEnabled", true)
                    .containsEntry("minScore", 72)
                    .containsEntry("applicationMode", "ASSISTED")
                    .containsEntry("assistedFloor", ApplicationDecisionService.HIGH_CONFIDENCE_THRESHOLD);
        }

        @Test
        void reportsAbsentRuleWithTheAssistedFloor() {
            ruleStateIs(ApplicationDecisionService.RuleAvailability.ABSENT, null, null);

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response))
                    .containsEntry("availability", "ABSENT")
                    .containsEntry("configured", false)
                    .containsEntry("autoApproveEnabled", false)
                    .containsEntry("minScore", ApplicationDecisionService.HIGH_CONFIDENCE_THRESHOLD);
        }

        @Test
        void reportsAConfiguredButDisabledRuleAsSuch() {
            ruleStateIs(ApplicationDecisionService.RuleAvailability.CONFIGURED,
                    new ApplicationDecisionService.UserApprovalRule(false, 60), null);

            ResponseEntity<?> response = controller.get();

            assertThat(bodyOf(response))
                    .containsEntry("availability", "CONFIGURED")
                    .containsEntry("configured", true)
                    .containsEntry("autoApproveEnabled", false)
                    .containsEntry("minScore", 60);
        }

        /**
         * The core 7.3 guarantee: an unreadable rule is an explicit failure, not
         * a successful "unconfigured" response, and it carries no settings a
         * client could mistake for loaded state.
         */
        @Test
        void reportsUnreadableRuleAs503WithExplicitStatusAndNoSettings() {
            ruleStateIs(ApplicationDecisionService.RuleAvailability.UNREADABLE, null,
                    ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED);

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            Map<String, Object> body = bodyOf(response);
            assertThat(body).containsEntry("availability", "UNREADABLE");
            assertThat(body).containsEntry("reason", "QUERY_FAILED");
            assertThat(body).doesNotContainKeys("autoApproveEnabled", "minScore", "configured");
            assertThat(String.valueOf(body.get("message"))).doesNotContain("QUERY_FAILED");
        }

        @Test
        void reportsAnInvalidStoredThresholdWithItsOwnBoundedReason() {
            ruleStateIs(ApplicationDecisionService.RuleAvailability.UNREADABLE, null,
                    ApplicationDecisionService.RuleUnavailableCause.INVALID_THRESHOLD);

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(bodyOf(response)).containsEntry("reason", "INVALID_THRESHOLD");
        }

        @Test
        void surfacesTheEffectiveApplicationModeSoClientsCanDescribeDefaults() {
            ruleStateIs(ApplicationDecisionService.RuleAvailability.ABSENT, null, null);
            when(decisions.effectiveApplicationMode(PROFILE)).thenReturn("CONTROLLED_AUTO");

            ResponseEntity<?> response = controller.get();

            assertThat(bodyOf(response)).containsEntry("applicationMode", "CONTROLLED_AUTO");
        }

        @Test
        void requiresProfile() {
            when(ownerContext.profileIdOrNull()).thenReturn(null);

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(decisions, never()).ruleState(any());
        }
    }

    @Nested
    class PutRule {

        @Test
        void upsertsRuleWritesAuditAndReportsConfiguredAvailability() {
            when(decisions.saveRule(PROFILE, true, 75))
                    .thenReturn(new ApplicationDecisionService.UserApprovalRule(true, 75));

            var request = new ApprovalRulesController.RuleRequest(true, 75);
            ResponseEntity<?> response = controller.put(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bodyOf(response))
                    .containsEntry("availability", "CONFIGURED")
                    .containsEntry("autoApproveEnabled", true)
                    .containsEntry("minScore", 75);
            verify(audit).write(any());
        }

        @Test
        void returnsBadRequestForInvalidScore() {
            when(decisions.saveRule(PROFILE, true, 0))
                    .thenThrow(new IllegalArgumentException(
                            "min_score must be between 1 and 100"));

            var request = new ApprovalRulesController.RuleRequest(true, 0);
            ResponseEntity<?> response = controller.put(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(audit, never()).write(any());
        }

        @Test
        void requiresProfile() {
            when(ownerContext.profileIdOrNull()).thenReturn(null);

            var request = new ApprovalRulesController.RuleRequest(true, 80);
            ResponseEntity<?> response = controller.put(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(decisions, never()).saveRule(any(), any(), any());
        }

        @Test
        void ownerIsolationPreventsOtherProfileAccess() {
            when(decisions.saveRule(eq(PROFILE), any(), any()))
                    .thenReturn(new ApplicationDecisionService.UserApprovalRule(true, 80));

            var request = new ApprovalRulesController.RuleRequest(true, 80);
            controller.put(request, httpRequest);

            // Verify saveRule is called with PROFILE from ownerContext,
            // not with any id from the request body
            verify(decisions).saveRule(eq(PROFILE), any(), any());
        }
    }
}
