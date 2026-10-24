package com.personal.jobagent.ats;

import com.personal.jobagent.ats.AtsAdapter.FormDescriptor;
import com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor;
import com.personal.jobagent.ats.AtsAdapter.RequiredState;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6: real READ-ONLY Lever apply-form inspection, against a fixture
 * reproducing a real, live Lever posting's DOM structure (field {@code name}
 * attributes, the {@code required} attribute pattern, the résumé input id,
 * and the per-question "card" JSON shape with real option ids) — see the
 * fixture's own module comment for provenance. Nothing in the inspection
 * path types, uploads, clicks or submits.
 */
class LeverAdapterTest {

    private static final String FIXTURE = loadFixture();
    private static final String URL = "https://jobs.lever.co/palantir/6ed76ce8-4156-4b60-b120-403538bd66cd/apply";

    private static String loadFixture() {
        try {
            return new String(new ClassPathResource("ats/lever-apply-form-fixture.html")
                    .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AtsAdapters.LeverAdapter fixtureAdapter() {
        return new AtsAdapters.LeverAdapter(url -> FIXTURE);
    }

    private static FormDescriptor inspectFixture() {
        return fixtureAdapter().inspectForm(URL);
    }

    private static FormFieldDescriptor field(FormDescriptor descriptor, String key) {
        return descriptor.fields().stream().filter(f -> f.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("field not inspected: " + key));
    }

    @Test
    void leverUrlIsInspectedIntoAFieldedDescriptor() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(descriptor.kind()).isEqualTo(AtsKind.LEVER);
        assertThat(descriptor.endpoint()).isEqualTo(URL);
        assertThat(descriptor.fields()).isNotEmpty();
        assertThat(descriptor.supportsFileUpload()).isTrue();
    }

    @Test
    void onlyHttpsLeverHostsMatch() {
        AtsAdapters.LeverAdapter adapter = fixtureAdapter();
        assertThat(adapter.matchesUrl("https://jobs.lever.co/acme/123/apply")).isTrue();
        assertThat(adapter.matchesUrl("https://jobs.eu.lever.co/acme/123/apply")).isTrue();
        assertThat(adapter.matchesUrl("http://jobs.lever.co/acme/123/apply")).isFalse();
        assertThat(adapter.matchesUrl("https://jobs.lever.co.evil.example/acme")).isFalse();
        assertThat(adapter.matchesUrl("https://user@jobs.lever.co/acme/123")).isFalse();
        assertThat(adapter.matchesUrl("https://lever.co/acme")).isFalse();
    }

    @Test
    void supportsLiveInspectionIsTrueUnlikeTheGenericBaseAdapter() {
        assertThat(fixtureAdapter().supportsLiveInspection()).isTrue();
    }

    @Test
    void nonLeverUrlIsRejectedBeforeAnyFetch() {
        Function<String, String> countingFetch = url -> {
            throw new AssertionError("fetch must not run for a non-Lever URL");
        };
        AtsAdapters.LeverAdapter adapter = new AtsAdapters.LeverAdapter(countingFetch);
        assertThatThrownBy(() -> adapter.inspectForm("https://boards.greenhouse.io/acme/jobs/42"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a Lever job-posting URL");
    }

    @Test
    void standardFieldsAreInspectedByTheirStableNameAttribute() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "name").htmlType()).isEqualTo("text");
        assertThat(field(descriptor, "email").htmlType()).isEqualTo("email");
        assertThat(field(descriptor, "phone").htmlType()).isEqualTo("text");
        assertThat(field(descriptor, "location").htmlType()).isEqualTo("text");
        assertThat(field(descriptor, "org").htmlType()).isEqualTo("text");
        assertThat(field(descriptor, "urls[LinkedIn]")).isNotNull();
        assertThat(field(descriptor, "urls[GitHub]")).isNotNull();
        assertThat(field(descriptor, "urls[Portfolio]")).isNotNull();
        assertThat(field(descriptor, "resume").htmlType()).isEqualTo("file");
        assertThat(field(descriptor, "comments").htmlType()).isEqualTo("textarea");
        assertThat(field(descriptor, "name").selector()).isEqualTo("[name=\"name\"]");
    }

    @Test
    void theHiddenSelectedLocationMirrorIsNeverReportedAsTheVisibleLocationField() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(descriptor.fields().stream().noneMatch(f -> "selectedLocation".equals(f.key()))).isTrue();
    }

    @Test
    void requiredNessIsReadDirectlyFromTheRequiredAttributeNoHeuristicNeeded() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "name").requiredState()).isEqualTo(RequiredState.REQUIRED);
        assertThat(field(descriptor, "email").requiredState()).isEqualTo(RequiredState.REQUIRED);
        assertThat(field(descriptor, "location").requiredState()).isEqualTo(RequiredState.REQUIRED);
        assertThat(field(descriptor, "phone").requiredState()).isEqualTo(RequiredState.OPTIONAL);
        assertThat(field(descriptor, "org").requiredState()).isEqualTo(RequiredState.OPTIONAL);
        assertThat(field(descriptor, "resume").requiredState()).isEqualTo(RequiredState.OPTIONAL);
    }

    @Test
    void customQuestionCardsAreParsedWithRealOptionTextAndRequiredness() {
        FormDescriptor descriptor = inspectFixture();
        String checkboxKey = "cards[a69a985a-eae9-4c14-90fb-b5a4b891523e][field0]";
        FormFieldDescriptor languages = field(descriptor, checkboxKey);
        assertThat(languages.label()).isEqualTo("Language Skill(s) (Check all that apply)");
        assertThat(languages.htmlType()).isEqualTo("checkbox");
        assertThat(languages.requiredState()).isEqualTo(RequiredState.REQUIRED);
        assertThat(languages.options()).containsExactly("English (ENG)", "Spanish (SPA)", "French (FRA)");

        String textKey = "cards[a69a985a-eae9-4c14-90fb-b5a4b891523e][field1]";
        FormFieldDescriptor preferredName = field(descriptor, textKey);
        assertThat(preferredName.label()).contains("Preferred Name");
        assertThat(preferredName.requiredState()).isEqualTo(RequiredState.OPTIONAL);
        assertThat(preferredName.options()).isEmpty();

        String workAuthKey = "cards[f1e2d3c4-b5a6-4789-9abc-def012345678][field0]";
        FormFieldDescriptor workAuth = field(descriptor, workAuthKey);
        assertThat(workAuth.label()).contains("legally authorized to work");
        assertThat(workAuth.requiredState()).isEqualTo(RequiredState.REQUIRED);
    }

    @Test
    void emptyOrUnparseableFetchResultsFailHonestly() {
        AtsAdapters.LeverAdapter emptyAdapter = new AtsAdapters.LeverAdapter(url -> "");
        assertThatThrownBy(() -> emptyAdapter.inspectForm(URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEVER_INSPECTION_UNAVAILABLE");

        AtsAdapters.LeverAdapter throwingAdapter = new AtsAdapters.LeverAdapter(url -> {
            throw new IllegalStateException("UPSTREAM_STATUS_500");
        });
        assertThatThrownBy(() -> throwingAdapter.inspectForm(URL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LEVER_INSPECTION_UNAVAILABLE");
    }

    @Test
    void theSupportedFieldListNamesOnlyTheStandardKeysActuallyInspected() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(descriptor.supportedFields()).contains("name", "email", "resume", "comments");
        assertThat(descriptor.supportedFields()).doesNotContain(
                "cards[a69a985a-eae9-4c14-90fb-b5a4b891523e][field0]");
    }
}
