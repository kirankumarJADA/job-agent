package com.personal.jobagent.automation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AtsCapabilityControllerTest {

    private final AtsCapabilityController controller = new AtsCapabilityController();

    @Test
    void allReturnsEveryRegistryRowShapedForTheFrontend() {
        var rows = controller.all();
        assertThat(rows).hasSize(AtsCapabilityRegistry.all().size());
        var greenhouse = rows.stream().filter(r -> r.kind().equals("GREENHOUSE")).findFirst().orElseThrow();
        assertThat(greenhouse.summary()).isEqualTo("SUBMISSION_AVAILABLE");
        assertThat(greenhouse.dimensions()).containsEntry("finalSubmission", "FULL");
        assertThat(greenhouse.dimensions()).containsEntry("submissionConfirmation", "FULL");
        assertThat(greenhouse.hostPatterns()).contains("greenhouse.io");
        assertThat(greenhouse.verificationNote()).isNotBlank();
    }

    @Test
    void byKindResolvesCaseInsensitivelyAndFallsBackToGenericForAnUnknownKind() {
        assertThat(controller.byKind("lever").kind()).isEqualTo("LEVER");
        assertThat(controller.byKind("workday").summary()).isEqualTo("FORM_FILL_AVAILABLE");
        assertThat(controller.byKind("some-unknown-ats").kind()).isEqualTo("GENERIC");
    }
}
