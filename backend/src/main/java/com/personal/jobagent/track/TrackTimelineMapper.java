package com.personal.jobagent.track;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.TrackRepository.TimelineRow;
import com.personal.jobagent.track.TrackVocabulary.EventClassification;
import com.personal.jobagent.track.TrackVocabulary.TimePrecision;
import com.personal.jobagent.track.TrackVocabulary.Verification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns persisted timeline rows (and projected audit records) into the
 * candidate-facing timeline item. Neutral by construction: internal type
 * names that identify an application platform (e.g. a plan type) are mapped
 * to neutral types, free-text payload fields are never copied through, and the
 * actor is reduced to ROBIN / YOU / EMPLOYER instead of an account address.
 */
public final class TrackTimelineMapper {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper json;

    public TrackTimelineMapper(ObjectMapper json) {
        this.json = json;
    }

    private record Neutral(String type, String summary, String actor) {
    }

    public Map<String, Object> map(TimelineRow row) {
        Map<String, Object> payload = parse(row.payload());
        EventClassification classification = parseClassification(row.classification());
        Verification verification = parseVerification(row.verification());
        Neutral neutral = "AUDIT".equals(row.origin()) ? fromAudit(row.kind(), payload) : fromEvent(row, payload, classification);

        TimePrecision precision = parsePrecision(row.precision());
        Instant occurredAt;
        String occurredOn = null;
        if ("AUDIT".equals(row.origin()) || classification == EventClassification.INTERNAL) {
            occurredAt = row.eventTime() != null ? row.eventTime() : row.recordedAt();
            precision = TimePrecision.EXACT;
        } else if (precision == TimePrecision.DATE && row.eventTime() != null) {
            occurredAt = null;
            occurredOn = LocalDate.ofInstant(row.eventTime(), ZoneOffset.UTC).toString();
        } else if (precision == TimePrecision.EXACT) {
            occurredAt = row.eventTime() != null ? row.eventTime() : row.recordedAt();
        } else {
            occurredAt = null;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ("AUDIT".equals(row.origin()) ? "audit:" : "") + row.id());
        item.put("type", neutral.type());
        item.put("summary", neutral.summary());
        item.put("classification", classification.name());
        item.put("verification", verification.name());
        item.put("label", TrackVocabulary.eventLabel(classification, verification));
        item.put("actor", neutral.actor());
        item.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
        item.put("occurredOn", occurredOn);
        item.put("occurredAtPrecision", precision.name());
        item.put("recordedAt", row.recordedAt() == null ? null : row.recordedAt().toString());
        item.put("supersedes", row.supersedesEventId() == null ? null : row.supersedesEventId().toString());
        item.put("receiptId", row.receiptId() == null ? null : row.receiptId().toString());
        item.put("emailId", row.emailId() == null ? null : row.emailId().toString());
        return item;
    }

    private Neutral fromEvent(TimelineRow row, Map<String, Object> payload, EventClassification classification) {
        String kind = row.kind() == null ? "" : row.kind();
        String actor = switch (classification) {
            case CANDIDATE_REPORTED -> "YOU";
            case EXTERNAL -> "EMPLOYER";
            case INTERNAL, INFERRED -> "ROBIN";
        };
        return switch (kind) {
            case "APPLICATION_CREATED" -> new Neutral("APPLICATION_CREATED", "Application record created", "ROBIN");
            case "PREPARATION" -> preparation(payload);
            case "GREENHOUSE_PLAN_CREATED", "ASHBY_PLAN_CREATED", "INSPECTION_PLAN_CREATED" -> new Neutral("FORM_PLAN_PREPARED",
                    "Form-filling plan prepared — nothing is sent to the employer", "ROBIN");
            case "EXECUTION_PACKAGE_DEFERRED" -> new Neutral("PACKAGE_DEFERRED",
                    "Package deferred until your documents are reviewed", "ROBIN");
            case "AUTOMATION_PLAN_FAILED" -> new Neutral("FORM_PLAN_FAILED",
                    "Robin could not prepare the form-filling plan", "ROBIN");
            case "STATUS_CHANGED" -> {
                String to = TrackVocabulary.recordedStatusLabel(str(payload.get("to")));
                yield classification == EventClassification.INFERRED
                        ? new Neutral("STATUS_RECORDED", "Robin set the status \"" + to
                        + "\" from an email before Phase 8.3 — inferred, never confirmed", "ROBIN")
                        : new Neutral("STATUS_RECORDED", "You recorded the status \"" + to + "\"", "YOU");
            }
            case TrackEvidenceRecorder.MESSAGE_EVENT ->
                    // The message is the employer's; what it means is Robin's inference (see classification).
                    new Neutral(kind, row.summary() == null ? "Employer message received" : row.summary(), "EMPLOYER");
            case TrackEvidenceRecorder.RECEIPT_EVENT, "CANDIDATE_REPORT", "MESSAGE_REVIEWED", "RECEIPT_REVIEWED" ->
                    new Neutral(kind, row.summary() == null ? "Tracking evidence recorded" : row.summary(), actor);
            default -> new Neutral("INTERNAL_EVENT", "Robin recorded an internal step", "ROBIN");
        };
    }

    private static Neutral preparation(Map<String, Object> payload) {
        String step = str(payload.get("step"));
        boolean ok = "OK".equals(str(payload.get("status")));
        String what = switch (step == null ? "" : step) {
            case "CV" -> "Tailored CV";
            case "COVER_LETTER" -> "Cover letter draft";
            case "ANSWERS" -> "Draft answers";
            default -> "A preparation step";
        };
        return new Neutral("PREPARATION_STEP", what + (ok ? " prepared" : " — preparation failed"), "ROBIN");
    }

    private static Neutral fromAudit(String action, Map<String, Object> after) {
        return switch (action == null ? "" : action) {
            case "RESUME_ATS_TAILORED" -> new Neutral("CV_GENERATED", "Robin generated a tailored CV", "ROBIN");
            case "TAILORED_CV_APPROVED" -> new Neutral("CV_APPROVED", "You approved the tailored CV", "YOU");
            case "TAILORED_CV_APPROVAL_WITHDRAWN" -> new Neutral("CV_APPROVAL_WITHDRAWN",
                    "You withdrew approval of the tailored CV", "YOU");
            case "COVER_LETTER_GENERATED" -> new Neutral("COVER_LETTER_GENERATED", "Robin drafted a cover letter", "ROBIN");
            case "COVER_LETTER_CORRECTED" -> new Neutral("COVER_LETTER_CORRECTED",
                    "You corrected the cover letter (saved as a new version)", "YOU");
            case "COVER_LETTER_APPROVAL_UPDATED" -> Boolean.TRUE.equals(after.get("approved"))
                    ? new Neutral("COVER_LETTER_APPROVED", "You approved the cover letter", "YOU")
                    : new Neutral("COVER_LETTER_APPROVAL_WITHDRAWN", "You withdrew approval of the cover letter", "YOU");
            case "APPLY_PACKAGE_CREATED" -> new Neutral("PACKAGE_CREATED",
                    "Application package built — nothing was sent to the employer", "YOU");
            case "APPLY_PACKAGE_REFUSED" -> new Neutral("PACKAGE_REFUSED",
                    "Package not built: blockers remained", "ROBIN");
            case "GREENHOUSE_FORM_REVIEWED" -> new Neutral("FORM_REVIEWED", "You reviewed the filled application form", "YOU");
            case "GREENHOUSE_SUBMIT_APPROVED" -> new Neutral("PACKAGE_APPROVED",
                    "You approved the package for submission — submission is disabled, nothing was sent", "YOU");
            case "GREENHOUSE_SUBMIT_APPROVAL_REFUSED", "ASHBY_SUBMIT_APPROVAL_REFUSED" -> new Neutral("PACKAGE_APPROVAL_REFUSED",
                    "Approval refused: the package was not ready", "ROBIN");
            case "ASHBY_FORM_REVIEWED" -> new Neutral("FORM_REVIEWED", "You reviewed the filled application form", "YOU");
            case "ASHBY_SUBMIT_APPROVED" -> new Neutral("PACKAGE_APPROVED",
                    "You approved the package for submission — a separate authorization step is still required before anything is sent", "YOU");
            case "PACKAGE_APPROVAL_INVALIDATED" -> new Neutral("PACKAGE_APPROVAL_WITHDRAWN",
                    "Package approval withdrawn because a document or answer changed", "ROBIN");
            case "AUTOMATION_PLAN_HUMAN_REQUIRED" -> new Neutral("FORM_FILL_PAUSED",
                    "Form filling stopped for your review — nothing was submitted", "ROBIN");
            case "AUTOMATION_PLAN_FAILED" -> new Neutral("FORM_FILL_FAILED", "Form filling failed — nothing was submitted", "ROBIN");
            case "AUTOMATION_PLAN_BLOCKED_ANTI_BOT" -> new Neutral("FORM_FILL_BLOCKED",
                    "Form filling stopped: the site blocked automation", "ROBIN");
            case "AUTOMATION_PLAN_COMPLETED" -> new Neutral("FORM_INSPECTED",
                    "Form inspection completed — nothing was submitted", "ROBIN");
            case "REVIEW_ITEM_APPROVED" -> new Neutral("MATCH_APPROVED", "You approved this job for an application", "YOU");
            default -> new Neutral("INTERNAL_EVENT", "Robin recorded an internal step", "ROBIN");
        };
    }

    private Map<String, Object> parse(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            Object parsed = json.readValue(value, Object.class);
            if (parsed instanceof Map<?, ?>) return json.readValue(value, MAP);
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static EventClassification parseClassification(String value) {
        try {
            return value == null ? EventClassification.INTERNAL : EventClassification.valueOf(value);
        } catch (IllegalArgumentException e) {
            return EventClassification.INTERNAL;
        }
    }

    private static Verification parseVerification(String value) {
        try {
            return value == null ? Verification.NOT_APPLICABLE : Verification.valueOf(value);
        } catch (IllegalArgumentException e) {
            return Verification.NOT_APPLICABLE;
        }
    }

    private static TimePrecision parsePrecision(String value) {
        try {
            return value == null ? TimePrecision.EXACT : TimePrecision.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TimePrecision.UNKNOWN;
        }
    }
}
