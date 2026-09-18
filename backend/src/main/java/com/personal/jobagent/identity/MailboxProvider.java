package com.personal.jobagent.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MailboxProvider {
    String providerId();
    boolean isConfigured();
    List<MailMessage> poll(String dedicatedAddress, Instant after, UUID applicationId);
    record MailMessage(String messageId,String from,String to,String subject,String body,Instant receivedAt) {}
}
