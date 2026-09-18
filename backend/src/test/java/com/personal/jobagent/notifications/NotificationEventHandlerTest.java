package com.personal.jobagent.notifications;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.events.Envelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventHandlerTest {

    private NotificationService notificationService;
    private NotificationEventHandler handler;

    @BeforeEach
    void setUp() {
        notificationService = Mockito.mock(NotificationService.class);
        handler = new NotificationEventHandler(notificationService);
    }

    private Envelope envelope(String type, String aggregateType, UUID aggregateId, Map<String, Object> payload) {
        return new Envelope(UuidV7.generate(), type, 1, Instant.now(),
                aggregateType, aggregateId, UuidV7.generate(), null, payload);
    }

    @Test
    void supportsCoversTheWholeCatalogue() {
        assertThat(handler.supports(NotificationEvents.JOB_MATCHED)).isTrue();
        assertThat(handler.supports(NotificationEvents.CV_GENERATED)).isTrue();
        assertThat(handler.supports(NotificationEvents.COVER_LETTER_GENERATED)).isTrue();
        assertThat(handler.supports(NotificationEvents.APPLICATION_PREPARED)).isTrue();
        assertThat(handler.supports(NotificationEvents.SIGNUP_STARTED)).isTrue();
        assertThat(handler.supports(NotificationEvents.SIGNUP_COMPLETED)).isTrue();
        assertThat(handler.supports(NotificationEvents.VERIFICATION_EMAIL_RECEIVED)).isTrue();
        assertThat(handler.supports(NotificationEvents.VERIFICATION_COMPLETED)).isTrue();
        assertThat(handler.supports(NotificationEvents.APPLICATION_SUBMITTED)).isTrue();
        assertThat(handler.supports(NotificationEvents.RECRUITER_REPLY)).isTrue();
        assertThat(handler.supports(NotificationEvents.INTERVIEW_INVITATION)).isTrue();
        assertThat(handler.supports(NotificationEvents.ASSESSMENT_RECEIVED)).isTrue();
        assertThat(handler.supports(NotificationEvents.REJECTION_RECEIVED)).isTrue();
        assertThat(handler.supports(NotificationEvents.OFFER_RECEIVED)).isTrue();
        assertThat(handler.supports(NotificationEvents.AUTOMATION_FAILURE)).isTrue();
        assertThat(handler.supports(NotificationEvents.HARD_STOP)).isTrue();
        assertThat(handler.supports(NotificationEvents.APPROVAL_REQUIRED)).isTrue();
        // unrelated events are not ours
        assertThat(handler.supports("test.synthetic_event")).isFalse();
        assertThat(handler.supports("cv.generated_v2")).isFalse();
    }

    @Test
    void jobMatchedEventDeliversWithJobCorrelationAndDefaultDedupKey() {
        UUID jobId = UuidV7.generate();
        Envelope envelope = envelope(NotificationEvents.JOB_MATCHED, "JOB", jobId, Map.of(
                "job_id", jobId.toString(),
                "job_title", "Senior Backend Engineer",
                "company", "Monzo",
                "score", 82));

        handler.handle(envelope);

        ArgumentCaptor<NotificationService.Delivery> captor =
                ArgumentCaptor.forClass(NotificationService.Delivery.class);
        verify(notificationService).deliver(captor.capture());
        NotificationService.Delivery delivery = captor.getValue();

        assertThat(delivery.jobId()).isEqualTo(jobId);
        assertThat(delivery.applicationId()).isNull();
        assertThat(delivery.category()).isEqualTo("JOB_MATCHED");
        assertThat(delivery.severity()).isEqualTo("INFO");
        assertThat(delivery.title()).contains("Senior Backend Engineer").contains("Monzo");
        assertThat(delivery.link()).isEqualTo("/jobs/" + jobId);
        assertThat(delivery.dedupKey()).isEqualTo("job-matched:" + jobId);
    }

    @Test
    void hardStopIsErrorSeverityAndKeysOnReason() {
        UUID applicationId = UuidV7.generate();
        UUID jobId = UuidV7.generate();
        Envelope envelope = envelope(NotificationEvents.HARD_STOP, "APPLICATION", applicationId, Map.of(
                "job_id", jobId.toString(),
                "application_id", applicationId.toString(),
                "reason", "CAPTCHA"));

        handler.handle(envelope);

        ArgumentCaptor<NotificationService.Delivery> captor =
                ArgumentCaptor.forClass(NotificationService.Delivery.class);
        verify(notificationService).deliver(captor.capture());
        NotificationService.Delivery delivery = captor.getValue();

        assertThat(delivery.severity()).isEqualTo("ERROR");
        assertThat(delivery.category()).isEqualTo("HARD_STOP");
        assertThat(delivery.jobId()).isEqualTo(jobId);
        assertThat(delivery.applicationId()).isEqualTo(applicationId);
        assertThat(delivery.dedupKey()).isEqualTo("hard-stop:" + applicationId + ":CAPTCHA");
    }

    @Test
    void approvalRequiredIsWarnSeverity() {
        UUID jobId = UuidV7.generate();
        Envelope envelope = envelope(NotificationEvents.APPROVAL_REQUIRED, "JOB", jobId, Map.of(
                "job_id", jobId.toString(),
                "approval_id", "apr-123"));

        handler.handle(envelope);

        ArgumentCaptor<NotificationService.Delivery> captor =
                ArgumentCaptor.forClass(NotificationService.Delivery.class);
        verify(notificationService).deliver(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo("WARN");
        assertThat(captor.getValue().dedupKey()).isEqualTo("approval-required:apr-123:" + jobId);
    }

    @Test
    void recruiterReplyKeysOnEmailMessageIdSoDistinctMessagesStayDistinct() {
        UUID jobId = UuidV7.generate();
        Map<String, Object> base = Map.of(
                "job_id", jobId.toString(),
                "job_title", "Platform Engineer",
                "company", "Wise");

        handler.handle(envelope(NotificationEvents.RECRUITER_REPLY, "JOB", jobId,
                merge(base, Map.of("email_message_id", "msg-1"))));
        handler.handle(envelope(NotificationEvents.RECRUITER_REPLY, "JOB", jobId,
                merge(base, Map.of("email_message_id", "msg-2"))));

        ArgumentCaptor<NotificationService.Delivery> captor =
                ArgumentCaptor.forClass(NotificationService.Delivery.class);
        verify(notificationService, Mockito.times(2)).deliver(captor.capture());
        assertThat(captor.getAllValues().get(0).dedupKey())
                .isEqualTo("recruiter-reply:" + jobId + ":msg-1");
        assertThat(captor.getAllValues().get(1).dedupKey())
                .isEqualTo("recruiter-reply:" + jobId + ":msg-2");
    }

    @Test
    void producerDedupKeyOverrideWins() {
        UUID jobId = UuidV7.generate();
        Envelope envelope = envelope(NotificationEvents.VERIFICATION_COMPLETED, "JOB", jobId, Map.of(
                "job_id", jobId.toString(),
                "dedup_key", "custom-key-1"));

        handler.handle(envelope);

        ArgumentCaptor<NotificationService.Delivery> captor =
                ArgumentCaptor.forClass(NotificationService.Delivery.class);
        verify(notificationService).deliver(captor.capture());
        assertThat(captor.getValue().dedupKey()).isEqualTo("custom-key-1");
    }

    @Test
    void malformedPayloadIsHandledSafelyWithoutDedupKeyCollision() {
        // null payload -> empty map -> falls back to event-id-scoped dedup key
        Envelope envelope = envelope(NotificationEvents.AUTOMATION_FAILURE, "APPLICATION", UuidV7.generate(), null);
        handler.handle(envelope);

        ArgumentCaptor<NotificationService.Delivery> captor =
                ArgumentCaptor.forClass(NotificationService.Delivery.class);
        verify(notificationService).deliver(captor.capture());
        NotificationService.Delivery delivery = captor.getValue();
        assertThat(delivery.severity()).isEqualTo("ERROR");
        assertThat(delivery.dedupKey()).startsWith("automation-failure:");
        assertThat(delivery.jobId()).isNull(); // no false correlation from a broken payload
    }

    @Test
    void unknownEventTypeNeverReachesDelivery() {
        Envelope envelope = envelope("some.other.event", "JOB", UuidV7.generate(), Map.of());
        // supports() is the gate; handle() on an unsupported type would still
        // deliver generically, so verify the gate rejects it outright.
        assertThat(handler.supports(envelope.type())).isFalse();
        verify(notificationService, never()).deliver(any());
    }

    @Test
    void deliveryFailurePropagatesForOutboxRetry() {
        UUID jobId = UuidV7.generate();
        Mockito.doThrow(new IllegalStateException("db down"))
                .when(notificationService).deliver(any());

        Envelope envelope = envelope(NotificationEvents.JOB_MATCHED, "JOB", jobId, Map.of("job_id", jobId.toString()));

        assertThatThrownBy(() -> handler.handle(envelope))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Map<String, Object> merge(Map<String, Object> a, Map<String, Object> b) {
        java.util.Map<String, Object> out = new java.util.HashMap<>(a);
        out.putAll(b);
        return out;
    }
}
