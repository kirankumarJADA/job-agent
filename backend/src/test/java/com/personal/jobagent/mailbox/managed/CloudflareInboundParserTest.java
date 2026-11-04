package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CloudflareInboundParserTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parsesValidCloudflareJsonPayload() {
        String payload = """
                {
                  "envelopeRecipient": "robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com",
                  "envelopeSender": "recruiter@acme.example",
                  "from": "\\"Acme HR\\" <recruiter@acme.example>",
                  "subject": "Interview Scheduling",
                  "text": "Hello, please schedule your interview at https://acme.example/interview/42",
                  "html": "<p>Hello, please schedule your interview at <a href=\\"https://acme.example/interview/42\\">link</a></p>",
                  "messageId": "<msg-42@acme.example>",
                  "date": "Sat, 24 Oct 2026 01:00:00 +0000",
                  "spf": "pass",
                  "dkim": "pass",
                  "spam": "no"
                }
                """;

        CloudflareInboundParser.Result result = CloudflareInboundParser.parse(payload, json);
        assertThat(result.accepted()).isTrue();
        InboundMailMessage msg = result.message();
        assertThat(msg.providerId()).isEqualTo(ManagedMailSettings.PROVIDER_CLOUDFLARE);
        assertThat(msg.envelopeRecipient()).isEqualTo("robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com");
        assertThat(msg.envelopeSender()).isEqualTo("recruiter@acme.example");
        assertThat(msg.fromHeader()).isEqualTo("\"Acme HR\" <recruiter@acme.example>");
        assertThat(msg.fromAddress()).isEqualTo("recruiter@acme.example");
        assertThat(msg.senderDomain()).isEqualTo("acme.example");
        assertThat(msg.subject()).isEqualTo("Interview Scheduling");
        assertThat(msg.text()).contains("https://acme.example/interview/42");
        assertThat(msg.links()).contains("https://acme.example/interview/42");
        assertThat(msg.messageId()).isEqualTo("msg-42@acme.example");
        assertThat(msg.spfResult()).isEqualTo("pass");
        assertThat(msg.dkimResult()).isEqualTo("pass");
        assertThat(msg.spamFlagged()).isFalse();
        assertThat(msg.authenticationFailed()).isFalse();
        assertThat(msg.threadReferences()).isEmpty();
    }

    @Test
    void readsReplyHeadersAsThreadReferencesNewestFirst() {
        String payload = """
                {
                  "envelopeRecipient": "robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com",
                  "from": "recruiter@acme.example",
                  "subject": "Re: Interview",
                  "text": "Thursday works.",
                  "messageId": "<reply-3@acme.example>",
                  "inReplyTo": "<msg-2@acme.example>",
                  "references": "<msg-1@acme.example> <msg-2@acme.example>"
                }
                """;

        InboundMailMessage msg = CloudflareInboundParser.parse(payload, json).message();

        assertThat(msg.threadReferences()).containsExactly("msg-2@acme.example", "msg-1@acme.example");
    }

    @Test
    void normalisesThreadReferencesLikeMessageIds() {
        assertThat(CloudflareInboundParser.threadReferences("bare-id@acme.example", null))
                .containsExactly("bare-id@acme.example");
        assertThat(CloudflareInboundParser.threadReferences(null, "<a@x.example>\r\n <b@x.example>"))
                .containsExactly("b@x.example", "a@x.example");
        assertThat(CloudflareInboundParser.threadReferences("not an id", "  ")).isEmpty();
        assertThat(CloudflareInboundParser.threadReferences(null, null)).isEmpty();
        assertThat(CloudflareInboundParser.threadReferences("<" + "x".repeat(256) + "@a.example>", null)).isEmpty();
    }

    @Test
    void boundsThreadReferencesAndPrefersTheNewest() {
        StringBuilder references = new StringBuilder();
        for (int i = 0; i < 60; i++) references.append("<m-").append(i).append("@acme.example> ");

        var refs = CloudflareInboundParser.threadReferences("<m-59@acme.example>", references.toString());

        assertThat(refs).hasSize(CloudflareInboundParser.MAX_THREAD_REFERENCES);
        assertThat(refs.get(0)).isEqualTo("m-59@acme.example");
        assertThat(refs.get(1)).isEqualTo("m-58@acme.example");
        assertThat(refs).doesNotHaveDuplicates();
    }

    @Test
    void rejectsEmptyOrMalformedJson() {
        assertThat(CloudflareInboundParser.parse("", json).accepted()).isFalse();
        assertThat(CloudflareInboundParser.parse("not json", json).accepted()).isFalse();
        assertThat(CloudflareInboundParser.parse("[]", json).accepted()).isFalse();
    }

    @Test
    void rejectsMissingOrInvalidEnvelopeRecipient() {
        String noRecipient = """
                {
                  "envelopeSender": "recruiter@acme.example",
                  "subject": "Hello",
                  "text": "World"
                }
                """;
        assertThat(CloudflareInboundParser.parse(noRecipient, json).accepted()).isFalse();

        String badRecipient = """
                {
                  "envelopeRecipient": "invalid-address-without-at",
                  "subject": "Hello"
                }
                """;
        assertThat(CloudflareInboundParser.parse(badRecipient, json).accepted()).isFalse();
    }

    @Test
    void extractsPlaintextFromHtmlWhenTextIsMissing() {
        String htmlOnly = """
                {
                  "envelopeRecipient": "robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com",
                  "html": "<div><p>Hello candidate,</p><p>Check this <a href=\\"https://careers.example/status\\">status</a></p></div>"
                }
                """;

        CloudflareInboundParser.Result result = CloudflareInboundParser.parse(htmlOnly, json);
        assertThat(result.accepted()).isTrue();
        assertThat(result.message().text()).contains("Hello candidate");
        assertThat(result.message().links()).contains("https://careers.example/status");
    }
}
