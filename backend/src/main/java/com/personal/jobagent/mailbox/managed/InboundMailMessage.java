package com.personal.jobagent.mailbox.managed;

import java.util.List;

/**
 * One inbound message as delivered by a receiving provider, already parsed,
 * validated and capped. Provider-neutral, so a second inbound provider only
 * needs its own parser.
 *
 * @param envelopeRecipient the SMTP envelope recipient (RCPT TO), validated and
 *                          lower-cased. Routing uses this and only this: the
 *                          visible To header is sender-controlled.
 * @param envelopeSender    the SMTP envelope sender (MAIL FROM), when given
 * @param fromHeader        the From header as delivered, capped
 * @param fromAddress       the single mailbox in the From header, or null when
 *                          it is missing or ambiguous (then the message is
 *                          never evidence of who sent it)
 * @param subject           capped subject (may contain a code: never log it)
 * @param text              capped plain text (may contain a code: never log it)
 * @param links             distinct http(s) links from the text and HTML parts
 * @param messageId         normalised Message-Id, or null when absent or repeated
 * @param date              the Date header, or null
 * @param spfResults        every provider SPF verdict header value, lower-cased
 * @param dkimResults       every provider DKIM verdict header value, lower-cased
 * @param spamFlags         every provider spam-flag header value, lower-cased
 */
public record InboundMailMessage(String providerId, String envelopeRecipient, String envelopeSender,
                                 String fromHeader, String fromAddress, String subject, String text,
                                 List<String> links, String messageId, String date,
                                 List<String> spfResults, List<String> dkimResults, List<String> spamFlags) {

    public InboundMailMessage {
        links = List.copyOf(links);
        spfResults = List.copyOf(spfResults);
        dkimResults = List.copyOf(dkimResults);
        spamFlags = List.copyOf(spamFlags);
    }

    /** Domain of the From mailbox, or null. */
    public String senderDomain() {
        return MailAddresses.domainOf(fromAddress);
    }

    /**
     * True when the provider reported a failed sender check, or reported
     * conflicting verdicts (a sender can add its own copy of these headers, so
     * a disagreement is treated as a failure). A "pass" is never treated as
     * proof of anything: these headers can only exclude a message.
     */
    public boolean authenticationFailed() {
        return failedOrConflicting(spfResults) || failedOrConflicting(dkimResults);
    }

    public boolean spamFlagged() {
        return spamFlags.stream().anyMatch(flag -> !flag.equals("no"));
    }

    /** The provider's SPF verdict when it reported exactly one, else null. */
    public String spfResult() {
        return single(spfResults);
    }

    /** The provider's DKIM verdict when it reported exactly one, else null. */
    public String dkimResult() {
        return single(dkimResults);
    }

    private static boolean failedOrConflicting(List<String> values) {
        if (values.stream().distinct().count() > 1) return true;
        return values.stream().anyMatch(value -> value.equals("fail") || value.equals("permerror"));
    }

    private static String single(List<String> values) {
        return values.stream().distinct().count() == 1 ? values.get(0) : null;
    }
}
