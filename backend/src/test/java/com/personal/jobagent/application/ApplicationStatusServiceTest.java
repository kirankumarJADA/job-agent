package com.personal.jobagent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.notifications.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationStatusServiceTest {

    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final ObjectMapper json = new ObjectMapper();
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final NotificationService notifications = mock(NotificationService.class);

    private ApplicationStatusService service;
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID APP_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ApplicationStatusService(db, json, audit, notifications);
    }

    @Test
    void allowedTransitionsFollowExplicitMatrix() {
        assertThat(ApplicationStatusService.isAllowed("READY_TO_APPLY", "APPLICATION_STARTED")).isTrue();
        assertThat(ApplicationStatusService.isAllowed("READY_TO_APPLY", "FAILED")).isTrue();
        assertThat(ApplicationStatusService.isAllowed("READY_TO_APPLY", "WITHDRAWN")).isTrue();
        assertThat(ApplicationStatusService.isAllowed("APPLICATION_STARTED", "APPLICATION_SUBMITTED")).isTrue();
        assertThat(ApplicationStatusService.isAllowed("APPLICATION_SUBMITTED", "INTERVIEW")).isTrue();
        assertThat(ApplicationStatusService.isAllowed("INTERVIEW", "OFFER")).isTrue();

        // Terminal states cannot transition
        assertThat(ApplicationStatusService.isAllowed("OFFER", "INTERVIEW")).isFalse();
        assertThat(ApplicationStatusService.isAllowed("REJECTED", "INTERVIEW")).isFalse();
        assertThat(ApplicationStatusService.isAllowed("WITHDRAWN", "READY_TO_APPLY")).isFalse();
        assertThat(ApplicationStatusService.isAllowed("FAILED", "APPLICATION_STARTED")).isFalse();
    }

    @Test
    void illegalTransitionRecordsAuditAndThrowsException() {
        // Mock existing status as REJECTED
        when(db.query(contains("select status from applications"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of("REJECTED"));

        assertThatThrownBy(() -> service.apply(PROFILE_ID, APP_ID, "INTERVIEW", "ev-1", "user", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid application transition REJECTED -> INTERVIEW");

        // Verify illegal transition was audited
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(audit).write(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.action()).isEqualTo("ILLEGAL_APPLICATION_TRANSITION_REJECTED");
        assertThat(entry.entityType()).isEqualTo("APPLICATION");
        assertThat(entry.entityId()).isEqualTo(APP_ID);
    }

    @Test
    void foreignApplicationThrowsNoSuchElementException() {
        // Query returns empty (not found for this profile)
        when(db.query(contains("select status from applications"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.apply(PROFILE_ID, APP_ID, "APPLICATION_STARTED", "ev-2", "user", Map.of()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("No application with that id");
    }

    @Test
    void successfulTransitionUpdatesStatusAndAudits() {
        when(db.query(contains("select status from applications"), any(RowMapper.class), eq(APP_ID), eq(PROFILE_ID)))
                .thenReturn(List.of("READY_TO_APPLY"));

        ApplicationStatusService.TransitionResult result =
                service.apply(PROFILE_ID, APP_ID, "APPLICATION_STARTED", "ev-3", "user", Map.of());

        assertThat(result.changed()).isTrue();
        assertThat(result.previousStatus()).isEqualTo("READY_TO_APPLY");
        assertThat(result.status()).isEqualTo("APPLICATION_STARTED");

        verify(db).update(contains("update applications set status=?"), eq("APPLICATION_STARTED"), eq(APP_ID), eq(PROFILE_ID), eq("READY_TO_APPLY"));
        verify(audit).write(argThat(entry -> "APPLICATION_STATUS_CHANGED".equals(entry.action())));
    }
}
