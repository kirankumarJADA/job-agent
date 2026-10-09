package com.personal.jobagent.application;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 7.3: makes an unusable per-user auto-approval rule visible.
 *
 * <p>When {@link ApplicationDecisionService} cannot read an owner's rule it
 * fails closed and queues the match for human review. That is the right
 * decision, but silently: nothing told the owner their auto-approval had
 * stopped working. This component is the reporting half of that fail-closed
 * path.
 *
 * <p><b>Two channels, deliberately different in strength.</b>
 *
 * <ol>
 *   <li><b>Metrics and structured logs</b> — always recorded, because neither
 *       touches the database. These survive exactly the situation that most
 *       often causes an unreadable rule.</li>
 *   <li><b>A durable owner alert</b> — a notification, emitted through the
 *       existing outbox fan-out ({@link NotificationService} →
 *       {@code outbox_events} → {@code NotificationEventHandler} →
 *       {@code notifications}). This needs a working database, so it is
 *       <em>attempted</em>, not guaranteed. When the attempt fails the failure
 *       is logged and counted as {@code PERSIST_FAILED}; this class never
 *       reports a durable alert it did not write.</li>
 * </ol>
 *
 * <p><b>Noise control.</b> One alert per owner per occurrence would be one row
 * per matched job, which is exactly the noise the notification surface must not
 * carry. De-duplication is therefore two-layered:
 *
 * <ul>
 *   <li>an in-process, bounded guard that allows at most one <em>attempt</em> per
 *       owner per UTC hour, so a burst of decisions produces one alert instead
 *       of hundreds, and a database that recovers within the hour is retried the
 *       next hour;</li>
 *   <li>a UTC-day bucket in the notification {@code dedup_key}, which the
 *       partial unique index on {@code notifications.dedup_key} enforces
 *       across processes and restarts. The owner sees at most one row per day
 *       even if several attempts are made.</li>
 * </ul>
 *
 * <p>The guard map is capped: if it ever exceeds {@value #MAX_TRACKED_WINDOWS}
 * windows it is cleared, which costs at most a few extra duplicate attempts
 * (still collapsed by the durable key) and bounds memory. Clearing can never
 * produce a duplicate notification.
 *
 * <p><b>Labels are bounded.</b> No profile id, job id, email address or other
 * per-user identifier is ever used as a metric label; those appear only in log
 * lines and in the payload of the owner's own notification. The cause carried
 * on the alert is a bounded enum, never a raw exception message — a JDBC
 * failure message can contain host, port and user names, which the notification
 * surface must not expose.
 */
@Component
public class ApprovalRuleHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRuleHealthMonitor.class);

    /** Number of distinct (owner, hour) windows tracked before the guard resets. */
    static final int MAX_TRACKED_WINDOWS = 10_000;

    private static final DateTimeFormatter HOUR_WINDOW =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_WINDOW =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    /** Bounded rule-level reasons automatic approval was withheld. */
    public enum WithheldReason {
        RULE_UNREADABLE,
        RULE_DISABLED,
        NO_RULE_IN_CONTROLLED_AUTO
    }

    private final AutomationMetrics metrics;
    private final NotificationService notificationService;

    /** One entry per owner per hour in which a durable alert was attempted. */
    private final ConcurrentHashMap<String, Boolean> alertAttempts = new ConcurrentHashMap<>();

    public ApprovalRuleHealthMonitor(AutomationMetrics metrics,
                                     NotificationService notificationService) {
        this.metrics = metrics;
        this.notificationService = notificationService;
    }

    /**
     * Records one decision-time rule resolution. The availability label is one
     * of the three bounded enum constants.
     *
     * <p>On {@code UNREADABLE} this also logs the failure and raises the
     * de-duplicated owner alert. It must be called after the fail-closed
     * decision has been made, so reporting can never change the decision.
     */
    public void ruleResolvedAtDecisionTime(UUID profileId, ApplicationDecisionService.RuleState state) {
        var availability = state.availability();
        metrics.approvalRuleLookup(availability.name());

        if (availability != ApplicationDecisionService.RuleAvailability.UNREADABLE) {
            return;
        }
        log.warn("Auto-approval rule unavailable for profile {} (cause={}); automatic approval is withheld "
                        + "and the match requires human review",
                profileId, state.unreadableCause());
        raiseOwnerAlert(profileId, state.unreadableCause());
    }

    /** Records that an available rule withheld automatic approval, by bounded reason. */
    public void approvalWithheld(WithheldReason reason) {
        metrics.approvalWithheldByRule(reason.name());
    }

    private void raiseOwnerAlert(UUID profileId, ApplicationDecisionService.RuleUnavailableCause cause) {
        if (profileId == null) {
            return;
        }
        // One attempt per owner per UTC hour. The attempt is recorded even when
        // it fails, so a sustained outage produces one log line per hour rather
        // than one per matched job; the next hour retries.
        if (alertAttempts.size() > MAX_TRACKED_WINDOWS) {
            alertAttempts.clear();
        }
        String window = profileId + "|" + HOUR_WINDOW.format(Instant.now());
        if (alertAttempts.putIfAbsent(window, Boolean.TRUE) != null) {
            metrics.approvalRuleAlert("DEDUPED");
            return;
        }

        String dedupKey = "approval-rule-unavailable:" + DAY_WINDOW.format(Instant.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile_id", profileId.toString());
        payload.put("dedup_key", dedupKey);
        payload.put("severity", "WARN");
        payload.put("message", "Auto-approval paused: your approval rule could not be read");
        payload.put("detail", detailFor(cause));
        payload.put("link", "/approval-rules");
        // Bounded cause code, never the raw exception text (see class docs).
        payload.put("reason", String.valueOf(cause));

        try {
            notificationService.emit(new NotificationService.NotificationCommand(
                    NotificationEvents.APPROVAL_RULE_UNAVAILABLE,
                    "PROFILE",
                    profileId,
                    payload,
                    UuidV7.generate(),
                    null));
            metrics.approvalRuleAlert("EMITTED");
        } catch (Exception e) {
            // The same outage that made the rule unreadable generally blocks the
            // outbox write too. Metrics and logs are the record of the failure;
            // no durable alert was written and none is claimed.
            log.warn("Could not queue the approval-rule alert for profile {}: {} "
                            + "(recorded in metrics/logs only; no durable notification was written)",
                    profileId, e.getMessage());
            metrics.approvalRuleAlert("PERSIST_FAILED");
        }
    }

    private static String detailFor(ApplicationDecisionService.RuleUnavailableCause cause) {
        if (cause == ApplicationDecisionService.RuleUnavailableCause.INVALID_THRESHOLD) {
            return "Your saved approval rule has an invalid minimum score, so automatic approval is "
                    + "paused for safety. Matches will wait for your review until it is saved again.";
        }
        // QUERY_FAILED, and any null cause, get the generic read-failure wording.
        return "Robin could not read your approval rule, so automatic approval is paused for "
                + "safety. Matches will wait for your review until the rule can be read again.";
    }
}
