package com.personal.jobagent.track;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.track.EmailApplicationMatcher.OwnedApplication;
import com.personal.jobagent.track.TrackModels.CandidateReport;
import com.personal.jobagent.track.TrackModels.MessageRecord;
import com.personal.jobagent.track.TrackModels.PlanState;
import com.personal.jobagent.track.TrackModels.Preparation;
import com.personal.jobagent.track.TrackModels.ReceiptRecord;
import com.personal.jobagent.track.TrackModels.StatusChange;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.EventClassification;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.ReportKind;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;
import com.personal.jobagent.track.TrackVocabulary.Verification;
import com.personal.jobagent.track.ReceiptTemporalPolicy.AnchorSource;
import com.personal.jobagent.track.ReceiptTemporalPolicy.SubmissionAnchor;
import com.personal.jobagent.track.TrackModels.SubmissionFacts;
import com.personal.jobagent.track.receipt.BrowserObservationReader;
import com.personal.jobagent.track.receipt.ReceiptCaptureService;
import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository;
import com.personal.jobagent.track.receipt.UniversalSubmissionEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistence for TRACK. Every statement carries the owner's profile id as a
 * predicate (or writes it, where the database trigger then re-checks it against
 * the application's owner), so nothing here can read or attach another
 * candidate's application, message or evidence.
 *
 * <p>Writes are idempotent by construction: timeline rows by (application,
 * event key), receipts by (application, fingerprint), links by message — a
 * retried or concurrent write of the same thing is a no-op, never a duplicate.
 */
@Repository
public class TrackRepository {

    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
    };

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ReceiptCaptureService receiptCaptureService;
    private final BrowserObservationReader observationReader;

    @Autowired
    public TrackRepository(JdbcTemplate db, ObjectMapper json,
                           @Autowired(required = false) ReceiptCaptureService receiptCaptureService,
                           @Autowired(required = false) BrowserObservationReader observationReader) {
        this.db = db;
        this.json = json;
        this.receiptCaptureService = receiptCaptureService;
        this.observationReader = observationReader;
    }

    public TrackRepository(JdbcTemplate db, ObjectMapper json, ReceiptCaptureService receiptCaptureService) {
        this(db, json, receiptCaptureService, null);
    }

    public TrackRepository(JdbcTemplate db, ObjectMapper json) {
        this(db, json, null, null);
    }

    // ── writes ───────────────────────────────────────────────────────────

    public record EventWrite(UUID applicationId, UUID profileId, String type, String eventKey,
                             EventClassification classification, Verification verification,
                             Instant eventTime, TimePrecision precision, String summary, String externalId,
                             UUID emailId, UUID receiptId, UUID supersedesEventId,
                             Map<String, Object> payload, Map<String, Object> provenance, String actor) {
    }

    /** Appends a timeline row; empty when this event key was already recorded for the application. */
    public Optional<UUID> insertEvent(EventWrite e) {
        UUID id = UuidV7.generate();
        List<UUID> inserted = db.query("""
                        insert into application_events (id, application_id, type, payload, actor, profile_id, event_key,
                                                        classification, verification_status, event_time, event_time_precision,
                                                        summary, external_id, email_id, receipt_id, supersedes_event_id, provenance)
                        values (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        on conflict (application_id, event_key) where event_key is not null do nothing
                        returning id
                        """, (rs, n) -> (UUID) rs.getObject(1),
                id, e.applicationId(), e.type(), write(e.payload()), e.actor() == null ? "SYSTEM" : e.actor(),
                e.profileId(), e.eventKey(), e.classification().name(), e.verification().name(),
                ts(e.eventTime()), e.precision().name(), e.summary(), e.externalId(), e.emailId(), e.receiptId(),
                e.supersedesEventId(), write(e.provenance()));
        return inserted.stream().findFirst();
    }

    public record ReceiptWrite(UUID profileId, UUID applicationId, EvidenceType type, Channel channel, UUID emailId,
                               String providerMessageId, String externalReference, String senderDomain,
                               Instant evidenceTime, TimePrecision precision, String fingerprint,
                               Verification verification, List<Map<String, String>> reasons, String summary,
                               List<String> identityEvidence) {
    }

    /** Stores one receipt; empty when the same evidence was already recorded for the application. */
    public Optional<UUID> insertReceipt(ReceiptWrite r) {
        UUID id = UuidV7.generate();
        List<UUID> inserted = db.query("""
                        insert into application_receipts (id, profile_id, application_id, evidence_type, channel, email_id,
                                                          provider_message_id, external_reference, sender_domain, evidence_time,
                                                          evidence_time_precision, fingerprint, verification_status,
                                                          verification_reasons, policy_version, summary, identity_evidence)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb)
                        on conflict (application_id, fingerprint) do nothing
                        returning id
                        """, (rs, n) -> (UUID) rs.getObject(1),
                id, r.profileId(), r.applicationId(), r.type().name(), r.channel().name(), r.emailId(),
                r.providerMessageId(), r.externalReference(), r.senderDomain(), ts(r.evidenceTime()),
                r.precision().name(), r.fingerprint(), r.verification().name(), write(r.reasons()),
                ReceiptVerificationPolicy.VERSION, r.summary(), write(r.identityEvidence()));
        return inserted.stream().findFirst();
    }

    public Optional<UUID> receiptIdByFingerprint(UUID profileId, UUID applicationId, String fingerprint) {
        return db.query("select id from application_receipts where profile_id = ? and application_id = ? and fingerprint = ?",
                (rs, n) -> (UUID) rs.getObject(1), profileId, applicationId, fingerprint).stream().findFirst();
    }

    public record LinkWrite(UUID profileId, UUID emailId, UUID applicationId, LinkStatus status, String matchStrength,
                            List<Map<String, String>> rationale, List<Map<String, Object>> candidates,
                            EmailCategory inferredCategory, double inferredConfidence, boolean explicitReceipt,
                            String quotedReference, String evidenceExcerpt, String processingVersion) {
    }

    public void insertLink(LinkWrite l) {
        db.update("""
                        insert into email_application_links (id, profile_id, email_id, application_id, status, match_strength,
                                                             rationale, candidates, inferred_category, inferred_confidence,
                                                             explicit_receipt, quoted_reference, evidence_excerpt, processing_version)
                        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
                        on conflict (email_id) do nothing
                        """,
                UuidV7.generate(), l.profileId(), l.emailId(), l.applicationId(), l.status().name(), l.matchStrength(),
                write(l.rationale()), write(l.candidates()), l.inferredCategory().name(),
                Math.round(l.inferredConfidence() * 100) / 100.0, l.explicitReceipt(), l.quotedReference(),
                l.evidenceExcerpt(), l.processingVersion());
    }

    public record LinkRow(UUID emailId, UUID applicationId, LinkStatus status, String matchStrength,
                          List<Map<String, Object>> rationale, List<Map<String, Object>> candidates,
                          EmailCategory inferredCategory, double inferredConfidence, boolean explicitReceipt,
                          String quotedReference, EmailCategory confirmedCategory, String excerpt, int decisionVersion,
                          Instant createdAt,
                          Instant decidedAt, String subject, String fromAddress, Instant receivedAt,
                          boolean receivedTimeKnown, String messageId, String channel,
                          Map<String, Object> senderAuthentication, String contentFingerprint) {
    }

    private static final String LINK_COLUMNS = """
            l.email_id, l.application_id, l.status, l.match_strength, l.rationale::text as rationale,
            l.candidates::text as candidates, l.inferred_category, l.inferred_confidence, l.explicit_receipt,
            l.quoted_reference, l.confirmed_category, l.evidence_excerpt, l.decision_version, l.created_at, l.decided_at,
            e.subject, e.from_address, e.received_at, e.received_time_known, e.message_id, e.channel,
            e.sender_authentication::text as sender_authentication, e.content_fingerprint
            """;

    public Optional<LinkRow> link(UUID profileId, UUID emailId) {
        return db.query("select " + LINK_COLUMNS + """
                        from email_application_links l
                        join emails e on e.id = l.email_id and e.profile_id = l.profile_id
                        where l.email_id = ? and l.profile_id = ?
                        """, (rs, n) -> linkRow(rs), emailId, profileId).stream().findFirst();
    }

    /** Messages awaiting the candidate: unresolved associations and unconfirmed meanings. Newest first, bounded. */
    public List<LinkRow> reviewQueue(UUID profileId, int limit) {
        return db.query("select " + LINK_COLUMNS + """
                        from email_application_links l
                        join emails e on e.id = l.email_id and e.profile_id = l.profile_id
                        where l.profile_id = ?
                          and (l.status in ('PENDING_REVIEW', 'UNMATCHED', 'CONFLICT')
                               or (l.status in ('LINKED', 'CONFIRMED') and l.confirmed_category is null
                                   and l.inferred_category not in ('APPLICATION_ACKNOWLEDGEMENT', 'UNRELATED_OR_AMBIGUOUS', 'VERIFICATION_CODE')))
                        order by l.created_at desc, l.email_id
                        limit ?
                        """, (rs, n) -> linkRow(rs), profileId, limit);
    }

    /**
     * Records the candidate's decision on one message. Optimistic: applies only
     * when the decision version is still the one the caller saw.
     */
    public boolean decideLink(UUID profileId, UUID emailId, int expectedVersion, LinkStatus status,
                              UUID applicationId, EmailCategory confirmedCategory) {
        int updated = db.update("""
                        update email_application_links
                        set status = ?, application_id = ?, confirmed_category = ?, decided_at = now(),
                            decision_version = decision_version + 1, updated_at = now()
                        where email_id = ? and profile_id = ? and decision_version = ?
                        """, status.name(), applicationId, confirmedCategory == null ? null : confirmedCategory.name(),
                emailId, profileId, expectedVersion);
        if (updated == 1) {
            // Keep the pre-8.3 pointer that other readers (the MCP mail list) use in step.
            db.update("update emails set application_id = ? where id = ? and profile_id = ?",
                    status == LinkStatus.LINKED || status == LinkStatus.CONFIRMED ? applicationId : null, emailId, profileId);
        }
        return updated == 1;
    }

    // ── receipts ─────────────────────────────────────────────────────────

    public record ReceiptRow(UUID id, UUID applicationId, EvidenceType type, Channel channel, UUID emailId,
                             String externalReference, Instant evidenceTime, TimePrecision precision, Instant capturedAt,
                             Verification verification, List<Map<String, Object>> reasons, String policyVersion,
                             String summary, String candidateReview, Instant candidateReviewedAt, String candidateReviewNote,
                             int reviewVersion, String linkStatus) {
    }

    private static final String RECEIPT_COLUMNS = """
            r.id, r.application_id, r.evidence_type, r.channel, r.email_id, r.external_reference, r.evidence_time,
            r.evidence_time_precision, r.captured_at, r.verification_status, r.verification_reasons::text as reasons,
            r.policy_version, r.summary, r.candidate_review, r.candidate_reviewed_at, r.candidate_review_note,
            r.review_version, l.status as link_status
            """;

    public List<ReceiptRow> receipts(UUID profileId, UUID applicationId) {
        return db.query("select " + RECEIPT_COLUMNS + """
                        from application_receipts r
                        left join email_application_links l on l.email_id = r.email_id and l.profile_id = r.profile_id
                        where r.profile_id = ? and r.application_id = ?
                        order by r.captured_at, r.id
                        """, (rs, n) -> receiptRow(rs), profileId, applicationId);
    }

    public Optional<ReceiptRow> receipt(UUID profileId, UUID applicationId, UUID receiptId) {
        return db.query("select " + RECEIPT_COLUMNS + """
                        from application_receipts r
                        left join email_application_links l on l.email_id = r.email_id and l.profile_id = r.profile_id
                        where r.profile_id = ? and r.application_id = ? and r.id = ?
                        """, (rs, n) -> receiptRow(rs), profileId, applicationId, receiptId).stream().findFirst();
    }

    public List<ReceiptRow> receiptsForEmail(UUID profileId, UUID emailId) {
        return db.query("select " + RECEIPT_COLUMNS + """
                        from application_receipts r
                        left join email_application_links l on l.email_id = r.email_id and l.profile_id = r.profile_id
                        where r.profile_id = ? and r.email_id = ?
                        """, (rs, n) -> receiptRow(rs), profileId, emailId);
    }

    public boolean reviewReceipt(UUID profileId, UUID applicationId, UUID receiptId, int expectedVersion,
                                 String decision, String note) {
        return db.update("""
                        update application_receipts
                        set candidate_review = ?, candidate_reviewed_at = now(), candidate_review_note = ?,
                            review_version = review_version + 1
                        where id = ? and application_id = ? and profile_id = ? and review_version = ?
                        """, decision, note, receiptId, applicationId, profileId, expectedVersion) == 1;
    }

    // ── receipt links (V071) ─────────────────────────────────────────────

    /** One append-only correlation decision about one receipt (see V071). */
    public record ReceiptLinkWrite(UUID profileId, UUID applicationId, UUID receiptId, UUID planId, UUID evidenceId,
                                   UUID supersedesLinkId, String origin, String decision, Channel channel,
                                   String providerId, Verification receiptVerification, String receiptFingerprint,
                                   String evidenceSha256, String receiptReference, String submissionReference,
                                   String referenceConsistency, String referenceDetail, Instant evidenceTime,
                                   TimePrecision evidencePrecision, String evidenceTimeSource, Instant anchorTime,
                                   TimePrecision anchorPrecision, String anchorSource, String temporalStatus,
                                   long clockSkewSeconds, String policyVersion, Map<String, Object> correlation,
                                   String actor) {
    }

    /**
     * Appends a link row; empty when it would duplicate the current one (a repeated or concurrent
     * write of the same decision: the receipt's root, or a second supersede of the same row).
     */
    public Optional<UUID> insertReceiptLink(ReceiptLinkWrite w) {
        if (!receiptLinksPresent()) return Optional.empty();
        UUID id = UuidV7.generate();
        List<UUID> inserted = db.query("""
                        insert into application_receipt_links (id, profile_id, application_id, receipt_id, plan_id,
                            submission_evidence_id, supersedes_link_id, origin, decision, channel, provider_id,
                            receipt_verification, receipt_fingerprint, evidence_sha256, receipt_reference,
                            submission_reference, reference_consistency, reference_detail, evidence_time,
                            evidence_time_precision, evidence_time_source, anchor_time, anchor_precision, anchor_source,
                            temporal_status, clock_skew_seconds, policy_version, correlation, actor)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        on conflict do nothing
                        returning id
                        """, (rs, n) -> (UUID) rs.getObject(1),
                id, w.profileId(), w.applicationId(), w.receiptId(), w.planId(), w.evidenceId(), w.supersedesLinkId(),
                w.origin(), w.decision(), w.channel().name(), w.providerId(), w.receiptVerification().name(),
                w.receiptFingerprint(), w.evidenceSha256(), w.receiptReference(), w.submissionReference(),
                w.referenceConsistency(), w.referenceDetail(), ts(w.evidenceTime()), w.evidencePrecision().name(),
                w.evidenceTimeSource(), ts(w.anchorTime()), w.anchorPrecision() == null ? null : w.anchorPrecision().name(),
                w.anchorSource(), w.temporalStatus(), (int) Math.max(0, w.clockSkewSeconds()), w.policyVersion(),
                write(w.correlation()), w.actor() == null ? "SYSTEM" : w.actor());
        return inserted.stream().findFirst();
    }

    public record ReceiptLinkRow(UUID id, UUID receiptId, UUID planId, UUID evidenceId, UUID supersedesLinkId,
                                 String origin, String decision, String channel, String providerId,
                                 String receiptVerification, String receiptFingerprint, String evidenceSha256,
                                 String receiptReference, String submissionReference, String referenceConsistency,
                                 String referenceDetail, Instant evidenceTime, String evidencePrecision,
                                 String evidenceTimeSource, Instant anchorTime, String anchorPrecision,
                                 String anchorSource, String temporalStatus, int clockSkewSeconds,
                                 String policyVersion, Instant linkedAt, boolean current) {
    }

    private static final String LINK_ROW_COLUMNS = """
            k.id, k.receipt_id, k.plan_id, k.submission_evidence_id, k.supersedes_link_id, k.origin, k.decision, k.channel,
            k.provider_id, k.receipt_verification, k.receipt_fingerprint, k.evidence_sha256, k.receipt_reference,
            k.submission_reference, k.reference_consistency, k.reference_detail, k.evidence_time, k.evidence_time_precision,
            k.evidence_time_source, k.anchor_time, k.anchor_precision, k.anchor_source, k.temporal_status,
            k.clock_skew_seconds, k.policy_version, k.linked_at,
            not exists (select 1 from application_receipt_links s where s.supersedes_link_id = k.id) as current
            """;

    /** Every link row for the owner's application, oldest first. */
    public List<ReceiptLinkRow> receiptLinks(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null || !receiptLinksPresent()) return List.of();
        return db.query("select " + LINK_ROW_COLUMNS + """
                        from application_receipt_links k
                        where k.profile_id = ? and k.application_id = ?
                        order by k.linked_at, k.id
                        """, (rs, n) -> linkRowOf(rs), profileId, applicationId);
    }

    /** The current (unsuperseded) link row for one of the owner's receipts. */
    public Optional<ReceiptLinkRow> currentReceiptLink(UUID profileId, UUID applicationId, UUID receiptId) {
        if (profileId == null || receiptId == null || !receiptLinksPresent()) return Optional.empty();
        return db.query("select " + LINK_ROW_COLUMNS + """
                        from application_receipt_links k
                        where k.profile_id = ? and k.application_id = ? and k.receipt_id = ?
                          and not exists (select 1 from application_receipt_links s where s.supersedes_link_id = k.id)
                        """, (rs, n) -> linkRowOf(rs), profileId, applicationId, receiptId).stream().findFirst();
    }

    private ReceiptLinkRow linkRowOf(ResultSet rs) throws SQLException {
        String sha = rs.getString("evidence_sha256");
        return new ReceiptLinkRow((UUID) rs.getObject("id"), (UUID) rs.getObject("receipt_id"),
                (UUID) rs.getObject("plan_id"), (UUID) rs.getObject("submission_evidence_id"),
                (UUID) rs.getObject("supersedes_link_id"), rs.getString("origin"), rs.getString("decision"),
                rs.getString("channel"), rs.getString("provider_id"), rs.getString("receipt_verification"),
                rs.getString("receipt_fingerprint"), sha == null ? null : sha.trim(), rs.getString("receipt_reference"),
                rs.getString("submission_reference"), rs.getString("reference_consistency"),
                rs.getString("reference_detail"), instant(rs, "evidence_time"), rs.getString("evidence_time_precision"),
                rs.getString("evidence_time_source"), instant(rs, "anchor_time"), rs.getString("anchor_precision"),
                rs.getString("anchor_source"), rs.getString("temporal_status"), rs.getInt("clock_skew_seconds"),
                rs.getString("policy_version"), instant(rs, "linked_at"), rs.getBoolean("current"));
    }

    private volatile Boolean linksPresent;

    private boolean receiptLinksPresent() {
        Boolean present = linksPresent;
        if (present == null) {
            present = Boolean.TRUE.equals(db.queryForObject(
                    "select to_regclass('application_receipt_links') is not null", Boolean.class));
            if (present) linksPresent = true; // only cache the positive answer: a migration may still add it
        }
        return present;
    }

    // ── submission context (Stage 3I Phase 2) ────────────────────────────

    /** What an acknowledgement for one application is checked against when it is recorded. */
    public record SubmissionContext(SubmissionAnchor anchor, UUID anchorPlanId, SubmissionFacts submission,
                                    Set<String> disqualifiedReferences) {
    }

    /** Owner-scoped: the submission anchor, Robin's submission record and the identifiers never compared. */
    public SubmissionContext submissionContext(UUID profileId, UUID applicationId) {
        SnapshotParts parts = snapshotParts(profileId, applicationId);
        ApplicationHeader header = parts.applications().get(applicationId);
        if (header == null) {
            return new SubmissionContext(SubmissionAnchor.none(), null, null, Set.of());
        }
        AnchorChoice choice = parts.anchorChoices().get(applicationId);
        SubmissionAnchor anchor = withCandidateReport(choice == null ? SubmissionAnchor.none() : choice.anchor(),
                parts.reports().getOrDefault(applicationId, List.of()));
        UUID anchorPlan = choice != null && anchor.equals(choice.anchor()) ? choice.planId() : null;
        return new SubmissionContext(anchor, anchorPlan, parts.submissions().get(applicationId),
                parts.disqualifiedReferences().getOrDefault(applicationId, Set.of()));
    }

    /** The earliest of a Robin attempt anchor and the day the candidate said they submitted it. */
    public static SubmissionAnchor withCandidateReport(SubmissionAnchor robin, List<CandidateReport> reports) {
        SubmissionAnchor out = robin == null ? SubmissionAnchor.none() : robin;
        for (CandidateReport r : reports == null ? List.<CandidateReport>of() : reports) {
            if (r.kind() == ReportKind.SUBMITTED && r.occurredOn() != null) {
                out = out.earliest(new SubmissionAnchor(r.occurredOn().atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                        TimePrecision.DATE, AnchorSource.CANDIDATE_REPORTED_SUBMISSION));
            }
        }
        return out;
    }

    /**
     * Groups email messages for an application into threads using e.thread_id.
     */
    public List<Map<String, Object>> threadsForApplication(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) return List.of();
        return db.query("""
                select coalesce(e.thread_id, e.id::text) as thread_id,
                       coalesce(max(e.subject), 'No Subject') as subject,
                       count(*) as message_count,
                       max(e.received_at) as last_message_at,
                       coalesce(max(coalesce(l.confirmed_category, l.inferred_category)), 'UNCLASSIFIED') as latest_classification
                from email_application_links l
                join emails e on e.id = l.email_id and e.profile_id = l.profile_id
                where l.profile_id = ? and l.application_id = ?
                  and l.status in ('LINKED', 'CONFIRMED')
                group by coalesce(e.thread_id, e.id::text)
                order by max(e.received_at) desc nulls last
                """, (rs, n) -> {
            Map<String, Object> thread = new LinkedHashMap<>();
            thread.put("threadId", rs.getString("thread_id"));
            thread.put("subject", rs.getString("subject"));
            thread.put("messageCount", rs.getInt("message_count"));
            java.sql.Timestamp ts = rs.getTimestamp("last_message_at");
            thread.put("lastMessageAt", ts == null ? null : ts.toInstant().toString());
            thread.put("latestClassification", rs.getString("latest_classification"));
            return thread;
        }, profileId, applicationId);
    }

    // ── applications ─────────────────────────────────────────────────────

    /** The owner's applications with the identity data a message is matched against. */
    public List<OwnedApplication> ownedApplicationsForMatching(UUID profileId) {
        return db.query("""
                        select a.id as application_id, a.job_id, a.status, a.created_at,
                               j.title, j.company_name_raw, j.location_raw, j.application_url, j.canonical_url,
                               coalesce((select string_agg(k.key, E'\\n') from job_identity_keys k where k.job_id = j.id), '') as keys
                        from applications a
                        join jobs j on j.id = a.job_id
                        where a.profile_id = ?
                        order by a.created_at desc
                        limit 2000
                        """, (rs, n) -> new OwnedApplication(
                        (UUID) rs.getObject("application_id"), (UUID) rs.getObject("job_id"), rs.getString("title"),
                        rs.getString("company_name_raw"), rs.getString("location_raw"), rs.getString("application_url"),
                        rs.getString("canonical_url"), splitKeys(rs.getString("keys")), instant(rs, "created_at"),
                        rs.getString("status")),
                profileId);
    }

    public record ApplicationHeader(UUID id, UUID jobId, String status, Instant createdAt, String jobTitle,
                                    String company, String location) {
    }

    public Optional<ApplicationHeader> application(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) return Optional.empty();
        return db.query("""
                        select a.id, a.job_id, a.status, a.created_at, j.title, coalesce(j.company_name_raw, '') as company,
                               j.location_raw
                        from applications a join jobs j on j.id = a.job_id
                        where a.id = ? and a.profile_id = ?
                        """, (rs, n) -> header(rs), applicationId, profileId).stream().findFirst();
    }

    /**
     * Owner-scoped server-owned URL and employer metadata for an application, used to bind
     * inbound acknowledgement emails to legitimate employer or vetted ATS domains.
     */
    public Optional<EmployerDomainPolicy.ApplicationTarget> applicationTarget(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) return Optional.empty();
        List<Map<String, Object>> rows = db.queryForList("""
                select j.application_url, j.canonical_url, j.company_name_raw, p.target_url
                from applications a
                join jobs j on j.id = a.job_id
                left join automation_plans p on p.application_id = a.id and p.profile_id = a.profile_id
                where a.id = ? and a.profile_id = ?
                order by p.created_at desc nulls last
                """, applicationId, profileId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> first = rows.get(0);
        String applicationUrl = first.get("application_url") instanceof String s ? s : null;
        String canonicalUrl = first.get("canonical_url") instanceof String s ? s : null;
        String company = first.get("company_name_raw") instanceof String s ? s : null;
        List<String> planUrls = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("target_url") instanceof String tu && !tu.isBlank() && !planUrls.contains(tu)) {
                planUrls.add(tu);
            }
        }
        return Optional.of(EmployerDomainPolicy.ApplicationTarget.ofUrls(applicationUrl, canonicalUrl, company, planUrls));
    }

    public List<ApplicationHeader> applications(UUID profileId, List<UUID> ids) {
        if (profileId == null || ids == null || ids.isEmpty()) return List.of();
        return db.query("""
                        select a.id, a.job_id, a.status, a.created_at, j.title, coalesce(j.company_name_raw, '') as company,
                               j.location_raw
                        from applications a join jobs j on j.id = a.job_id
                        where a.profile_id = ? and a.id = any(?)
                        """, (rs, n) -> header(rs), profileId, ids.toArray(new UUID[0]));
    }

    // ── reconciliation snapshot ──────────────────────────────────────────

    /** Everything reconciliation reads, for the owner's applications (all, or the given ones). */
    public record SnapshotParts(Map<UUID, ApplicationHeader> applications, Map<UUID, Preparation> preparation,
                                Map<UUID, PlanState> plans, Map<UUID, List<StatusChange>> statusChanges,
                                Map<UUID, List<CandidateReport>> reports, Map<UUID, List<ReceiptRecord>> receipts,
                                Map<UUID, List<MessageRecord>> messages, Map<UUID, SubmissionFacts> submissions,
                                Map<UUID, AnchorChoice> anchorChoices, Map<UUID, Set<String>> disqualifiedReferences,
                                Map<UUID, Set<String>> foreignReferences) {

        /** Pre-Phase-2 shape: no submission facts, anchors or reference sets. */
        public SnapshotParts(Map<UUID, ApplicationHeader> applications, Map<UUID, Preparation> preparation,
                             Map<UUID, PlanState> plans, Map<UUID, List<StatusChange>> statusChanges,
                             Map<UUID, List<CandidateReport>> reports, Map<UUID, List<ReceiptRecord>> receipts,
                             Map<UUID, List<MessageRecord>> messages) {
            this(applications, preparation, plans, statusChanges, reports, receipts, messages, Map.of(), Map.of(),
                    Map.of(), Map.of());
        }

        /** The full anchor for one application: its Robin attempt anchor, or the candidate's reported date. */
        public SubmissionAnchor anchorFor(UUID applicationId) {
            AnchorChoice choice = anchorChoices.get(applicationId);
            return withCandidateReport(choice == null ? SubmissionAnchor.none() : choice.anchor(),
                    reports.getOrDefault(applicationId, List.of()));
        }
    }

    /** A Robin attempt anchor and the plan it came from. */
    public record AnchorChoice(SubmissionAnchor anchor, UUID planId) {
    }

    public SnapshotParts snapshotParts(UUID profileId, UUID onlyApplicationId) {
        String appFilter = onlyApplicationId == null ? "" : " and a.id = ?";
        Object[] args = onlyApplicationId == null ? new Object[]{profileId} : new Object[]{profileId, onlyApplicationId};

        Map<UUID, ApplicationHeader> apps = new LinkedHashMap<>();
        db.query("""
                select a.id, a.job_id, a.status, a.created_at, j.title, coalesce(j.company_name_raw, '') as company,
                       j.location_raw
                from applications a join jobs j on j.id = a.job_id
                where a.profile_id = ?""" + appFilter, (ResultSet rs) -> {
            ApplicationHeader h = header(rs);
            apps.put(h.id(), h);
        }, args);

        Map<UUID, Preparation> preparation = new HashMap<>();
        Map<UUID, Map<String, String>> steps = new HashMap<>();
        db.query("""
                select distinct on (ev.application_id, ev.payload->>'step')
                       ev.application_id, ev.payload->>'step' as step, ev.payload->>'status' as status
                from application_events ev join applications a on a.id = ev.application_id
                where a.profile_id = ?""" + appFilter + "\n" + """
                  and ev.type = 'PREPARATION'
                order by ev.application_id, ev.payload->>'step', ev.occurred_at desc, ev.id desc
                """, (ResultSet rs) -> {
            steps.computeIfAbsent((UUID) rs.getObject("application_id"), k -> new HashMap<>())
                    .put(String.valueOf(rs.getString("step")), String.valueOf(rs.getString("status")));
        }, args);
        steps.forEach((id, byStep) -> preparation.put(id, new Preparation(true,
                "OK".equals(byStep.get("CV")) && "OK".equals(byStep.get("COVER_LETTER")) && "OK".equals(byStep.get("ANSWERS")),
                byStep.containsValue("FAILED"))));

        boolean receiptTablesPresent = Boolean.TRUE.equals(db.queryForObject(
                "select to_regclass('submission_receipts') is not null and to_regclass('submission_evidence') is not null",
                Boolean.class));
        record PlanRow(UUID applicationId, UUID planId, String status, boolean submitApproved, String reconciliationNote,
                       UUID jobId, String targetUrl) {}
        List<PlanRow> planRows = new ArrayList<>();
        db.query("""
                select distinct on (p.application_id) p.application_id, p.id as plan_id, p.status, p.submit_approved,
                       p.result::text as result, a.job_id, p.target_url
                from automation_plans p join applications a on a.id = p.application_id and a.profile_id = p.profile_id
                where a.profile_id = ?""" + appFilter + "\n" + """
                order by p.application_id, p.created_at desc, p.id desc
                """, (ResultSet rs) -> {
            String status = rs.getString("status");
            planRows.add(new PlanRow(
                    (UUID) rs.getObject("application_id"),
                    (UUID) rs.getObject("plan_id"),
                    status,
                    rs.getBoolean("submit_approved"),
                    "RECONCILIATION_REQUIRED".equals(status) ? reconciliationNoteOf(rs.getString("result")) : null,
                    (UUID) rs.getObject("job_id"),
                    rs.getString("target_url")));
        }, args);

        // Identifiers that are never employer confirmation references: Robin's own ids and the job's keys.
        Map<UUID, Set<String>> disqualified = new HashMap<>();
        db.query("""
                select a.id as application_id, a.job_id, j.application_url, j.canonical_url,
                       coalesce((select string_agg(k.key, E'\\n') from job_identity_keys k where k.job_id = a.job_id), '') as keys,
                       coalesce((select string_agg(p.id::text, E'\\n') from automation_plans p
                                 where p.application_id = a.id and p.profile_id = a.profile_id), '') as plan_ids
                from applications a join jobs j on j.id = a.job_id
                where a.profile_id = ?""" + appFilter, (ResultSet rs) -> {
            List<String> values = new ArrayList<>();
            values.add(String.valueOf(rs.getObject("application_id")));
            values.add(String.valueOf(rs.getObject("job_id")));
            values.addAll(splitKeys(rs.getString("keys")));
            values.addAll(splitKeys(rs.getString("plan_ids")));
            values.add(postingIdOf(rs.getString("application_url")));
            values.add(postingIdOf(rs.getString("canonical_url")));
            disqualified.put((UUID) rs.getObject("application_id"), SubmissionReferencePolicy.disqualifiedIdentifiers(values));
        }, args);

        Map<UUID, PlanState> plans = new HashMap<>();
        Map<UUID, SubmissionFacts> submissions = new HashMap<>();
        for (PlanRow row : planRows) {
            boolean submitted = "SUBMITTED".equals(row.status());
            boolean verifiedReceipt = receiptTablesPresent
                    && receiptCaptureService != null
                    && submitted
                    && receiptCaptureService.isStoredReceiptIntegrityVerified(profileId, row.applicationId(), row.planId());
            boolean browserObserved = false;
            if (submitted && receiptTablesPresent && observationReader != null) {
                SubmissionFacts facts = submissionFacts(profileId, row.applicationId(), row.planId(), row.jobId(),
                        row.targetUrl(), verifiedReceipt, disqualified.getOrDefault(row.applicationId(), Set.of()));
                if (facts != null) {
                    submissions.put(row.applicationId(), facts);
                    browserObserved = facts.browserObserved();
                }
            }
            plans.put(row.applicationId(),
                    new PlanState(row.status(), row.submitApproved(), row.reconciliationNote(), verifiedReceipt, browserObserved));
        }

        // Submission anchors: per plan, Robin's pre-submit fence time, else (for a real attempt) its approval time.
        Map<UUID, AnchorChoice> anchors = new HashMap<>();
        db.query("""
                select p.application_id, p.id as plan_id, p.status,
                       (select min(st.created_at) from automation_steps st
                         where st.plan_id = p.id
                           and (st.step_key = 'pre-submit-attempting' or coalesce(st.result->>'phase', '') = 'PRE_SUBMIT_ATTEMPTING')) as fence_at,
                       (select min(al.created_at) from audit_logs al
                         where al.entity_type = 'AUTOMATION_PLAN' and al.entity_id = p.id
                           and al.action in ('GREENHOUSE_SUBMIT_APPROVED', 'ASHBY_SUBMIT_APPROVED', 'WORKDAY_SUBMIT_APPROVED',
                                             'UNIVERSAL_SUBMIT_APPROVED')) as approved_at
                from automation_plans p join applications a on a.id = p.application_id and a.profile_id = p.profile_id
                where a.profile_id = ?""" + appFilter, (ResultSet rs) -> {
            Instant fence = instant(rs, "fence_at");
            Instant approved = instant(rs, "approved_at");
            String status = rs.getString("status");
            SubmissionAnchor anchor = fence != null
                    ? new SubmissionAnchor(fence, TimePrecision.EXACT, AnchorSource.PRE_SUBMIT_ATTEMPT)
                    : approved != null && ("SUBMITTED".equals(status) || "RECONCILIATION_REQUIRED".equals(status))
                    ? new SubmissionAnchor(approved, TimePrecision.EXACT, AnchorSource.SUBMIT_APPROVAL)
                    : SubmissionAnchor.none();
            if (!anchor.present()) return;
            UUID applicationId = (UUID) rs.getObject("application_id");
            AnchorChoice current = anchors.get(applicationId);
            if (current == null || anchor.earliest(current.anchor()) == anchor) {
                anchors.put(applicationId, new AnchorChoice(anchor, (UUID) rs.getObject("plan_id")));
            }
        }, args);

        // Confirmation references Robin recorded for the owner's applications (worker provenance only).
        Map<UUID, Set<String>> ownReferences = new HashMap<>();
        if (receiptTablesPresent) {
            db.query("""
                    select e.application_id, e.employer_confirmation_id from submission_evidence e
                    join applications a on a.id = e.application_id and a.profile_id = e.profile_id
                    where e.profile_id = ? and e.employer_confirmation_id is not null
                      and e.worker_principal = 'worker' and e.recorded_via = ?
                    """, (ResultSet rs) -> {
                UUID owner = (UUID) rs.getObject("application_id");
                SubmissionReferencePolicy.normalize(rs.getString("employer_confirmation_id")).ifPresent(ref ->
                        ownReferences.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(ref));
            }, profileId, SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE);
        }
        Map<UUID, Set<String>> foreign = new HashMap<>();
        for (UUID applicationId : apps.keySet()) {
            Set<String> others = new LinkedHashSet<>();
            ownReferences.forEach((app, refs) -> {
                if (!app.equals(applicationId)) others.addAll(refs);
            });
            others.removeAll(ownReferences.getOrDefault(applicationId, Set.of()));
            foreign.put(applicationId, others);
        }

        Map<UUID, List<StatusChange>> changes = new HashMap<>();
        Map<UUID, List<CandidateReport>> reports = new HashMap<>();
        db.query("""
                select ev.id, ev.application_id, ev.type, ev.payload->>'from' as from_status, ev.payload->>'to' as to_status,
                       ev.payload->>'kind' as kind, ev.payload->>'occurredOn' as occurred_on, ev.classification, ev.occurred_at,
                       ev.payload->>'event_key' as status_event_key
                from application_events ev join applications a on a.id = ev.application_id
                where a.profile_id = ?""" + appFilter + "\n" + """
                  and ev.type in ('STATUS_CHANGED', 'CANDIDATE_REPORT')
                order by ev.occurred_at, ev.id
                """, (ResultSet rs) -> {
            UUID applicationId = (UUID) rs.getObject("application_id");
            String statusEventKey = rs.getString("status_event_key");
            if ("STATUS_CHANGED".equals(rs.getString("type")) && statusEventKey != null && statusEventKey.startsWith("automation.")) {
                // Robin's own mirror of its real submission onto the pre-8.3 status (AutomationController
                // #markApplicationSubmitted). The V038 trigger labels every non-email status change as the
                // candidate's, which this is not; Robin's submission is represented by its plan and evidence.
                return;
            }
            if ("STATUS_CHANGED".equals(rs.getString("type"))) {
                changes.computeIfAbsent(applicationId, k -> new ArrayList<>()).add(new StatusChange(
                        (UUID) rs.getObject("id"), rs.getString("from_status"), rs.getString("to_status"),
                        "INFERRED".equals(rs.getString("classification")), instant(rs, "occurred_at")));
            } else {
                ReportKind kind = parseReportKind(rs.getString("kind"));
                if (kind != null) {
                    reports.computeIfAbsent(applicationId, k -> new ArrayList<>()).add(new CandidateReport(
                            (UUID) rs.getObject("id"), kind, parseDate(rs.getString("occurred_on")), instant(rs, "occurred_at")));
                }
            }
        }, args);

        Map<UUID, List<ReceiptRecord>> receipts = new HashMap<>();
        db.query("""
                select r.id, r.application_id, r.evidence_type, r.channel, r.verification_status, r.candidate_review,
                       r.external_reference, r.evidence_time, r.evidence_time_precision, r.captured_at, l.status as link_status
                from application_receipts r
                join applications a on a.id = r.application_id and a.profile_id = r.profile_id
                left join email_application_links l on l.email_id = r.email_id and l.profile_id = r.profile_id
                where a.profile_id = ?""" + appFilter + "\n" + """
                order by r.captured_at, r.id
                """, (ResultSet rs) -> {
            receipts.computeIfAbsent((UUID) rs.getObject("application_id"), k -> new ArrayList<>()).add(new ReceiptRecord(
                    (UUID) rs.getObject("id"), EvidenceType.valueOf(rs.getString("evidence_type")),
                    Channel.valueOf(rs.getString("channel")), Verification.valueOf(rs.getString("verification_status")),
                    rs.getString("candidate_review"), "CONFIRMED".equals(rs.getString("link_status")),
                    rs.getString("external_reference"), instant(rs, "evidence_time"),
                    TimePrecision.valueOf(rs.getString("evidence_time_precision")), instant(rs, "captured_at")));
        }, args);

        Map<UUID, List<MessageRecord>> messages = new HashMap<>();
        db.query("""
                select l.email_id, l.status, l.application_id, l.candidates::text as candidates, l.inferred_category,
                       l.confirmed_category, l.created_at, e.received_at, e.received_time_known
                from email_application_links l join emails e on e.id = l.email_id and e.profile_id = l.profile_id
                where l.profile_id = ?
                  and l.status not in ('NOT_APPLICABLE', 'REJECTED')
                """ + (onlyApplicationId == null ? "" : """
                  and (l.application_id = ? or l.candidates @> ?::jsonb)
                """), (ResultSet rs) -> {
            UUID emailId = (UUID) rs.getObject("email_id");
            UUID linked = (UUID) rs.getObject("application_id");
            Set<UUID> candidateIds = candidateIds(rs.getString("candidates"));
            Set<UUID> concerned = new LinkedHashSet<>(candidateIds);
            if (linked != null) concerned.add(linked);
            for (UUID applicationId : concerned) {
                if (onlyApplicationId != null && !onlyApplicationId.equals(applicationId)) continue;
                messages.computeIfAbsent(applicationId, k -> new ArrayList<>()).add(new MessageRecord(
                        emailId, LinkStatus.valueOf(rs.getString("status")), applicationId.equals(linked),
                        candidateIds.contains(applicationId), EmailCategory.parse(rs.getString("inferred_category")),
                        rs.getString("confirmed_category") == null ? null : EmailCategory.parse(rs.getString("confirmed_category")),
                        instant(rs, "received_at"), rs.getBoolean("received_time_known"), instant(rs, "created_at")));
            }
        }, onlyApplicationId == null ? new Object[]{profileId}
                : new Object[]{profileId, onlyApplicationId, candidateFilter(onlyApplicationId)});

        return new SnapshotParts(apps, preparation, plans, changes, reports, receipts, messages, submissions, anchors,
                disqualified, foreign);
    }

    /**
     * Robin's own submission record for one SUBMITTED plan: the worker evidence, whether it is a clean
     * browser observation, and whether its confirmation reference can be trusted for comparison.
     */
    private SubmissionFacts submissionFacts(UUID profileId, UUID applicationId, UUID planId, UUID jobId,
                                            String targetUrl, boolean sandboxVerified, Set<String> disqualified) {
        BrowserObservationReader.Observation observation;
        try {
            observation = observationReader.read(profileId, applicationId, planId, jobId, targetUrl);
        } catch (RuntimeException e) {
            return null;
        }
        SubmissionEvidenceRepository.StoredEvidence e = observation.evidence();
        if (e == null) return null;
        boolean intact = observation.decryptedPayload() != null
                && "worker".equals(e.workerPrincipal())
                && SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE.equals(e.recordedVia());
        UniversalSubmissionEvidence.Parsed parsed = observation.parsed();
        boolean v2Intact = parsed == null || !parsed.isV2()
                || parsed.digest() == UniversalSubmissionEvidence.DigestStatus.VALID;
        String reference = SubmissionReferencePolicy.normalize(e.employerConfirmationId()).orElse(null);
        boolean trusted = intact && v2Intact && reference != null && !disqualified.contains(reference);
        boolean observed = observation.assessment() != null && observation.assessment().clean()
                && "UNIVERSAL".equalsIgnoreCase(e.atsPlatform());
        List<String> withoutProof = new ArrayList<>();
        if (observed && parsed != null && parsed.isV2()) {
            // A document the plan prepared that the accepted submission request did not visibly carry:
            // attached but unconfirmed, or never attached (rejected by the form, or not attempted).
            for (String kind : List.of("cv", "coverLetter")) {
                UniversalSubmissionEvidence.Document d = parsed.documents().get(kind);
                if (d == null) continue;
                boolean attachedUnconfirmed = "ATTACHED_NOT_CONFIRMED".equals(d.uploadVerification());
                boolean preparedNotAttached = "NOT_ATTACHED".equals(d.uploadVerification())
                        && ("REJECTED".equals(d.formAttachment()) || "NOT_ATTEMPTED".equals(d.formAttachment()));
                if (attachedUnconfirmed || preparedNotAttached) withoutProof.add(kind);
            }
        }
        return new SubmissionFacts(planId, e.id(), e.rawEvidenceSha256(), e.atsPlatform(), e.employerConfirmationId(),
                trusted, e.recordedAt(), observed, sandboxVerified,
                parsed == null ? "UNREADABLE" : parsed.kind().name(), withoutProof,
                observation.assessment() == null ? null : observation.assessment().code());
    }

    private static final java.util.regex.Pattern POSTING_PATH = java.util.regex.Pattern.compile(
            "/(?:jobs?|positions?|openings?|roles?|apply)/([A-Za-z0-9._-]{3,128})(?:/|$)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The posting identifier in a job URL's path (e.g. /jobs/4471), which is never a confirmation reference. */
    static String postingIdOf(String url) {
        if (url == null) return null;
        try {
            String path = java.net.URI.create(url.strip()).getPath();
            if (path == null) return null;
            java.util.regex.Matcher m = POSTING_PATH.matcher(path);
            return m.find() ? m.group(1) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── timeline ─────────────────────────────────────────────────────────

    public record TimelineRow(UUID id, String origin, String kind, String payload, String actor, Instant recordedAt,
                              Instant eventTime, String precision, String classification, String verification,
                              String summary, UUID supersedesEventId, UUID receiptId, UUID emailId,
                              Instant sortTime, String entityType) {
    }

    /** Audit actions that describe something done to this application's documents or package. */
    static final List<String> PROJECTED_AUDIT_ACTIONS = List.of(
            "RESUME_ATS_TAILORED", "TAILORED_CV_APPROVED", "TAILORED_CV_APPROVAL_WITHDRAWN",
            "COVER_LETTER_GENERATED", "COVER_LETTER_CORRECTED", "COVER_LETTER_APPROVAL_UPDATED",
            "APPLY_PACKAGE_CREATED", "APPLY_PACKAGE_REFUSED", "GREENHOUSE_FORM_REVIEWED", "GREENHOUSE_SUBMIT_APPROVED",
            "GREENHOUSE_SUBMIT_APPROVAL_REFUSED", "UNIVERSAL_FORM_REVIEWED", "UNIVERSAL_SUBMIT_APPROVED",
            "UNIVERSAL_SUBMIT_APPROVAL_REFUSED", "UNIVERSAL_SUBMIT_QUEUED", "PACKAGE_APPROVAL_INVALIDATED",
            "AUTOMATION_PLAN_HUMAN_REQUIRED", "AUTOMATION_PLAN_FAILED", "AUTOMATION_PLAN_BLOCKED_ANTI_BOT",
            "AUTOMATION_PLAN_COMPLETED", "AUTOMATION_PLAN_SUBMITTED", "AUTOMATION_PLAN_RECONCILIATION_REQUIRED",
            "REVIEW_ITEM_APPROVED");

    /**
     * One page of the application's timeline in chronological order: its own
     * timeline rows plus the append-only audit records of actions on its CV,
     * cover letter, package and plan. The caller has already established
     * ownership; every projection below also re-checks it through the entity's
     * own owner column.
     */
    public List<TimelineRow> timelinePage(UUID profileId, UUID applicationId, UUID jobId, Instant afterTime, UUID afterId,
                                          int limit) {
        String cursor = afterTime == null ? "" : " where (t.sort_time, t.id) > (?, ?) ";
        // Positional parameters, in the order they appear in the statement below.
        List<Object> args = new ArrayList<>(List.of(
                applicationId, profileId,            // timeline rows
                applicationId, profileId,            // audit: APPLICATION
                applicationId, profileId,            // audit: CV
                profileId, applicationId, jobId,     // audit: COVER_LETTER
                applicationId, profileId,            // audit: AUTOMATION_PLAN
                applicationId, profileId));          // audit: APPLICATION_DECISION
        if (afterTime != null) {
            args.add(Timestamp.from(afterTime));
            args.add(afterId);
        }
        args.add(limit);
        String actions = String.join(",", PROJECTED_AUDIT_ACTIONS.stream().map(a -> "'" + a + "'").toList());
        return db.query("""
                        select * from (
                          select ev.id, 'EVENT' as origin, ev.type as kind, ev.payload::text as payload, ev.actor,
                                 ev.occurred_at as recorded_at, ev.event_time, ev.event_time_precision as precision,
                                 ev.classification, ev.verification_status, ev.summary, ev.supersedes_event_id,
                                 ev.receipt_id, ev.email_id, coalesce(ev.event_time, ev.occurred_at) as sort_time,
                                 null as entity_type
                          from application_events ev
                          where ev.application_id = ? and exists (
                                select 1 from applications a where a.id = ev.application_id and a.profile_id = ?)
                          union all
                          select al.id, 'AUDIT', al.action, coalesce(al.after_state::text, '{}'), al.actor,
                                 al.created_at, null, 'EXACT', 'INTERNAL', 'NOT_APPLICABLE', null, null, null, null,
                                 al.created_at, al.entity_type
                          from audit_logs al
                          where al.action in (""" + actions + """
                          ) and (
                                (al.entity_type = 'APPLICATION' and al.entity_id = ?
                                    and exists (select 1 from applications a where a.id = al.entity_id and a.profile_id = ?))
                             or (al.entity_type = 'CV' and al.entity_id in (
                                    select c.id from cv_versions c where c.application_id = ? and c.profile_id = ?))
                             or (al.entity_type = 'COVER_LETTER' and al.entity_id in (
                                    select l.id from cover_letters l where l.profile_id = ?
                                      and (l.application_id = ? or (l.application_id is null and l.job_id = ?))))
                             or (al.entity_type = 'AUTOMATION_PLAN' and al.entity_id in (
                                    select p.id from automation_plans p where p.application_id = ? and p.profile_id = ?))
                             or (al.entity_type = 'APPLICATION_DECISION' and al.entity_id in (
                                    select d.id from application_decisions d where d.application_id = ? and d.profile_id = ?))
                          )
                        ) t""" + cursor + "\n" + """
                        order by t.sort_time, t.id
                        limit ?
                        """, (rs, n) -> new TimelineRow((UUID) rs.getObject("id"), rs.getString("origin"),
                        rs.getString("kind"), rs.getString("payload"), rs.getString("actor"), instant(rs, "recorded_at"),
                        instant(rs, "event_time"), rs.getString("precision"), rs.getString("classification"),
                        rs.getString("verification_status"), rs.getString("summary"),
                        (UUID) rs.getObject("supersedes_event_id"), (UUID) rs.getObject("receipt_id"),
                        (UUID) rs.getObject("email_id"), instant(rs, "sort_time"), rs.getString("entity_type")),
                args.toArray());
    }

    /** The timeline event id written for a message on one application (to supersede on review). */
    public Optional<UUID> eventIdByKey(UUID profileId, UUID applicationId, String eventKey) {
        return db.query("select id from application_events where application_id = ? and profile_id = ? and event_key = ?",
                (rs, n) -> (UUID) rs.getObject(1), applicationId, profileId, eventKey).stream().findFirst();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private LinkRow linkRow(ResultSet rs) throws SQLException {
        String confirmed = rs.getString("confirmed_category");
        return new LinkRow((UUID) rs.getObject("email_id"), (UUID) rs.getObject("application_id"),
                LinkStatus.valueOf(rs.getString("status")), rs.getString("match_strength"),
                readList(rs.getString("rationale")), readList(rs.getString("candidates")),
                EmailCategory.parse(rs.getString("inferred_category")), rs.getDouble("inferred_confidence"),
                rs.getBoolean("explicit_receipt"), rs.getString("quoted_reference"),
                confirmed == null ? null : EmailCategory.parse(confirmed),
                rs.getString("evidence_excerpt"), rs.getInt("decision_version"), instant(rs, "created_at"),
                instant(rs, "decided_at"), rs.getString("subject"), rs.getString("from_address"),
                instant(rs, "received_at"), rs.getBoolean("received_time_known"), rs.getString("message_id"),
                rs.getString("channel"), readMap(rs.getString("sender_authentication")),
                rs.getString("content_fingerprint"));
    }

    private ReceiptRow receiptRow(ResultSet rs) throws SQLException {
        return new ReceiptRow((UUID) rs.getObject("id"), (UUID) rs.getObject("application_id"),
                EvidenceType.valueOf(rs.getString("evidence_type")), Channel.valueOf(rs.getString("channel")),
                (UUID) rs.getObject("email_id"), rs.getString("external_reference"), instant(rs, "evidence_time"),
                TimePrecision.valueOf(rs.getString("evidence_time_precision")), instant(rs, "captured_at"),
                Verification.valueOf(rs.getString("verification_status")), readList(rs.getString("reasons")),
                rs.getString("policy_version"), rs.getString("summary"), rs.getString("candidate_review"),
                instant(rs, "candidate_reviewed_at"), rs.getString("candidate_review_note"), rs.getInt("review_version"),
                rs.getString("link_status"));
    }

    private static ApplicationHeader header(ResultSet rs) throws SQLException {
        return new ApplicationHeader((UUID) rs.getObject("id"), (UUID) rs.getObject("job_id"), rs.getString("status"),
                instant(rs, "created_at"), rs.getString("title"), rs.getString("company"), rs.getString("location_raw"));
    }

    private List<Map<String, Object>> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return json.readValue(value, LIST_OF_MAPS);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * Phase 15: the worker's own persisted {@code category}/{@code evidence}
     * for a {@code RECONCILIATION_REQUIRED} plan — the same {@code
     * SUBMISSION_RECONCILIATION} shape {@code outcome.js} writes via {@code
     * transitionWithResult}. Returns a short, human-readable note, or
     * {@code null} when the result carries no such entry (never fabricated).
     */
    private String reconciliationNoteOf(String resultJson) {
        for (Map<String, Object> entry : readList(resultJson)) {
            if ("SUBMISSION_RECONCILIATION".equals(entry.get("type"))) {
                Object category = entry.get("category");
                Object evidence = entry.get("evidence");
                return "[" + (category == null ? "UNKNOWN" : category) + "] " + (evidence == null ? "" : evidence);
            }
        }
        return null;
    }

    private Map<String, Object> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return json.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private Set<UUID> candidateIds(String value) {
        Set<UUID> out = new LinkedHashSet<>();
        for (Map<String, Object> candidate : readList(value)) {
            Object id = candidate.get("applicationId");
            if (id == null) continue;
            try {
                out.add(UUID.fromString(String.valueOf(id)));
            } catch (IllegalArgumentException ignored) {
                // a malformed candidate is not an application
            }
        }
        return out;
    }

    private String candidateFilter(UUID applicationId) {
        return write(List.of(Map.of("applicationId", applicationId.toString())));
    }

    String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise TRACK record", e);
        }
    }

    private static Set<String> splitKeys(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return new LinkedHashSet<>(Arrays.asList(value.split("\n")));
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static ReportKind parseReportKind(String value) {
        if (value == null) return null;
        try {
            return ReportKind.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
