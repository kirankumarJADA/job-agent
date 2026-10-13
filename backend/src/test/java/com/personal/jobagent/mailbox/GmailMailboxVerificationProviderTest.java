package com.personal.jobagent.mailbox;

import com.personal.jobagent.gmail.GmailConnectionStore;
import com.personal.jobagent.gmail.GmailOAuthClient;
import com.personal.jobagent.gmail.GmailSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GmailMailboxVerificationProviderTest {
  @Test
  void resolvesContextualOtpFromConnectedMailboxAndHonoursRequestedWindow() {
    UUID profile=UUID.randomUUID(), emailId=UUID.randomUUID();
    Instant after=Instant.now().minus(15, ChronoUnit.MINUTES);
    GmailConnectionStore connections=mock(GmailConnectionStore.class);
    GmailSyncService sync=mock(GmailSyncService.class);
    when(connections.find(profile)).thenReturn(Optional.of(new GmailConnectionStore.Connection(
      UUID.randomUUID(),profile,"candidate@example.com","encrypted-token",GmailOAuthClient.SCOPE_READONLY,
      "CONNECTED",null,null,null,null)));
    when(sync.sync(profile)).thenReturn(new GmailSyncService.SyncOutcome("OK",1,1,100L,null));
    JdbcTemplate db=new JdbcTemplate(){
      @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
        assertThat(args).containsExactly(profile,"%@example.com%",after);
        return List.of(Map.of("id",emailId,"message_id","message-123","from_address","Security <security@example.com>",
          "to_address","Candidate <candidate@example.com>","subject","Your verification code",
          "body_text","Use 483921 to finish signing in.","received_at",Timestamp.from(Instant.now().minus(2,ChronoUnit.MINUTES))));
      }
    };
    var hit=new GmailMailboxVerificationProvider(connections,sync,db)
      .findVerification(profile,"example.com",null,after).orElseThrow();
    assertThat(hit.otp()).isEqualTo("483921");
    assertThat(hit.sourceEmailId()).isEqualTo(emailId);
  }
  @Test
  void refusesMultipleMatchingMessagesRatherThanGuessing() {
    UUID profile=UUID.randomUUID();
    Instant after=Instant.now().minus(15, ChronoUnit.MINUTES);
    GmailConnectionStore connections=mock(GmailConnectionStore.class);
    GmailSyncService sync=mock(GmailSyncService.class);
    when(connections.find(profile)).thenReturn(Optional.of(new GmailConnectionStore.Connection(
      UUID.randomUUID(),profile,"candidate@example.com","encrypted-token",GmailOAuthClient.SCOPE_READONLY,
      "CONNECTED",null,null,null,null)));
    when(sync.sync(profile)).thenReturn(new GmailSyncService.SyncOutcome("OK",2,2,100L,null));
    JdbcTemplate db=new JdbcTemplate(){
      @Override public List<Map<String,Object>> queryForList(String sql,Object...args){
        return List.of(
          Map.of("id",UUID.randomUUID(),"message_id","m1","from_address","security@example.com","to_address","candidate@example.com",
            "subject","Verification code","body_text","Your code is 111111","received_at",Timestamp.from(Instant.now().minus(2,ChronoUnit.MINUTES))),
          Map.of("id",UUID.randomUUID(),"message_id","m2","from_address","security@example.com","to_address","candidate@example.com",
            "subject","Verification code","body_text","Your code is 222222","received_at",Timestamp.from(Instant.now().minus(1,ChronoUnit.MINUTES))));
      }
    };
    assertThat(new GmailMailboxVerificationProvider(connections,sync,db)
      .findVerification(profile,"example.com",null,after)).isEmpty();
  }
}
