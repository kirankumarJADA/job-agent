package com.personal.jobagent.track;

import com.personal.jobagent.track.ReceiptTemporalPolicy.Assessment;
import com.personal.jobagent.track.ReceiptTemporalPolicy.Status;
import com.personal.jobagent.track.SubmissionReferencePolicy.Comparison;
import com.personal.jobagent.track.TrackModels.AcknowledgementView;
import com.personal.jobagent.track.TrackModels.Attention;
import com.personal.jobagent.track.TrackModels.CandidateReport;
import com.personal.jobagent.track.TrackModels.Communications;
import com.personal.jobagent.track.TrackModels.Conflict;
import com.personal.jobagent.track.TrackModels.MessageRecord;
import com.personal.jobagent.track.TrackModels.PendingSuggestion;
import com.personal.jobagent.track.TrackModels.ReceiptRecord;
import com.personal.jobagent.track.TrackModels.StatusChange;
import com.personal.jobagent.track.TrackModels.SubmissionFacts;
import com.personal.jobagent.track.TrackModels.SubmissionView;
import com.personal.jobagent.track.TrackModels.TrackSnapshot;
import com.personal.jobagent.track.TrackModels.TrackState;
import com.personal.jobagent.track.TrackVocabulary.AcknowledgementState;
import com.personal.jobagent.track.TrackVocabulary.Basis;
import com.personal.jobagent.track.TrackVocabulary.EmailCategory;
import com.personal.jobagent.track.TrackVocabulary.EvidenceType;
import com.personal.jobagent.track.TrackVocabulary.LinkStatus;
import com.personal.jobagent.track.TrackVocabulary.ReportKind;
import com.personal.jobagent.track.TrackVocabulary.Stage;
import com.personal.jobagent.track.TrackVocabulary.SubmissionState;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;
import com.personal.jobagent.track.TrackVocabulary.Verification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Derives the candidate-facing status of one application from its evidence
 * ({@value #VERSION}). Pure: the same snapshot always gives the same state,
 * whatever order its records were written or read in.
 *
 * <p><b>Rules.</b>
 * <ol>
 *   <li>Robin's own terminal records win: an application the candidate withdrew in
 *       Robin is WITHDRAWN; a FAILED one is STOPPED. Neither erases the submission
 *       record: the submission view still reports it.</li>
 *   <li>Contradictions are not resolved by guessing. Conflicting acknowledgement
 *       references, an acknowledgement whose confirmation reference differs from
 *       the one the browser observed at submission (or belongs to a different
 *       application), an acknowledgement that predates the submission, a candidate
 *       choice contradicted by a message's own identifiers, an offer and a
 *       rejection on the same day, or a recorded status that rests only on an
 *       inference all produce NEEDS_REVIEW, with every value kept. A contested
 *       acknowledgement never counts as verified; once the candidate confirms it,
 *       it counts as confirmed by them — still not verified.</li>
 *   <li>Only <em>eligible</em> evidence moves the stage: Robin's own submission
 *       record (browser-observed: unverified; sandbox receipt: verified, and
 *       labelled sandbox), verified employer evidence, the candidate's own reports
 *       and confirmations, and — for "applied" and "acknowledged" only —
 *       unverified employer acknowledgements that identify this application.
 *       Inferences never move it; they surface as items needing attention.</li>
 *   <li>Progress is monotonic: the furthest eligible stage wins, then the stronger
 *       basis, then the later time, then a fixed tie-break. A weaker or older
 *       signal never pulls a stronger one back, so a verified acknowledgement or a
 *       later lifecycle stage advances a Robin submission past APPLIED, and an
 *       older message never overwrites a newer stage. A rejection or a reported
 *       withdrawal ends progress only when it is not older than the furthest
 *       progress signal.</li>
 *   <li>Browser observation is never employer verification; a candidate report is
 *       never verification; a sandbox receipt is never a live employer.</li>
 * </ol>
 */
public final class ApplicationReconciler {

    public static final String VERSION = "track-reconcile-v2";

    static final String ROBIN_SUBMISSION_NOTE = "Robin has not submitted this application: real submission is disabled. "
            + "Preparing, approving or queueing a package is never a submission.";

    static final String ROBIN_VERIFIED_SUBMISSION =
            "Robin submitted this application and confirmed it with platform evidence observed at the time of submission. "
                    + "[SANDBOX] The verified receipt comes from a registered synthetic sandbox, not a live employer.";

    static final String ROBIN_BROWSER_OBSERVED_SUBMISSION =
            "Robin submitted this application in the browser and observed the employer's confirmation page. "
                    + "The employer has not independently verified that it was received.";

    static final String ROBIN_UNVERIFIED_SUBMISSION =
            "Robin's worker reported submitting this application, but the employer confirmation evidence could not be "
                    + "verified. Check directly with the employer that it was received.";

    private static final ReceiptTemporalPolicy TEMPORAL = new ReceiptTemporalPolicy();
    private static final SubmissionReferencePolicy REFERENCES = new SubmissionReferencePolicy();

    /** Fixed tie-break order: Robin's own record, then verified/received evidence, then reports and messages. */
    private enum Source { SUBMISSION, RECEIPT, REPORT, MESSAGE, STATUS }

    private record Signal(Stage stage, Basis basis, Instant time, TimePrecision precision, Instant recordedAt,
                          String description, Source source, UUID emailId, String key) {
        Instant sortTime() {
            return time != null ? time : recordedAt;
        }
    }

    private static final Comparator<Instant> TIME = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<Signal> TIE_BREAK = Comparator
            .comparing((Signal x) -> x.source().ordinal(), Comparator.reverseOrder())
            .thenComparing(Signal::key, Comparator.nullsFirst(Comparator.naturalOrder()));

    public TrackState reconcile(TrackSnapshot s) {
        Objects.requireNonNull(s, "snapshot");
        Instant now = s.now() == null ? Instant.now() : s.now();
        List<Signal> signals = new ArrayList<>();
        List<Attention> attention = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> unknowns = new LinkedHashSet<>();
        PendingSuggestion pending = null;

        // 0. Robin's own submission record (Level B browser observation or Level C2 sandbox receipt).
        boolean robinSubmitted = s.plan() != null && "SUBMITTED".equals(s.plan().status());
        boolean sandboxVerified = robinSubmitted && s.plan().verifiedReceipt();
        boolean browserObserved = robinSubmitted && !sandboxVerified && s.plan().browserObserved();
        SubmissionFacts submission = s.submission();
        if (robinSubmitted) {
            Instant at = submission == null ? null : submission.recordedAt();
            signals.add(new Signal(Stage.APPLIED, sandboxVerified ? Basis.VERIFIED : Basis.UNVERIFIED, at,
                    at == null ? TimePrecision.UNKNOWN : TimePrecision.EXACT, at == null ? s.applicationCreatedAt() : at,
                    sandboxVerified ? ROBIN_VERIFIED_SUBMISSION
                            : browserObserved ? ROBIN_BROWSER_OBSERVED_SUBMISSION : ROBIN_UNVERIFIED_SUBMISSION,
                    Source.SUBMISSION, null, "submission"));
        }

        // 1. Pre-8.3 status transitions: candidate statements or email inferences.
        for (StatusChange change : sorted(s.statusChanges(), Comparator.comparing(StatusChange::recordedAt, TIME)
                .thenComparing(c -> String.valueOf(c.eventId())))) {
            Stage stage = stageForRecorded(change.to());
            if (stage == null) continue;
            String label = TrackVocabulary.recordedStatusLabel(change.to());
            signals.add(new Signal(stage, change.inferred() ? Basis.INFERRED : Basis.CANDIDATE_REPORTED,
                    change.recordedAt(), TimePrecision.EXACT, change.recordedAt(),
                    change.inferred()
                            ? "Before Phase 8.3 Robin set the status \"" + label + "\" from an email it had not verified."
                            : "You recorded the status \"" + label + "\" " + on(change.recordedAt(), TimePrecision.EXACT) + ".",
                    Source.STATUS, null, String.valueOf(change.eventId())));
        }

        // 2. TRACK candidate reports.
        for (CandidateReport report : sorted(s.reports(), Comparator.comparing(CandidateReport::recordedAt, TIME)
                .thenComparing(r -> String.valueOf(r.eventId())))) {
            Stage stage = stageForReport(report.kind());
            Instant time = report.occurredOn() == null ? null : report.occurredOn().atStartOfDay(ZoneOffset.UTC).toInstant();
            TimePrecision precision = report.occurredOn() == null ? TimePrecision.UNKNOWN : TimePrecision.DATE;
            signals.add(new Signal(stage, Basis.CANDIDATE_REPORTED, time, precision, report.recordedAt(),
                    "You reported " + reportPhrase(report.kind())
                            + (report.occurredOn() == null ? " (date not given)" : " on " + report.occurredOn()) + ".",
                    Source.REPORT, null, String.valueOf(report.eventId())));
        }

        // 3. Acknowledgement receipts, each checked against the submission's time and reference.
        List<ReceiptRecord> liveReceipts = new ArrayList<>();
        boolean verifiedReceipt = false;
        boolean submissionReferenceConflict = false;
        for (ReceiptRecord receipt : sorted(s.receipts(), Comparator.comparing(ReceiptRecord::capturedAt, TIME)
                .thenComparing(r -> String.valueOf(r.id())))) {
            if (receipt.rejectedByCandidate() || receipt.verification() == Verification.REJECTED) continue;
            if (receipt.type() == EvidenceType.CANDIDATE_STATEMENT) {
                liveReceipts.add(receipt);
                continue; // the matching candidate report already carries this statement
            }
            Assessment timing = TEMPORAL.assess(receipt.evidenceTime(), receipt.precision(),
                    ReceiptTemporalPolicy.sourceFor(receipt.channel(), receipt.evidenceTime(), receipt.precision()),
                    s.anchor(), now);
            if (timing.status() == Status.FUTURE_DATED) {
                attention.add(new Attention("RECEIPT_TIMING_IMPOSSIBLE",
                        "An acknowledgement is dated in the future, which is impossible, so it is not used. Reject it, "
                                + "or check where it came from.", null, receipt.id()));
                continue;
            }
            boolean resolved = receipt.confirmedByCandidate();
            String referenceIssue = receipt.verification() == Verification.VERIFIED
                    ? referenceConflict(receipt, submission, s.disqualifiedReferences(), s.foreignSubmissionReferences())
                    : null;
            boolean timingConflict = timing.contradictsSubmission();
            liveReceipts.add(receipt);

            if (!resolved && referenceIssue != null) {
                submissionReferenceConflict = true;
                conflicts.add(new Conflict("SUBMISSION_REFERENCE_CONFLICT", referenceIssue,
                        referenceValues(receipt, submission)));
                attention.add(new Attention("RESOLVE_REFERENCE_CONFLICT",
                        "Confirm this acknowledgement if it is about this application despite the different reference, "
                                + "or reject it. Confirming it records your decision; it does not make it verified.",
                        null, receipt.id()));
                continue;
            }
            if (!resolved && timingConflict) {
                conflicts.add(new Conflict("RECEIPT_TEMPORAL_CONFLICT",
                        "An acknowledgement for this application arrived before it was submitted ("
                                + ReceiptTemporalPolicy.anchorLabel(timing.anchor().source()) + " "
                                + on(timing.anchor().time(), timing.anchor().precision()) + ", message "
                                + on(receipt.evidenceTime(), receipt.precision())
                                + "). Review whether it belongs to this application.",
                        List.of(String.valueOf(receipt.id()))));
                attention.add(new Attention("RESOLVE_RECEIPT_TIMING",
                        "Confirm this acknowledgement if it is about this application despite its timing, or reject it.",
                        null, receipt.id()));
                continue;
            }
            boolean contested = referenceIssue != null || timingConflict;
            Basis basis = switch (receipt.verification()) {
                case VERIFIED -> contested || timing.blocksVerification()
                        ? (resolved ? Basis.CANDIDATE_CONFIRMED : Basis.UNVERIFIED)
                        : Basis.VERIFIED;
                case UNVERIFIED -> resolved ? Basis.CANDIDATE_CONFIRMED : Basis.UNVERIFIED;
                case UNKNOWN -> resolved ? Basis.CANDIDATE_CONFIRMED : null;
                default -> null;
            };
            if (basis == null) {
                attention.add(new Attention("RECEIPT_NEEDS_CONFIRMATION",
                        "An acknowledgement was added that does not identify this application by itself. Confirm it if it "
                                + "is about this application, or reject it.", null, receipt.id()));
                continue;
            }
            if (receipt.verification() == Verification.VERIFIED && timing.status() == Status.EVIDENCE_TIME_UNKNOWN) {
                unknowns.add("An acknowledgement has no time recorded by the mail provider, so it is not treated as verified.");
            }
            if (basis == Basis.VERIFIED) verifiedReceipt = true;
            signals.add(new Signal(Stage.ACKNOWLEDGED, basis, receipt.evidenceTime(), receipt.precision(),
                    receipt.capturedAt(), switch (basis) {
                        case VERIFIED -> "An employer acknowledgement for this application was verified.";
                        case CANDIDATE_CONFIRMED -> contested
                                ? "You confirmed an employer acknowledgement for this application despite a conflict; it is not verified."
                                : "You confirmed an employer acknowledgement for this application.";
                        default -> receipt.verification() == Verification.VERIFIED
                                ? "An employer acknowledgement identifies this application, but its arrival time is unknown, "
                                + "so it is not treated as verified."
                                : "An employer acknowledgement identifies this application, but it was added by you, "
                                + "so its sender could not be authenticated.";
                    }, Source.RECEIPT, null, String.valueOf(receipt.id())));
        }

        // 4. Employer messages.
        int linkedMessages = 0;
        int awaitingReview = 0;
        for (MessageRecord message : sorted(s.messages(), Comparator.comparing(MessageRecord::recordedAt, TIME)
                .thenComparing(m -> String.valueOf(m.emailId())))) {
            if (message.status() == LinkStatus.CONFLICT) {
                awaitingReview++;
                conflicts.add(new Conflict("ASSOCIATION_CONFLICT",
                        "You added an employer message to an application, but the message itself identifies a different "
                                + "one. Review which application it belongs to.", List.of(String.valueOf(message.emailId()))));
                continue;
            }
            if (message.status() == LinkStatus.PENDING_REVIEW || message.status() == LinkStatus.UNMATCHED) {
                if (message.candidateForThisApplication()) {
                    awaitingReview++;
                    attention.add(new Attention("MESSAGE_ASSOCIATION_REVIEW",
                            "An employer message may be about this application. Confirm or reject the match.",
                            message.emailId(), null));
                }
                continue;
            }
            if (!message.linkedToThisApplication()
                    || (message.status() != LinkStatus.LINKED && message.status() != LinkStatus.CONFIRMED)) {
                continue;
            }
            linkedMessages++;
            EmailCategory category = message.confirmedCategory() != null ? message.confirmedCategory() : message.inferredCategory();
            Stage stage = stageForCategory(category);
            if (stage == null) continue;
            Instant time = message.receivedTimeKnown() ? message.receivedAt() : null;
            TimePrecision precision = message.receivedTimeKnown() ? TimePrecision.EXACT : TimePrecision.UNKNOWN;
            if (message.confirmedCategory() != null) {
                signals.add(new Signal(stage, Basis.CANDIDATE_CONFIRMED, time, precision, message.recordedAt(),
                        "You confirmed an employer message " + on(time, precision) + " as: "
                                + TrackVocabulary.categoryLabel(category).toLowerCase(Locale.ROOT) + ".",
                        Source.MESSAGE, message.emailId(), String.valueOf(message.emailId())));
            } else if (category != EmailCategory.APPLICATION_ACKNOWLEDGEMENT) {
                // Acknowledgements are assessed by the receipt policy above; every
                // other meaning is an inference until the candidate confirms it.
                awaitingReview++;
                signals.add(new Signal(stage, Basis.INFERRED, time, precision, message.recordedAt(),
                        "An employer message suggests: " + TrackVocabulary.categoryLabel(category).toLowerCase(Locale.ROOT)
                                + " (inferred).", Source.MESSAGE, message.emailId(), String.valueOf(message.emailId())));
                attention.add(new Attention("CONFIRM_MESSAGE_MEANING",
                        "Robin thinks an employer message is: " + TrackVocabulary.categoryLabel(category).toLowerCase(Locale.ROOT)
                                + " (inferred, not confirmed). Confirm it to update this application's status.",
                        message.emailId(), null));
            }
        }

        // 5. Stage from eligible evidence, with a total order so ties never depend on input order.
        List<Signal> eligible = signals.stream().filter(ApplicationReconciler::eligible).toList();
        Signal best = eligible.stream().filter(x -> !terminal(x.stage()))
                .max(Comparator.comparingInt((Signal x) -> progress(x.stage()))
                        .thenComparingInt(x -> x.basis().strength)
                        .thenComparing(Signal::sortTime, TIME)
                        .thenComparing(TIE_BREAK))
                .orElse(null);
        Signal end = eligible.stream().filter(x -> terminal(x.stage()))
                .max(Comparator.comparing(Signal::sortTime, TIME).thenComparingInt((Signal x) -> x.basis().strength)
                        .thenComparing(TIE_BREAK))
                .orElse(null);
        // A rejection or reported withdrawal ends progress when it is not older than the
        // furthest progress. When the two fall on the same day and one of them only has a
        // date, their order is unknown: the end-of-process signal is taken (a stated,
        // conservative rule) — except against an offer, which is a contradiction to review.
        boolean sameDayAsProgress = end != null && best != null && sameDay(end, best);
        boolean orderUnknown = sameDayAsProgress
                && !(end.precision() == TimePrecision.EXACT && best.precision() == TimePrecision.EXACT);
        if (sameDayAsProgress && best.stage() == Stage.OFFER && end.stage() == Stage.REJECTED) {
            conflicts.add(new Conflict("OUTCOME_CONFLICT",
                    "An offer and a rejection for this application are recorded for the same day. Review which is current.",
                    List.of()));
        }
        Signal deciding = end != null && (best == null || orderUnknown || !isBefore(end.sortTime(), best.sortTime()))
                ? end : best;
        Stage evidenceStage = deciding != null ? deciding.stage()
                : (s.preparation() != null && s.preparation().complete()) || s.plan() != null
                ? Stage.READY_TO_APPLY : Stage.PREPARING;

        // An inference that would move the stage further is offered, not applied.
        Signal suggestion = signals.stream()
                .filter(x -> x.basis() == Basis.INFERRED && x.source() == Source.MESSAGE)
                .filter(x -> progress(x.stage()) > progress(evidenceStage) || (terminal(x.stage()) && !terminal(evidenceStage)))
                .max(Comparator.comparing(Signal::sortTime, TIME).thenComparing(TIE_BREAK)).orElse(null);
        if (suggestion != null) {
            pending = new PendingSuggestion(suggestion.stage(),
                    TrackVocabulary.stageLabel(suggestion.stage()) + " — suggested by an employer message, awaiting your confirmation",
                    suggestion.emailId());
        }

        // 6. Acknowledgement-reference conflicts (between employer messages).
        Set<String> references = new LinkedHashSet<>();
        for (ReceiptRecord receipt : liveReceipts) {
            if (receipt.type() == EvidenceType.EMPLOYER_ACKNOWLEDGEMENT_EMAIL && receipt.externalReference() != null) {
                SubmissionReferencePolicy.normalize(receipt.externalReference())
                        .filter(r -> !s.disqualifiedReferences().contains(r))
                        .ifPresent(references::add);
            }
        }
        boolean referenceConflict = references.size() > 1;
        if (referenceConflict) {
            conflicts.add(new Conflict("RECEIPT_REFERENCE_CONFLICT",
                    "Acknowledgements for this application quote different application references ("
                            + String.join(", ", references) + "). Review which belong to this application.",
                    new ArrayList<>(references)));
        }

        // 7. Status.
        String recorded = s.recordedStatus();
        Stage status;
        Basis basis;
        String reason;
        Instant since = deciding == null ? null : deciding.time();
        TimePrecision sincePrecision = deciding == null ? null : deciding.precision();
        boolean unknownRecorded = false;
        StatusChange recordedSetter = latestSetter(s.statusChanges(), recorded);
        if ("WITHDRAWN".equals(recorded)) {
            status = Stage.WITHDRAWN;
            basis = Basis.INTERNAL;
            since = recordedSetter == null ? null : recordedSetter.recordedAt();
            sincePrecision = since == null ? null : TimePrecision.EXACT;
            reason = "You withdrew this application in Robin" + (since == null ? "" : " " + on(since, TimePrecision.EXACT))
                    + ". If you had applied, the employer is not told by Robin.";
            if (deciding != null && deciding.time() != null && since != null && deciding.time().isAfter(since)
                    && deciding.stage() != Stage.WITHDRAWN) {
                attention.add(new Attention("ACTIVITY_AFTER_WITHDRAWAL",
                        "Employer activity was recorded after you withdrew this application.", null, null));
            }
        } else if ("FAILED".equals(recorded)) {
            status = Stage.STOPPED;
            basis = Basis.INTERNAL;
            reason = "This application was marked as failed in Robin, so Robin stopped working on it.";
        } else {
            if (!robinSubmitted) {
                Stage recordedStage = stageForRecorded(recorded);
                boolean recordedAhead = recordedStage != null
                        && (progress(recordedStage) > progress(evidenceStage)
                        || (terminal(recordedStage) && recordedStage != evidenceStage));
                if (recordedAhead) {
                    if (recordedSetter == null) {
                        unknownRecorded = true;
                    } else if (recordedSetter.inferred()) {
                        conflicts.add(new Conflict("RECORDED_STATUS_INFERRED",
                                "Robin's recorded status \"" + TrackVocabulary.recordedStatusLabel(recorded) + "\" was set from an "
                                        + "email before Phase 8.3 and was never confirmed. Review the message to confirm or reject it.",
                                List.of(String.valueOf(recordedSetter.eventId()))));
                    }
                }
            }
            if (!conflicts.isEmpty()) {
                status = Stage.NEEDS_REVIEW;
                basis = null;
                reason = conflicts.get(0).message();
            } else if (unknownRecorded) {
                status = Stage.UNKNOWN;
                basis = null;
                reason = "Robin's records show the status \"" + TrackVocabulary.recordedStatusLabel(recorded)
                        + "\", but there is no record of how it was set, so it cannot be relied on.";
            } else if (deciding != null) {
                status = deciding.stage();
                basis = deciding.basis();
                reason = deciding.description();
            } else {
                status = evidenceStage;
                basis = Basis.INTERNAL;
                reason = status == Stage.PREPARING
                        ? "Robin is still preparing this application's documents. Nothing has been sent to the employer yet."
                        : "The application package is prepared, but nothing has been sent to the employer yet, "
                        + "and there is no evidence that it was submitted another way."
                        + (s.plan() != null && "READY_TO_SUBMIT".equals(s.plan().status())
                        ? " You approved the package; an approval is not a submission." : "")
                        + (s.plan() != null && "RECONCILIATION_REQUIRED".equals(s.plan().status())
                        ? " A real submission was attempted but its outcome could not be confirmed — Robin will never "
                        + "retry it automatically; see the attention item below for what is needed." : "");
            }
            if (pending != null && status != Stage.NEEDS_REVIEW) {
                reason = reason + " An employer message suggests a newer stage; confirm it to update the status.";
            }
        }

        // 8. Submission and acknowledgement dimensions.
        Signal submissionSignal = eligible.stream()
                .filter(x -> x.stage() != Stage.WITHDRAWN && x.stage() != Stage.APPLYING)
                .max(Comparator.comparingInt((Signal x) -> x.basis().strength).thenComparing(Signal::sortTime, TIME)
                        .thenComparing(TIE_BREAK))
                .orElse(null);
        SubmissionView submissionView;
        if (referenceConflict || submissionReferenceConflict) {
            submissionView = new SubmissionView(SubmissionState.CONFLICTING, "Conflicting evidence",
                    submissionReferenceConflict
                            ? "An employer acknowledgement quotes a different confirmation reference from the one observed "
                            + "when this application was submitted."
                            : "Acknowledgements disagree about which application was received.",
                    "CONFLICTING", false);
        } else if (verifiedReceipt) {
            submissionView = new SubmissionView(SubmissionState.CONFIRMED, "Confirmed by verified employer evidence",
                    "A verified employer acknowledgement identifies this application.", "EMPLOYER_EMAIL_VERIFIED", false);
        } else if (sandboxVerified) {
            submissionView = new SubmissionView(SubmissionState.CONFIRMED, "Confirmed by sandbox receipt",
                    ROBIN_VERIFIED_SUBMISSION, "SANDBOX_VERIFIED", true);
        } else if (browserObserved) {
            submissionView = new SubmissionView(SubmissionState.UNVERIFIED, "Submitted in browser — not verified by the employer",
                    ROBIN_BROWSER_OBSERVED_SUBMISSION, "BROWSER_OBSERVED", false);
        } else if (robinSubmitted) {
            submissionView = new SubmissionView(SubmissionState.UNVERIFIED, "Submission not verified",
                    ROBIN_UNVERIFIED_SUBMISSION, "ATTEMPT_RECORDED", false);
        } else if (submissionSignal != null) {
            submissionView = new SubmissionView(SubmissionState.UNVERIFIED, "Not verified", switch (submissionSignal.basis()) {
                case CANDIDATE_REPORTED -> "You reported this; no employer evidence has verified it.";
                case CANDIDATE_CONFIRMED -> "Based on employer evidence you confirmed; it could not be verified.";
                case UNVERIFIED -> "An employer acknowledgement identifies this application but could not be verified.";
                default -> "No verified employer evidence.";
            }, submissionSignal.basis() == Basis.UNVERIFIED ? "EMPLOYER_EMAIL_UNVERIFIED" : "CANDIDATE_REPORTED", false);
        } else if (s.plan() != null && "RECONCILIATION_REQUIRED".equals(s.plan().status())) {
            submissionView = new SubmissionView(SubmissionState.UNVERIFIED, "Submission unconfirmed",
                    "A real submission was attempted by Robin, but the employer's acceptance could not be confirmed.",
                    "ATTEMPT_RECORDED", false);
        } else {
            submissionView = new SubmissionView(SubmissionState.UNKNOWN, "No evidence of submission",
                    "There is no evidence that this application was submitted. " + ROBIN_SUBMISSION_NOTE, "NOT_ATTEMPTED", false);
        }
        AcknowledgementView acknowledgement;
        if (verifiedReceipt) {
            acknowledgement = new AcknowledgementView(AcknowledgementState.VERIFIED, "Verified acknowledgement");
        } else if (eligible.stream().anyMatch(x -> x.stage() == Stage.ACKNOWLEDGED)) {
            acknowledgement = new AcknowledgementView(AcknowledgementState.UNVERIFIED, "Acknowledgement not verified");
        } else {
            acknowledgement = new AcknowledgementView(AcknowledgementState.NONE, "No acknowledgement received");
        }

        // 9. Attention from Robin's own workflow, and what cannot be established.
        if (s.plan() != null && "AWAITING_APPROVAL".equals(s.plan().status())) {
            attention.add(new Attention("PACKAGE_REVIEW",
                    "The filled application form is waiting for your review. Approving it does not submit anything.", null, null));
        }
        if (s.plan() != null && "RECONCILIATION_REQUIRED".equals(s.plan().status())) {
            String note = s.plan().reconciliationNote();
            String detail = (note == null || note.isBlank())
                    ? "A real submission attempt could not be confirmed. Robin will never retry it automatically; verify with the employer whether your application was received."
                    : "A real submission attempt could not be confirmed (" + note + "). Robin will never retry it automatically; verify with the employer whether your application was received.";
            attention.add(new Attention("RECONCILIATION_REQUIRED", detail, null, null));
        }
        if (robinSubmitted && !sandboxVerified && !browserObserved && !verifiedReceipt) {
            attention.add(new Attention("RECEIPT_UNVERIFIED",
                    "Robin's worker reported this submission, but its employer confirmation could not be verified. "
                            + "Check directly with the employer that your application was received.", null, null));
        }
        if (browserObserved && !verifiedReceipt) {
            unknowns.add("Robin saw the employer's confirmation page, but the employer has not verified receipt yet. "
                    + "An authenticated acknowledgement email, if one arrives, will verify it.");
        }
        if (robinSubmitted && submission != null) {
            for (String kind : submission.documentsWithoutUploadProof()) {
                unknowns.add("Robin could not confirm that your " + ("coverLetter".equals(kind) ? "cover letter" : "CV")
                        + " was included when the form was submitted.");
            }
        }
        if (s.preparation() != null && s.preparation().failed()) {
            attention.add(new Attention("PREPARATION_FAILED",
                    "A preparation step failed. Re-run preparation to retry it.", null, null));
        }
        if (!s.mailboxConnected()) {
            unknowns.add("Robin cannot read your mailbox (no mailbox is connected), so employer replies appear here only "
                    + "when you add them.");
        }
        if (submissionView.state() == SubmissionState.UNVERIFIED || submissionView.state() == SubmissionState.CONFLICTING) {
            unknowns.add("Whether the employer received this application is not verified.");
        }
        if (eligible.stream().anyMatch(x -> x.source() != Source.SUBMISSION && x.precision() == TimePrecision.UNKNOWN)) {
            unknowns.add("Some events have no known date; Robin orders them by when they were recorded.");
        }

        String robinSubmissionNote = sandboxVerified
                ? ROBIN_VERIFIED_SUBMISSION
                : browserObserved ? ROBIN_BROWSER_OBSERVED_SUBMISSION
                : robinSubmitted
                ? ROBIN_UNVERIFIED_SUBMISSION
                : (s.plan() != null && "RECONCILIATION_REQUIRED".equals(s.plan().status())
                ? "A real submission was attempted by Robin, but the employer's acceptance could not be confirmed. Robin will never retry it automatically."
                : ROBIN_SUBMISSION_NOTE);

        boolean sandboxLabel = sandboxVerified && deciding != null && deciding.source() == Source.SUBMISSION
                && status == Stage.APPLIED;
        String label = labelFor(status, basis) + (sandboxLabel ? " (sandbox)" : "");
        return new TrackState(status, label, basis, TrackVocabulary.basisLabel(basis), reason, since, sincePrecision,
                evidenceStage, recorded, TrackVocabulary.recordedStatusLabel(recorded), submissionView, acknowledgement,
                new Communications(linkedMessages, awaitingReview), pending, List.copyOf(attention),
                List.copyOf(unknowns), List.copyOf(conflicts), robinSubmitted, robinSubmissionNote);
    }

    /**
     * Why a verified acknowledgement's confirmation reference contradicts Robin's submission record, or
     * null. Only trusted references are compared: a verified receipt's, and the browser-observed one from
     * intact worker evidence. Requisition, posting and Robin-internal identifiers are never compared.
     */
    private static String referenceConflict(ReceiptRecord receipt, SubmissionFacts submission,
                                            Set<String> disqualified, Set<String> foreign) {
        String quoted = receipt.externalReference();
        if (quoted == null) return null;
        if (submission != null && submission.referenceTrusted()) {
            Comparison c = REFERENCES.compare(quoted, submission.employerConfirmationId(), disqualified);
            if (c.conflicting()) {
                return "An employer acknowledgement quotes the confirmation reference " + c.receiptNormalized()
                        + ", but the browser observed " + c.submissionNormalized() + " when Robin submitted this "
                        + "application. Both are kept; review which is right.";
            }
            if (c.consistency() == SubmissionReferencePolicy.Consistency.ALIGNED) return null;
        }
        String normalized = SubmissionReferencePolicy.normalize(quoted).orElse(null);
        if (normalized != null && !disqualified.contains(normalized) && foreign.contains(normalized)) {
            return "An employer acknowledgement linked to this application quotes the confirmation reference " + normalized
                    + ", which Robin recorded for a different one of your applications. Review which application it belongs to.";
        }
        return null;
    }

    private static List<String> referenceValues(ReceiptRecord receipt, SubmissionFacts submission) {
        List<String> values = new ArrayList<>();
        values.add("acknowledgement:" + receipt.externalReference());
        if (submission != null && submission.employerConfirmationId() != null) {
            values.add("browser-observed:" + submission.employerConfirmationId());
        }
        values.add("receipt:" + receipt.id());
        return values;
    }

    // ── rules ────────────────────────────────────────────────────────────

    static boolean eligible(Signal s) {
        return switch (s.basis()) {
            case VERIFIED, CANDIDATE_CONFIRMED, CANDIDATE_REPORTED -> true;
            case UNVERIFIED -> s.stage() == Stage.APPLIED || s.stage() == Stage.ACKNOWLEDGED;
            case INTERNAL, INFERRED -> false;
        };
    }

    static boolean terminal(Stage stage) {
        return stage == Stage.REJECTED || stage == Stage.WITHDRAWN;
    }

    static int progress(Stage stage) {
        return switch (stage) {
            case PREPARING -> 0;
            case READY_TO_APPLY -> 0;
            case APPLYING -> 1;
            case APPLIED -> 2;
            case ACKNOWLEDGED -> 3;
            case IN_CONTACT -> 4;
            case ASSESSMENT -> 5;
            case INTERVIEW -> 6;
            case OFFER -> 7;
            default -> 0;
        };
    }

    static Stage stageForRecorded(String status) {
        if (status == null) return null;
        return switch (status) {
            case "APPLICATION_STARTED", "OTP_PENDING" -> Stage.APPLYING;
            case "APPLICATION_SUBMITTED" -> Stage.APPLIED;
            case "CONFIRMATION_RECEIVED" -> Stage.ACKNOWLEDGED;
            case "RECRUITER_CONTACT" -> Stage.IN_CONTACT;
            case "ASSESSMENT" -> Stage.ASSESSMENT;
            case "INTERVIEW" -> Stage.INTERVIEW;
            case "OFFER" -> Stage.OFFER;
            case "REJECTED" -> Stage.REJECTED;
            default -> null; // READY_TO_APPLY, WITHDRAWN and FAILED are handled as Robin's own records
        };
    }

    static Stage stageForReport(ReportKind kind) {
        return switch (kind) {
            case SUBMITTED -> Stage.APPLIED;
            case ACKNOWLEDGED -> Stage.ACKNOWLEDGED;
            case RECRUITER_CONTACT -> Stage.IN_CONTACT;
            case ASSESSMENT -> Stage.ASSESSMENT;
            case INTERVIEW -> Stage.INTERVIEW;
            case OFFER -> Stage.OFFER;
            case REJECTED -> Stage.REJECTED;
            case WITHDRAWN -> Stage.WITHDRAWN;
        };
    }

    static Stage stageForCategory(EmailCategory category) {
        if (category == null) return null;
        return switch (category) {
            case APPLICATION_ACKNOWLEDGEMENT -> Stage.ACKNOWLEDGED;
            case RECRUITER_FOLLOW_UP, INFORMATION_REQUEST -> Stage.IN_CONTACT;
            case ASSESSMENT_INVITATION -> Stage.ASSESSMENT;
            case INTERVIEW_INVITATION -> Stage.INTERVIEW;
            case OFFER -> Stage.OFFER;
            case REJECTION -> Stage.REJECTED;
            case VERIFICATION_CODE, UNRELATED_OR_AMBIGUOUS -> null;
        };
    }

    private static String reportPhrase(ReportKind kind) {
        return switch (kind) {
            case SUBMITTED -> "submitting this application";
            case ACKNOWLEDGED -> "that the employer acknowledged your application";
            case RECRUITER_CONTACT -> "contact from the employer";
            case ASSESSMENT -> "an assessment request";
            case INTERVIEW -> "an interview";
            case OFFER -> "an offer";
            case REJECTED -> "that the application was not progressed";
            case WITHDRAWN -> "withdrawing this application with the employer";
        };
    }

    private static String labelFor(Stage status, Basis basis) {
        String stage = TrackVocabulary.stageLabel(status);
        if (basis == null || basis == Basis.INTERNAL) return stage;
        return stage + " — " + switch (basis) {
            case VERIFIED -> "verified";
            case UNVERIFIED -> "not verified";
            case CANDIDATE_CONFIRMED -> "confirmed by you";
            case CANDIDATE_REPORTED -> "reported by you";
            default -> TrackVocabulary.basisLabel(basis);
        };
    }

    private static StatusChange latestSetter(List<StatusChange> changes, String status) {
        if (status == null) return null;
        return nullSafe(changes).stream().filter(c -> status.equals(c.to()))
                .max(Comparator.comparing(StatusChange::recordedAt, TIME).thenComparing(c -> String.valueOf(c.eventId())))
                .orElse(null);
    }

    private static boolean isBefore(Instant a, Instant b) {
        if (a == null) return b != null;
        return b != null && a.isBefore(b);
    }

    private static boolean sameDay(Signal a, Signal b) {
        if (a.sortTime() == null || b.sortTime() == null) return false;
        return LocalDate.ofInstant(a.sortTime(), ZoneOffset.UTC).equals(LocalDate.ofInstant(b.sortTime(), ZoneOffset.UTC));
    }

    private static String on(Instant time, TimePrecision precision) {
        if (time == null || precision == TimePrecision.UNKNOWN) return "(date unknown)";
        return "on " + LocalDate.ofInstant(time, ZoneOffset.UTC);
    }

    private static <T> List<T> sorted(List<T> list, Comparator<T> order) {
        List<T> copy = new ArrayList<>(nullSafe(list));
        copy.sort(order);
        return copy;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
