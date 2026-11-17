package com.personal.jobagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.resumeimport.ResumeImportFixtures;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SimulatedProviderResumeExtractionTest {

    private final SimulatedProvider provider = new SimulatedProvider();
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode extract(String resumeText) throws Exception {
        String prompt = "Extract this.\n<untrusted-resume-content>\n" + resumeText + "\n</untrusted-resume-content>\nReturn JSON.";
        LlmCompletionRequest request = new LlmCompletionRequest("simulated-v1", null,
                List.of(Map.of("role", "user", "content", prompt)), null, 0.0, 8192, UUID.randomUUID());
        return json.readTree(provider.complete(request, Duration.ofSeconds(5)).text());
    }

    @Test
    void extractsTheSharedFixtureResume() throws Exception {
        JsonNode result = extract(ResumeImportFixtures.SAMPLE_RESUME_TEXT);

        assertThat(result.at("/personal/fullName").asText()).isEqualTo("Jordan Rivera");
        assertThat(result.at("/personal/email").asText()).isEqualTo("jordan.rivera@example.com");
        assertThat(result.at("/personal/location").asText()).isEqualTo("Austin, TX");
        assertThat(result.at("/experiences/0/company").asText()).isEqualTo("Acme Corp");
        assertThat(result.at("/experiences/0/title").asText()).isEqualTo("Software Engineer");
        assertThat(result.at("/experiences/0/startDate").asText()).isEqualTo("2021-01");
        assertThat(result.at("/experiences/0/endDate").asText()).isEqualTo("2023-12");
        assertThat(result.at("/education/0/institution").asText()).isEqualTo("State University");
        assertThat(result.at("/education/0/endYear").asInt()).isEqualTo(2021);
        assertThat(result.at("/certifications/0/issuer").asText()).isEqualTo("Amazon Web Services");
        assertThat(result.at("/skills/technical").size()).isEqualTo(4);
    }

    @Test
    void controlCharactersInTheResumeStillProduceValidJson() throws Exception {
        JsonNode result = extract("Name\twith\ttabs\u0001\nHeadline \"quoted\" \\ backslash\n");
        assertThat(result.at("/personal/fullName").asText()).contains("with");
    }

    @Test
    void hostileInputIsProcessedInLinearTime() {
        // Shapes that make backtracking patterns quadratic or worse: a huge token with
        // no '@' (email), long digit runs (phone), and many dash-separated fields.
        StringBuilder hostile = new StringBuilder();
        hostile.append("a".repeat(200_000)).append('\n');
        hostile.append(("1".repeat(299) + "\n").repeat(8));
        hostile.append("EXPERIENCE\n");
        for (int i = 0; i < 700; i++) hostile.append("- ".repeat(150)).append('\n');
        hostile.append(" \n".repeat(20_000));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            JsonNode result = extract(hostile.toString());
            assertThat(result.has("experiences")).isTrue();
        });
    }
}
