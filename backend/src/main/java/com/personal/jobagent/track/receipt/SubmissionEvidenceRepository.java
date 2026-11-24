package com.personal.jobagent.track.receipt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.security.SecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Worker-recorded submission evidence ({@code submission_evidence}, V069).
 *
 * <p><b>Provenance is the row.</b> {@link #insert} is called only by the
 * authenticated worker completion path ({@code SubmissionCompletionService}),
 * inside the same transaction that moves the plan to {@code SUBMITTED}. The
 * worker principal and the {@code recorded_via} marker are written by the
 * server, never copied from the request body, so no string inside
 * {@code automation_plans.result} can make a row appear here.
 *
 * <p><b>Raw evidence at rest.</b> The raw DOM/screenshot payload is encrypted
 * with {@link SecretCipher} (AES-256-GCM) and its SHA-256 digest is computed
 * by the server over the plaintext it actually received. If no key is
 * configured the raw payload is <em>not</em> stored and the status says so —
 * a receipt for that attempt then cannot be verified (fail closed). Reading
 * back ({@link #decryptRawEvidence}) re-checks the digest, so a tampered
 * ciphertext is detected instead of trusted.
 */
@Repository
public class SubmissionEvidenceRepository {

    public static final String RECORDED_VIA_WORKER_COMPLETE = "WORKER_COMPLETE_ENDPOINT";
    public static final int MAX_RAW_EVIDENCE_BYTES = 512 * 1024;

    public static final String RAW_STORED = "STORED_ENCRYPTED";
    public static final String RAW_NOT_PROVIDED = "NOT_PROVIDED";
    public static final String RAW_TOO_LARGE = "REJECTED_TOO_LARGE";
    public static final String RAW_ENCRYPTION_UNAVAILABLE = "ENCRYPTION_UNAVAILABLE";

    public record NewEvidence(
            UUID planId,
            UUID applicationId,
            UUID profileId,
            String workerPrincipal,
            String atsPlatform,
            String targetUrl,
            String confirmationCategory,
            String employerConfirmationId,
            List<String> observedSignals,
            String evidenceText,
            String rawEvidence
    ) {}

    public record StoredEvidence(
            UUID id,
            UUID planId,
            UUID applicationId,
            UUID profileId,
            String workerPrincipal,
            String recordedVia,
            String atsPlatform,
            String targetUrl,
            String confirmationCategory,
            String employerConfirmationId,
            List<String> observedSignals,
            String evidenceText,
            String rawEvidenceSha256,
            Integer rawEvidenceByteLength,
            String rawEvidenceStatus,
            Instant recordedAt,
            String rawEvidenceCiphertext
    ) {
        /** Never serialise the ciphertext by accident through toString-based logging. */
        @Override
        public String toString() {
            return "StoredEvidence[id=" + id + ", planId=" + planId + ", status=" + rawEvidenceStatus + "]";
        }
    }

    private static final String COLUMNS = """
            id, plan_id, application_id, profile_id, worker_principal, recorded_via, ats_platform, target_url,
            confirmation_category, employer_confirmation_id, observed_signals::text as observed_signals,
            evidence_text, raw_evidence_sha256, raw_evidence_byte_length, raw_evidence_status, recorded_at,
            raw_evidence_ciphertext
            """;

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SecretCipher cipher;

    public SubmissionEvidenceRepository(JdbcTemplate db, ObjectMapper json, SecretCipher cipher) {
        this.db = db;
        this.json = json;
        this.cipher = cipher;
    }

    /**
     * Inserts the worker's evidence for one plan. Must run inside the caller's
     * transaction (the SUBMITTED transition); a second insert for the same plan
     * violates {@code submission_evidence.plan_id} uniqueness and fails.
     */
    public StoredEvidence insert(NewEvidence e) {
        UUID id = UuidV7.generate();
        String raw = e.rawEvidence();
        String status;
        String sha256 = null;
        String ciphertext = null;
        Integer byteLength = null;
        if (raw == null || raw.isBlank()) {
            status = RAW_NOT_PROVIDED;
        } else {
            byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            byteLength = bytes.length;
            if (bytes.length > MAX_RAW_EVIDENCE_BYTES) {
                status = RAW_TOO_LARGE;
            } else if (cipher == null || !cipher.configured()) {
                status = RAW_ENCRYPTION_UNAVAILABLE;
            } else {
                sha256 = ReceiptVerificationPolicy.computeEvidenceSha256(raw);
                ciphertext = cipher.encrypt(raw);
                status = RAW_STORED;
            }
        }
        List<String> signals = e.observedSignals() == null ? List.of() : List.copyOf(e.observedSignals());
        String signalsJson;
        try {
            signalsJson = json.writeValueAsString(signals);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialise observed signals", ex);
        }
        db.update("""
                insert into submission_evidence(id, plan_id, application_id, profile_id, worker_principal, recorded_via,
                    ats_platform, target_url, confirmation_category, employer_confirmation_id, observed_signals,
                    evidence_text, raw_evidence_sha256, raw_evidence_ciphertext, raw_evidence_byte_length,
                    raw_evidence_status)
                values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
                """, id, e.planId(), e.applicationId(), e.profileId(), e.workerPrincipal(),
                RECORDED_VIA_WORKER_COMPLETE, e.atsPlatform(), e.targetUrl(), e.confirmationCategory(),
                e.employerConfirmationId(), signalsJson, e.evidenceText(), sha256, ciphertext, byteLength, status);
        return findById(id).orElseThrow(() -> new IllegalStateException("submission evidence vanished after insert"));
    }

    /** Owner-scoped read: evidence for exactly this plan, application and profile. */
    public Optional<StoredEvidence> findForPlan(UUID profileId, UUID applicationId, UUID planId) {
        if (profileId == null || applicationId == null || planId == null) {
            return Optional.empty();
        }
        return db.query("select " + COLUMNS + " from submission_evidence where plan_id = ? and application_id = ? and profile_id = ?",
                (rs, n) -> map(rs), planId, applicationId, profileId).stream().findFirst();
    }

    private Optional<StoredEvidence> findById(UUID id) {
        return db.query("select " + COLUMNS + " from submission_evidence where id = ?", (rs, n) -> map(rs), id)
                .stream().findFirst();
    }

    /**
     * Decrypts the stored raw evidence and re-verifies its digest. Empty when
     * no raw evidence was stored for this attempt.
     *
     * @throws EvidenceIntegrityException when the ciphertext cannot be decrypted
     *         or no longer matches the digest recorded at completion time
     */
    public Optional<String> decryptRawEvidence(StoredEvidence evidence) {
        if (evidence == null || !RAW_STORED.equals(evidence.rawEvidenceStatus())
                || evidence.rawEvidenceCiphertext() == null) {
            return Optional.empty();
        }
        if (cipher == null || !cipher.configured()) {
            throw new EvidenceIntegrityException("raw evidence is stored encrypted but no decryption key is configured");
        }
        String plaintext;
        try {
            plaintext = cipher.decrypt(evidence.rawEvidenceCiphertext());
        } catch (RuntimeException e) {
            throw new EvidenceIntegrityException("stored raw evidence could not be decrypted", e);
        }
        String digest = ReceiptVerificationPolicy.computeEvidenceSha256(plaintext);
        if (!digest.equalsIgnoreCase(evidence.rawEvidenceSha256())) {
            throw new EvidenceIntegrityException("stored raw evidence failed its integrity check");
        }
        return Optional.of(plaintext);
    }

    private StoredEvidence map(ResultSet rs) throws SQLException {
        List<String> signals;
        try {
            String s = rs.getString("observed_signals");
            signals = s == null ? List.of() : json.readValue(s, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            signals = List.of();
        }
        Object byteLength = rs.getObject("raw_evidence_byte_length");
        String sha = rs.getString("raw_evidence_sha256");
        return new StoredEvidence(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("plan_id"),
                (UUID) rs.getObject("application_id"),
                (UUID) rs.getObject("profile_id"),
                rs.getString("worker_principal"),
                rs.getString("recorded_via"),
                rs.getString("ats_platform"),
                rs.getString("target_url"),
                rs.getString("confirmation_category"),
                rs.getString("employer_confirmation_id"),
                signals,
                rs.getString("evidence_text"),
                sha == null ? null : sha.trim(),
                byteLength == null ? null : ((Number) byteLength).intValue(),
                rs.getString("raw_evidence_status"),
                rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("raw_evidence_ciphertext"));
    }
}
