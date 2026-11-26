package com.personal.jobagent.track.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.StoredEvidence;
import com.personal.jobagent.track.receipt.UniversalSubmissionEvidence.DigestStatus;
import com.personal.jobagent.track.receipt.UniversalSubmissionEvidence.Kind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/** Universal browser evidence v2: parsing, digest, correlation and host policy (Stage 3I Phase 2, Gate 5). */
class BrowserObservationPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID PLAN = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID APP = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID JOB = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final String TARGET = "https://careers.acme.example/jobs/505/apply";

    /** v2 evidence exactly as the worker builds it, sealed with its canonical digest after {@code edit}. */
    static String v2(Consumer<ObjectNode> edit) {
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", UniversalSubmissionEvidence.V2);
        root.put("atsPlatform", "UNIVERSAL");
        ObjectNode corr = root.putObject("attemptCorrelation");
        corr.put("planId", PLAN.toString());
        corr.put("applicationId", APP.toString());
        corr.put("jobId", JOB.toString());
        root.put("observedAt", "2026-11-26T10:00:00.000Z");
        ArrayNode hops = root.putArray("redirectChain");
        hops.addObject().put("host", "careers.acme.example").put("status", 303);
        hops.addObject().put("host", "careers.acme.example").put("status", 200);
        root.put("finalOrigin", "https://careers.acme.example");
        root.put("confirmationNumber", "APP-50599");
        root.put("confirmationIdentifierSource", "CONTAINER_TEXT");
        ObjectNode docs = root.putObject("documents");
        docs.putObject("cv").put("artifactSha256", "b".repeat(64)).put("formAttachment", "ATTACHED")
                .put("submissionRequestFilePart", "NOT_OBSERVED").put("uploadVerification", "ATTACHED_NOT_CONFIRMED");
        docs.putObject("coverLetter").put("formAttachment", "NO_FIELD").put("uploadVerification", "NO_FIELD");
        root.putNull("submittedCvSha256");
        root.putNull("submittedCoverLetterSha256");
        root.put("employerVerification", "NOT_VERIFIED_BY_BROWSER");
        edit.accept(root);
        root.put("evidenceSha256", ReceiptVerificationPolicy.computeEvidenceSha256(CanonicalJson.write(root)));
        return CanonicalJson.write(root);
    }

    private static StoredEvidence stored(List<String> signals) {
        return new StoredEvidence(UUID.randomUUID(), PLAN, APP, UUID.randomUUID(), "worker",
                SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE, "UNIVERSAL", TARGET, "CONFIRMED", "APP-50599",
                signals, "observed", "c".repeat(64), 100, SubmissionEvidenceRepository.RAW_STORED, Instant.now(), "ct");
    }

    private static BrowserObservationPolicy.Assessment assess(String payload) {
        return assess(stored(List.of("UNIVERSAL_SUBMISSION_RESPONSE_2XX")), true, true, payload);
    }

    private static BrowserObservationPolicy.Assessment assess(StoredEvidence e, boolean approved, boolean integrity, String payload) {
        return BrowserObservationPolicy.assess(new BrowserObservationPolicy.Inputs(e, approved, integrity, payload,
                integrity ? UniversalSubmissionEvidence.parse(payload, JSON) : null, PLAN, APP, JOB, TARGET));
    }

    @Test
    void intactV2EvidenceIsACleanBrowserObservationWithItsFactsReadable() {
        String payload = v2(r -> {});
        var parsed = UniversalSubmissionEvidence.parse(payload, JSON);
        assertThat(parsed.kind()).isEqualTo(Kind.V2);
        assertThat(parsed.digest()).isEqualTo(DigestStatus.VALID);
        assertThat(parsed.redirectChain()).hasSize(2);
        assertThat(parsed.documents().get("cv").filePartObserved()).isFalse();
        assertThat(parsed.documents().get("cv").artifactSha256()).isEqualTo("b".repeat(64));
        assertThat(parsed.submittedCvSha256()).isNull();
        var a = assess(payload);
        assertThat(a.clean()).isTrue();
        assertThat(a.code()).isEqualTo("BROWSER_OBSERVED_V2");
    }

    @Test
    void aTamperedEvidenceDigestIsRejected() {
        String payload = v2(r -> {}).replace("APP-50599", "APP-99999");
        assertThat(UniversalSubmissionEvidence.parse(payload, JSON).digest()).isEqualTo(DigestStatus.MISMATCH);
        assertThat(assess(payload).code()).isEqualTo("EVIDENCE_DIGEST_MISMATCH");
        // A v2 record without its digest is not accepted either.
        String unsealed = v2(r -> {}).replaceAll(",\"evidenceSha256\":\"[0-9a-f]{64}\"", "");
        assertThat(UniversalSubmissionEvidence.parse(unsealed, JSON).digest()).isEqualTo(DigestStatus.ABSENT);
        assertThat(assess(unsealed).clean()).isFalse();
    }

    @Test
    void evidenceForAnotherAttemptOrApplicationIsRejected() {
        assertThat(assess(v2(r -> ((ObjectNode) r.get("attemptCorrelation")).put("planId", UUID.randomUUID().toString()))).code())
                .isEqualTo("CORRELATION_MISMATCH");
        assertThat(assess(v2(r -> ((ObjectNode) r.get("attemptCorrelation")).putNull("applicationId"))).code())
                .isEqualTo("CORRELATION_MISMATCH");
    }

    @Test
    void aCrossOriginRedirectOrConfirmationPageIsRejected() {
        String viaSso = v2(r -> ((ArrayNode) r.get("redirectChain")).insertObject(1).put("host", "login.acme-sso.example").put("status", 302));
        assertThat(assess(viaSso).code()).isEqualTo("REDIRECT_OUTSIDE_TARGET_HOST");
        String otherPort = v2(r -> ((ArrayNode) r.get("redirectChain")).addObject().put("host", "careers.acme.example:8443").put("status", 200));
        assertThat(assess(otherPort).code()).isEqualTo("REDIRECT_OUTSIDE_TARGET_HOST");
        assertThat(assess(v2(r -> r.put("finalOrigin", "https://evil.example"))).code()).isEqualTo("FINAL_ORIGIN_MISMATCH");
        assertThat(assess(v2(r -> r.remove("finalOrigin"))).code()).isEqualTo("FINAL_ORIGIN_MISMATCH");
        // The default port written explicitly is the same origin.
        assertThat(assess(v2(r -> r.put("finalOrigin", "https://careers.acme.example:443"))).clean()).isTrue();
    }

    @Test
    void historicalEvidenceBeforeV2IsStillABrowserObservationButMalformedOrUnknownSchemasFailClosed() {
        var v1 = assess("{\"atsPlatform\":\"UNIVERSAL\",\"schemaVersion\":\"robin-submission-evidence-v1\"}");
        assertThat(v1.clean()).isTrue();
        assertThat(v1.code()).isEqualTo("BROWSER_OBSERVED_LEGACY_SCHEMA");
        assertThat(v1.parsed().kind()).isEqualTo(Kind.V1);
        var unversioned = assess("{\"atsPlatform\":\"UNIVERSAL\"}");
        assertThat(unversioned.clean()).isTrue();
        assertThat(unversioned.parsed().kind()).isEqualTo(Kind.UNVERSIONED);

        for (String unknown : new String[]{
                "{\"atsPlatform\":\"UNIVERSAL\",\"schemaVersion\":\"robin-submission-evidence-v99\"}",
                "{\"atsPlatform\":\"UNIVERSAL\",\"schemaVersion\":99}",
                "{\"atsPlatform\":\"UNIVERSAL\",\"schemaVersion\":null}"}) {
            var rejected = assess(unknown);
            assertThat(rejected.clean()).isFalse();
            assertThat(rejected.code()).isEqualTo("UNSUPPORTED_EVIDENCE_SCHEMA");
            assertThat(rejected.parsed().kind()).isEqualTo(Kind.UNKNOWN_VERSION);
        }

        for (String malformed : new String[]{"not json at all", "[1,2,3]"}) {
            var rejected = assess(malformed);
            assertThat(rejected.clean()).isFalse();
            assertThat(rejected.code()).isEqualTo("EVIDENCE_UNPARSEABLE");
            assertThat(rejected.parsed().kind()).isEqualTo(Kind.UNPARSEABLE);
        }
    }

    @Test
    void approvalIntegrityProvenanceAndSignalsAreStillRequired() {
        String payload = v2(r -> {});
        StoredEvidence ok = stored(List.of("UNIVERSAL_SUBMISSION_RESPONSE_2XX"));
        assertThat(assess(ok, false, true, payload).code()).isEqualTo("APPROVAL_MISSING");
        assertThat(assess(ok, true, false, null).code()).isEqualTo("EVIDENCE_INTEGRITY_FAILED");
        assertThat(assess(stored(List.of()), true, true, payload).code()).isEqualTo("NO_SIGNALS");
        StoredEvidence notWorker = new StoredEvidence(ok.id(), PLAN, APP, ok.profileId(), "candidate", ok.recordedVia(),
                "UNIVERSAL", TARGET, "CONFIRMED", null, ok.observedSignals(), "x", ok.rawEvidenceSha256(), 1,
                SubmissionEvidenceRepository.RAW_STORED, Instant.now(), "ct");
        assertThat(assess(notWorker, true, true, payload).code()).isEqualTo("PROVENANCE_MISMATCH");
        assertThat(assess(null, true, true, payload).code()).isEqualTo("NO_EVIDENCE");
    }
}
