package com.personal.jobagent.track;

import com.personal.jobagent.discovery.platform.AddressGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false",
        "app.rate-limit.enabled=false",
        "app.worker-event-token=" + SandboxRegistrationIT.WORKER_TOKEN,
        "app.sandbox.allowed-domains=boards.greenhouse.io,job-boards.greenhouse.io",
        "app.sandbox.allowed-path-prefixes=/robin-sandbox/,/sandbox/",
        "app.sandbox.require-https=true",
        "app.sandbox.allow-local-test-hosts=false"
})
@AutoConfigureMockMvc
@Testcontainers
class SandboxRegistrationIT {

    static final String WORKER_TOKEN = "sandbox-it-worker-token";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class OfflineAddressGuardConfig {
        @Bean
        @Primary
        AddressGuard sandboxTestAddressGuard() {
            return new AddressGuard(host -> {
                if ("boards.greenhouse.io".equalsIgnoreCase(host)
                        || "job-boards.greenhouse.io".equalsIgnoreCase(host)) {
                    return new InetAddress[] { InetAddress.getByName("151.101.1.69") };
                }
                throw new UnknownHostException(host);
            });
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;

    private MockHttpSession devSession;
    private UUID devProfileId;
    private MockHttpSession otherSession;
    private UUID otherProfileId;

    @BeforeEach
    void setUp() throws Exception {
        devSession = login("dev@example.local", "DevPassword123!");
        devProfileId = profileOf("dev@example.local");

        String devHash = jdbc.queryForObject(
                "select password_hash from users where email = 'dev@example.local'", String.class);
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values ('00000000-0000-7000-8000-000000000077', 'other-sandbox@example.local', ?, 'Other Sandbox User', 'LOCAL')
                on conflict (email) do update set password_hash = excluded.password_hash
                """, devHash);
        jdbc.update("""
                insert into profiles (id, user_id, setup_status, phone, location, links)
                values ('00000000-0000-7000-8000-000000000076', '00000000-0000-7000-8000-000000000077', 'READY', null, null, '{}')
                on conflict (id) do nothing
                """);
        otherProfileId = UUID.fromString("00000000-0000-7000-8000-000000000076");
        otherSession = login("other-sandbox@example.local", "DevPassword123!");
        jdbc.update("delete from candidate_sandbox_registrations where profile_id in (?, ?)", devProfileId, otherProfileId);
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) res.getRequest().getSession(false);
    }

    private UUID profileOf(String email) {
        return jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = ?",
                UUID.class, email);
    }

    @Test
    void candidateCanRegisterListAndRevokeSandboxWithFullAuditTrailAndCrossProfileIsolation() throws Exception {
        // 1. Register with trailing slash & uppercase host -> stored canonical lowercase without trailing slash.
        MvcResult createRes = mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "atsPlatform": "GREENHOUSE",
                                  "postingUrl": "https://BOARDS.GREENHOUSE.IO/robin-sandbox/jobs/7001/"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.atsPlatform").value("GREENHOUSE"))
                .andExpect(jsonPath("$.postingUrl").value("https://boards.greenhouse.io/robin-sandbox/jobs/7001"))
                .andExpect(jsonPath("$.permittedDomain").value("boards.greenhouse.io"))
                .andExpect(jsonPath("$.approvedBy").value("dev@example.local"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.revokedAt").isEmpty())
                .andReturn();

        String regId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(createRes.getResponse().getContentAsString())
                .get("id").asText();

        // 2. Owner sees it; foreign candidate sees an empty list and 404 on revoke.
        mvc.perform(get("/api/v1/sandbox-registrations").session(devSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(regId))
                .andExpect(jsonPath("$.items[0].active").value(true));

        mvc.perform(get("/api/v1/sandbox-registrations").session(otherSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mvc.perform(post("/api/v1/sandbox-registrations/" + regId + "/revoke")
                        .session(otherSession)
                        .with(csrf()))
                .andExpect(status().isNotFound());

        // 3. Duplicate active registration is rejected with 409 Conflict.
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7001"}
                                """))
                .andExpect(status().isConflict());

        // 4. Revoke sets revoked_at without deleting immutable history.
        mvc.perform(post("/api/v1/sandbox-registrations/" + regId + "/revoke")
                        .session(devSession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        Integer rowCount = jdbc.queryForObject(
                "select count(*) from candidate_sandbox_registrations where id = ?::uuid", Integer.class, regId);
        assertThat(rowCount).isEqualTo(1);

        // 5. Revoking again returns 409 Conflict.
        mvc.perform(post("/api/v1/sandbox-registrations/" + regId + "/revoke")
                        .session(devSession)
                        .with(csrf()))
                .andExpect(status().isConflict());

        // 6. Audit logs recorded both creation and revocation.
        Integer createdAudit = jdbc.queryForObject(
                "select count(*) from audit_logs where entity_id = ?::uuid and action = 'SANDBOX_REGISTRATION_CREATED'",
                Integer.class, regId);
        Integer revokedAudit = jdbc.queryForObject(
                "select count(*) from audit_logs where entity_id = ?::uuid and action = 'SANDBOX_REGISTRATION_REVOKED'",
                Integer.class, regId);
        assertThat(createdAudit).isEqualTo(1);
        assertThat(revokedAudit).isEqualTo(1);
    }

    @Test
    void rejectsCallerTrustFieldsArbitraryEmployerDomainsSsrfAndWorkerTokens() throws Exception {
        // Unauthenticated & CSRF enforcement
        mvc.perform(get("/api/v1/sandbox-registrations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002"}
                                """))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002"}
                                """))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/sandbox-registrations/" + UUID.randomUUID() + "/revoke")
                        .session(devSession))
                .andExpect(status().isForbidden());

        // Worker bearer token cannot access or manage candidate sandboxes (401 across GET/POST/revoke)
        mvc.perform(get("/api/v1/sandbox-registrations")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .header("Authorization", "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002"}
                                """))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/sandbox-registrations/" + UUID.randomUUID() + "/revoke")
                        .header("Authorization", "Bearer " + WORKER_TOKEN))
                .andExpect(status().isUnauthorized());

        // Caller-supplied profileId, permittedDomain, approvedBy (even blank), approvedAt, revokedAt, and non-GREENHOUSE platform are rejected (400)
        for (String rejectedBody : new String[] {
                """
                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002","profileId":"%s"}
                """.formatted(otherProfileId),
                """
                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002","permittedDomain":"evil.com"}
                """,
                """
                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002","permittedDomain":"  "}
                """,
                """
                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002","approvedBy":"attacker@evil.com"}
                """,
                """
                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002","approvedBy":""}
                """,
                """
                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002","approvedAt":"2020-01-01T00:00:00Z"}
                """,
                """
                {"atsPlatform":"GREENHOUSE","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002","revokedAt":"2020-01-01T00:00:00Z"}
                """,
                """
                {"atsPlatform":"WORKDAY","postingUrl":"https://boards.greenhouse.io/robin-sandbox/jobs/7002"}
                """
        }) {
            mvc.perform(post("/api/v1/sandbox-registrations")
                            .session(devSession)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(rejectedBody))
                    .andExpect(status().isBadRequest());
        }

        // Arbitrary production employer on Greenhouse (outside allowed path prefix) & arbitrary custom domain & SSRF
        for (String badUrl : new String[] {
                "https://boards.greenhouse.io/stripe/jobs/999",
                "https://careers.evil-company.com/robin-sandbox/jobs/7002",
                "http://boards.greenhouse.io/robin-sandbox/jobs/7002",
                "http://localhost:8080/robin-sandbox/jobs/7002",
                "https://127.0.0.1/robin-sandbox/jobs/7002",
                "https://169.254.169.254/latest/meta-data",
                "https://10.0.0.1/robin-sandbox/jobs/7002"
        }) {
            mvc.perform(post("/api/v1/sandbox-registrations")
                            .session(devSession)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                    """.formatted(badUrl)))
                    .andExpect(status().isBadRequest());
        }
    }
}
