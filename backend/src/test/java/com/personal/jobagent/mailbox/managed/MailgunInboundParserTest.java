package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class MailgunInboundParserTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Map<String, String[]> base() {
        Map<String, String[]> form = new HashMap<>();
        form.put("recipient", new String[]{"U7KQ2M9X4VB3TN5P@mail.robin.example"});
        form.put("sender", new String[]{"bounces+123@esp.example"});
        form.put("from", new String[]{"Employer Talent <no-reply@employer.example>"});
        form.put("subject", new String[]{"Your verification code"});
        form.put("body-plain", new String[]{"Your verification code is 482913. https://employer.example/help"});
        form.put("body-html", new String[]{"<p>Your verification code is <b>482913</b></p>"
                + "<a href=\"https://employer.example/verify?token=a&amp;b=1\">Verify</a>"});
        form.put("message-headers", new String[]{"[[\"Message-Id\", \"<ABC123@mail.employer.example>\"],"
                + "[\"Date\", \"Sat, 11 Oct 2026 10:00:00 +0000\"],"
                + "[\"To\", \"someone-else@mail.robin.example\"],"
                + "[\"X-Mailgun-Spf\", \"Pass\"], [\"X-Mailgun-Dkim-Check-Result\", \"Pass\"],"
                + "[\"X-Mailgun-Sflag\", \"No\"]]"});
        form.put("timestamp", new String[]{"1760184000"});
        form.put("token", new String[]{"c3a4f1d29b8e7f60a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9"});
        form.put("signature", new String[]{"f102cbd9018b11e30820d735916beae558e4930e6379af3854d5cec7378f4a06"});
        return form;
    }

    private MailgunInboundParser.Result parse(Map<String, String[]> form) {
        Function<String, String[]> params = form::get;
        return MailgunInboundParser.parse(params, json);
    }

    @Test
    void parsesTheDocumentedFieldsRoutingOnTheEnvelopeRecipient() {
        MailgunInboundParser.Result result = parse(base());
        assertThat(result.accepted()).isTrue();
        InboundMailMessage m = result.message();
        // Routing key is the SMTP envelope recipient, not the visible To header.
        assertThat(m.envelopeRecipient()).isEqualTo("u7kq2m9x4vb3tn5p@mail.robin.example");
        assertThat(m.envelopeSender()).isEqualTo("bounces+123@esp.example");
        assertThat(m.fromAddress()).isEqualTo("no-reply@employer.example");
        assertThat(m.senderDomain()).isEqualTo("employer.example");
        assertThat(m.messageId()).isEqualTo("<abc123@mail.employer.example>");
        assertThat(m.links()).containsExactly("https://employer.example/help",
                "https://employer.example/verify?token=a&b=1");
        assertThat(m.authenticationFailed()).isFalse();
        assertThat(m.spamFlagged()).isFalse();
        assertThat(m.spfResult()).isEqualTo("pass");
    }

    @Test
    void htmlOnlyMessagesAreReducedToText() {
        Map<String, String[]> form = base();
        form.remove("body-plain");
        form.put("body-html", new String[]{"<html><head><style>p{color:red}</style><script>var otp='999999'</script></head>"
                + "<body><p>Your verification code is</p><p><b>482913</b></p></body></html>"});
        InboundMailMessage m = parse(form).message();
        assertThat(m.text()).contains("Your verification code is").contains("482913")
                .doesNotContain("999999").doesNotContain("color:red");
    }

    @Test
    void refusesMalformedOrPollutedRequests() {
        Map<String, String[]> noRecipient = base();
        noRecipient.remove("recipient");
        assertThat(parse(noRecipient).rejection()).isEqualTo("INVALID_RECIPIENT");

        Map<String, String[]> twoRecipients = base();
        twoRecipients.put("recipient", new String[]{"a@mail.robin.example, b@mail.robin.example"});
        assertThat(parse(twoRecipients).rejection()).isEqualTo("INVALID_RECIPIENT");

        Map<String, String[]> duplicated = base();
        duplicated.put("recipient", new String[]{"a@mail.robin.example", "b@mail.robin.example"});
        assertThat(parse(duplicated).rejection()).isEqualTo("DUPLICATED_FIELD");

        Map<String, String[]> badHeaders = base();
        badHeaders.put("message-headers", new String[]{"{not json"});
        assertThat(parse(badHeaders).rejection()).isEqualTo("MALFORMED_HEADERS");

        Map<String, String[]> wrongShape = base();
        wrongShape.put("message-headers", new String[]{"[[\"Subject\"]]"});
        assertThat(parse(wrongShape).rejection()).isEqualTo("MALFORMED_HEADERS");
    }

    @Test
    void conflictingOrFailedSenderVerdictsMarkTheMessage() {
        Map<String, String[]> forgedPass = base();
        // The sender added its own "Pass" next to the provider's "Fail".
        forgedPass.put("message-headers", new String[]{"[[\"X-Mailgun-Spf\", \"Fail\"], [\"X-Mailgun-Spf\", \"Pass\"]]"});
        assertThat(parse(forgedPass).message().authenticationFailed()).isTrue();

        Map<String, String[]> dkimFail = base();
        dkimFail.put("message-headers", new String[]{"[[\"X-Mailgun-Dkim-Check-Result\", \"Fail\"]]"});
        assertThat(parse(dkimFail).message().authenticationFailed()).isTrue();

        Map<String, String[]> spam = base();
        spam.put("message-headers", new String[]{"[[\"X-Mailgun-Sflag\", \"Yes\"]]"});
        assertThat(parse(spam).message().spamFlagged()).isTrue();
    }

    @Test
    void anAmbiguousFromHeaderLeavesNoSenderEvidence() {
        Map<String, String[]> form = base();
        form.put("from", new String[]{"no-reply@employer.example <x@attacker.example>"});
        InboundMailMessage m = parse(form).message();
        assertThat(m.fromAddress()).isNull();
        assertThat(m.senderDomain()).isNull();
    }

    @Test
    void thePayloadDigestChangesWithContentButNotWithUnreadFields() {
        Map<String, String[]> form = base();
        String digest = MailgunInboundParser.payloadDigest(form::get);
        Map<String, String[]> withAttachment = base();
        withAttachment.put("attachment-count", new String[]{"1"});
        assertThat(MailgunInboundParser.payloadDigest(withAttachment::get)).isEqualTo(digest);
        Map<String, String[]> changed = base();
        changed.put("body-plain", new String[]{"Your verification code is 000000."});
        assertThat(MailgunInboundParser.payloadDigest(changed::get)).isNotEqualTo(digest);
    }
}
