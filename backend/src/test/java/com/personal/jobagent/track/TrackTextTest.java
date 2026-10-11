package com.personal.jobagent.track;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Bounded, redacting text helpers for untrusted message content. */
class TrackTextTest {

    @Test
    void extractsLinksWithoutTrailingPunctuation() {
        assertThat(TrackText.urls("See https://boards.greenhouse.io/acme/jobs/4001. Or (https://acme.example/careers/42)!"))
                .containsExactly("https://boards.greenhouse.io/acme/jobs/4001", "https://acme.example/careers/42");
    }

    @Test
    void onlyLabelledRequisitionsAreTokens() {
        assertThat(TrackText.requisitionTokens("Requisition ID: R-1001, Req #4471 and Job Number: JR-2026-042"))
                .contains("r-1001", "4471", "jr-2026-042");
        assertThat(TrackText.requisitionTokens("Call 4471 now")).isEmpty();
    }

    @Test
    void referencesMustBeLabelledAndContainADigit() {
        assertThat(TrackText.references("Application reference: APP-778812")).containsExactly("APP-778812");
        assertThat(TrackText.references("Application reference: please keep this email")).isEmpty();
    }

    @Test
    void redactionRemovesLinksAddressesAndLongNumbers() {
        String redacted = TrackText.redact("Mail jobs@acme.example or call 0207 946 0000, see https://acme.example/x?t=secret");
        assertThat(redacted).doesNotContain("jobs@acme.example").doesNotContain("0207").doesNotContain("secret")
                .contains("[email address]", "[number]", "[link]");
    }

    @Test
    void domainAndAddressParsing() {
        assertThat(TrackText.domainOf("Acme Talent <Talent@Acme.Example>")).isEqualTo("acme.example");
        assertThat(TrackText.addressOf("Acme Talent <Talent@Acme.Example>")).isEqualTo("talent@acme.example");
        assertThat(TrackText.domainOf("no address here")).isNull();
    }

    @Test
    void pathologicalWhitespaceDoesNotBacktrack() {
        String text = "job" + " ".repeat(200_000) + "#";
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            TrackText.requisitionTokens(text);
            TrackText.references("application" + " ".repeat(200_000) + "reference");
        });
    }
}
