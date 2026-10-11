package com.personal.jobagent.track;

import com.personal.jobagent.track.ReceiptVerificationPolicy.Evidence;
import com.personal.jobagent.track.ReceiptVerificationPolicy.SenderAuthentication;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.Verification;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The documented receipt verification policy, rule by rule. */
class ReceiptVerificationPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final SenderAuthentication PASS = new SenderAuthentication("pass", "acme.example", "pass", "pass");

    private final ReceiptVerificationPolicy policy = new ReceiptVerificationPolicy();

    private static Evidence email(Channel channel, boolean identifiesThis, boolean identifiesOther, boolean explicit,
                                  SenderAuthentication auth, boolean delivered, Instant time) {
        return new Evidence(EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL, channel, identifiesThis, identifiesOther, explicit,
                "talent.acme.example", auth, delivered, time, NOW);
    }

    private static Evidence valid() {
        return email(Channel.MAILBOX_CONNECTOR, true, false, true, PASS, true, NOW.minus(Duration.ofHours(2)));
    }

    @Test
    void anAuthenticatedExplicitAcknowledgementForThisApplicationIsVerified() {
        var outcome = policy.evaluate(valid());
        assertThat(outcome.status()).isEqualTo(Verification.VERIFIED);
        assertThat(outcome.reasons()).extracting(r -> r.get("code"))
                .contains("IDENTIFIES_THIS_APPLICATION", "SENDER_AUTHENTICATED", "EXPLICIT_ACKNOWLEDGEMENT");
    }

    @Test
    void aForwardedMessageIsNeverVerifiedEvenWhenEverythingElseHolds() {
        var outcome = policy.evaluate(email(Channel.CANDIDATE_FORWARDED, true, false, true, PASS, true, NOW));
        assertThat(outcome.status()).isEqualTo(Verification.UNVERIFIED);
        assertThat(outcome.reasons()).extracting(r -> r.get("code")).contains("CHANNEL_NOT_AUTHENTICATED");
    }

    @Test
    void failedOrMisalignedSenderAuthenticationIsUnverified() {
        var fail = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, false, true,
                new SenderAuthentication("fail", "acme.example", "pass", "pass"), true, NOW));
        var misaligned = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, false, true,
                new SenderAuthentication("pass", "mailer.example", "pass", "pass"), true, NOW));
        var dmarcFail = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, false, true,
                new SenderAuthentication("pass", "acme.example", "pass", "fail"), true, NOW));
        assertThat(fail.status()).isEqualTo(Verification.UNVERIFIED);
        assertThat(misaligned.status()).isEqualTo(Verification.UNVERIFIED);
        assertThat(dmarcFail.status()).isEqualTo(Verification.UNVERIFIED);
        assertThat(fail.reasons()).extracting(r -> r.get("code")).contains("SENDER_NOT_AUTHENTICATED");
    }

    @Test
    void aMessageNotDeliveredToTheConnectedMailboxIsUnverified() {
        var outcome = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, false, true, PASS, false, NOW));
        assertThat(outcome.status()).isEqualTo(Verification.UNVERIFIED);
        assertThat(outcome.reasons()).extracting(r -> r.get("code")).contains("NOT_DELIVERED_TO_YOUR_MAILBOX");
    }

    @Test
    void wordingThatDoesNotExplicitlyAcknowledgeReceiptIsUnverified() {
        var outcome = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, false, false, PASS, true, NOW));
        assertThat(outcome.status()).isEqualTo(Verification.UNVERIFIED);
        assertThat(outcome.reasons()).extracting(r -> r.get("code")).contains("NO_EXPLICIT_ACKNOWLEDGEMENT");
    }

    @Test
    void evidenceForADifferentApplicationIsRejected() {
        var outcome = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, false, true, true, PASS, true, NOW));
        assertThat(outcome.status()).isEqualTo(Verification.REJECTED);
        assertThat(outcome.reasons()).extracting(r -> r.get("code")).containsExactly("IDENTIFIES_A_DIFFERENT_APPLICATION");
    }

    @Test
    void futureDatedEvidenceIsRejected() {
        var outcome = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, false, true, PASS, true, NOW.plus(Duration.ofDays(3))));
        assertThat(outcome.status()).isEqualTo(Verification.REJECTED);
    }

    @Test
    void evidenceThatDoesNotIdentifyThisApplicationIsUnknown() {
        var outcome = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, false, false, true, PASS, true, NOW));
        assertThat(outcome.status()).isEqualTo(Verification.UNKNOWN);
    }

    @Test
    void evidenceIdentifyingSeveralApplicationsIsUnknown() {
        var outcome = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, true, true, PASS, true, NOW));
        assertThat(outcome.status()).isEqualTo(Verification.UNKNOWN);
    }

    @Test
    void aCandidateStatementIsAlwaysUnverified() {
        var outcome = policy.evaluate(new Evidence(EvidenceType.CANDIDATE_STATEMENT, Channel.MAILBOX_CONNECTOR, true, false,
                true, "acme.example", PASS, true, NOW, NOW));
        assertThat(outcome.status()).isEqualTo(Verification.UNVERIFIED);
        assertThat(outcome.reasons()).extracting(r -> r.get("code")).containsExactly("CANDIDATE_STATEMENT");
    }

    @Test
    void anUnknownEvidenceTimeIsNotFabricatedAndDoesNotFailVerification() {
        var outcome = policy.evaluate(email(Channel.MAILBOX_CONNECTOR, true, false, true, PASS, true, null));
        assertThat(outcome.status()).isEqualTo(Verification.VERIFIED);
    }
}
