package com.personal.jobagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.notifications.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The migration kill switch {@code app.events.dispatch-enabled} (plan §6, §10.7). */
class OutboxDispatchSwitchTest {

    @Test
    void pausedDispatchNeitherReadsNorWritesTheOutbox() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EventPublisher publisher = mock(EventPublisher.class);
        NotificationRepository notifications = mock(NotificationRepository.class);
        OutboxProcessor processor = new OutboxProcessor(jdbc, new ObjectMapper(), publisher, notifications);
        ReflectionTestUtils.setField(processor, "dispatchEnabled", false);

        processor.dispatchPending();
        processor.reconcileDlq();
        processor.dispatchPending();

        assertThat(processor.isDispatchEnabled()).isFalse();
        verifyNoInteractions(jdbc, publisher, notifications);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchIsOnUnlessTurnedOff() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        OutboxProcessor processor = new OutboxProcessor(jdbc, new ObjectMapper(), mock(EventPublisher.class),
                mock(NotificationRepository.class));

        processor.dispatchPending();

        assertThat(processor.isDispatchEnabled()).isTrue();
        verify(jdbc).query(anyString(), any(RowMapper.class), any(), any());
    }
}
