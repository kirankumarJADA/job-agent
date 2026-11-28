package com.personal.jobagent.mailbox.managed;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
 * @param threadReferences  normalised Message-Ids this message replies to: the
 *                          In-Reply-To id first, then References newest first.
 *                          Sender-supplied, so only ever a hint that the
 *                          owner-scoped thread lookup can confirm.
 * @param dkimDomain        verified DKIM signing domain ({@code header.d} / {@code header.i})
 *                          from a trusted receiver Authentication-Results header, or null.
 *                          Never derived from the unauthenticated {@code From} header.
 * @param dmarcResult       DMARC verdict from the same trusted receiver header, or null.
 * @param authServId        receiver {@code authserv-id} that produced the verdict, or null.
 * @param authConflict      true when conflicting authentication headers/clauses were detected.
 */
public record InboundMailMessage(String providerId, String envelopeRecipient, String envelopeSender,
                                 String fromHeader, String fromAddress, String subject, String text,
                                 List<String> links, String messageId, String date,
                                 List<String> spfResults, List<String> dkimResults, List<String> spamFlags,
                                 List<String> threadReferences,
                                 String dkimDomain, String dmarcResult, String authServId, boolean authConflict) {

    private static final Pattern VALID_DOMAIN = Pattern.compile(
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$");
    private static final Pattern VALID_AUTHSERV = Pattern.compile("^[a-z0-9._-]+$");

    public InboundMailMessage {
        links = List.copyOf(links);
        spfResults = List.copyOf(spfResults);
        dkimResults = List.copyOf(dkimResults);
        spamFlags = List.copyOf(spamFlags);
        threadReferences = threadReferences == null ? List.of() : List.copyOf(threadReferences);
        dkimDomain = normaliseDomain(dkimDomain);
        dmarcResult = normaliseToken(dmarcResult);
        authServId = normaliseAuthServId(authServId);
    }

    /** Convenience constructor preserving the 14-arg shape without trusted DKIM/DMARC provenance. */
    public InboundMailMessage(String providerId, String envelopeRecipient, String envelopeSender,
                              String fromHeader, String fromAddress, String subject, String text,
                              List<String> links, String messageId, String date,
                              List<String> spfResults, List<String> dkimResults, List<String> spamFlags,
                              List<String> threadReferences) {
        this(providerId, envelopeRecipient, envelopeSender, fromHeader, fromAddress, subject, text, links,
                messageId, date, spfResults, dkimResults, spamFlags, threadReferences, null, null, null, false);
    }

    /** A message from a provider that does not report reply headers. */
    public InboundMailMessage(String providerId, String envelopeRecipient, String envelopeSender,
                              String fromHeader, String fromAddress, String subject, String text,
                              List<String> links, String messageId, String date,
                              List<String> spfResults, List<String> dkimResults, List<String> spamFlags) {
        this(providerId, envelopeRecipient, envelopeSender, fromHeader, fromAddress, subject, text, links,
                messageId, date, spfResults, dkimResults, spamFlags, List.of(), null, null, null, false);
    }

    /** Domain of the From mailbox, or null. */
    public String senderDomain() {
        return MailAddresses.domainOf(fromAddress);
    }

    /**
     * True when the provider reported a failed sender check, reported conflicting
     * verdicts across headers, or flagged an authentication conflict.
     */
    public boolean authenticationFailed() {
        if (authConflict) return true;
        if ("fail".equals(dmarcResult) || "permerror".equals(dmarcResult)) return true;
        return failedOrConflicting(spfResults) || failedOrConflicting(dkimResults);
    }

    public boolean spamFlagged() {
        return spamFlags.stream().anyMatch(flag -> !flag.equals("no"));
    }

    /** The provider's SPF verdict when it reported a single consistent value and no conflict. */
    public String spfResult() {
        return authConflict ? "fail" : single(spfResults);
    }

    /** The provider's DKIM verdict when consistent and not conflicted, else null/fail. */
    public String dkimResult() {
        return authConflict ? "fail" : single(dkimResults);
    }

    private static boolean failedOrConflicting(List<String> values) {
        if (values.stream().distinct().count() > 1) return true;
        return values.stream().anyMatch(value -> value.equals("fail") || value.equals("permerror"));
    }

    private static String single(List<String> values) {
        return values.stream().distinct().count() == 1 ? values.get(0) : null;
    }

    private static String normaliseDomain(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        while (cleaned.startsWith("@")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return VALID_DOMAIN.matcher(cleaned).matches() ? cleaned : null;
    }

    private static String normaliseToken(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        return cleaned.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_')
                ? cleaned : null;
    }

    private static String normaliseAuthServId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return VALID_AUTHSERV.matcher(cleaned).matches() ? cleaned : null;
    }
}
