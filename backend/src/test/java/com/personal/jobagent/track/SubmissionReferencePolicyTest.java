package com.personal.jobagent.track;

import com.personal.jobagent.track.SubmissionReferencePolicy.Consistency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Browser-observed vs acknowledgement confirmation references (Stage 3I Phase 2, Gate 3). */
class SubmissionReferencePolicyTest {

    private final SubmissionReferencePolicy policy = new SubmissionReferencePolicy();

    @Test
    void matchingReferencesAreAlignedAfterConservativeNormalisation() {
        var c = policy.compare("  #app-778812 ", "APP-778812", Set.of());
        assertThat(c.consistency()).isEqualTo(Consistency.ALIGNED);
        assertThat(c.receiptNormalized()).isEqualTo("APP-778812");
    }

    @Test
    void separatorsAreNotFoldedSoDistinctReferencesStayDistinct() {
        var c = policy.compare("APP778812", "APP-778812", Set.of());
        assertThat(c.consistency()).isEqualTo(Consistency.CONFLICTING);
        assertThat(c.code()).isEqualTo("REFERENCES_DIFFER");
        assertThat(c.receiptNormalized()).isEqualTo("APP778812");
        assertThat(c.submissionNormalized()).isEqualTo("APP-778812");
    }

    @Test
    void differingReferencesConflict() {
        assertThat(policy.compare("REF-1001", "REF-2002", Set.of()).conflicting()).isTrue();
    }

    @Test
    void missingReferencesAreNotComparable() {
        assertThat(policy.compare(null, "APP-1001", Set.of()).code()).isEqualTo("RECEIPT_REFERENCE_MISSING");
        assertThat(policy.compare("APP-1001", null, Set.of()).code()).isEqualTo("SUBMISSION_REFERENCE_MISSING");
        assertThat(policy.compare("  ", "APP-1001", Set.of()).consistency()).isEqualTo(Consistency.NOT_COMPARABLE);
    }

    @Test
    void malformedReferencesAreNotComparable() {
        assertThat(policy.compare("A1", "APP-1001", Set.of()).code()).isEqualTo("RECEIPT_REFERENCE_MALFORMED");
        assertThat(policy.compare("APP-1001", "<script>", Set.of()).code()).isEqualTo("SUBMISSION_REFERENCE_MALFORMED");
        assertThat(policy.compare("APP 1001 EXTRA", "APP-1001", Set.of()).consistency()).isEqualTo(Consistency.NOT_COMPARABLE);
        assertThat(SubmissionReferencePolicy.normalize("x".repeat(80))).isEmpty();
    }

    @Test
    void requisitionPostingAndInternalIdentifiersAreNeverComparedAsConfirmationIds() {
        Set<String> excluded = SubmissionReferencePolicy.disqualifiedIdentifiers(List.of(
                "req:acme:R-12345", "ats:greenhouse:4471", "00000000-0000-7000-8000-0000000000aa"));
        assertThat(excluded).contains("R-12345", "4471", "00000000-0000-7000-8000-0000000000AA");
        assertThat(policy.compare("R-12345", "APP-1001", excluded).code()).isEqualTo("RECEIPT_REFERENCE_NOT_A_CONFIRMATION_ID");
        assertThat(policy.compare("APP-1001", "4471", excluded).code()).isEqualTo("SUBMISSION_REFERENCE_NOT_A_CONFIRMATION_ID");
        assertThat(policy.compare("00000000-0000-7000-8000-0000000000aa", "APP-1001", excluded).consistency())
                .isEqualTo(Consistency.NOT_COMPARABLE);
    }

    @Test
    void duplicatedReferencesAreAligned() {
        assertThat(policy.compare("APP-1001", "app-1001", Set.of()).consistency()).isEqualTo(Consistency.ALIGNED);
    }
}
