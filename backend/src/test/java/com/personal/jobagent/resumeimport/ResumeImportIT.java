package com.personal.jobagent.resumeimport;

import com.jayway.jsonpath.JsonPath;
import com.personal.jobagent.common.UuidV7;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the Master Resume Import workflow against a real
 * Postgres (Testcontainers) and the full Spring context, exercising
 * {@link ResumeImportController} through {@link ResumeImportService} down to
 * {@link com.personal.jobagent.profile.ProfileRepository} — exactly the path
 * production traffic takes. The AI step runs through the real
 * {@link com.personal.jobagent.llm.ModelRouter}, which falls back to
 * {@link com.personal.jobagent.llm.SimulatedProvider} because no real
 * provider API key is configured for tests (same as every other LLM-backed
 * IT in this codebase).
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ResumeImportIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private MockHttpSession session;
    private MockHttpSession otherSession;

    private MockHttpSession login() throws Exception {
        if (session != null) return session;
        session = loginAs("dev@example.local", "DevPassword123!");
        return session;
    }

    private MockHttpSession loginAs(String email, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }

    /** A second, real LOCAL account with its own profile, signed in through the real login endpoint. */
    private MockHttpSession otherAccount() throws Exception {
        if (otherSession != null) return otherSession;
        jdbcTemplate.update("""
                insert into users (id, email, password_hash, display_name, auth_provider, role)
                values (?, 'bob-resume-import@test.local', ?, 'Bob', 'LOCAL', 'CANDIDATE')
                on conflict (email) do nothing
                """, UuidV7.generate(), passwordEncoder.encode("BobPassword123!"));
        jdbcTemplate.update("""
                insert into profiles (id, user_id, headline)
                values (?, (select id from users where email = 'bob-resume-import@test.local'), 'Bob Headline')
                on conflict (user_id) do nothing
                """, UuidV7.generate());
        otherSession = loginAs("bob-resume-import@test.local", "BobPassword123!");
        return otherSession;
    }

    private long masterRevision(MockHttpSession who) throws Exception {
        String body = mockMvc.perform(get("/api/v1/profile").session(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.profile.masterRevision")).longValue();
    }

    private MockMultipartFile samplePdfPart() throws Exception {
        return new MockMultipartFile("file", "resume.pdf", "application/pdf", ResumeImportFixtures.samplePdfBytes());
    }

    private MvcResult runPreview() throws Exception {
        return mockMvc.perform(multipart("/api/v1/profile/resume-import/preview").file(samplePdfPart())
                        .with(csrf()).session(login()))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    @Order(1)
    void previewDoesNotPersistAnythingToTheProfile() throws Exception {
        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences").isEmpty())
                .andExpect(jsonPath("$.profile.headline").value("Backend Engineer"));
        long revisionBefore = masterRevision(login());

        MvcResult preview = runPreview();
        String body = preview.getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(body, "$.experiences")).isNotEmpty();
        assertThat((String) JsonPath.read(body, "$.previewToken")).isNotBlank();

        // Still untouched — a preview is read-only, down to the revision counter.
        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences").isEmpty())
                .andExpect(jsonPath("$.profile.headline").value("Backend Engineer"));
        assertThat(masterRevision(login())).isEqualTo(revisionBefore);
    }

    @Test
    @Order(2)
    void confirmPersistsOnlySelectedItemsAndPreservesExistingPersonalFieldsByDefault() throws Exception {
        MvcResult preview = runPreview();
        String body = preview.getResponse().getContentAsString();
        String previewToken = JsonPath.read(body, "$.previewToken");
        String company = JsonPath.read(body, "$.experiences[0].data.company");
        String title = JsonPath.read(body, "$.experiences[0].data.title");
        assertThat(company).isEqualTo("Acme Corp");
        assertThat(title).isEqualTo("Software Engineer");

        List<Map<String, Object>> education = JsonPath.read(body, "$.education");
        assertThat(education).isNotEmpty();

        String confirmBody = """
                {
                  "previewToken": "%s",
                  "personalFieldUpdates": {},
                  "experiences": [
                    {"action": "ADD", "matchedExistingId": null, "company": "Acme Corp", "title": "Software Engineer",
                     "location": "Austin, TX", "startMonth": "2021-01-01", "endMonth": "2023-12-01",
                     "responsibilities": ["Built payment processing services handling 10k requests per second"], "achievements": []}
                  ],
                  "education": [],
                  "projects": [],
                  "skills": [
                    {"action": "ADD", "matchedExistingId": null, "name": "Java", "category": "Technical"},
                    {"action": "SKIP", "matchedExistingId": null, "name": "PostgreSQL", "category": "Technical"}
                  ],
                  "certifications": []
                }
                """.formatted(previewToken);

        long revisionBefore = masterRevision(login());
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2));
        // Bumped by the existing master-revision triggers, exactly as a manual add would.
        assertThat(masterRevision(login())).isGreaterThan(revisionBefore);

        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experiences[0].company").value("Acme Corp"))
                .andExpect(jsonPath("$.experiences[0].title").value("Software Engineer"))
                // Skipped skill never persisted:
                .andExpect(jsonPath("$.skills[?(@.name=='Java')]").exists())
                .andExpect(jsonPath("$.skills[?(@.name=='PostgreSQL')]").doesNotExist())
                // Personal fields untouched: no key was sent for them.
                .andExpect(jsonPath("$.profile.headline").value("Backend Engineer"))
                .andExpect(jsonPath("$.profile.location").value("London, UK"));
    }

    @Test
    @Order(3)
    void reimportingTheSameResumeClassifiesTheExperienceAsExistingAndSkippingItAddsNothingNew() throws Exception {
        MvcResult preview = runPreview();
        String body = preview.getResponse().getContentAsString();
        String previewToken = JsonPath.read(body, "$.previewToken");
        String matchStatus = JsonPath.read(body, "$.experiences[0].matchStatus");
        assertThat(matchStatus).isEqualTo("EXISTING");

        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.experiences.length()").value(1));

        String confirmBody = """
                {"previewToken": "%s", "personalFieldUpdates": {}, "experiences": [], "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken);
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.experiences.length()").value(1));
    }

    @Test
    @Order(4)
    void explicitPersonalFieldUpdateIsApplied() throws Exception {
        MvcResult preview = runPreview();
        String previewToken = JsonPath.read(preview.getResponse().getContentAsString(), "$.previewToken");

        String confirmBody = """
                {"previewToken": "%s", "personalFieldUpdates": {"headline": "Senior Backend Engineer"},
                 "experiences": [], "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken);
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.profile.headline").value("Senior Backend Engineer"))
                // Not touched — never included in personalFieldUpdates:
                .andExpect(jsonPath("$.profile.location").value("London, UK"));
    }

    @Test
    @Order(5)
    void aConsumedPreviewTokenCannotBeConfirmedAgain() throws Exception {
        MvcResult preview = runPreview();
        String previewToken = JsonPath.read(preview.getResponse().getContentAsString(), "$.previewToken");
        String confirmBody = """
                {"previewToken": "%s", "personalFieldUpdates": {}, "experiences": [], "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken);

        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(6)
    void anInvalidOrUnknownPreviewTokenIsRejected() throws Exception {
        String confirmBody = """
                {"previewToken": "%s", "personalFieldUpdates": {}, "experiences": [], "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(7)
    void rejectsANonResumeFileWithA422NotA500() throws Exception {
        MockMultipartFile notAResume = new MockMultipartFile("file", "resume.pdf", "application/pdf", "hello world".getBytes());
        mockMvc.perform(multipart("/api/v1/profile/resume-import/preview").file(notAResume)
                        .with(csrf()).session(login()))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @Order(8)
    void auditRecordsForTheImportContainNoResumeTextOrPii() throws Exception {
        runPreview();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select after_state::text as after_state from audit_logs where action in ('PROFILE_RESUME_IMPORT_PREVIEWED','PROFILE_RESUME_IMPORT_CONFIRMED') order by created_at desc limit 5");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> row : rows) {
            String afterState = String.valueOf(row.get("after_state"));
            assertThat(afterState).doesNotContain("jordan.rivera@example.com");
            assertThat(afterState).doesNotContain("Acme Corp");
        }
    }

    @Test
    @Order(16)
    void extractionGoesThroughTheExistingRouterLedgerWithoutStoringTheResume() throws Exception {
        runPreview();
        // Routed through ModelRouter (not a side channel), and the router's own
        // ledger keeps neither the prompt nor the model's answer.
        List<Map<String, Object>> calls = jdbcTemplate.queryForList("""
                select ok, coalesce(request_redacted::text, '') as request, coalesce(response_excerpt::text, '') as response
                from llm_calls where task_type = 'PROFILE_EXTRACTION'
                """);
        assertThat(calls).isNotEmpty();
        assertThat(calls).anyMatch(call -> Boolean.TRUE.equals(call.get("ok")));
        for (Map<String, Object> call : calls) {
            assertThat(String.valueOf(call.get("request"))).doesNotContain("Jordan").doesNotContain("Acme");
            assertThat(String.valueOf(call.get("response"))).doesNotContain("Jordan").doesNotContain("Acme");
        }
    }

    @Test
    @Order(9)
    void anUnauthenticatedUploadIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/v1/profile/resume-import/preview").file(samplePdfPart()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(10)
    void anotherAccountCannotConfirmSomeoneElsesPreviewTokenAndDoesNotBurnIt() throws Exception {
        String previewToken = JsonPath.read(runPreview().getResponse().getContentAsString(), "$.previewToken");
        String intruderBody = """
                {"previewToken": "%s", "personalFieldUpdates": {"headline": "Hijacked"},
                 "experiences": [{"action": "ADD", "matchedExistingId": null, "company": "Intruder Ltd", "title": "Intruder",
                                  "location": null, "startMonth": "2020-01-01", "endMonth": null,
                                  "responsibilities": [], "achievements": []}],
                 "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken);

        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(otherAccount())
                        .contentType(MediaType.APPLICATION_JSON).content(intruderBody))
                .andExpect(status().isConflict());

        // Neither account was written to.
        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.experiences[?(@.company=='Intruder Ltd')]").doesNotExist())
                .andExpect(jsonPath("$.profile.headline").value("Senior Backend Engineer"));
        mockMvc.perform(get("/api/v1/profile").session(otherAccount()))
                .andExpect(jsonPath("$.experiences").isEmpty())
                .andExpect(jsonPath("$.profile.headline").value("Bob Headline"));

        // The failed attempt did not consume the owner's token: the owner can still use it.
        String ownerBody = """
                {"previewToken": "%s", "personalFieldUpdates": {}, "experiences": [], "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken);
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(ownerBody))
                .andExpect(status().isOk());
    }

    @Test
    @Order(11)
    void aReplaceAimedAtAnotherAccountsRecordIsRefusedAndLeavesItUntouched() throws Exception {
        String created = mockMvc.perform(post("/api/v1/profile/experiences").with(csrf()).session(otherAccount())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"company\":\"Bob Co\",\"title\":\"Bob Role\",\"startMonth\":\"2019-01-01\",\"endMonth\":null,"
                                + "\"location\":null,\"bullets\":[],\"sortOrder\":0}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String bobExperienceId = JsonPath.read(created, "$.id");

        String previewToken = JsonPath.read(runPreview().getResponse().getContentAsString(), "$.previewToken");
        // A malicious client pointing a REPLACE at a record id it does not own.
        String hijackBody = """
                {"previewToken": "%s", "personalFieldUpdates": {},
                 "experiences": [{"action": "REPLACE", "matchedExistingId": "%s", "company": "Hijacked", "title": "Hijacked",
                                  "location": null, "startMonth": "2020-01-01", "endMonth": null,
                                  "responsibilities": [], "achievements": []}],
                 "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken, bobExperienceId);

        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(hijackBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replaced").value(0))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.warnings").isNotEmpty());

        // The other account's record is unchanged, and the refused REPLACE was not turned into an ADD.
        mockMvc.perform(get("/api/v1/profile").session(otherAccount()))
                .andExpect(jsonPath("$.experiences[0].id").value(bobExperienceId))
                .andExpect(jsonPath("$.experiences[0].company").value("Bob Co"))
                .andExpect(jsonPath("$.experiences[0].title").value("Bob Role"));
        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.experiences[?(@.company=='Hijacked')]").doesNotExist());
    }

    @Test
    @Order(12)
    void duplicatesInsideOneConfirmOrAgainstTheProfileAreSkippedWithoutAbortingTheImport() throws Exception {
        // Regression: confirm() is one PostgreSQL transaction, and a failed INSERT
        // (here the case-insensitive unique skill name) aborts all of it. "Java" is
        // already on the profile (Order 2), "docker" repeats "Docker", and the
        // Acme Corp experience is identical to the stored one.
        String previewToken = JsonPath.read(runPreview().getResponse().getContentAsString(), "$.previewToken");
        String body = """
                {"previewToken": "%s", "personalFieldUpdates": {},
                 "experiences": [{"action": "ADD", "matchedExistingId": null, "company": "Acme Corp", "title": "Software Engineer",
                                  "location": "Austin, TX", "startMonth": "2021-01-01", "endMonth": "2023-12-01",
                                  "responsibilities": [], "achievements": []}],
                 "education": [], "projects": [],
                 "skills": [
                   {"action": "ADD", "matchedExistingId": null, "name": "JAVA", "category": "Technical"},
                   {"action": "ADD", "matchedExistingId": null, "name": "Docker", "category": "Tool"},
                   {"action": "ADD", "matchedExistingId": null, "name": "docker", "category": "Tool"}
                 ],
                 "certifications": []}
                """.formatted(previewToken);

        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.skipped").value(3))
                .andExpect(jsonPath("$.warnings.length()").value(3));

        String profile = mockMvc.perform(get("/api/v1/profile").session(login()))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(profile, "$.experiences[?(@.company=='Acme Corp')]")).hasSize(1);
        List<String> skillNames = JsonPath.read(profile, "$.skills[*].name");
        assertThat(skillNames.stream().filter(n -> n.equalsIgnoreCase("docker"))).hasSize(1);
        assertThat(skillNames.stream().filter(n -> n.equalsIgnoreCase("java"))).hasSize(1);
    }

    @Test
    @Order(13)
    void replacingASkillOnlyChangesWhatTheResumeCanKnowAndKeepsTheUsersMasteryAndYears() throws Exception {
        String created = mockMvc.perform(post("/api/v1/profile/skills").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kotlin\",\"category\":\"Mobile\",\"mastery\":4,\"years\":3}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String kotlinId = JsonPath.read(created, "$.id");

        String previewToken = JsonPath.read(runPreview().getResponse().getContentAsString(), "$.previewToken");
        String body = """
                {"previewToken": "%s", "personalFieldUpdates": {}, "experiences": [], "education": [], "projects": [],
                 "skills": [{"action": "REPLACE", "matchedExistingId": "%s", "name": "Kotlin", "category": "Programming Language"}],
                 "certifications": []}
                """.formatted(previewToken, kotlinId);
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replaced").value(1));

        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.skills[?(@.name=='Kotlin')].category").value("Programming Language"))
                .andExpect(jsonPath("$.skills[?(@.name=='Kotlin')].mastery").value(4))
                .andExpect(jsonPath("$.skills[?(@.name=='Kotlin')].years").value(3.0));
    }

    @Test
    @Order(14)
    void anExpiredPreviewCannotBeConfirmed() throws Exception {
        String previewToken = JsonPath.read(runPreview().getResponse().getContentAsString(), "$.previewToken");
        jdbcTemplate.update("update resume_import_previews set expires_at = now() - interval '1 minute' where id = ?::uuid", previewToken);

        String body = """
                {"previewToken": "%s", "personalFieldUpdates": {"headline": "Should Not Apply"},
                 "experiences": [], "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken);
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.profile.headline").value("Senior Backend Engineer"));
    }

    @Test
    @Order(15)
    void neitherAScriptLinkNorTheAccountEmailCanBeImported() throws Exception {
        String emailBefore = jdbcTemplate.queryForObject(
                "select email::text from users where email = 'dev@example.local'", String.class);
        String previewToken = JsonPath.read(runPreview().getResponse().getContentAsString(), "$.previewToken");
        String body = """
                {"previewToken": "%s",
                 "personalFieldUpdates": {"websiteUrl": "javascript:alert(1)", "linkedinUrl": "linkedin.com/in/jordanrivera",
                                          "email": "attacker@example.com"},
                 "experiences": [], "education": [], "projects": [], "skills": [], "certifications": []}
                """.formatted(previewToken);
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.warnings[0]").value(org.hamcrest.Matchers.containsString("websiteUrl")));

        mockMvc.perform(get("/api/v1/profile").session(login()))
                .andExpect(jsonPath("$.profile.links.linkedin").value("linkedin.com/in/jordanrivera"))
                .andExpect(jsonPath("$.profile.links.website").doesNotExist());
        assertThat(jdbcTemplate.queryForObject("select count(*) from users where email = 'attacker@example.com'", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select email::text from users where email = 'dev@example.local'", String.class))
                .isEqualTo(emailBefore);
    }
}
