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
 * Phase 7: REST API for per-user auto-approval rules.
 *
 * <p>Every operation is owner-scoped via OwnerContext. The controller
 * validates input, delegates to ApplicationDecisionService for persistence,
 * and writes an audit trail on mutations.
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
        controller = new ApprovalRulesController(decisions, audit, ownerContext);
    }

    @Nested
    class GetRule {

        @Test
        void returnsDefaultsWhenNoRuleConfigured() {
            when(decisions.ruleFor(PROFILE)).thenReturn(null);

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("autoApproveEnabled", false);
            assertThat(body).containsEntry("minScore",
                    ApplicationDecisionService.HIGH_CONFIDENCE_THRESHOLD);
            assertThat(body).containsEntry("configured", false);
        }

        @Test
        void returnsSavedRule() {
            when(decisions.ruleFor(PROFILE)).thenReturn(
                    new ApplicationDecisionService.UserApprovalRule(true, 72));

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("autoApproveEnabled", true);
            assertThat(body).containsEntry("minScore", 72);
            assertThat(body).containsEntry("configured", true);
        }

        @Test
        void requiresProfile() {
            when(ownerContext.profileIdOrNull()).thenReturn(null);

            ResponseEntity<?> response = controller.get();

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class PutRule {

        @Test
        void upsertsRuleAndWritesAudit() {
            when(decisions.saveRule(PROFILE, true, 75))
                    .thenReturn(new ApplicationDecisionService.UserApprovalRule(true, 75));

            var request = new ApprovalRulesController.RuleRequest(true, 75);
            ResponseEntity<?> response = controller.put(request, httpRequest);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("autoApproveEnabled", true);
            assertThat(body).containsEntry("minScore", 75);
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
