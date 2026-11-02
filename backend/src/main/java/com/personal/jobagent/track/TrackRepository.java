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

    public TrackRepository(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
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
                                Map<UUID, List<MessageRecord>> messages) {
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

        Map<UUID, PlanState> plans = new HashMap<>();
        db.query("""
                select distinct on (p.application_id) p.application_id, p.status, p.submit_approved, p.result::text as result
                from automation_plans p join applications a on a.id = p.application_id and a.profile_id = p.profile_id
                where a.profile_id = ?""" + appFilter + "\n" + """
                order by p.application_id, p.created_at desc, p.id desc
                """, (ResultSet rs) -> {
            String status = rs.getString("status");
            plans.put((UUID) rs.getObject("application_id"),
                    new PlanState(status, rs.getBoolean("submit_approved"),
                            "RECONCILIATION_REQUIRED".equals(status) ? reconciliationNoteOf(rs.getString("result")) : null));
        }, args);

        Map<UUID, List<StatusChange>> changes = new HashMap<>();
        Map<UUID, List<CandidateReport>> reports = new HashMap<>();
        db.query("""
                select ev.id, ev.application_id, ev.type, ev.payload->>'from' as from_status, ev.payload->>'to' as to_status,
                       ev.payload->>'kind' as kind, ev.payload->>'occurredOn' as occurred_on, ev.classification, ev.occurred_at
                from application_events ev join applications a on a.id = ev.application_id
                where a.profile_id = ?""" + appFilter + "\n" + """
                  and ev.type in ('STATUS_CHANGED', 'CANDIDATE_REPORT')
                order by ev.occurred_at, ev.id
                """, (ResultSet rs) -> {
            UUID applicationId = (UUID) rs.getObject("application_id");
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

        return new SnapshotParts(apps, preparation, plans, changes, reports, receipts, messages);
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
            "GREENHOUSE_SUBMIT_APPROVAL_REFUSED", "PACKAGE_APPROVAL_INVALIDATED", "AUTOMATION_PLAN_HUMAN_REQUIRED",
            "AUTOMATION_PLAN_FAILED", "AUTOMATION_PLAN_BLOCKED_ANTI_BOT", "AUTOMATION_PLAN_COMPLETED",
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
