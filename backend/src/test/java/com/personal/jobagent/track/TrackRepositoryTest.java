package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.receipt.ReceiptCaptureService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrackRepositoryTest {

    @Test
    void submittedPlanRequiresIntegrityVerifiedReceiptAndFailsClosedWhenUnwired() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        JdbcTemplate jdbc = stubSubmittedPlanJdbc(profileId, appId, planId, true);
        ObjectMapper json = new ObjectMapper();

        // 2-arg legacy constructor without ReceiptCaptureService fails closed.
        TrackRepository unwired = new TrackRepository(jdbc, json);
        assertThat(unwired.snapshotParts(profileId, appId).plans().get(appId).verifiedReceipt()).isFalse();

        // Integrity verification returning false never marks verifiedReceipt true.
        ReceiptCaptureService failedIntegrity = mock(ReceiptCaptureService.class);
        when(failedIntegrity.isStoredReceiptIntegrityVerified(profileId, appId, planId)).thenReturn(false);
        TrackRepository untrusted = new TrackRepository(jdbc, json, failedIntegrity);
        assertThat(untrusted.snapshotParts(profileId, appId).plans().get(appId).verifiedReceipt()).isFalse();

        // Integrity verification returning true marks verifiedReceipt true.
        ReceiptCaptureService validIntegrity = mock(ReceiptCaptureService.class);
        when(validIntegrity.isStoredReceiptIntegrityVerified(profileId, appId, planId)).thenReturn(true);
        TrackRepository verified = new TrackRepository(jdbc, json, validIntegrity);
        assertThat(verified.snapshotParts(profileId, appId).plans().get(appId).verifiedReceipt()).isTrue();
    }

    @Test
    void databaseOutageDuringReceiptRevalidationPropagatesDataAccessException() throws Exception {
        UUID profileId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();

        JdbcTemplate jdbc = stubSubmittedPlanJdbc(profileId, appId, planId, true);
        ReceiptCaptureService outageService = mock(ReceiptCaptureService.class);
        when(outageService.isStoredReceiptIntegrityVerified(profileId, appId, planId))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        TrackRepository repo = new TrackRepository(jdbc, new ObjectMapper(), outageService);
        assertThatThrownBy(() -> repo.snapshotParts(profileId, appId))
                .isInstanceOf(DataAccessException.class);
    }

    private static JdbcTemplate stubSubmittedPlanJdbc(
            UUID profileId, UUID appId, UUID planId, boolean receiptTablesPresent) throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(receiptTablesPresent);
        doAnswer(inv -> {
            String sql = inv.getArgument(0, String.class);
            RowCallbackHandler rch = inv.getArgument(1, RowCallbackHandler.class);
            if (sql.contains("from automation_plans")) {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getObject("application_id")).thenReturn(appId);
                when(rs.getObject("plan_id")).thenReturn(planId);
                when(rs.getString("status")).thenReturn("SUBMITTED");
                when(rs.getBoolean("submit_approved")).thenReturn(true);
                when(rs.getString("result")).thenReturn(null);
                rch.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        return jdbc;
    }
}
