package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.common.MaintenanceModeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ManagedMailMaintenanceTest {

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ManagedMailSettings settings = mock(ManagedMailSettings.class);
    private final ManagedMailboxService mailboxes = mock(ManagedMailboxService.class);
    private final ManagedMessageContent content = mock(ManagedMessageContent.class);

    @BeforeEach
    void setUp() {
        reset(db, settings, mailboxes, content);
        when(settings.contentRetention()).thenReturn(Duration.ofHours(2));
        when(settings.metadataRetention()).thenReturn(Duration.ofDays(30));
        when(settings.tokenRetention()).thenReturn(Duration.ofHours(24));
    }

    @Test
    @DisplayName("When maintenance mode is active, ManagedMailRetentionJob.sweep() and purge() perform 0 DB updates/deletes")
    void retentionJobPerformsZeroWritesWhenMaintenanceActive() {
        MaintenanceModeService maintenanceMode = new MaintenanceModeService(true);
        ManagedMailRetentionJob job = new ManagedMailRetentionJob(db, settings, maintenanceMode);

        job.sweep();
        verify(db, never()).update(anyString(), any(Object[].class));

        ManagedMailRetentionJob.Result result = job.purge(Instant.now());
        assertThat(result.total()).isZero();
        assertThat(result.contentErased()).isZero();
        assertThat(result.messagesDeleted()).isZero();
        assertThat(result.tokensDeleted()).isZero();
        verify(db, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("When maintenance mode is disabled, ManagedMailRetentionJob.purge() executes normal cleanup queries")
    void retentionJobExecutesNormallyWhenMaintenanceDisabled() {
        MaintenanceModeService maintenanceMode = new MaintenanceModeService(false);
        ManagedMailRetentionJob job = new ManagedMailRetentionJob(db, settings, maintenanceMode);

        ManagedMailRetentionJob.Result result = job.purge(Instant.now());

        // Verify the bounded lease-expiration, content-erase, metadata-delete, token-delete and email-scrub queries
        var invocations = mockingDetails(db).getInvocations().stream().toList();
        assertThat(invocations).hasSize(7);

        List<String> executedSql = invocations.stream()
                .map(inv -> inv.getArgument(0, String.class))
                .toList();
        assertThat(executedSql.get(0)).contains("update mailbox_verifications");
        assertThat(executedSql.get(1)).contains("update managed_inbound_messages");
        assertThat(executedSql.get(2)).contains("update managed_inbound_messages");
        assertThat(executedSql.get(3)).contains("delete from managed_inbound_messages");
        assertThat(executedSql.get(4)).contains("delete from managed_mail_webhook_tokens");
        assertThat(executedSql.get(5)).contains("delete from mailbox_verifications");
        assertThat(executedSql.get(6)).contains("update emails");
        assertThat(result.total()).isEqualTo(0); // unmocked db.update returns default 0
    }

    @Test
    @DisplayName("When maintenance mode is active, ManagedInboundService.accept() throws IllegalStateException before touching DB")
    void inboundServiceRefusesDeliveryBeforeAnyDatabaseWriteWhenMaintenanceActive() {
        MaintenanceModeService maintenanceMode = new MaintenanceModeService(true);
        ManagedInboundService service = new ManagedInboundService(db, settings, mailboxes, content, null, maintenanceMode);

        InboundMailMessage msg = mock(InboundMailMessage.class);

        assertThatThrownBy(() -> service.accept("cloudflare", msg, null, "test-token", Instant.now(), "payload-sha"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maintenance mode active");

        // Zero interactions with database or mailbox routing
        verify(db, never()).update(anyString(), any(Object[].class));
        verify(db, never()).queryForObject(anyString(), any(Class.class), any());
        verifyNoInteractions(mailboxes);
        verifyNoInteractions(content);
    }
}
