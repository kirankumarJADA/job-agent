package com.personal.jobagent.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.notifications.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Database migration Option C (V052): which outbox query the dispatcher and
 * the dead-letter sweep run, with and without an active exclusion batch.
 * The behaviour against a real database is in OutboxDiscoveryExclusionIT.
 */
class OutboxExclusionDispatchTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final EventPublisher publisher = mock(EventPublisher.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final OutboxProcessor processor = new OutboxProcessor(jdbc, new ObjectMapper(), publisher, notifications);

    private void watermark(Long value) {
        when(jdbc.queryForObject(OutboxProcessor.EXCLUSION_WATERMARK_SQL, Long.class)).thenReturn(value);
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutAnActiveBatchTheOriginalQueriesRun() {
        watermark(0L);
        when(jdbc.query(eq(OutboxProcessor.PENDING_SQL), any(RowMapper.class), eq(5), eq(50))).thenReturn(List.of());

        processor.dispatchPending();
        processor.reconcileDlq();

        verify(jdbc).query(eq(OutboxProcessor.PENDING_SQL), any(RowMapper.class), eq(5), eq(50));
        verify(jdbc).queryForList(OutboxProcessor.DEAD_LETTER_SQL, 5);
        verify(jdbc, never()).query(eq(OutboxProcessor.PENDING_WITH_EXCLUSION_SQL), any(RowMapper.class),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aMissingWatermarkCountsAsNoBatch() {
        watermark(null);

        processor.dispatchPending();

        assertThat(processor.exclusionWatermark()).isZero();
        verify(jdbc).query(eq(OutboxProcessor.PENDING_SQL), any(RowMapper.class), eq(5), eq(50));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anActiveBatchSwitchesBothPathsToTheExclusionQueries() {
        watermark(771_636L);
        when(jdbc.query(eq(OutboxProcessor.PENDING_WITH_EXCLUSION_SQL), any(RowMapper.class),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        processor.dispatchPending();
        processor.reconcileDlq();

        verify(jdbc).query(eq(OutboxProcessor.PENDING_WITH_EXCLUSION_SQL), any(RowMapper.class),
                eq(5), eq(771_636L), eq(50), eq(5), eq(771_636L), eq(50), eq(50));
        verify(jdbc).queryForList(OutboxProcessor.DEAD_LETTER_WITH_EXCLUSION_SQL, 5, 771_636L);
        verify(jdbc, never()).query(eq(OutboxProcessor.PENDING_SQL), any(RowMapper.class), any(), any());
        verify(jdbc, never()).queryForList(OutboxProcessor.DEAD_LETTER_SQL, 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreadableWatermarkFailsTheRoundInsteadOfDispatchingEverything() {
        when(jdbc.queryForObject(OutboxProcessor.EXCLUSION_WATERMARK_SQL, Long.class))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(processor::dispatchPending).isInstanceOf(DataAccessResourceFailureException.class);
        assertThatThrownBy(processor::reconcileDlq).isInstanceOf(DataAccessResourceFailureException.class);

        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(), any());
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
        verifyNoInteractions(publisher, notifications);
    }

    @Test
    void theExclusionQueriesNameOnlyJobDiscovered() {
        // The partial index outbox_pending_non_discovery_idx (V052) matches only
        // this literal; the dead-letter sweep must skip exactly the same set.
        assertThat(OutboxProcessor.PENDING_WITH_EXCLUSION_SQL)
                .contains("event_type <> 'job.discovered'")
                .contains("sequence_id > ?")
                .contains("sequence_id <= ?");
        assertThat(OutboxProcessor.DEAD_LETTER_WITH_EXCLUSION_SQL)
                .contains("not (event_type = 'job.discovered' and sequence_id <= ?)");
    }
}
