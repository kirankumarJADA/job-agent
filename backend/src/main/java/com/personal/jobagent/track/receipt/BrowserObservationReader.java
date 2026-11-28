package com.personal.jobagent.track.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.receipt.BrowserObservationPolicy.Assessment;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.StoredEvidence;
import com.personal.jobagent.track.receipt.UniversalSubmissionEvidence.Parsed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Loads one attempt's worker evidence (owner-scoped), verifies it, and applies
 * {@link BrowserObservationPolicy}. The single implementation behind both the
 * receipt view and TRACK's reconciliation snapshot.
 */
@Component
public class BrowserObservationReader {

    private static final Logger log = LoggerFactory.getLogger(BrowserObservationReader.class);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final SubmissionEvidenceRepository evidence;

    public BrowserObservationReader(JdbcTemplate db, ObjectMapper json, SubmissionEvidenceRepository evidence) {
        this.db = db;
        this.json = json;
        this.evidence = evidence;
    }

    /** The evidence row (if any), what its payload says, and whether it is a clean browser observation. */
    public record Observation(StoredEvidence evidence, Assessment assessment, String decryptedPayload) {
        public Parsed parsed() {
            return assessment == null ? null : assessment.parsed();
        }
    }

    public Observation read(UUID profileId, UUID applicationId, UUID planId, UUID jobId, String targetUrl) {
        return read(profileId, applicationId, planId, jobId, targetUrl, null);
    }

    /**
     * @param approvalAttested the caller's strict-approval result, or null to look it up here
     */
    public Observation read(UUID profileId, UUID applicationId, UUID planId, UUID jobId, String targetUrl,
                            Boolean approvalAttested) {
        StoredEvidence e = evidence.findForPlan(profileId, applicationId, planId).orElse(null);
        boolean approved = approvalAttested != null ? approvalAttested : approved(profileId, applicationId, planId);
        String payload = null;
        boolean integrity = false;
        if (e != null) {
            try {
                Optional<String> decrypted = evidence.decryptRawEvidence(e);
                payload = decrypted.orElse(null);
                integrity = decrypted.isPresent();
            } catch (RuntimeException ex) {
                log.warn("Stored evidence for plan {} failed decryption or its integrity check: {}", planId, ex.getMessage());
            }
        }
        Parsed parsed = integrity ? UniversalSubmissionEvidence.parse(payload, json) : null;
        Assessment assessment = BrowserObservationPolicy.assess(new BrowserObservationPolicy.Inputs(
                e, approved, integrity, payload, parsed, planId, applicationId, jobId, targetUrl));
        return new Observation(e, assessment, integrity ? payload : null);
    }

    private boolean approved(UUID profileId, UUID applicationId, UUID planId) {
        try {
            return !db.queryForList(JdbcReceiptTrustStore.STRICT_APPROVAL_SQL, planId, applicationId, profileId).isEmpty();
        } catch (RuntimeException e) {
            log.warn("Could not read submission approval for plan {}: {}", planId, e.getMessage());
            return false;
        }
    }
}
