package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.gmail.GmailConnectionStore;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.track.ReceiptVerificationPolicy.SenderAuthentication;
import com.personal.jobagent.track.TrackEvidenceRecorder.IdentityFacts;
import com.personal.jobagent.track.TrackEvidenceRecorder.MessageFacts;
import com.personal.jobagent.track.TrackModels.TrackSnapshot;
import com.personal.jobagent.track.TrackModels.TrackState;
import com.personal.jobagent.track.TrackRepository.ApplicationHeader;
import com.personal.jobagent.track.TrackRepository.EventWrite;
import com.personal.jobagent.track.TrackRepository.LinkRow;
import com.personal.jobagent.track.TrackRepository.ReceiptRow;
import com.personal.jobagent.track.TrackRepository.ReceiptWrite;
import com.personal.jobagent.track.TrackRepository.SnapshotParts;
import com.personal.jobagent.track.TrackRepository.TimelineRow;
import com.personal.jobagent.track.TrackVocabulary.Channel;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.EventClassification;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.ReportKind;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;
import com.personal.jobagent.track.TrackVocabulary.Verification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * TRACK's read models and the candidate's TRACK actions.
 *
 * <p>Reads ({@link #view}, {@link #summaries}, {@link #timeline},
 * {@link #reviews}) are owner-scoped snapshots reconciled on every request; the
 * derived status is never stored, so it can always be re-derived from the
 * evidence. Writes ({@link #report}, {@link #reviewReceipt},
 * {@link #reviewMessage}) are transactional, idempotent (stable event keys and
 * optimistic versions) and audited, and they only ever append history: an
 * earlier interpretation is superseded by a new row, never rewritten.
 *
 * <p>Nothing here changes Robin's internal application status, and nothing
 * here can make evidence VERIFIED: candidate confirmations are recorded as the
 * candidate's, and verification belongs to {@link ReceiptVerificationPolicy}.
 */
@Service
public class ApplicationTrackingService {

    public static final int DEFAULT_TIMELINE_LIMIT = 50;
    public static final int MAX_TIMELINE_LIMIT = 100;
    public static final int REVIEW_LIMIT = 50;
    private static final int MAX_NOTE_CHARS = 500;
    private static final Pattern CLIENT_REPORT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> STRONG_SIGNALS = Set.of("JOB_URL", "POSTING_IDENTIFIER", "REQUISITION_NUMBER");

    /** A concurrent decision changed the record first; the caller should reload. */
    public static class TrackConflictException extends RuntimeException {
        public TrackConflictException(String message) {
            super(message);
        }
    }

    private final TrackRepository repository;
    private final TrackEvidenceRecorder recorder;
    private final AuditLogWriter audit;
    private final OwnerContext ownerContext;
    private final TrackTimelineMapper timelineMapper;
    private final GmailConnectionStore gmailConnections;
    private final ApplicationReconciler reconciler = new ApplicationReconciler();
    private final ReceiptVerificationPolicy policy = new ReceiptVerificationPolicy();

    public ApplicationTrackingService(TrackRepository repository, TrackEvidenceRecorder recorder, AuditLogWriter audit,
                                      OwnerContext ownerContext, ObjectMapper json,
                                      GmailConnectionStore gmailConnections) {
        this.repository = repository;
        this.recorder = recorder;
        this.audit = audit;
        this.ownerContext = ownerContext;
        this.timelineMapper = new TrackTimelineMapper(json);
        this.gmailConnections = gmailConnections;
    }

    /**
     * True when the signed-in candidate has an active Gmail connection
     * (Phase 8.4). The reconciler only treats MAILBOX_CONNECTOR evidence as
     * verifiable when this is backed by a real, connected mailbox.
     */
    public boolean mailboxConnected() {
        try {
            UUID profileId = ownerContext.profileIdOrNull();
            if (profileId == null) return false;
            return gmailConnections.find(profileId)
                    .map(GmailConnectionStore.Connection::active)
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    // ── reads ────────────────────────────────────────────────────────────

    public Optional<Map<String, Object>> view(UUID profileId, UUID applicationId) {
        if (profileId == null || applicationId == null) return Optional.empty();
        SnapshotParts parts = repository.snapshotParts(profileId, applicationId);
        ApplicationHeader header = parts.applications().get(applicationId);
        if (header == null) return Optional.empty();
        TrackState state = reconciler.reconcile(snapshot(parts, header));

        Map<String, Object> out = stateMap(state);
        out.put("applicationId", applicationId.toString());
        out.put("availability", "AVAILABLE");
        out.put("application", applicationMap(header));
        out.put("receipts", repository.receipts(profileId, applicationId).stream().map(this::receiptMap).toList());
        List<LinkRow> concerning = repository.reviewQueue(profileId, 200).stream()
                .filter(l -> applicationId.equals(l.applicationId()) || candidateIds(l).contains(applicationId))
                .toList();
        out.put("reviews", reviewMaps(profileId, concerning));
        out.put("mailbox", Map.of("connected", mailboxConnected(),
                "note", mailboxConnected()
                        ? "Connected to your Gmail account. Robin reads new employer messages read-only; they are "
                          + "classified and verified under the same policy as everything else."
                        : "Robin cannot read your mailbox: no mailbox connection exists in this version. Add employer "
                          + "messages yourself; they are recorded as added by you and cannot be verified."));
        out.put("versions", Map.of("reconciler", ApplicationReconciler.VERSION, "receiptPolicy", ReceiptVerificationPolicy.VERSION,
                "classifier", TrackEmailClassifier.VERSION, "matcher", EmailApplicationMatcher.VERSION));
        out.put("computedAt", Instant.now().toString());
        return Optional.of(out);
    }

    /** Compact per-application status for the applications list, keyed by application id. */
    public Map<UUID, Map<String, Object>> summaries(UUID profileId) {
        Map<UUID, Map<String, Object>> out = new LinkedHashMap<>();
        if (profileId == null) return out;
        SnapshotParts parts = repository.snapshotParts(profileId, null);
        for (ApplicationHeader header : parts.applications().values()) {
            TrackState state = reconciler.reconcile(snapshot(parts, header));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("availability", "AVAILABLE");
            summary.put("status", state.status().name());
            summary.put("label", state.label());
            summary.put("basis", state.basis() == null ? null : state.basis().name());
            summary.put("submission", state.submission().state().name());
            summary.put("needsReview", state.needsReview());
            summary.put("attentionCount", state.attention().size() + state.conflicts().size());
            summary.put("pending", state.pending() == null ? null : state.pending().label());
            out.put(header.id(), summary);
        }
        return out;
    }

    public record TimelinePage(List<Map<String, Object>> items, String nextCursor, int limit) {
    }

    /**
     * One page of the application's timeline, oldest first.
     *
     * @throws IllegalArgumentException for a malformed cursor or an out-of-range limit
     */
    public Optional<TimelinePage> timeline(UUID profileId, UUID applicationId, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_TIMELINE_LIMIT : limit;
        if (size < 1 || size > MAX_TIMELINE_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_TIMELINE_LIMIT);
        }
        Instant afterTime = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = decoded.split("\\|", 2);
                afterTime = Instant.parse(parts[0]);
                afterId = UUID.fromString(parts[1]);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("malformed cursor");
            }
        }
        Optional<ApplicationHeader> header = repository.application(profileId, applicationId);
        if (header.isEmpty()) return Optional.empty();
        List<TimelineRow> rows = repository.timelinePage(profileId, applicationId, header.get().jobId(), afterTime, afterId, size + 1);
        boolean more = rows.size() > size;
        List<TimelineRow> page = more ? rows.subList(0, size) : rows;
        String next = null;
        if (more) {
            TimelineRow last = page.get(page.size() - 1);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.sortTime().toString() + "|" + last.id()).getBytes(StandardCharsets.UTF_8));
        }
        return Optional.of(new TimelinePage(page.stream().map(timelineMapper::map).toList(), next, size));
    }

    /** Employer messages awaiting the candidate, newest first (bounded). */
    public List<Map<String, Object>> reviews(UUID profileId) {
        if (profileId == null) return List.of();
        return reviewMaps(profileId, repository.reviewQueue(profileId, REVIEW_LIMIT));
    }

    // ── candidate reports ────────────────────────────────────────────────

    public record ReportCommand(String kind, String occurredOn, String note, String clientReportId) {
    }

    /**
     * Records something the candidate tells Robin about an application (that they
     * applied themselves, an interview, a rejection…). Always labelled as reported
     * by the candidate; a submission statement becomes a CANDIDATE_STATEMENT
     * receipt that the policy (and the schema) keeps UNVERIFIED.
     */
    @Transactional
    public Map<String, Object> report(UUID profileId, UUID applicationId, ReportCommand command) {
        if (command == null) throw new IllegalArgumentException("a report is required");
        ReportKind kind = parseKind(command.kind());
        LocalDate occurredOn = parseOccurredOn(command.occurredOn());
        String note = cleanNote(command.note());
        if (command.clientReportId() != null && !CLIENT_REPORT_ID.matcher(command.clientReportId()).matches()) {
            throw new IllegalArgumentException("clientReportId must be 1-64 letters, digits, '-' or '_'");
        }
        ApplicationHeader header = repository.application(profileId, applicationId)
                .orElseThrow(() -> new NoSuchElementException("No application with that id"));

        String key = command.clientReportId() != null ? command.clientReportId()
                : TrackText.sha256(kind + "|" + occurredOn + "|" + (note == null ? "" : note)).substring(0, 32);
        String eventKey = "track-report:" + kind + ":" + key;
        if (repository.eventIdByKey(profileId, applicationId, eventKey).isPresent()) {
            Map<String, Object> replay = view(profileId, applicationId).orElseThrow();
            replay.put("replayed", true);
            return replay;
        }

        Instant eventTime = occurredOn == null ? null : occurredOn.atStartOfDay(ZoneOffset.UTC).toInstant();
        TimePrecision precision = occurredOn == null ? TimePrecision.UNKNOWN : TimePrecision.DATE;
        UUID receiptId = null;
        if (kind == ReportKind.SUBMITTED) {
            ReceiptVerificationPolicy.Outcome outcome = policy.evaluate(new ReceiptVerificationPolicy.Evidence(
                    EvidenceType.CANDIDATE_STATEMENT, Channel.CANDIDATE_ENTERED, false, false, false, null,
                    SenderAuthentication.none(), false, eventTime, Instant.now()));
            receiptId = repository.insertReceipt(new ReceiptWrite(profileId, applicationId, EvidenceType.CANDIDATE_STATEMENT,
                    Channel.CANDIDATE_ENTERED, null, null, null, null, eventTime, precision,
                    TrackText.sha256("STATEMENT|" + eventKey), outcome.status(), outcome.reasons(),
                    "Your statement that you submitted this application — not verified", List.of())).orElse(null);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind.name());
        payload.put("occurredOn", occurredOn == null ? null : occurredOn.toString());
        if (note != null) payload.put("note", note);
        String when = occurredOn == null ? " (date not given)" : " on " + occurredOn;
        repository.insertEvent(new EventWrite(applicationId, profileId, "CANDIDATE_REPORT", eventKey,
                EventClassification.CANDIDATE_REPORTED, Verification.NOT_APPLICABLE, eventTime, precision,
                "You reported " + reportPhrase(kind) + when, null, null, receiptId, null, payload,
                Map.of("source", "candidate"), actor()));
        audit.write(new AuditEntry(actor(), "TRACK_CANDIDATE_REPORT", "APPLICATION", header.id(), null,
                mapOf("kind", kind.name(), "occurredOn", occurredOn == null ? "" : occurredOn.toString(),
                        "receiptId", receiptId == null ? "" : receiptId.toString()),
                null, UuidV7.generate()));
        Map<String, Object> out = view(profileId, applicationId).orElseThrow();
        out.put("replayed", false);
        return out;
    }

    // ── receipt review ───────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> reviewReceipt(UUID profileId, UUID applicationId, UUID receiptId, String decision,
                                             String note, Integer expectedVersion) {
        String value = parseDecision(decision);
        String cleanNote = cleanNote(note);
        ReceiptRow receipt = repository.receipt(profileId, applicationId, receiptId)
                .orElseThrow(() -> new NoSuchElementException("No such evidence for this application"));
        if (expectedVersion != null && expectedVersion != receipt.reviewVersion()) {
            throw new TrackConflictException("This evidence was reviewed in the meantime. Reload and try again.");
        }
        if (value.equals(receipt.candidateReview())) {
            return view(profileId, applicationId).orElseThrow();
        }
        if (!repository.reviewReceipt(profileId, applicationId, receiptId, receipt.reviewVersion(), value, cleanNote)) {
            throw new TrackConflictException("This evidence was reviewed in the meantime. Reload and try again.");
        }
        appendReceiptReview(profileId, applicationId, receipt, value, receipt.reviewVersion() + 1);
        audit.write(new AuditEntry(actor(), "TRACK_RECEIPT_REVIEWED", "APPLICATION", applicationId,
                mapOf("receiptId", receiptId.toString(), "candidateReview", nullToEmpty(receipt.candidateReview())),
                mapOf("receiptId", receiptId.toString(), "candidateReview", value,
                        "verification", receipt.verification().name()), null, UuidV7.generate()));
        return view(profileId, applicationId).orElseThrow();
    }

    private void appendReceiptReview(UUID profileId, UUID applicationId, ReceiptRow receipt, String value, int version) {
        UUID supersedes = repository.eventIdByKey(profileId, applicationId,
                TrackEvidenceRecorder.receiptEventKey(receipt.id())).orElse(null);
        String summary = "CONFIRMED".equals(value)
                ? "You confirmed this evidence is about this application — your confirmation does not make it verified"
                : "You rejected this evidence for this application";
        repository.insertEvent(new EventWrite(applicationId, profileId, "RECEIPT_REVIEWED",
                "track-receipt-review:" + receipt.id() + ":v" + version, EventClassification.CANDIDATE_REPORTED,
                Verification.NOT_APPLICABLE, null, TimePrecision.EXACT, summary, null, receipt.emailId(), receipt.id(),
                supersedes, Map.of("decision", value), Map.of("source", "candidate"), actor()));
    }

    // ── message review ───────────────────────────────────────────────────

    public record MessageReviewCommand(String decision, UUID applicationId, String category, Integer expectedVersion) {
    }

    /**
     * The candidate's decision on one employer message: confirm it belongs to an
     * application (optionally correcting what it means), or reject it. Recorded as
     * a manual confirmation — never promoted to verified evidence.
     */
    @Transactional
    public Map<String, Object> reviewMessage(UUID profileId, UUID emailId, MessageReviewCommand command) {
        if (command == null) throw new IllegalArgumentException("a decision is required");
        String decision = parseDecision(command.decision());
        LinkRow link = repository.link(profileId, emailId)
                .orElseThrow(() -> new NoSuchElementException("No such message"));
        if (link.status() == LinkStatus.NOT_APPLICABLE) {
            throw new IllegalStateException("A sign-in verification message is not application evidence.");
        }
        if (command.expectedVersion() != null && command.expectedVersion() != link.decisionVersion()) {
            throw new TrackConflictException("This message was reviewed in the meantime. Reload and try again.");
        }
        UUID previous = (link.status() == LinkStatus.LINKED || link.status() == LinkStatus.CONFIRMED)
                ? link.applicationId() : null;
        int version = link.decisionVersion() + 1;
        Map<String, Object> before = mapOf("status", link.status().name(),
                "applicationId", link.applicationId() == null ? "" : link.applicationId().toString(),
                "category", (link.confirmedCategory() != null ? link.confirmedCategory() : link.inferredCategory()).name());

        if ("REJECTED".equals(decision)) {
            if (link.status() == LinkStatus.REJECTED) return reviewResult(profileId, emailId, null);
            if (!repository.decideLink(profileId, emailId, link.decisionVersion(), LinkStatus.REJECTED, null, null)) {
                throw new TrackConflictException("This message was reviewed in the meantime. Reload and try again.");
            }
            if (previous != null) dissociate(profileId, previous, link, version);
            audit.write(new AuditEntry(actor(), "TRACK_MESSAGE_REVIEWED", "EMAIL", emailId, before,
                    mapOf("status", LinkStatus.REJECTED.name()), null, UuidV7.generate()));
            return reviewResult(profileId, emailId, null);
        }

        if (command.applicationId() == null) {
            throw new IllegalArgumentException("applicationId is required to confirm a message");
        }
        ApplicationHeader target = repository.application(profileId, command.applicationId())
                .orElseThrow(() -> new NoSuchElementException("No application with that id"));
        EmailCategory category = command.category() == null || command.category().isBlank()
                ? (link.confirmedCategory() != null ? link.confirmedCategory() : link.inferredCategory())
                : parseCategory(command.category());
        if (link.status() == LinkStatus.CONFIRMED && target.id().equals(link.applicationId())
                && category == link.confirmedCategory()) {
            return reviewResult(profileId, emailId, target.id());
        }
        if (!repository.decideLink(profileId, emailId, link.decisionVersion(), LinkStatus.CONFIRMED, target.id(), category)) {
            throw new TrackConflictException("This message was reviewed in the meantime. Reload and try again.");
        }

        MessageFacts facts = facts(link);
        UUID messageEvent = recorder.recordMessage(profileId, target.id(), facts, link.inferredCategory(), true).orElse(null);
        String meaning = TrackVocabulary.categoryLabel(category).toLowerCase(Locale.ROOT);
        String summary = category == link.inferredCategory()
                ? "You confirmed this employer message is about this application: " + meaning
                : "You confirmed this employer message is about this application and corrected its meaning to: "
                + meaning + " (Robin had inferred: " + TrackVocabulary.categoryLabel(link.inferredCategory())
                .toLowerCase(Locale.ROOT) + ")";
        repository.insertEvent(new EventWrite(target.id(), profileId, "MESSAGE_REVIEWED",
                "track-email-review:" + emailId + ":v" + version, EventClassification.CANDIDATE_REPORTED,
                Verification.NOT_APPLICABLE, null, TimePrecision.EXACT, summary, null, emailId, null, messageEvent,
                mapOf("decision", "CONFIRMED", "category", category.name(), "inferredCategory", link.inferredCategory().name()),
                Map.of("source", "candidate"), actor()));
        if (category == EmailCategory.APPLICATION_ACKNOWLEDGEMENT || link.explicitReceipt()) {
            recorder.recordAcknowledgement(profileId, target.id(), facts, identityFor(link, target.id()), actor());
        }
        if (previous != null && !previous.equals(target.id())) {
            dissociate(profileId, previous, link, version);
        }
        audit.write(new AuditEntry(actor(), category == link.inferredCategory()
                ? "TRACK_MESSAGE_REVIEWED" : "TRACK_MESSAGE_RECLASSIFIED", "EMAIL", emailId, before,
                mapOf("status", LinkStatus.CONFIRMED.name(), "applicationId", target.id().toString(),
                        "category", category.name()), null, UuidV7.generate()));
        return reviewResult(profileId, emailId, target.id());
    }

    /** The message no longer belongs to {@code applicationId}: supersede its row and exclude its receipts. */
    private void dissociate(UUID profileId, UUID applicationId, LinkRow link, int version) {
        if (repository.application(profileId, applicationId).isEmpty()) return; // the application is gone
        UUID messageEvent = repository.eventIdByKey(profileId, applicationId,
                TrackEvidenceRecorder.messageEventKey(link.emailId())).orElse(null);
        repository.insertEvent(new EventWrite(applicationId, profileId, "MESSAGE_REVIEWED",
                "track-email-review:" + link.emailId() + ":v" + version, EventClassification.CANDIDATE_REPORTED,
                Verification.NOT_APPLICABLE, null, TimePrecision.EXACT,
                "You said this employer message is not about this application", null, link.emailId(), null,
                messageEvent, Map.of("decision", "REJECTED"), Map.of("source", "candidate"), actor()));
        for (ReceiptRow receipt : repository.receiptsForEmail(profileId, link.emailId())) {
            if (!applicationId.equals(receipt.applicationId()) || "REJECTED".equals(receipt.candidateReview())) continue;
            if (repository.reviewReceipt(profileId, applicationId, receipt.id(), receipt.reviewVersion(), "REJECTED",
                    "The message was removed from this application")) {
                appendReceiptReview(profileId, applicationId, receipt, "REJECTED", receipt.reviewVersion() + 1);
            }
        }
    }

    private Map<String, Object> reviewResult(UUID profileId, UUID emailId, UUID applicationId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emailId", emailId.toString());
        out.put("applicationId", applicationId == null ? null : applicationId.toString());
        out.put("status", repository.link(profileId, emailId).map(l -> l.status().name()).orElse(null));
        out.put("reviews", reviews(profileId));
        return out;
    }

    // ── mapping ──────────────────────────────────────────────────────────

    private TrackSnapshot snapshot(SnapshotParts parts, ApplicationHeader header) {
        UUID id = header.id();
        return new TrackSnapshot(id, header.status(), header.createdAt(),
                parts.preparation().getOrDefault(id, TrackModels.Preparation.none()), parts.plans().get(id),
                parts.statusChanges().getOrDefault(id, List.of()), parts.reports().getOrDefault(id, List.of()),
                parts.receipts().getOrDefault(id, List.of()), parts.messages().getOrDefault(id, List.of()),
                mailboxConnected(), Instant.now());
    }

    private Map<String, Object> stateMap(TrackState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", state.status().name());
        out.put("label", state.label());
        out.put("basis", state.basis() == null ? null : state.basis().name());
        out.put("basisLabel", state.basisLabel());
        out.put("reason", state.reason());
        out.put("since", state.since() == null ? null
                : state.sincePrecision() == TimePrecision.DATE
                ? LocalDate.ofInstant(state.since(), ZoneOffset.UTC).toString() : state.since().toString());
        out.put("sincePrecision", state.sincePrecision() == null ? null : state.sincePrecision().name());
        out.put("evidenceStage", state.evidenceStage().name());
        out.put("recordedStatus", state.recordedStatus());
        out.put("recordedStatusLabel", state.recordedStatusLabel());
        out.put("submission", mapOf("state", state.submission().state().name(), "label", state.submission().label(),
                "reason", state.submission().reason()));
        out.put("acknowledgement", mapOf("state", state.acknowledgement().state().name(),
                "label", state.acknowledgement().label()));
        out.put("communications", mapOf("linkedMessages", state.communications().linkedMessages(),
                "awaitingReview", state.communications().awaitingReview()));
        out.put("pending", state.pending() == null ? null : mapOf("stage", state.pending().stage().name(),
                "label", state.pending().label(),
                "emailId", state.pending().emailId() == null ? null : state.pending().emailId().toString()));
        out.put("attention", state.attention().stream().map(a -> mapOf("code", a.code(), "message", a.message(),
                "emailId", a.emailId() == null ? null : a.emailId().toString(),
                "receiptId", a.receiptId() == null ? null : a.receiptId().toString())).toList());
        out.put("unknowns", state.unknowns());
        out.put("conflicts", state.conflicts().stream().map(c -> mapOf("code", c.code(), "message", c.message())).toList());
        out.put("needsReview", state.needsReview());
        out.put("robinSubmission", mapOf("submitted", state.robinSubmitted(), "note", state.robinSubmissionNote()));
        return out;
    }

    private Map<String, Object> receiptMap(ReceiptRow r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id().toString());
        out.put("evidenceType", r.type().name());
        out.put("evidenceLabel", r.type() == EvidenceType.CANDIDATE_STATEMENT
                ? "Your statement" : "Employer acknowledgement evidence");
        out.put("channel", r.channel().name());
        out.put("channelLabel", switch (r.channel()) {
            case MAILBOX_CONNECTOR -> "Retrieved from your connected mailbox";
            case CANDIDATE_FORWARDED -> "Added by you";
            case CANDIDATE_ENTERED -> "Entered by you";
        });
        out.put("verification", r.verification().name());
        out.put("verificationLabel", TrackVocabulary.verificationLabel(r.verification()));
        out.put("reasons", r.reasons());
        out.put("policyVersion", r.policyVersion());
        out.put("summary", r.summary());
        out.put("externalReference", r.externalReference());
        out.put("evidenceAt", r.evidenceTime() == null ? null
                : r.precision() == TimePrecision.DATE ? LocalDate.ofInstant(r.evidenceTime(), ZoneOffset.UTC).toString()
                : r.evidenceTime().toString());
        out.put("evidenceAtPrecision", r.precision().name());
        out.put("capturedAt", r.capturedAt() == null ? null : r.capturedAt().toString());
        out.put("candidateReview", r.candidateReview());
        out.put("candidateReviewedAt", r.candidateReviewedAt() == null ? null : r.candidateReviewedAt().toString());
        out.put("reviewVersion", r.reviewVersion());
        out.put("emailId", r.emailId() == null ? null : r.emailId().toString());
        return out;
    }

    private List<Map<String, Object>> reviewMaps(UUID profileId, List<LinkRow> links) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (LinkRow link : links) {
            ids.addAll(candidateIds(link));
            if (link.applicationId() != null) ids.add(link.applicationId());
        }
        Map<UUID, ApplicationHeader> headers = new LinkedHashMap<>();
        for (ApplicationHeader h : repository.applications(profileId, new ArrayList<>(ids))) headers.put(h.id(), h);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LinkRow link : links) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("emailId", link.emailId().toString());
            item.put("status", link.status().name());
            item.put("reviewType", switch (link.status()) {
                case CONFLICT -> "CONFLICT";
                case PENDING_REVIEW -> "ASSOCIATION";
                case UNMATCHED -> "UNMATCHED";
                default -> "MEANING";
            });
            item.put("subject", TrackText.cap(link.subject(), 200));
            item.put("from", TrackText.addressOf(link.fromAddress()));
            item.put("receivedAt", link.receivedTimeKnown() && link.receivedAt() != null ? link.receivedAt().toString() : null);
            item.put("receivedTimeKnown", link.receivedTimeKnown());
            EmailCategory effective = link.confirmedCategory() != null ? link.confirmedCategory() : link.inferredCategory();
            item.put("category", effective.name());
            item.put("categoryLabel", TrackVocabulary.categoryLabel(effective));
            item.put("categoryConfirmed", link.confirmedCategory() != null);
            item.put("confidence", link.inferredConfidence());
            item.put("confidenceNote", "A rule-strength score for Robin's inference — not proof of anything.");
            item.put("excerpt", link.excerpt());
            item.put("rationale", link.rationale());
            item.put("decisionVersion", link.decisionVersion());
            item.put("addedBy", "MAILBOX_CONNECTOR".equals(link.channel()) ? "MAILBOX_CONNECTOR" : "YOU");
            ApplicationHeader current = link.applicationId() == null ? null : headers.get(link.applicationId());
            item.put("currentApplication", current == null ? null : applicationMap(current));
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Map<String, Object> candidate : link.candidates()) {
                UUID id = uuidOrNull(candidate.get("applicationId"));
                ApplicationHeader h = id == null ? null : headers.get(id);
                if (h == null) continue; // deleted or not the owner's: never shown
                Map<String, Object> c = applicationMap(h);
                Object signals = candidate.get("signals");
                c.put("signals", signals instanceof List<?> list
                        ? list.stream().map(s -> signalLabel(String.valueOf(s))).toList() : List.of());
                candidates.add(c);
            }
            item.put("candidates", candidates);
            item.put("allowedCategories", java.util.Arrays.stream(EmailCategory.values())
                    .filter(c -> c != EmailCategory.VERIFICATION_CODE)
                    .map(c -> mapOf("value", c.name(), "label", TrackVocabulary.categoryLabel(c))).toList());
            out.add(item);
        }
        return out;
    }

    private static Map<String, Object> applicationMap(ApplicationHeader h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", h.id().toString());
        m.put("jobTitle", h.jobTitle());
        m.put("company", h.company());
        m.put("location", h.location());
        m.put("createdAt", h.createdAt() == null ? null : h.createdAt().toString());
        return m;
    }

    private static String signalLabel(String signal) {
        return switch (signal) {
            case "JOB_URL" -> "Links to this job's posting";
            case "POSTING_IDENTIFIER" -> "Contains this posting's identifier";
            case "REQUISITION_NUMBER" -> "Quotes this job's requisition number";
            case "COMPANY_NAME" -> "Company name matches";
            case "JOB_TITLE" -> "Job title matches";
            case "YOUR_CHOICE" -> "You chose this application";
            case "LEGACY_COMPANY_NAME" -> "Matched by company name before Phase 8.3";
            default -> "Other signal";
        };
    }

    private MessageFacts facts(LinkRow link) {
        Map<String, Object> auth = link.senderAuthentication() == null ? Map.of() : link.senderAuthentication();
        SenderAuthentication authentication = new SenderAuthentication(str(auth.get("dkim")), str(auth.get("dkimDomain")),
                str(auth.get("spf")), str(auth.get("dmarc")));
        Channel channel = "MAILBOX_CONNECTOR".equals(link.channel()) ? Channel.MAILBOX_CONNECTOR : Channel.CANDIDATE_FORWARDED;
        return new MessageFacts(link.emailId(), link.messageId(), link.contentFingerprint(), link.fromAddress(), channel,
                link.receivedAt(), link.receivedTimeKnown(), link.explicitReceipt(), link.quotedReference(),
                authentication, Boolean.TRUE.equals(auth.get("deliveredToConnectedMailbox")));
    }

    private static IdentityFacts identityFor(LinkRow link, UUID applicationId) {
        boolean thisOne = false;
        boolean other = false;
        List<String> evidence = new ArrayList<>();
        for (Map<String, Object> candidate : link.candidates()) {
            UUID id = uuidOrNull(candidate.get("applicationId"));
            Object signals = candidate.get("signals");
            List<String> strong = signals instanceof List<?> list
                    ? list.stream().map(String::valueOf).filter(STRONG_SIGNALS::contains).toList() : List.of();
            if (strong.isEmpty()) continue;
            if (applicationId.equals(id)) {
                thisOne = true;
                evidence.addAll(strong);
            } else {
                other = true;
            }
        }
        return new IdentityFacts(thisOne, other, evidence);
    }

    private static Set<UUID> candidateIds(LinkRow link) {
        Set<UUID> out = new LinkedHashSet<>();
        for (Map<String, Object> candidate : link.candidates()) {
            UUID id = uuidOrNull(candidate.get("applicationId"));
            if (id != null) out.add(id);
        }
        return out;
    }

    // ── validation ───────────────────────────────────────────────────────

    private static ReportKind parseKind(String value) {
        try {
            return ReportKind.valueOf(Objects.requireNonNull(value).trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("kind must be one of " + java.util.Arrays.toString(ReportKind.values()));
        }
    }

    private static LocalDate parseOccurredOn(String value) {
        if (value == null || value.isBlank()) return null;
        LocalDate date;
        try {
            date = LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("occurredOn must be a date (YYYY-MM-DD)");
        }
        if (date.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new IllegalArgumentException("occurredOn cannot be in the future");
        }
        if (date.isBefore(LocalDate.of(2000, 1, 1))) {
            throw new IllegalArgumentException("occurredOn is too far in the past");
        }
        return date;
    }

    private static String parseDecision(String value) {
        if (value == null) throw new IllegalArgumentException("decision must be CONFIRM or REJECT");
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CONFIRM", "CONFIRMED" -> "CONFIRMED";
            case "REJECT", "REJECTED" -> "REJECTED";
            default -> throw new IllegalArgumentException("decision must be CONFIRM or REJECT");
        };
    }

    private static EmailCategory parseCategory(String value) {
        EmailCategory category;
        try {
            category = EmailCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown category");
        }
        if (category == EmailCategory.VERIFICATION_CODE) {
            throw new IllegalArgumentException("a sign-in verification code is not application evidence");
        }
        return category;
    }

    private static String cleanNote(String note) {
        if (note == null) return null;
        String cleaned = note.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").strip();
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > MAX_NOTE_CHARS) throw new IllegalArgumentException("note must be at most " + MAX_NOTE_CHARS + " characters");
        return cleaned;
    }

    private static String reportPhrase(ReportKind kind) {
        return switch (kind) {
            case SUBMITTED -> "submitting this application yourself";
            case ACKNOWLEDGED -> "that the employer acknowledged your application";
            case RECRUITER_CONTACT -> "contact from the employer";
            case ASSESSMENT -> "an assessment request";
            case INTERVIEW -> "an interview";
            case OFFER -> "an offer";
            case REJECTED -> "that the application was not progressed";
            case WITHDRAWN -> "withdrawing this application with the employer";
        };
    }

    private String actor() {
        return ownerContext.actorOr("SYSTEM");
    }

    private static UUID uuidOrNull(Object value) {
        if (value == null) return null;
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** LinkedHashMap that, unlike Map.of, tolerates null values and keeps insertion order. */
    static Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        return m;
    }
}
