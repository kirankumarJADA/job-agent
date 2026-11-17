package com.personal.jobagent.resumeimport;

import com.jayway.jsonpath.JsonPath;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves confirm is all-or-nothing against a real PostgreSQL: a database
 * failure on a late item rolls back the items written before it, the
 * master-revision bumps their triggers made, and the preview-token claim.
 */
@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class ResumeImportRollbackIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @SpyBean private ProfileRepository profileRepository;

    @AfterEach
    void resetSpy() {
        Mockito.reset(profileRepository);
    }

    private MockHttpSession login() throws Exception {
        return (MockHttpSession) mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    @Test
    void aDatabaseFailureMidImportRollsBackEverythingAndLeavesThePreviewUsable() throws Exception {
        MockHttpSession session = login();
        String profileBefore = mockMvc.perform(get("/api/v1/profile").session(session))
                .andReturn().getResponse().getContentAsString();
        long revisionBefore = ((Number) JsonPath.read(profileBefore, "$.profile.masterRevision")).longValue();

        String previewToken = JsonPath.read(mockMvc.perform(multipart("/api/v1/profile/resume-import/preview")
                        .file(new MockMultipartFile("file", "resume.pdf", "application/pdf", ResumeImportFixtures.samplePdfBytes()))
                        .with(csrf()).session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.previewToken");

        // Experiences are written before certifications, so the experience insert
        // has already happened (and bumped the revision) when this fails.
        Mockito.doThrow(new DataAccessResourceFailureException("injected certification-write failure"))
                .when(profileRepository).insertCertification(any(), any(), any(), any(), any(), any());

        String body = """
                {"previewToken": "%s", "personalFieldUpdates": {"headline": "Rolled Back Headline"},
                 "experiences": [{"action": "ADD", "matchedExistingId": null, "company": "Rollback Corp", "title": "Engineer",
                                  "location": null, "startMonth": "2020-01-01", "endMonth": null,
                                  "responsibilities": [], "achievements": []}],
                 "education": [], "projects": [], "skills": [],
                 "certifications": [{"action": "ADD", "matchedExistingId": null, "name": "Some Certificate",
                                     "issuer": "Some Issuer", "issuedOn": "2022-01-01", "credentialId": null}]}
                """.formatted(previewToken);

        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Nothing was changed")));

        String profileAfterFailure = mockMvc.perform(get("/api/v1/profile").session(session))
                .andReturn().getResponse().getContentAsString();
        assertThat((java.util.List<?>) JsonPath.read(profileAfterFailure, "$.experiences[?(@.company=='Rollback Corp')]")).isEmpty();
        assertThat((String) JsonPath.read(profileAfterFailure, "$.profile.headline")).isNotEqualTo("Rolled Back Headline");
        assertThat(((Number) JsonPath.read(profileAfterFailure, "$.profile.masterRevision")).longValue()).isEqualTo(revisionBefore);

        // The claim was rolled back with everything else: once the fault clears,
        // the same preview can be confirmed, exactly once.
        Mockito.reset(profileRepository);
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2));
        mockMvc.perform(get("/api/v1/profile").session(session))
                .andExpect(jsonPath("$.experiences[?(@.company=='Rollback Corp')]").exists())
                .andExpect(jsonPath("$.profile.headline").value("Rolled Back Headline"));
        mockMvc.perform(post("/api/v1/profile/resume-import/confirm").with(csrf()).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }
}
