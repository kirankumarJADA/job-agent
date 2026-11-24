package com.personal.jobagent.ats;

import com.personal.jobagent.track.receipt.InvalidReceiptException;
import com.personal.jobagent.track.receipt.ReceiptVerificationPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Agent A: Greenhouse Candidate-Owned Sandbox Execution Contract")
class GreenhouseSandboxContractTest {

    private static final String SANDBOX_GREENHOUSE_URL = "https://job-boards.greenhouse.io/candidate-sandbox/jobs/99887766";
    private static final String CANONICAL_DOM_EVIDENCE = "<div class=\"confirmation\"><h1>Application Submitted</h1><p>Confirmation: GH-CONF-2026-9988</p></div>";

    private final GreenhouseAdapter adapter = new GreenhouseAdapter(url -> """
            <html>
              <body>
                <form id="application_form">
                  <input id="first_name" name="first_name" type="text" />
                  <input id="last_name" name="last_name" type="text" />
                  <input id="email" name="email" type="email" />
                  <input id="phone" name="phone" type="tel" />
                  <input id="resume" name="resume" type="file" />
                  <div class="requiredInput"></div>
                </form>
              </body>
            </html>
            """);

    private final ReceiptVerificationPolicy receiptPolicy = new ReceiptVerificationPolicy();

    @Test
    @DisplayName("1. Greenhouse sandbox apply URL is inspected into structured form descriptor")
    void testSandboxFormInspection() {
        AtsAdapter.FormDescriptor descriptor = adapter.inspectForm(SANDBOX_GREENHOUSE_URL);

        assertThat(descriptor.kind()).isEqualTo(AtsKind.GREENHOUSE);
        assertThat(descriptor.endpoint()).isEqualTo(SANDBOX_GREENHOUSE_URL);
        assertThat(descriptor.fields()).isNotEmpty();
        assertThat(descriptor.fields()).anyMatch(f -> f.key().equals("first_name"));
        assertThat(descriptor.fields()).anyMatch(f -> f.key().equals("email"));
        assertThat(descriptor.fields()).anyMatch(f -> f.key().equals("resume"));
    }

    @Test
    @DisplayName("2. Dry-run submission against candidate sandbox returns deterministic simulation response")
    void testSandboxDryRunSubmission() {
        AtsAdapter.SubmissionPayload payload = new AtsAdapter.SubmissionPayload(
                "Jane Doe",
                "jane.doe@example.org",
                "+447700900077",
                "London, UK",
                "JVBERi0xLjQKJcTl8uXrCg==", // valid base64
                null,
                Map.of("first_name", "Jane", "last_name", "Doe")
        );

        AtsAdapter.SubmissionResult result = adapter.submitApplication(SANDBOX_GREENHOUSE_URL, payload, true);

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("DRY_RUN_PASSED");
        assertThat(result.confirmationId()).startsWith("MOCK-GREENHOUSE-");
        assertThat(result.details()).containsEntry("simulated", true);
        assertThat(result.details()).containsEntry("dryRun", true);
    }

    @Test
    @DisplayName("3. Non-dry-run submission without browser harness deterministically fails closed with REAL_SUBMIT_NOT_IMPLEMENTED")
    void testNonDryRunSubmissionFailsClosed() {
        AtsAdapter.SubmissionPayload payload = new AtsAdapter.SubmissionPayload(
                "Jane Doe",
                "jane.doe@example.org",
                "+447700900077",
                "London, UK",
                "JVBERi0xLjQKJcTl8uXrCg==",
                null,
                Map.of()
        );

        AtsAdapter.SubmissionResult result = adapter.submitApplication(SANDBOX_GREENHOUSE_URL, payload, false);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("REAL_SUBMIT_NOT_IMPLEMENTED");
        assertThat(result.confirmationId()).isNull();
        assertThat(result.details()).containsEntry("simulated", false);
    }

    @Test
    @DisplayName("4. Sandbox receipt verification requires non-empty raw DOM evidence and computed SHA-256")
    void testSandboxReceiptCryptographicVerification() throws Exception {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        String confirmationId = "GH-CONF-2026-9988";

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(CANONICAL_DOM_EVIDENCE.getBytes(StandardCharsets.UTF_8));
        String computedSha256 = HexFormat.of().formatHex(hash);

        ReceiptVerificationPolicy.ReceiptEvidence evidence = ReceiptVerificationPolicy.ReceiptEvidence.builder()
                .applicationId(applicationId)
                .profileId(profileId)
                .employerConfirmationId(confirmationId)
                .receiptSha256(computedSha256)
                .rawEvidencePayload(CANONICAL_DOM_EVIDENCE)
                .atsPlatform("GREENHOUSE")
                .submittedAt(Instant.now())
                .attestationConfirmed(true)
                .sandboxPosting(true)
                .candidateAuthorizedSandbox(true)
                .build();

        ReceiptVerificationPolicy.VerificationOutcome outcome = receiptPolicy.evaluate(evidence);

        assertThat(outcome.verified()).isTrue();
        assertThat(outcome.receiptSha256()).isEqualTo(computedSha256);
    }

    @Test
    @DisplayName("5. Non-sandbox receipt or missing authorization in sandbox is strictly rejected")
    void testNonSandboxReceiptRejected() throws Exception {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(CANONICAL_DOM_EVIDENCE.getBytes(StandardCharsets.UTF_8));
        String computedSha256 = HexFormat.of().formatHex(hash);

        // A) Non-sandbox
        ReceiptVerificationPolicy.ReceiptEvidence nonSandboxEvidence = ReceiptVerificationPolicy.ReceiptEvidence.builder()
                .applicationId(applicationId)
                .profileId(profileId)
                .employerConfirmationId("GH-CONF-2026-9988")
                .receiptSha256(computedSha256)
                .rawEvidencePayload(CANONICAL_DOM_EVIDENCE)
                .atsPlatform("GREENHOUSE")
                .submittedAt(Instant.now())
                .attestationConfirmed(true)
                .sandboxPosting(false) // Non-sandbox
                .candidateAuthorizedSandbox(true)
                .build();

        assertThatThrownBy(() -> receiptPolicy.evaluate(nonSandboxEvidence))
                .isInstanceOf(InvalidReceiptException.class)
                .hasMessageContaining("real submissions are disabled. Receipts are only accepted for candidate-owned sandbox postings");

        // B) Unauthorized sandbox
        ReceiptVerificationPolicy.ReceiptEvidence unauthorizedSandboxEvidence = ReceiptVerificationPolicy.ReceiptEvidence.builder()
                .applicationId(applicationId)
                .profileId(profileId)
                .employerConfirmationId("GH-CONF-2026-9988")
                .receiptSha256(computedSha256)
                .rawEvidencePayload(CANONICAL_DOM_EVIDENCE)
                .atsPlatform("GREENHOUSE")
                .submittedAt(Instant.now())
                .attestationConfirmed(true)
                .sandboxPosting(true)
                .candidateAuthorizedSandbox(false) // Not candidate authorized
                .build();

        assertThatThrownBy(() -> receiptPolicy.evaluate(unauthorizedSandboxEvidence))
                .isInstanceOf(InvalidReceiptException.class)
                .hasMessageContaining("candidate-owned sandbox must be explicitly verified and authorized");
    }

    @Test
    @DisplayName("6. Requisition ID cannot substitute for employer confirmation ID in Greenhouse sandbox")
    void testRequisitionIdSubstitutedForConfirmationIdIsRejected() throws Exception {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(CANONICAL_DOM_EVIDENCE.getBytes(StandardCharsets.UTF_8));
        String computedSha256 = HexFormat.of().formatHex(hash);

        ReceiptVerificationPolicy.ReceiptEvidence reqIdEvidence = ReceiptVerificationPolicy.ReceiptEvidence.builder()
                .applicationId(applicationId)
                .profileId(profileId)
                .employerConfirmationId("REQ-GH-2026-5544") // Requisition ID fallback
                .receiptSha256(computedSha256)
                .rawEvidencePayload(CANONICAL_DOM_EVIDENCE)
                .atsPlatform("GREENHOUSE")
                .submittedAt(Instant.now())
                .attestationConfirmed(true)
                .sandboxPosting(true)
                .candidateAuthorizedSandbox(true)
                .build();

        assertThatThrownBy(() -> receiptPolicy.evaluate(reqIdEvidence))
                .isInstanceOf(InvalidReceiptException.class)
                .hasMessageContaining("Requisition ID or receipt ID fallback cannot be substituted for employer confirmation ID");
    }
}
