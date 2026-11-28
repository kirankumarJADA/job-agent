package com.personal.jobagent.track.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cross-language half of the evidence v2 contract: the worker generated
 * {@code contracts/submissionEvidenceV2Fixtures.json} with its own
 * {@code canonicalJson}; the backend must reproduce every canonical string and
 * digest byte for byte, and re-derive the sealed record's self-digest. The
 * worker suite checks the same file against the JavaScript implementation.
 */
class SubmissionEvidenceV2ContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode fixture() throws Exception {
        try (InputStream in = SubmissionEvidenceV2ContractTest.class.getResourceAsStream("/contracts/submissionEvidenceV2Fixtures.json")) {
            assertThat(in).as("contract fixture").isNotNull();
            return JSON.readTree(in);
        }
    }

    @Test
    void javaCanonicalJsonMatchesTheWorkerByteForByte() throws Exception {
        JsonNode cases = fixture().get("cases");
        assertThat(cases.size()).isGreaterThanOrEqualTo(5);
        for (JsonNode c : cases) {
            String canonical = CanonicalJson.write(JSON.readTree(c.get("json").asText()));
            assertThat(canonical).as(c.get("name").asText()).isEqualTo(c.get("canonical").asText());
            assertThat(ReceiptVerificationPolicy.computeEvidenceSha256(canonical)).as(c.get("name").asText())
                    .isEqualTo(c.get("sha256").asText());
        }
    }

    @Test
    void theBackendReDerivesAWorkerSealedRecordsDigest() throws Exception {
        String sealed = fixture().get("sealedEvidence").asText();
        UniversalSubmissionEvidence.Parsed parsed = UniversalSubmissionEvidence.parse(sealed, JSON);
        assertThat(parsed.kind()).isEqualTo(UniversalSubmissionEvidence.Kind.V2);
        assertThat(parsed.digest()).isEqualTo(UniversalSubmissionEvidence.DigestStatus.VALID);
        assertThat(parsed.documents().get("cv").filePartObserved()).isTrue();
        assertThat(parsed.submittedCvSha256()).isEqualTo("b".repeat(64));
        assertThat(parsed.redirectChain()).extracting(UniversalSubmissionEvidence.Hop::host)
                .containsOnly("careers.acme.example");
        assertThat(CanonicalJson.write(JSON.readTree(sealed))).isEqualTo(sealed);

        String tampered = sealed.replace("EMP-REAL-4201", "EMP-REAL-4202");
        assertThat(UniversalSubmissionEvidence.parse(tampered, JSON).digest())
                .isEqualTo(UniversalSubmissionEvidence.DigestStatus.MISMATCH);
    }

    @Test
    void fractionalNumbersAreRefusedRatherThanRiskingAFormattingDifference() {
        assertThatThrownBy(() -> CanonicalJson.write(JSON.readTree("{\"a\":1.5}"))).isInstanceOf(IllegalArgumentException.class);
    }
}
