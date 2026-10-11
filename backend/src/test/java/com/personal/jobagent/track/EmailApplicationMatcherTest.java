package com.personal.jobagent.track;

import com.personal.jobagent.discovery.platform.JobIdentity;
import com.personal.jobagent.track.EmailApplicationMatcher.MessageEvidence;
import com.personal.jobagent.track.EmailApplicationMatcher.OwnedApplication;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.MatchStrength;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Message → application association: strong identifiers link, weak signals only
 * propose, similar roles are never guessed between, and a candidate's choice is
 * recorded as theirs and checked against the message's own identifiers.
 */
class EmailApplicationMatcherTest {

    private final EmailApplicationMatcher matcher = new EmailApplicationMatcher();

    private static final UUID A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-7000-8000-00000000000c");

    private static OwnedApplication app(UUID id, String company, String title, String url, String requisition) {
        Set<String> keys = new LinkedHashSet<>();
        for (JobIdentity.Key key : JobIdentity.keys(UUID.randomUUID(), "ext-" + id, url, url, company, requisition)) {
            keys.add(key.asString());
        }
        return new OwnedApplication(id, UUID.randomUUID(), title, company, "London", url, url, keys,
                Instant.parse("2026-09-01T10:00:00Z"), "READY_TO_APPLY");
    }

    private static final OwnedApplication ACME_PLATFORM = app(A, "Acme Ltd", "Platform Engineer",
            "https://boards.greenhouse.io/acme/jobs/4001", "R-1001");
    private static final OwnedApplication ACME_PLATFORM_TWO = app(B, "Acme Ltd", "Platform Engineer",
            "https://boards.greenhouse.io/acme/jobs/4002", "R-1002");
    private static final OwnedApplication GLOBEX_DATA = app(C, "Globex", "Data Analyst",
            "https://jobs.lever.co/globex/5ac0f0a0-1111-4222-8333-944444444444", null);

    private EmailApplicationMatcher.Association match(String from, String subject, String body, UUID asserted) {
        return matcher.match(MessageEvidence.of(from, subject, body), List.of(ACME_PLATFORM, ACME_PLATFORM_TWO, GLOBEX_DATA),
                asserted, EmailCategory.APPLICATION_ACKNOWLEDGEMENT);
    }

    @Test
    void theJobsOwnPostingLinkIsStrongEvidence() {
        var a = match("Acme Careers <no-reply@acme.example>", "Application received",
                "We have received your application. View the role: https://boards.greenhouse.io/acme/jobs/4001?gh_src=mail", null);
        assertThat(a.status()).isEqualTo(LinkStatus.LINKED);
        assertThat(a.strength()).isEqualTo(MatchStrength.STRONG);
        assertThat(a.applicationId()).isEqualTo(A);
        assertThat(a.identityEvidence()).isNotEmpty();
        assertThat(a.identifiesOtherApplication()).isFalse();
    }

    @Test
    void aQuotedRequisitionNumberWithTheEmployerNameIsStrongEvidence() {
        var a = match("Acme Ltd Recruiting <talent@acme.example>", "Re: Requisition ID: R-1002",
                "Thanks for applying to Acme Ltd for Requisition ID: R-1002.", null);
        assertThat(a.status()).isEqualTo(LinkStatus.LINKED);
        assertThat(a.applicationId()).isEqualTo(B);
        assertThat(a.identityEvidence()).contains("REQUISITION_NUMBER");
    }

    @Test
    void aRequisitionNumberWithoutTheEmployerIsNotEnough() {
        var a = match("someone@unrelated.example", "Req #R-1002", "Your request R-1002 was processed.", null);
        assertThat(a.status()).isNotEqualTo(LinkStatus.LINKED);
    }

    @Test
    void similarTitlesAtTheSameCompanyAreNeverGuessed() {
        var a = match("recruiting@acme.example", "Your Platform Engineer application",
                "Thank you for your application for Platform Engineer at Acme Ltd.", null);
        assertThat(a.status()).isEqualTo(LinkStatus.PENDING_REVIEW);
        assertThat(a.strength()).isEqualTo(MatchStrength.WEAK);
        assertThat(a.applicationId()).isNull();
        assertThat(a.candidates()).extracting(EmailApplicationMatcher.Candidate::applicationId).containsExactlyInAnyOrder(A, B);
    }

    @Test
    void aSingleWeakMatchIsStillOnlyAProposal() {
        var a = match("hr@globex.example", "Hello", "Thanks for your interest in Globex.", null);
        assertThat(a.status()).isEqualTo(LinkStatus.PENDING_REVIEW);
        assertThat(a.applicationId()).isNull();
        assertThat(a.candidates()).extracting(EmailApplicationMatcher.Candidate::applicationId).containsExactly(C);
    }

    @Test
    void aMatchingTitleAloneDoesNotLink() {
        var a = match("someone@elsewhere.example", "Data Analyst", "We are hiring a Data Analyst.", null);
        assertThat(a.status()).isEqualTo(LinkStatus.PENDING_REVIEW);
        assertThat(a.applicationId()).isNull();
    }

    @Test
    void nothingIdentifyingIsUnmatched() {
        var a = match("news@example.org", "Weekly digest", "Top stories this week.", null);
        assertThat(a.status()).isEqualTo(LinkStatus.UNMATCHED);
        assertThat(a.candidates()).isEmpty();
    }

    @Test
    void severalStronglyIdentifiedApplicationsAreLeftToTheCandidate() {
        var a = match("no-reply@acme.example", "Two roles",
                "Links: https://boards.greenhouse.io/acme/jobs/4001 and https://boards.greenhouse.io/acme/jobs/4002", null);
        assertThat(a.status()).isEqualTo(LinkStatus.PENDING_REVIEW);
        assertThat(a.strength()).isEqualTo(MatchStrength.STRONG);
        assertThat(a.identifiesOtherApplication()).isTrue();
    }

    @Test
    void theCandidatesChoiceIsRecordedAsTheirsWhenTheMessageHasNoIdentifier() {
        var a = match("hr@acme.example", "Thanks", "Thank you for applying.", B);
        assertThat(a.status()).isEqualTo(LinkStatus.CONFIRMED);
        assertThat(a.strength()).isEqualTo(MatchStrength.CANDIDATE);
        assertThat(a.applicationId()).isEqualTo(B);
        assertThat(a.identityEvidence()).isEmpty();
    }

    @Test
    void aChoiceContradictedByTheMessagesOwnIdentifierIsAConflict() {
        var a = match("no-reply@acme.example", "Application received",
                "We have received your application: https://boards.greenhouse.io/acme/jobs/4001", B);
        assertThat(a.status()).isEqualTo(LinkStatus.CONFLICT);
        assertThat(a.established()).isFalse();
        assertThat(a.candidates()).extracting(EmailApplicationMatcher.Candidate::applicationId).contains(A, B);
    }

    @Test
    void aChoiceConfirmedByTheIdentifierIsAStrongLink() {
        var a = match("no-reply@acme.example", "Application received",
                "We have received your application: https://boards.greenhouse.io/acme/jobs/4001", A);
        assertThat(a.status()).isEqualTo(LinkStatus.LINKED);
        assertThat(a.applicationId()).isEqualTo(A);
    }

    @Test
    void aSignInCodeIsNotAssociatedWithAnyApplication() {
        var a = matcher.match(MessageEvidence.of("no-reply@acme.example", "Your code", "Use 123456"),
                List.of(ACME_PLATFORM), null, EmailCategory.VERIFICATION_CODE);
        assertThat(a.status()).isEqualTo(LinkStatus.NOT_APPLICABLE);
        assertThat(a.applicationId()).isNull();
    }

    @Test
    void onlyTheOwnersApplicationsCanEverBeProposed() {
        // The caller passes only the owner's applications; with none, nothing can match.
        var a = matcher.match(MessageEvidence.of("no-reply@acme.example", "Application received",
                "https://boards.greenhouse.io/acme/jobs/4001"), List.of(), null, EmailCategory.APPLICATION_ACKNOWLEDGEMENT);
        assertThat(a.status()).isEqualTo(LinkStatus.UNMATCHED);
    }

    @Test
    void rationaleNeverNamesAnApplicationPlatform() {
        var a = match("no-reply@acme.example", "Application received",
                "We have received your application: https://boards.greenhouse.io/acme/jobs/4001", null);
        assertThat(a.rationale().toString().toLowerCase()).doesNotContain("greenhouse").doesNotContain("lever")
                .doesNotContain("tsenta");
    }
}
