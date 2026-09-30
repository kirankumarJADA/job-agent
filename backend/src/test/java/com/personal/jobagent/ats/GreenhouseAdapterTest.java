package com.personal.jobagent.ats;

import org.junit.jupiter.api.Test;
import com.personal.jobagent.ats.AtsAdapter.FormDescriptor;
import com.personal.jobagent.ats.AtsAdapter.FormFieldDescriptor;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3A contract: real READ-ONLY Greenhouse apply-form inspection against
 * a local fixture replicating the verified live Greenhouse DOM (control ids,
 * {@code label[for]} associations, hidden required-input mirrors, JS-rendered
 * option absence). No test touches the public internet, and nothing in the
 * inspection path can type, upload, click, or submit.
 */
class GreenhouseAdapterTest {

    private static final String FIXTURE = loadFixture();

    private static String loadFixture() {
        try {
            return new String(new ClassPathResource("ats/greenhouse-apply-form-fixture.html")
                    .getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static GreenhouseAdapter fixtureAdapter() {
        return new GreenhouseAdapter(url -> FIXTURE);
    }

    private static FormDescriptor inspectFixture() {
        return fixtureAdapter().inspectForm("https://job-boards.greenhouse.io/monzo/jobs/8200681");
    }

    private static FormFieldDescriptor field(FormDescriptor descriptor, String key) {
        return descriptor.fields().stream()
                .filter(f -> f.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("field not inspected: " + key));
    }

    // 1. Greenhouse URL accepted

    @Test
    void greenhouseUrlIsInspectedIntoAFieldedDescriptor() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(descriptor.kind()).isEqualTo(AtsKind.GREENHOUSE);
        assertThat(descriptor.endpoint()).isEqualTo("https://job-boards.greenhouse.io/monzo/jobs/8200681");
        assertThat(descriptor.fields()).isNotEmpty();
    }

    // 2. Non-Greenhouse URL rejected

    @Test
    void nonGreenhouseUrlIsRejectedBeforeAnyFetch() {
        Function<String, String> countingFetch = url -> {
            throw new AssertionError("fetch must not run for a non-Greenhouse URL");
        };
        GreenhouseAdapter adapter = new GreenhouseAdapter(countingFetch);
        assertThatThrownBy(() -> adapter.inspectForm("https://jobs.lever.co/acme/123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a Greenhouse board URL");
    }

    // 3. Required fields detected

    @Test
    void requiredFieldsAreDetectedViaTheHiddenRequiredInputMirror() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "first_name").required()).isTrue();
        assertThat(field(descriptor, "last_name").required()).isTrue();
        assertThat(field(descriptor, "email").required()).isTrue();
        assertThat(field(descriptor, "phone").required()).isTrue();
        assertThat(field(descriptor, "question_123456").required()).isTrue();
    }

    // 4. Optional fields detected

    @Test
    void optionalFieldsAreDetectedAsOptional() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "cover_letter").required()).isFalse();
        assertThat(field(descriptor, "question_654321").required()).isFalse();
        assertThat(field(descriptor, "portfolio").required()).isFalse();
    }

    // 5. Field types detected

    @Test
    void htmlTypesAreDetected() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "first_name").htmlType()).isEqualTo("text");
        assertThat(field(descriptor, "phone").htmlType()).isEqualTo("tel");
        assertThat(field(descriptor, "email").htmlType()).isEqualTo("text");
        assertThat(field(descriptor, "question_123456").htmlType()).isEqualTo("textarea");
        assertThat(field(descriptor, "work_auth").htmlType()).isEqualTo("select");
        assertThat(field(descriptor, "relocate").htmlType()).isEqualTo("checkbox");
        assertThat(field(descriptor, "resume").htmlType()).isEqualTo("file");
    }

    // 6. Labels detected

    @Test
    void labelsComeFromLabelForAssociationsAndStripRequiredMarkers() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "first_name").label()).isEqualTo("First name");
        assertThat(field(descriptor, "phone").label()).isEqualTo("Phone");
        assertThat(field(descriptor, "question_123456").label())
                .isEqualTo("Why do you want to work at Pipeline Corp?");
    }

    // 7. Select/radio/checkbox options detected

    @Test
    void selectRadioAndCheckboxOptionsAreDetectedWhenStaticallyPresent() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "work_auth").options()).containsExactly("yes", "no");
        // Greenhouse renders radio groups per-option with individual ids:
        assertThat(field(descriptor, "emp_full").options()).containsExactly("FULL_TIME", "PART_TIME");
        assertThat(field(descriptor, "emp_full").htmlType()).isEqualTo("radio");
        assertThat(field(descriptor, "relocate").options()).containsExactly("yes");
    }

    @Test
    void jsRenderedOptionsAreReportedAsEmptyRatherThanGuessed() {
        // The live Monzo page renders react-select dropdowns with no static
        // options; honest uncertainty (later phases: REQUIRES_HUMAN).
        FormDescriptor empty = new GreenhouseAdapter(url ->
                "<html><body><input id='candidate-location' class='select__input' type='text'/></body></html>")
                .inspectForm("https://boards.greenhouse.io/monzo/jobs/1");
        assertThat(empty.fields()).hasSize(1);
        assertThat(empty.fields().get(0).options()).isEmpty();
    }

    // 8. File inputs detected

    @Test
    void resumeAndCoverLetterFileInputsAreDetected() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(field(descriptor, "resume").htmlType()).isEqualTo("file");
        assertThat(field(descriptor, "cover_letter").htmlType()).isEqualTo("file");
    }

    // 9. Custom questions detected

    @Test
    void customQuestionsAreDetectedByTheirGreenhouseKeys() {
        FormDescriptor descriptor = inspectFixture();
        List<String> questionKeys = descriptor.fields().stream()
                .map(FormFieldDescriptor::key)
                .filter(k -> k.startsWith("question_"))
                .toList();
        assertThat(questionKeys).containsExactlyInAnyOrder("question_123456", "question_654321");
        // numeric-id demographic-style questions are reported generically:
        assertThat(field(descriptor, "work_auth").key()).isEqualTo("work_auth");
    }

    // 10. Malformed HTML handled safely

    @Test
    void malformedHtmlIsHandledSafelyWithoutThrowing() {
        GreenhouseAdapter adapter = new GreenhouseAdapter(url ->
                "<html><body><input id='broken' type='text' <div class='x'>unclosed</body></html>");
        FormDescriptor descriptor = adapter.inspectForm("https://boards.greenhouse.io/monzo/jobs/1");
        assertThat(descriptor.fields()).isNotEmpty(); // lenient parser still finds the control
    }

    @Test
    void anEmptyPageIsReportedAsInspectionUnavailable() {
        GreenhouseAdapter adapter = new GreenhouseAdapter(url -> "   ");
        assertThatThrownBy(() -> adapter.inspectForm("https://boards.greenhouse.io/monzo/jobs/1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GREENHOUSE_INSPECTION_UNAVAILABLE");
    }

    // 11. Network failure handled deterministically

    @Test
    void networkFailureMapsToAStableDeterministicError() throws Exception {
        GreenhouseAdapter adapter = new GreenhouseAdapter(url -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("connection reset"));
        });
        assertThatThrownBy(() -> adapter.inspectForm("https://boards.greenhouse.io/monzo/jobs/1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GREENHOUSE_INSPECTION_UNAVAILABLE")
                .hasMessageContaining("connection reset");
    }

    // 12. Inspection does not mutate the form

    @Test
    void inspectionIsDeterministicAndPure() {
        FormDescriptor first = inspectFixture();
        FormDescriptor second = inspectFixture();
        assertThat(first).isEqualTo(second); // pure: same input, identical descriptor
        assertThat(first.endpoint()).isEqualTo(second.endpoint());
    }

    // 13. No candidate data is inserted during inspection

    @Test
    void fieldMetadataCarriesNoCandidateValues() {
        FormDescriptor descriptor = inspectFixture();
        for (FormFieldDescriptor field : descriptor.fields()) {
            // Structural guarantee: the record has no value/content component —
            // asserted via its accessible metadata only.
            assertThat(field.key()).isNotBlank();
            assertThat(field.selector()).startsWith("#");
            assertThat(field.options()).doesNotContain("Jane", "jane@example.com");
        }
    }

    // 14. No submit action occurs

    @Test
    void inspectionProducesNoSubmitArtifacts() {
        FormDescriptor descriptor = inspectFixture();
        for (FormFieldDescriptor field : descriptor.fields()) {
            assertThat(field.htmlType()).isNotEqualTo("submit");
            assertThat(field.key()).doesNotContain("submit");
        }
    }

    @Test
    void supportedFieldContractRemindsCallersOfTheKnownCoreFields() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(descriptor.supportedFields())
                .contains("first_name", "last_name", "email", "phone", "resume", "cover_letter");
    }

    @Test
    void hiddenValidationMirrorsAreNeverReportedAsFields() {
        FormDescriptor descriptor = inspectFixture();
        assertThat(descriptor.fields()).noneMatch(f -> f.key().isBlank());
        assertThat(descriptor.fields().stream().filter(f -> f.htmlType().equals("hidden")).count()).isZero();
    }

    @Test
    void aRandomUuidFetchIsNotMistakenForInspectionState() {
        // guards against accidental reuse of the fixture fetcher across URLs
        Function<String, String> countingFetch = url -> FIXTURE;
        GreenhouseAdapter adapter = new GreenhouseAdapter(countingFetch);
        FormDescriptor a = adapter.inspectForm("https://boards.greenhouse.io/monzo/jobs/" + UUID.randomUUID());
        FormDescriptor b = adapter.inspectForm("https://boards.greenhouse.io/monzo/jobs/" + UUID.randomUUID());
        assertThat(a.fields()).isEqualTo(b.fields()); // pure parse, no accumulated state
    }
}
