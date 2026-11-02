package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.TrackRepository.TimelineRow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The candidate-facing timeline item: neutral types (no platform names), clear
 * evidence labels, ROBIN/YOU/EMPLOYER instead of account addresses, and times
 * that are never invented.
 */
class TrackTimelineMapperTest {

    private final TrackTimelineMapper mapper = new TrackTimelineMapper(new ObjectMapper());
    private static final Instant RECORDED = Instant.parse("2026-10-05T10:15:00Z");

    private static TimelineRow event(String kind, String payload, String classification, String verification,
                                     Instant eventTime, String precision, String summary, String actor) {
        return new TimelineRow(UUID.randomUUID(), "EVENT", kind, payload, actor, RECORDED, eventTime, precision,
                classification, verification, summary, null, null, null, eventTime == null ? RECORDED : eventTime, null);
    }

    private static TimelineRow audit(String action, String afterState, String actor) {
        return new TimelineRow(UUID.randomUUID(), "AUDIT", action, afterState, actor, RECORDED, null, "EXACT",
                "INTERNAL", "NOT_APPLICABLE", null, null, null, null, RECORDED, "AUTOMATION_PLAN");
    }

    @Test
    void platformNamedInternalTypesAreNeutralised() {
        for (TimelineRow row : List.of(
                event("GREENHOUSE_PLAN_CREATED", "{\"plan_id\":\"x\"}", "INTERNAL", "NOT_APPLICABLE", null, "EXACT", null, "SYSTEM"),
                audit("GREENHOUSE_SUBMIT_APPROVED", "{\"approvedForSubmission\":true,\"submissionEnabled\":false}", "alice@example.com"),
                audit("GREENHOUSE_FORM_REVIEWED", "{}", "alice@example.com"),
                event("SOME_FUTURE_GREENHOUSE_THING", "{}", "INTERNAL", "NOT_APPLICABLE", null, "EXACT", null, "SYSTEM"))) {
            Map<String, Object> item = mapper.map(row);
            assertThat(item.toString().toLowerCase()).doesNotContain("greenhouse").doesNotContain("tsenta")
                    .doesNotContain("alice@example.com");
        }
    }

    /** Phase 14: the same neutralisation guarantee, now for the Ashby audit actions. */
    @Test
    void ashbyPlatformNamedInternalTypesAreNeutralisedToo() {
        for (TimelineRow row : List.of(
                event("ASHBY_PLAN_CREATED", "{\"plan_id\":\"x\"}", "INTERNAL", "NOT_APPLICABLE", null, "EXACT", null, "SYSTEM"),
                audit("ASHBY_SUBMIT_APPROVED", "{\"approvedForSubmission\":true,\"submissionEnabled\":false}", "alice@example.com"),
                audit("ASHBY_SUBMIT_APPROVAL_REFUSED", "{}", "alice@example.com"),
                audit("ASHBY_FORM_REVIEWED", "{}", "alice@example.com"))) {
            Map<String, Object> item = mapper.map(row);
            assertThat(item.toString().toLowerCase()).doesNotContain("ashby").doesNotContain("alice@example.com");
        }
    }

    /**
     * Unlike Greenhouse's approval summary (accurately describing a
     * permanently-disabled submission action at the time that text was
     * written), Ashby's real submission genuinely can proceed once a
     * separate authorization step (prepare-submit) is also taken — so this
     * summary must never claim submission is disabled, only that nothing
     * has been sent YET.
     */
    @Test
    void anAshbyApprovalNeverClaimsSubmissionIsDisabled() {
        Map<String, Object> item = mapper.map(audit("ASHBY_SUBMIT_APPROVED", "{}", "alice@example.com"));
        assertThat(item.get("type")).isEqualTo("PACKAGE_APPROVED");
        assertThat((String) item.get("summary")).doesNotContain("submission is disabled");
        assertThat((String) item.get("summary")).contains("separate authorization step is still required");
        assertThat(item.get("actor")).isEqualTo("YOU");
    }

    @Test
    void anApprovalSaysNothingWasSent() {
        Map<String, Object> item = mapper.map(audit("GREENHOUSE_SUBMIT_APPROVED", "{}", "alice@example.com"));
        assertThat(item.get("type")).isEqualTo("PACKAGE_APPROVED");
        assertThat((String) item.get("summary")).contains("submission is disabled").contains("nothing was sent");
        assertThat(item.get("actor")).isEqualTo("YOU");
        assertThat(item.get("label")).isEqualTo("Robin activity");
    }

    @Test
    void legacyStatusRowsAreLabelledByWhoSetThem() {
        Map<String, Object> candidate = mapper.map(event("STATUS_CHANGED", "{\"to\":\"APPLICATION_SUBMITTED\"}",
                "CANDIDATE_REPORTED", "NOT_APPLICABLE", null, "EXACT", null, "alice@example.com"));
        assertThat(candidate.get("summary")).isEqualTo("You recorded the status \"Submitted\"");
        assertThat(candidate.get("label")).isEqualTo("Reported by you");
        assertThat(candidate.get("actor")).isEqualTo("YOU");

        Map<String, Object> inferred = mapper.map(event("STATUS_CHANGED", "{\"to\":\"INTERVIEW\",\"event_key\":\"email:1\"}",
                "INFERRED", "UNVERIFIED", null, "EXACT", null, "alice@example.com"));
        assertThat((String) inferred.get("summary")).contains("inferred, never confirmed");
        assertThat(inferred.get("label")).isEqualTo("Inferred by Robin — not confirmed");
    }

    @Test
    void evidenceLabelsDistinguishVerifiedUnverifiedAndRejected() {
        assertThat(mapper.map(event("RECEIPT_RECORDED", "{}", "EXTERNAL", "VERIFIED", RECORDED, "EXACT", "s", "SYSTEM"))
                .get("label")).isEqualTo("Verified employer evidence");
        assertThat(mapper.map(event("RECEIPT_RECORDED", "{}", "EXTERNAL", "UNVERIFIED", RECORDED, "EXACT", "s", "SYSTEM"))
                .get("label")).isEqualTo("Employer evidence — unverified");
        assertThat(mapper.map(event("RECEIPT_RECORDED", "{}", "EXTERNAL", "REJECTED", RECORDED, "EXACT", "s", "SYSTEM"))
                .get("label")).isEqualTo("Evidence rejected");
        assertThat(mapper.map(event("RECEIPT_RECORDED", "{}", "EXTERNAL", "UNKNOWN", RECORDED, "EXACT", "s", "SYSTEM"))
                .get("label")).isEqualTo("Employer evidence — insufficient to verify");
    }

    @Test
    void unknownAndDateOnlyTimesAreNeverInvented() {
        Map<String, Object> unknown = mapper.map(event("CANDIDATE_REPORT", "{\"kind\":\"SUBMITTED\"}", "CANDIDATE_REPORTED",
                "NOT_APPLICABLE", null, "UNKNOWN", "You reported submitting this application (date not given)", "a@b.c"));
        assertThat(unknown.get("occurredAt")).isNull();
        assertThat(unknown.get("occurredOn")).isNull();
        assertThat(unknown.get("occurredAtPrecision")).isEqualTo("UNKNOWN");
        assertThat(unknown.get("recordedAt")).isEqualTo(RECORDED.toString());

        Map<String, Object> dated = mapper.map(event("CANDIDATE_REPORT", "{}", "CANDIDATE_REPORTED", "NOT_APPLICABLE",
                Instant.parse("2026-10-02T00:00:00Z"), "DATE", "You reported …", "a@b.c"));
        assertThat(dated.get("occurredAt")).isNull();
        assertThat(dated.get("occurredOn")).isEqualTo("2026-10-02");
    }

    @Test
    void anEmployerMessageIsTheEmployersButItsMeaningIsAnInference() {
        Map<String, Object> item = mapper.map(event(TrackEvidenceRecorder.MESSAGE_EVENT, "{}", "INFERRED", "UNVERIFIED",
                RECORDED, "EXACT", "Employer message received — Robin thinks it is: interview invitation (not confirmed)", "SYSTEM"));
        assertThat(item.get("actor")).isEqualTo("EMPLOYER");
        assertThat(item.get("label")).isEqualTo("Inferred by Robin — not confirmed");
    }

    @Test
    void freeTextPayloadFieldsAreNeverCopiedThrough() {
        Map<String, Object> item = mapper.map(audit("AUTOMATION_PLAN_FAILED",
                "{\"detail\":\"Selector #first_name on boards.greenhouse.io failed for alice@example.com\"}", "worker"));
        assertThat(item.toString()).doesNotContain("Selector").doesNotContain("alice@example.com");
        assertThat(item.get("summary")).isEqualTo("Form filling failed — nothing was submitted");
    }
}
