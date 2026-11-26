package com.personal.jobagent.automation;

import com.personal.jobagent.automation.UniversalFieldMapper.CandidateFactSnapshot;
import com.personal.jobagent.automation.UniversalFieldMapper.DiscoveredControl;
import com.personal.jobagent.automation.UniversalFieldMapper.MappedControl;
import com.personal.jobagent.automation.UniversalFieldMapper.VerifiedAnswer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UniversalFieldMapperTest {

    private static CandidateFactSnapshot sampleSnapshot(List<VerifiedAnswer> scoped, List<VerifiedAnswer> reusable) {
        return new CandidateFactSnapshot(
                "ada.apply@inbox.example",
                "Ada Lovelace",
                "+44 7700 900077",
                "London, UK",
                Map.of("linkedin", "https://linkedin.com/in/adalovelace", "github", "https://github.com/ada"),
                true,
                UUID.randomUUID(),
                "a".repeat(64),
                "cv-1.pdf",
                false,
                null,
                null,
                scoped,
                reusable
        );
    }

    @Test
    void mapsVerifiedContactLinksAndRefusesAmbiguousMultiTokenNames() {
        var snapshot = sampleSnapshot(List.of(), List.of());
        Map<String, Object> facts = UniversalFieldMapper.buildProfileFactsPayload(snapshot);
        assertThat(facts)
                .containsEntry("firstName", "Ada")
                .containsEntry("lastName", "Lovelace")
                .containsEntry("email", "ada.apply@inbox.example")
                .containsEntry("linkedin", "https://linkedin.com/in/adalovelace");

        var singleWord = new CandidateFactSnapshot("a@b.test", "Cher", null, null, Map.of(),
                true, null, null, null, false, null, null, List.of(), List.of());
        MappedControl first = UniversalFieldMapper.classifyControl(
                new DiscoveredControl("c1", "input", "text", null, "First Name *", "first_name", "first_name", "", "", true, List.of()),
                singleWord);
        assertThat(first.classification()).isEqualTo(UniversalFieldMapper.HUMAN);
        assertThat(first.value()).isNull();

        var compoundName = new CandidateFactSnapshot("a@b.test", "Mary Jane Watson", null, null, Map.of(),
                true, null, null, null, false, null, null, List.of(), List.of());
        MappedControl full = UniversalFieldMapper.classifyControl(
                new DiscoveredControl("c2", "input", "text", null, "Full Name", "full_name", "full_name", "", "", true, List.of()),
                compoundName);
        assertThat(full.classification()).isEqualTo(UniversalFieldMapper.AUTO);
        assertThat(full.value()).isEqualTo("Mary Jane Watson");
    }

    @Test
    void refusesModelDraftAnswersSponsorshipAssumptionsAndEeoEvenWithConfirmedText() {
        UUID appId = UUID.randomUUID();
        var modelDraft = new VerifiedAnswer(UUID.randomUUID(), appId,
                "Are you legally authorized to work in the United Kingdom?", "STRUCTURED_FIELD",
                "Yes", "ANSWERED", true, "MODEL_DRAFT", false);
        var eeoConfirmed = new VerifiedAnswer(UUID.randomUUID(), appId,
                "What is your gender identity?", "STRUCTURED_FIELD",
                "Prefer not to say", "ANSWERED", true, "CANDIDATE_CONFIRMED", true);

        var snapshot = sampleSnapshot(List.of(modelDraft, eeoConfirmed), List.of());

        assertThat(UniversalFieldMapper.buildConfirmedAnswersPayload(snapshot)).isEmpty();

        MappedControl visa = UniversalFieldMapper.classifyControl(
                new DiscoveredControl("q-auth", "select", "select", "combobox",
                        "Are you legally authorized to work in the United Kingdom?", "auth", "auth", "", "", true,
                        List.of("Yes", "No")),
                snapshot);
        assertThat(visa.classification()).isEqualTo(UniversalFieldMapper.HUMAN);

        MappedControl gender = UniversalFieldMapper.classifyControl(
                new DiscoveredControl("q-gender", "select", "select", "combobox",
                        "What is your gender identity?", "gender", "gender", "", "", true,
                        List.of("Female", "Male", "Prefer not to say")),
                snapshot);
        assertThat(gender.classification()).isEqualTo(UniversalFieldMapper.UNSUPPORTED);
    }

    @Test
    void reusesExplicitlyConfirmedNonEssayAnswersAndSelectsValidOptionOnly() {
        UUID appId = UUID.randomUUID();
        var confirmedAuth = new VerifiedAnswer(UUID.randomUUID(), appId,
                "Are you legally authorized to work in the United Kingdom?", "STRUCTURED_FIELD",
                "Yes", "ANSWERED", true, "CANDIDATE_CONFIRMED", true);
        var whyCompanyReusable = new VerifiedAnswer(UUID.randomUUID(), null,
                "Why do you want to join?", "WHY_COMPANY",
                "Because I like Acme", "ANSWERED", true, "CANDIDATE_CONFIRMED", true);

        var snapshot = sampleSnapshot(List.of(), List.of(confirmedAuth, whyCompanyReusable));

        List<Map<String, Object>> answers = UniversalFieldMapper.buildConfirmedAnswersPayload(snapshot);
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0)).containsEntry("answerText", "Yes");

        MappedControl matchedSelect = UniversalFieldMapper.classifyControl(
                new DiscoveredControl("q-auth", "select", "select", "combobox",
                        "Are you legally authorized to work in the United Kingdom? *", "auth", "auth", "", "", true,
                        List.of("Select...", "Yes", "No")),
                snapshot);
        assertThat(matchedSelect.classification()).isEqualTo(UniversalFieldMapper.AUTO);
        assertThat(matchedSelect.value()).isEqualTo("Yes");

        MappedControl optionalUnknown = UniversalFieldMapper.classifyControl(
                new DiscoveredControl("q-opt", "input", "text", null, "Favorite podcast", "podcast", "podcast", "", "", false, List.of()),
                snapshot);
        assertThat(optionalUnknown.classification()).isEqualTo(UniversalFieldMapper.OPTIONAL_SKIP);
    }
}
