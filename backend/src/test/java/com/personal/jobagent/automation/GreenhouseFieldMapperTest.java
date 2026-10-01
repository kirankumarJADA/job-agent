package com.personal.jobagent.automation;

import com.personal.jobagent.ats.AtsAdapter.FormDescriptor;
import com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3B field-classification contract (deterministic, pure): every
 * inspected Greenhouse field lands in exactly one classification bucket, and
 * no value is ever invented.
 */
class GreenhouseFieldMapperTest {

    private static final GreenhouseFieldMapper.CandidateData FULL_CANDIDATE =
            new GreenhouseFieldMapper.CandidateData(
                    "jane@example.com", "Jane Doe", "+447700900123", "London",
                    Map.of("linkedin", "https://linkedin.com/in/janedoe"),
                    Map.of(), true, UUID.randomUUID(), "cvhash", true,
                    UUID.randomUUID(), "Dear hiring team...");

    private static FormFieldDescriptor field(String key, String label, String type, boolean required) {
        return new FormFieldDescriptor(key, label, type, required, "#" + key, List.of());
    }

    private final UUID answerId = UUID.randomUUID();

    private List<GreenhouseFieldMapper.Answer> answers(String question, String text, String status) {
        return List.of(new GreenhouseFieldMapper.Answer(answerId, question, text, status,
                "ANSWERED".equals(status)));
    }

    @Test
    void knownContactFieldsClassifyAuto() {
        var fields = List.of(
                field("first_name", "First name", "text", true),
                field("last_name", "Last name", "text", true),
                field("email", "Email", "text", true),
                field("phone", "Phone", "tel", true));

        var result = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE, List.of());

        assertThat(result.mapped()).hasSize(4);
        assertThat(result.mapped()).allSatisfy(m -> assertThat(m.classification()).isEqualTo(GreenhouseFieldMapper.AUTO));
        assertThat(result.mapped()).filteredOn(m -> m.field().key().equals("first_name"))
                .singleElement().satisfies(m -> assertThat(m.value()).isEqualTo("Jane"));
        assertThat(result.mapped()).filteredOn(m -> m.field().key().equals("last_name"))
                .singleElement().satisfies(m -> assertThat(m.value()).isEqualTo("Doe"));
        assertThat(result.humanRequired()).isEmpty();
    }

    @Test
    void aThreeTokenNameIsNotSplitAutomatically() {
        var fields = List.of(field("first_name", "First name", "text", true));
        var candidate = new GreenhouseFieldMapper.CandidateData(
                "jane@example.com", "Jane Maria Doe", null, null, Map.of(), Map.of(),
                false, null, null, false, null, null);

        var result = GreenhouseFieldMapper.classify(fields, candidate, List.of());

        assertThat(result.mapped()).isEmpty();
        assertThat(result.humanRequired()).singleElement().satisfies(item ->
                assertThat(item.classification()).isEqualTo(GreenhouseFieldMapper.HUMAN));
    }

    @Test
    void missingPhoneBecomesHumanRequiredWithoutAValue() {
        var fields = List.of(field("phone", "Phone", "tel", true));
        var candidate = new GreenhouseFieldMapper.CandidateData(
                "jane@example.com", "Jane Doe", null, "London", Map.of(), Map.of(),
                false, null, null, false, null, null);

        var result = GreenhouseFieldMapper.classify(fields, candidate, List.of());

        assertThat(result.mapped()).isEmpty();
        assertThat(result.humanRequired()).singleElement().satisfies(item -> {
            assertThat(item.classification()).isEqualTo(GreenhouseFieldMapper.HUMAN);
            assertThat(item.label()).isEqualTo("Phone");
        });
    }

    @Test
    void answeredQuestionsClassifyAutoAndUnansweredRequireHuman() {
        String question = "Why do you want to work at Acme?";
        var fields = List.of(
                field("question_1", question, "textarea", true),
                field("question_2", "How did you hear about us?", "text", false));

        var result = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE,
                List.of(new GreenhouseFieldMapper.Answer(answerId, question,
                        "Because the role fits my skills.", "ANSWERED", true)));

        assertThat(result.mapped()).hasSize(1);
        assertThat(result.mapped().get(0).classification()).isEqualTo(GreenhouseFieldMapper.AUTO);
        assertThat(result.mapped().get(0).value()).isEqualTo("Because the role fits my skills.");
        assertThat(result.humanRequired()).singleElement().satisfies(item ->
                assertThat(item.classification()).isEqualTo(GreenhouseFieldMapper.HUMAN));
    }

    @Test
    void generatedAnsweredDraftIsNotAutofilledUntilHumanConfirmed() {
        String question = "Why do you want to work at Acme?";
        var field = field("question_1", question, "textarea", true);

        var result = GreenhouseFieldMapper.classify(List.of(field), FULL_CANDIDATE,
                List.of(new GreenhouseFieldMapper.Answer(UUID.randomUUID(), question,
                        "Generated draft", "ANSWERED")));

        assertThat(result.mapped()).isEmpty();
        assertThat(result.humanRequired()).singleElement().satisfies(item ->
                assertThat(item.classification()).isEqualTo(GreenhouseFieldMapper.HUMAN));
    }

    @Test
    void needsUserInputAnswersRequireHumanAndHardStopAnswersStop() {
        var fields = List.of(
                field("question_1", "Question one?", "textarea", true),
                field("question_2", "Question two?", "textarea", true));

        var result = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE, List.of(
                new GreenhouseFieldMapper.Answer(UUID.randomUUID(), "Question one?", "draft", "NEEDS_USER_INPUT"),
                new GreenhouseFieldMapper.Answer(UUID.randomUUID(), "Question two?", "draft", "HARD_STOP")));

        assertThat(result.humanRequired()).extracting(GreenhouseFieldMapper.HumanItem::classification)
                .containsExactly(GreenhouseFieldMapper.HUMAN, "HARD_STOP");
        assertThat(result.mapped()).isEmpty();
    }

    @Test
    void demographicQuestionsAreNeverAutoFilledEvenWhenAnswered() {
        String question = "How would you describe your gender identity?";
        var fields = List.of(field("627", question, "text", false));

        var result = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE,
                List.of(new GreenhouseFieldMapper.Answer(answerId, question, "nonbinary", "ANSWERED", true)));

        assertThat(result.unsupported()).singleElement().satisfies(item ->
                assertThat(item.classification()).isEqualTo(GreenhouseFieldMapper.UNSUPPORTED));
        assertThat(result.mapped()).isEmpty();
        assertThat(result.humanRequired()).isEmpty();
    }

    @Test
    void workAuthorizationIsAutoOnlyWithAStoredVerifiedAnswer() {
        var fields = List.of(field("work_auth", "Are you legally authorized to work in the UK?", "select", true));

        var withoutAnswer = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE, List.of());
        assertThat(withoutAnswer.humanRequired()).singleElement().satisfies(item ->
                assertThat(item.classification()).isEqualTo(GreenhouseFieldMapper.HUMAN));

        var unconfirmedAnswer = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE,
                List.of(new GreenhouseFieldMapper.Answer(UUID.randomUUID(),
                        "Are you legally authorized to work in the UK?", "Yes", "ANSWERED", false)));
        assertThat(unconfirmedAnswer.mapped()).isEmpty();
        var confirmedAnswer = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE,
                List.of(new GreenhouseFieldMapper.Answer(UUID.randomUUID(),
                        "Are you legally authorized to work in the UK?", "Yes", "ANSWERED", true)));
        assertThat(confirmedAnswer.mapped()).singleElement().satisfies(m ->
                assertThat(m.classification()).isEqualTo(GreenhouseFieldMapper.AUTO));
    }

    @Test
    void sponsorshipIsNeverInferredFromPolicyData() {
        var fields = List.of(field("sponsorship_q", "Do you require sponsorship?", "radio", true));

        var result = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE, List.of());

        assertThat(result.humanRequired()).singleElement().satisfies(item ->
                assertThat(item.classification()).isEqualTo(GreenhouseFieldMapper.HUMAN));
    }

    @Test
    void resumeAndCoverLetterMapFromArtifacts() {
        var fields = List.of(
                field("resume", "Attach resume", "file", false),
                field("cover_letter", "Attach cover letter", "file", false));

        var result = GreenhouseFieldMapper.classify(fields, FULL_CANDIDATE, List.of());

        assertThat(result.cvMapped()).isTrue();
        assertThat(result.coverLetterMapped()).isTrue();
        assertThat(result.mapped()).filteredOn(m -> m.field().key().equals("cover_letter"))
                .singleElement().satisfies(m -> assertThat(m.classification())
                        .isEqualTo(GreenhouseFieldMapper.REVIEW));
    }
}
