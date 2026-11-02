package com.personal.jobagent.automation;

import com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor;
import com.personal.jobagent.ats.AtsAdapter.RequiredState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LeverFieldMapperTest {

    private static FormFieldDescriptor field(String key, String label, String htmlType, boolean required, List<String> options) {
        return new FormFieldDescriptor(key, label, htmlType,
                required ? RequiredState.REQUIRED : RequiredState.OPTIONAL, "[name=\"" + key + "\"]", options);
    }

    @SuppressWarnings("unchecked")
    private static LeverFieldMapper.CandidateData candidate(Map<String, Object> over) {
        return new LeverFieldMapper.CandidateData(
                (String) over.getOrDefault("email", null), (String) over.getOrDefault("fullName", null),
                (String) over.getOrDefault("phone", null), (String) over.getOrDefault("location", null),
                (Map<String, Object>) over.getOrDefault("links", Map.of()),
                (boolean) over.getOrDefault("cvAvailable", false),
                (boolean) over.getOrDefault("coverLetterAvailable", false),
                (String) over.getOrDefault("coverLetterMarkdown", null));
    }

    private static final LeverFieldMapper.CandidateData FULL_CANDIDATE = candidate(Map.of(
            "email", "ada@example.test", "fullName", "Ada Lovelace", "phone", "+44 20 7946 0000",
            "location", "London, UK", "links", Map.of("LinkedIn", "https://linkedin.com/in/ada"),
            "cvAvailable", true, "coverLetterAvailable", true, "coverLetterMarkdown", "Dear hiring team..."));

    @Test
    void nameIsFilledFromDisplayNameWithNoSplitAmbiguityUnlikeGreenhouse() {
        var outcome = LeverFieldMapper.classifyField(field("name", "Name", "text", true, List.of()), FULL_CANDIDATE, List.of());
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.AUTO);
        assertThat(outcome.value()).isEqualTo("Ada Lovelace");
    }

    @Test
    void emailPhoneAndLocationAreFilledFromVerifiedProfileData() {
        assertThat(LeverFieldMapper.classifyField(field("email", "Email", "email", true, List.of()), FULL_CANDIDATE, List.of()).value())
                .isEqualTo("ada@example.test");
        assertThat(LeverFieldMapper.classifyField(field("phone", "Phone", "text", false, List.of()), FULL_CANDIDATE, List.of()).value())
                .isEqualTo("+44 20 7946 0000");
        assertThat(LeverFieldMapper.classifyField(field("location", "Location", "text", true, List.of()), FULL_CANDIDATE, List.of()).value())
                .isEqualTo("London, UK");
    }

    @Test
    void missingContactDataRequiresHumanNeverFabricated() {
        var empty = candidate(Map.of());
        var outcome = LeverFieldMapper.classifyField(field("email", "Email", "email", true, List.of()), empty, List.of());
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.HUMAN);
        assertThat(outcome.value()).isNull();
    }

    @Test
    void resumeIsAutoOnlyWhenACvArtifactExists() {
        assertThat(LeverFieldMapper.classifyField(field("resume", "Resume", "file", true, List.of()), FULL_CANDIDATE, List.of()).classification())
                .isEqualTo(LeverFieldMapper.AUTO);
        var noCv = candidate(Map.of("cvAvailable", false));
        assertThat(LeverFieldMapper.classifyField(field("resume", "Resume", "file", true, List.of()), noCv, List.of()).classification())
                .isEqualTo(LeverFieldMapper.HUMAN);
    }

    @Test
    void commentsIsFilledWithTheApprovedCoverLetterUnderReviewClassification() {
        var outcome = LeverFieldMapper.classifyField(field("comments", "Comments", "textarea", false, List.of()), FULL_CANDIDATE, List.of());
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.REVIEW);
        assertThat(outcome.value()).isEqualTo("Dear hiring team...");
    }

    @Test
    void currentEmployerAlwaysRequiresHumanRobinHasNoVerifiedSource() {
        var outcome = LeverFieldMapper.classifyField(field("org", "Current company", "text", false, List.of()), FULL_CANDIDATE, List.of());
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.HUMAN);
    }

    @Test
    void profileLinksFillTheMatchingUrlFieldCaseInsensitively() {
        var outcome = LeverFieldMapper.classifyField(field("urls[LinkedIn]", "LinkedIn", "text", false, List.of()), FULL_CANDIDATE, List.of());
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.AUTO);
        assertThat(outcome.value()).isEqualTo("https://linkedin.com/in/ada");

        var outcome2 = LeverFieldMapper.classifyField(field("urls[GitHub]", "GitHub", "text", false, List.of()), FULL_CANDIDATE, List.of());
        assertThat(outcome2.classification()).isEqualTo(LeverFieldMapper.HUMAN);
    }

    @Test
    void demographicQuestionsAreNeverAutoAnsweredEvenWithAStoredAnswer() {
        var answers = List.of(new LeverFieldMapper.Answer(UUID.randomUUID(), "What is your gender?", "Prefer not to say", "ANSWERED", true));
        var outcome = LeverFieldMapper.classifyField(
                field("cards[x][field0]", "What is your gender?", "radio", false, List.of("Male", "Female", "Prefer not to say")),
                FULL_CANDIDATE, answers);
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.UNSUPPORTED);
    }

    @Test
    void workAuthorizationQuestionUsesAHumanConfirmedStoredAnswerOnly() {
        var noAnswer = LeverFieldMapper.classifyField(
                field("cards[x][field0]", "Are you legally authorized to work in this country?", "text", true, List.of()),
                FULL_CANDIDATE, List.of());
        assertThat(noAnswer.classification()).isEqualTo(LeverFieldMapper.HUMAN);

        var answers = List.of(new LeverFieldMapper.Answer(UUID.randomUUID(),
                "Are you legally authorized to work in this country?", "Yes", "ANSWERED", true));
        var withAnswer = LeverFieldMapper.classifyField(
                field("cards[x][field0]", "Are you legally authorized to work in this country?", "text", true, List.of()),
                FULL_CANDIDATE, answers);
        assertThat(withAnswer.classification()).isEqualTo(LeverFieldMapper.AUTO);
        assertThat(withAnswer.value()).isEqualTo("Yes");
    }

    @Test
    void anUnconfirmedOrUnderConfidenceAnswerNeverAutoFills() {
        var unconfirmed = List.of(new LeverFieldMapper.Answer(UUID.randomUUID(), "What is your favourite language?", "Java", "ANSWERED", false));
        var outcome = LeverFieldMapper.classifyField(
                field("cards[x][field0]", "What is your favourite language?", "text", false, List.of()), FULL_CANDIDATE, unconfirmed);
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.HUMAN);
    }

    @Test
    void genericCustomQuestionWithAConfirmedAnswerIsAutoFilled() {
        var answers = List.of(new LeverFieldMapper.Answer(UUID.randomUUID(), "What is your favourite language?", "Java", "ANSWERED", true));
        var outcome = LeverFieldMapper.classifyField(
                field("cards[x][field0]", "What is your favourite language?", "text", false, List.of()), FULL_CANDIDATE, answers);
        assertThat(outcome.classification()).isEqualTo(LeverFieldMapper.AUTO);
        assertThat(outcome.value()).isEqualTo("Java");
    }

    @Test
    void classifyBuildsMappedHumanRequiredAndUnsupportedBucketsCorrectly() {
        List<FormFieldDescriptor> fields = List.of(
                field("name", "Name", "text", true, List.of()),
                field("org", "Current company", "text", false, List.of()),
                field("cards[x][field0]", "Gender", "text", false, List.of()));
        var result = LeverFieldMapper.classify(fields, FULL_CANDIDATE, List.of());
        assertThat(result.mapped()).hasSize(1);
        assertThat(result.humanRequired()).hasSize(1);
        assertThat(result.unsupported()).hasSize(1);
    }
}
