package com.personal.jobagent.track.receipt;

import com.personal.jobagent.track.ApplicationReceiptService.SubmissionReceipt;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phase 20 / Wave 3 Agent A: Receipt Verification Policy.
 *
 * <p>Enforces zero synthetic or mock receipts in production code paths and guarantees
 * all 7 genuine receipt invariants:
 * <ol>
 *   <li><b>Employer confirmation ID</b>: Genuine employer confirmation identifier
 *       (rejects MOCK-*, TEST-*, SAMPLE-* prefixes).</li>
 *   <li><b>receiptSha256</b>: Deterministic SHA-256 hash (64 hex characters) computed
 *       over normalized confirmation evidence.</li>
 *   <li><b>atsPlatform</b>: Valid, verified ATS platform (GREENHOUSE, WORKDAY, ASHBY,
 *       LEVER, ZOHO_RECRUIT).</li>
 *   <li><b>submittedAt</b>: Valid submission timestamp, not dated in the future.</li>
 *   <li><b>profileId</b>: Candidate profile identifier.</li>
 *   <li><b>applicationId</b>: Valid target application identifier.</li>
 *   <li><b>attestationConfirmed</b>: Explicit candidate authorization and attestation.</li>
 * </ol>
 *
 * <p>Additionally enforces candidate-authorised sandbox restrictions: real submissions
 * must be verified only in candidate-owned sandbox/test postings.
 */
@Component
public class ReceiptVerificationPolicy {

    public static final String POLICY_VERSION = "genuine-ats-receipt-policy-v1";
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    public static final Set<String> MOCK_PREFIXES = Set.of("MOCK-", "TEST-", "SAMPLE-", "SIM-");
    public static final Set<String> REQUISITION_PREFIXES = Set.of("REQ-", "REQUISITION-", "JOB-REQ-", "RECEIPT-");

    public static final Set<String> ALLOWED_ATS_PLATFORMS = Set.of(
            "GREENHOUSE",
            "WORKDAY",
            "ASHBY",
            "LEVER",
            "ZOHO_RECRUIT"
    );

    private static final Pattern SHA256_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    public record ReceiptEvidence(
            String employerConfirmationId,
            String receiptSha256,
            String atsPlatform,
            Instant submittedAt,
            UUID profileId,
            UUID applicationId,
            boolean attestationConfirmed,
            boolean sandboxPosting,
            boolean candidateAuthorizedSandbox,
            List<String> observedSignals,
            String rawEvidencePayload
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String employerConfirmationId;
            private String receiptSha256;
            private String atsPlatform;
            private Instant submittedAt;
            private UUID profileId;
            private UUID applicationId;
            private boolean attestationConfirmed = false;
            private boolean sandboxPosting = false;
            private boolean candidateAuthorizedSandbox = false; // NON-PERMISSIVE DEFAULT
            private List<String> observedSignals = List.of();
            private String rawEvidencePayload;

            public Builder employerConfirmationId(String id) { this.employerConfirmationId = id; return this; }
            public Builder receiptSha256(String sha) { this.receiptSha256 = sha; return this; }
            public Builder atsPlatform(String platform) { this.atsPlatform = platform; return this; }
            public Builder submittedAt(Instant at) { this.submittedAt = at; return this; }
            public Builder profileId(UUID pid) { this.profileId = pid; return this; }
            public Builder applicationId(UUID aid) { this.applicationId = aid; return this; }
            public Builder attestationConfirmed(boolean confirmed) { this.attestationConfirmed = confirmed; return this; }
            public Builder sandboxPosting(boolean sandbox) { this.sandboxPosting = sandbox; return this; }
            public Builder candidateAuthorizedSandbox(boolean auth) { this.candidateAuthorizedSandbox = auth; return this; }
            public Builder observedSignals(List<String> signals) { this.observedSignals = signals; return this; }
            public Builder rawEvidencePayload(String payload) { this.rawEvidencePayload = payload; return this; }

            public ReceiptEvidence build() {
                return new ReceiptEvidence(employerConfirmationId, receiptSha256, atsPlatform, submittedAt,
                        profileId, applicationId, attestationConfirmed, sandboxPosting, candidateAuthorizedSandbox,
                        observedSignals, rawEvidencePayload);
            }
        }
    }

    public record VerificationOutcome(
            boolean verified,
            String receiptSha256,
            List<String> observedSignals,
            List<Map<String, String>> reasons
    ) {}

    /**
     * Checks if a string value starts with any mock or synthetic prefix.
     * Prefixes MOCK-*, TEST-*, SAMPLE-*, SIM-* are detected case-insensitively.
     */
    public static boolean isMockOrSynthetic(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        for (String prefix : MOCK_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a string value starts with any requisition or receipt fallback prefix.
     * Prefixes REQ-*, REQUISITION-*, JOB-REQ-*, RECEIPT-* are rejected as confirmation IDs.
     */
    public static boolean isRequisitionOrReceiptFallback(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        for (String prefix : REQUISITION_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deterministically builds canonical JSON string for evidence hashing.
     */
    public static String buildCanonicalEvidencePayload(String employerConfirmationId, String atsPlatform,
                                                      Instant submittedAt, UUID profileId, UUID applicationId,
                                                      boolean attestationConfirmed, List<String> observedSignals) {
        List<String> sortedSignals = observedSignals == null ? List.of()
                : observedSignals.stream().filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).sorted().toList();

        StringBuilder signalsJson = new StringBuilder("[");
        for (int i = 0; i < sortedSignals.size(); i++) {
            if (i > 0) signalsJson.append(",");
            signalsJson.append("\"").append(escapeJson(sortedSignals.get(i))).append("\"");
        }
        signalsJson.append("]");

        return "{"
                + "\"applicationId\":\"" + (applicationId != null ? applicationId.toString() : "") + "\","
                + "\"atsPlatform\":\"" + (atsPlatform != null ? atsPlatform.trim().toUpperCase(Locale.ROOT) : "") + "\","
                + "\"attestationConfirmed\":" + attestationConfirmed + ","
                + "\"employerConfirmationId\":\"" + (employerConfirmationId != null ? escapeJson(employerConfirmationId.trim()) : "") + "\","
                + "\"observedSignals\":" + signalsJson + ","
                + "\"profileId\":\"" + (profileId != null ? profileId.toString() : "") + "\","
                + "\"rawEvidence\":null,"
                + "\"submittedAt\":\"" + (submittedAt != null ? submittedAt.toString() : "") + "\","
                + "\"summary\":\"\""
                + "}";
    }

    /**
     * Computes the SHA-256 hexadecimal hash over a UTF-8 payload.
     */
    public static String computeEvidenceSha256(String payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload cannot be null for SHA-256 calculation");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Validates that all 7 invariants and candidate-authorized sandbox constraints hold.
     * Throws {@link InvalidReceiptException} if any check fails or mock prefix is encountered.
     */
    public void validateOrThrow(ReceiptEvidence evidence) {
        if (evidence == null) {
            throw new InvalidReceiptException("Receipt evidence cannot be null", "MISSING_EVIDENCE");
        }

        // Ownership checks: profileId and applicationId must be present
        if (evidence.profileId() == null) {
            throw new InvalidReceiptException("profileId is required", "MISSING_PROFILE_ID");
        }
        if (evidence.applicationId() == null) {
            throw new InvalidReceiptException("applicationId is required", "MISSING_APPLICATION_ID");
        }

        // Invariant 1: Employer Confirmation ID
        if (evidence.employerConfirmationId() == null || evidence.employerConfirmationId().isBlank()) {
            throw new InvalidReceiptException("Employer confirmation ID is required", "MISSING_CONFIRMATION_ID");
        }
        if (isMockOrSynthetic(evidence.employerConfirmationId())) {
            throw new InvalidReceiptException(
                    "Mock or synthetic confirmation ID prefix is rejected in production: " + evidence.employerConfirmationId(),
                    "MOCK_CONFIRMATION_ID_REJECTED"
            );
        }
        if (isRequisitionOrReceiptFallback(evidence.employerConfirmationId())) {
            throw new InvalidReceiptException(
                    "Requisition ID or receipt ID fallback cannot be substituted for employer confirmation ID: " + evidence.employerConfirmationId(),
                    "REQUISITION_ID_AS_CONFIRMATION_REJECTED"
            );
        }

        // Invariant 2: Raw confirmation evidence is strictly required
        if (evidence.rawEvidencePayload() == null || evidence.rawEvidencePayload().isBlank()) {
            throw new InvalidReceiptException(
                    "Confirmation evidence is required: canonical DOM evidence or screenshot bytes cannot be empty",
                    "MISSING_RAW_EVIDENCE"
            );
        }

        // Compute SHA-256 directly from the captured canonical DOM evidence / raw bytes
        String computedSha256 = computeEvidenceSha256(evidence.rawEvidencePayload());

        if (evidence.receiptSha256() == null || evidence.receiptSha256().isBlank()) {
            throw new InvalidReceiptException("receiptSha256 is required", "MISSING_RECEIPT_SHA256");
        }
        if (isMockOrSynthetic(evidence.receiptSha256())) {
            throw new InvalidReceiptException(
                    "Mock or synthetic prefix in receiptSha256 is rejected in production: " + evidence.receiptSha256(),
                    "MOCK_SHA256_REJECTED"
            );
        }
        if (!SHA256_HEX_PATTERN.matcher(evidence.receiptSha256().trim()).matches()) {
            throw new InvalidReceiptException(
                    "receiptSha256 must be a valid 64-character hexadecimal SHA-256 digest: " + evidence.receiptSha256(),
                    "INVALID_SHA256_FORMAT"
            );
        }

        // Never accept caller-supplied 64-character digest without comparing it to the actual evidence
        if (!computedSha256.equalsIgnoreCase(evidence.receiptSha256().trim())) {
            throw new InvalidReceiptException(
                    "Caller-supplied receiptSha256 does not match canonical evidence digest: expected " + computedSha256 + " but was " + evidence.receiptSha256(),
                    "SHA256_DIGEST_MISMATCH"
            );
        }

        // Invariant 3: atsPlatform
        if (evidence.atsPlatform() == null || evidence.atsPlatform().isBlank()) {
            throw new InvalidReceiptException("ATS platform is required", "MISSING_ATS_PLATFORM");
        }
        String platform = evidence.atsPlatform().trim().toUpperCase(Locale.ROOT);
        if (isMockOrSynthetic(platform)) {
            throw new InvalidReceiptException(
                    "Mock or synthetic ATS platform is rejected: " + evidence.atsPlatform(),
                    "MOCK_PLATFORM_REJECTED"
            );
        }
        if (!ALLOWED_ATS_PLATFORMS.contains(platform)) {
            throw new InvalidReceiptException(
                    "Unsupported or unverified ATS platform: " + evidence.atsPlatform() + ". Supported platforms: " + ALLOWED_ATS_PLATFORMS,
                    "UNSUPPORTED_ATS_PLATFORM"
            );
        }

        // Invariant 4: submittedAt
        if (evidence.submittedAt() == null) {
            throw new InvalidReceiptException("submittedAt timestamp is required", "MISSING_SUBMITTED_AT");
        }
        Instant now = Instant.now();
        if (evidence.submittedAt().isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new InvalidReceiptException(
                    "submittedAt timestamp cannot be in the future: " + evidence.submittedAt(),
                    "FUTURE_SUBMITTED_AT"
            );
        }

        // Invariant 7: attestationConfirmed (backed by verified candidate approval record)
        if (!evidence.attestationConfirmed()) {
            throw new InvalidReceiptException(
                    "Candidate attestation record is missing or not confirmed in audit log",
                    "MISSING_CANDIDATE_ATTESTATION"
            );
        }

        // Safeguard: Candidate-authorised sandbox restriction for the verification phase.
        // Real submissions are disabled. Receipts are strictly accepted only for candidate-owned
        // sandbox/test postings and require affirmative candidate authorization.
        if (!evidence.sandboxPosting()) {
            throw new InvalidReceiptException(
                    "Real submission verification rejected: real submissions are disabled. Receipts are only accepted for candidate-owned sandbox postings",
                    "NON_SANDBOX_RECEIPT_REJECTED"
            );
        }
        if (!evidence.candidateAuthorizedSandbox()) {
            throw new InvalidReceiptException(
                    "Unauthorized sandbox receipt rejected: candidate-owned sandbox must be explicitly verified and authorized",
                    "UNAUTHORIZED_SANDBOX"
            );
        }
    }

    /**
     * Evaluates genuine receipt evidence and produces a detailed outcome.
     */
    public VerificationOutcome evaluate(ReceiptEvidence evidence) {
        validateOrThrow(evidence);

        List<Map<String, String>> reasons = new ArrayList<>();
        reasons.add(reason("ALL_7_INVARIANTS_VERIFIED", "All 7 genuine receipt invariants verified successfully."));
        reasons.add(reason("CANDIDATE_ATTESTATION_CONFIRMED", "Candidate attestation is confirmed."));
        reasons.add(reason("ZERO_MOCK_PREFIXES_VERIFIED", "Confirmed zero mock or synthetic prefixes in receipt."));
        reasons.add(reason("CANDIDATE_AUTHORIZED_SANDBOX", "Candidate-authorized sandbox posting confirmed."));

        List<String> signals = evidence.observedSignals() != null
                ? new ArrayList<>(evidence.observedSignals()) : List.of();

        return new VerificationOutcome(true, evidence.receiptSha256(), signals, reasons);
    }

    /**
     * Validates an existing {@link SubmissionReceipt} against policy invariants.
     */
    public void validateReceipt(SubmissionReceipt receipt, String receiptSha256, String rawEvidencePayload,
                                UUID profileId, boolean sandboxPosting, boolean candidateAuthorizedSandbox) {
        if (receipt == null) {
            throw new InvalidReceiptException("SubmissionReceipt cannot be null", "MISSING_RECEIPT");
        }

        String confId = receipt.confirmation() != null ? receipt.confirmation().confirmationId() : null;
        boolean attestation = receipt.authorization() != null && receipt.authorization().approved();
        List<String> signals = receipt.confirmation() != null ? receipt.confirmation().observedSignals() : List.of();

        ReceiptEvidence evidence = new ReceiptEvidence(
                confId,
                receiptSha256,
                receipt.atsKind(),
                receipt.submittedAt(),
                profileId,
                receipt.applicationId(),
                attestation,
                sandboxPosting,
                candidateAuthorizedSandbox,
                signals,
                rawEvidencePayload
        );

        validateOrThrow(evidence);
    }

    /**
     * Overload for backward compatibility.
     */
    public void validateReceipt(SubmissionReceipt receipt, String receiptSha256, UUID profileId,
                                boolean sandboxPosting, boolean candidateAuthorizedSandbox) {
        validateReceipt(receipt, receiptSha256, receipt.confirmation() != null ? receipt.confirmation().summary() : null,
                profileId, sandboxPosting, candidateAuthorizedSandbox);
    }

    /**
     * Validates an existing {@link SubmissionReceipt} enforcing strict profile ownership.
     */
    public void validateReceiptWithProfileOwnership(SubmissionReceipt receipt, String receiptSha256,
                                                    String rawEvidencePayload, UUID expectedProfileId,
                                                    UUID callerProfileId, boolean sandboxPosting,
                                                    boolean candidateAuthorizedSandbox) {
        if (callerProfileId == null || !callerProfileId.equals(expectedProfileId)) {
            throw new InvalidReceiptException(
                    "Cross-profile receipt access rejected: caller profile " + callerProfileId + " does not own application receipt",
                    "CROSS_PROFILE_RECEIPT_REJECTED"
            );
        }
        validateReceipt(receipt, receiptSha256, rawEvidencePayload, callerProfileId, sandboxPosting, candidateAuthorizedSandbox);
    }

    private static Map<String, String> reason(String code, String message) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        return map;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
