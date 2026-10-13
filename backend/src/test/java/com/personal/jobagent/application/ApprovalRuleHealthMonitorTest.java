package com.personal.jobagent.application;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7.3: the reporting half of the fail-closed rule path.
 *
 * <p>Two properties matter most here:
 * <ol>
 *   <li>metric labels are bounded — never a profile id or other per-user
 *       identifier;</li>
 *   <li>a durable alert that could not be written is reported as such, never
 *       implied to have succeeded.</li>
 * </ol>
 */
class ApprovalRuleHealthMonitorTest {

    private static final UUID PROFILE = UUID.randomUUID();

    private final AutomationMetrics metrics = mock(AutomationMetrics.class);
    private final NotificationService notificationService = mock(NotificationService.class);

    private ApprovalRuleHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        reset(metrics, notificationService);
        monitor = new ApprovalRuleHealthMonitor(metrics, notificationService);
    }

    private static ApplicationDecisionService.RuleState state(
            ApplicationDecisionService.RuleAvailability availability,
            ApplicationDecisionService.RuleUnavailableCause cause) {
        return new ApplicationDecisionService.RuleState(availability, null, cause);
    }

    @Nested
    class Metrics {

        @Test
        void reportsOneBoundedLabelPerAvailability() {
            monitor.ruleResolvedAtDecisionTime(PROFILE,
                    state(ApplicationDecisionService.RuleAvailability.CONFIGURED, null));
            monitor.ruleResolvedAtDecisionTime(PROFILE,
                    state(ApplicationDecisionService.RuleAvailability.ABSENT, null));

            verify(metrics).approvalRuleLookup("CONFIGURED");
            verify(metrics).approvalRuleLookup("ABSENT");
        }

        /**
         * The label vocabulary is exactly the enum, so a per-user identifier can
         * never reach the metrics backend through this path.
         */
        @Test
        void neverUsesAPersonalIdentifierAsALabel() {
            monitor.ruleResolvedAtDecisionTime(PROFILE,
                    state(ApplicationDecisionService.RuleAvailability.CONFIGURED, null));
            monitor.ruleResolvedAtDecisionTime(PROFILE,
                    state(ApplicationDecisionService.RuleAvailability.ABSENT, null));
            monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                    ApplicationDecisionService.RuleAvailability.UNREADABLE,
                    ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));

            ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
            verify(metrics, times(3)).approvalRuleLookup(labels.capture());

            Set<String> bounded = Set.of("CONFIGURED", "ABSENT", "UNREADABLE");
            assertThat(labels.getAllValues()).allSatisfy(
                    label -> assertThat(bounded).contains(label));
            assertThat(labels.getAllValues()).doesNotContain(PROFILE.toString());
        }

        @Test
        void mapsWithheldReasonsToBoundedLabels() {
            monitor.approvalWithheld(ApprovalRuleHealthMonitor.WithheldReason.RULE_DISABLED);
            monitor.approvalWithheld(ApprovalRuleHealthMonitor.WithheldReason.NO_RULE_IN_CONTROLLED_AUTO);
            monitor.approvalWithheld(ApprovalRuleHealthMonitor.WithheldReason.RULE_UNREADABLE);

            verify(metrics).approvalWithheldByRule("RULE_DISABLED");
            verify(metrics).approvalWithheldByRule("NO_RULE_IN_CONTROLLED_AUTO");
            verify(metrics).approvalWithheldByRule("RULE_UNREADABLE");
        }

        @Test
        void healthyLookupsRaiseNoAlert() {
            monitor.ruleResolvedAtDecisionTime(PROFILE,
                    state(ApplicationDecisionService.RuleAvailability.CONFIGURED, null));
            monitor.ruleResolvedAtDecisionTime(PROFILE,
                    state(ApplicationDecisionService.RuleAvailability.ABSENT, null));

            verify(notificationService, never()).emit(any());
            verify(metrics, never()).approvalRuleAlert(any());
        }
    }

    @Nested
    class OwnerAlert {

        @Test
        void alertsOnceWhenTheRuleIsUnreadable() {
            monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                    ApplicationDecisionService.RuleAvailability.UNREADABLE,
                    ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));

            var captor = ArgumentCaptor.forClass(NotificationService.NotificationCommand.class);
            verify(notificationService).emit(captor.capture());
            verify(metrics).approvalRuleAlert("EMITTED");

            NotificationService.NotificationCommand command = captor.getValue();
            assertThat(command.eventType()).isEqualTo(NotificationEvents.APPROVAL_RULE_UNAVAILABLE);
            assertThat(command.aggregateType()).isEqualTo("PROFILE");
            assertThat(command.aggregateId()).isEqualTo(PROFILE);

            Map<String, Object> payload = payloadOf(command);
            assertThat(payload).containsEntry("profile_id", PROFILE.toString());
            assertThat(payload).containsEntry("severity", "WARN");
            assertThat(payload).containsEntry("link", "/approval-rules");
            assertThat(String.valueOf(payload.get("dedup_key")))
                    .startsWith("approval-rule-unavailable:");
            assertThat(payload).containsEntry("reason", "QUERY_FAILED");
        }

        /**
         * A burst of decisions for the same owner must collapse to one alert,
         * not one per matched job.
         */
        @Test
        void collapsesABurstToASingleAlert() {
            for (int i = 0; i < 25; i++) {
                monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                        ApplicationDecisionService.RuleAvailability.UNREADABLE,
                        ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));
            }

            verify(notificationService, times(1)).emit(any());
            verify(metrics, times(1)).approvalRuleAlert("EMITTED");
            verify(metrics, times(24)).approvalRuleAlert("DEDUPED");
            // Metrics for every lookup are still recorded — only the alert is collapsed.
            verify(metrics, times(25)).approvalRuleLookup("UNREADABLE");
        }

        @Test
        void eachOwnerGetsItsOwnAlert() {
            UUID other = UUID.randomUUID();
            monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                    ApplicationDecisionService.RuleAvailability.UNREADABLE,
                    ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));
            monitor.ruleResolvedAtDecisionTime(other, state(
                    ApplicationDecisionService.RuleAvailability.UNREADABLE,
                    ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));

            verify(notificationService, times(2)).emit(any());
        }

        /** The alert must never carry a raw database message. */
        @Test
        void neverLeaksRawFailureTextIntoTheAlert() {
            monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                    ApplicationDecisionService.RuleAvailability.UNREADABLE,
                    ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));

            var captor = ArgumentCaptor.forClass(NotificationService.NotificationCommand.class);
            verify(notificationService).emit(captor.capture());
            Map<String, Object> payload = payloadOf(captor.getValue());

            assertThat(payload.values().stream().map(String::valueOf).toList())
                    .allSatisfy(value -> assertThat(value)
                            .doesNotContain("connection")
                            .doesNotContain("5432")
                            .doesNotContain("Exception"));
        }

        @Test
        void distinguishesAnInvalidThresholdFromAReadFailure() {
            monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                    ApplicationDecisionService.RuleAvailability.UNREADABLE,
                    ApplicationDecisionService.RuleUnavailableCause.INVALID_THRESHOLD));

            var captor = ArgumentCaptor.forClass(NotificationService.NotificationCommand.class);
            verify(notificationService).emit(captor.capture());
            Map<String, Object> payload = payloadOf(captor.getValue());

            assertThat(payload).containsEntry("reason", "INVALID_THRESHOLD");
            assertThat(String.valueOf(payload.get("detail"))).contains("invalid minimum score");
        }
    }

    @Nested
    class DurablePersistenceFailure {

        /**
         * The database outage that made the rule unreadable usually blocks the
         * outbox write too. That must not throw into the decision path, and it
         * must not be reported as a delivered alert.
         */
        @Test
        void reportsPersistFailureWithoutThrowingAndWithoutClaimingDelivery() {
            when(notificationService.emit(any())).thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                    ApplicationDecisionService.RuleAvailability.UNREADABLE,
                    ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED)))
                    .doesNotThrowAnyException();

            verify(metrics).approvalRuleAlert("PERSIST_FAILED");
            verify(metrics, never()).approvalRuleAlert("EMITTED");
            // The lookup itself is still reported: metrics and logs survive the outage.
            verify(metrics).approvalRuleLookup("UNREADABLE");
        }

        /** A failed attempt is still rate-limited, so an outage cannot spam logs. */
        @Test
        void failedAttemptsAreStillRateLimited() {
            when(notificationService.emit(any())).thenThrow(new RuntimeException("db down"));

            for (int i = 0; i < 10; i++) {
                monitor.ruleResolvedAtDecisionTime(PROFILE, state(
                        ApplicationDecisionService.RuleAvailability.UNREADABLE,
                        ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));
            }

            verify(notificationService, times(1)).emit(any());
            verify(metrics, times(1)).approvalRuleAlert("PERSIST_FAILED");
            verify(metrics, times(9)).approvalRuleAlert("DEDUPED");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(NotificationService.NotificationCommand command) {
        return (Map<String, Object>) command.payload();
    }
}
