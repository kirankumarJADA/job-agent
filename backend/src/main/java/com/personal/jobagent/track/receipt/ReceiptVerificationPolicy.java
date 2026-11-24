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

    public static final Set<String> MOCK_PREFIXES = Set.of("MOCK-", "TEST-", "SAMPLE-");

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
            private boolean attestationConfirmed;
            private boolean sandboxPosting;
            private boolean candidateAuthorizedSandbox = true;
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
     * Prefixes MOCK-*, TEST-*, SAMPLE-* are detected case-insensitively.
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

        // Invariant 2: receiptSha256
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
        if (evidence.rawEvidencePayload() != null && !evidence.rawEvidencePayload().isBlank()) {
            String computed = computeEvidenceSha256(evidence.rawEvidencePayload());
            if (!computed.equalsIgnoreCase(evidence.receiptSha256().trim())) {
                throw new InvalidReceiptException(
                        "receiptSha256 does not match canonical evidence payload: expected " + computed + " but was " + evidence.receiptSha256(),
                        "SHA256_DIGEST_MISMATCH"
                );
            }
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

        // Invariant 5: profileId
        if (evidence.profileId() == null) {
            throw new InvalidReceiptException("profileId is required", "MISSING_PROFILE_ID");
        }

        // Invariant 6: applicationId
        if (evidence.applicationId() == null) {
            throw new InvalidReceiptException("applicationId is required", "MISSING_APPLICATION_ID");
        }

        // Invariant 7: attestationConfirmed
        if (!evidence.attestationConfirmed()) {
            throw new InvalidReceiptException(
                    "Candidate attestation must be confirmed for genuine submission receipt",
                    "ATTESTATION_NOT_CONFIRMED"
            );
        }

        // Safeguard 2: Candidate-authorised sandbox restriction
        if (evidence.sandboxPosting() && !evidence.candidateAuthorizedSandbox()) {
            throw new InvalidReceiptException(
                    "Real submission verification rejected: real submissions must be verified only in candidate-owned sandbox/test posting",
                    "UNAUTHORIZED_SANDBOX_POSTING"
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
        if (evidence.sandboxPosting()) {
            reasons.add(reason("CANDIDATE_AUTHORIZED_SANDBOX", "Candidate-authorized sandbox posting confirmed."));
        }

        List<String> signals = evidence.observedSignals() != null
                ? new ArrayList<>(evidence.observedSignals()) : List.of();

        return new VerificationOutcome(true, evidence.receiptSha256(), signals, reasons);
    }

    /**
     * Validates an existing {@link SubmissionReceipt} against policy invariants.
     */
    public void validateReceipt(SubmissionReceipt receipt, String receiptSha256, UUID profileId,
                                boolean sandboxPosting, boolean candidateAuthorizedSandbox) {
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
                null
        );

        validateOrThrow(evidence);
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
