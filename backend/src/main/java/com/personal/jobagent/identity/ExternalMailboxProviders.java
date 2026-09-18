package com.personal.jobagent.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

public final class ExternalMailboxProviders {
    private ExternalMailboxProviders(){}
    abstract static class Base implements MailboxProvider {
        private final String id; private final String credential;
        Base(String id,String credential){this.id=id;this.credential=credential;}
        public String providerId(){return id;}
        public boolean isConfigured(){return credential!=null&&!credential.isBlank();}
        public List<MailMessage> poll(String dedicatedAddress,Instant after,UUID applicationId){
            if(dedicatedAddress==null||dedicatedAddress.isBlank())throw new IllegalArgumentException("dedicated mailbox address is required");
            if(applicationId==null)throw new IllegalArgumentException("application correlation is required");
            if(!isConfigured())throw new IllegalStateException(id+" mailbox credentials are not configured; no mailbox was accessed");
            throw new UnsupportedOperationException(id+" transport requires provider OAuth/IMAP configuration and is intentionally disabled in local mode");
        }
    }
    @Component("gmailMailboxProvider") public static class Gmail extends Base { public Gmail(@Value("${GMAIL_CREDENTIAL_REF:}") String c){super("gmail",c);} }
    @Component("outlookMailboxProvider") public static class Outlook extends Base { public Outlook(@Value("${OUTLOOK_CREDENTIAL_REF:}") String c){super("outlook",c);} }
    @Component("imapMailboxProvider") public static class Imap extends Base { public Imap(@Value("${IMAP_CREDENTIAL_REF:}") String c){super("imap",c);} }
}
