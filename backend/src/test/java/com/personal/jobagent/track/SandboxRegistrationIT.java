package com.personal.jobagent.track;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.ApiExceptionAdvice;
import com.personal.jobagent.config.FlywayTestSupport;
import com.personal.jobagent.discovery.platform.AddressGuard;
import com.personal.jobagent.security.OwnerContext;
import com.personal.jobagent.track.receipt.SandboxRegistrationPolicy;
import com.personal.jobagent.track.receipt.SandboxRegistrationRepository;
import com.personal.jobagent.track.receipt.SandboxRegistrationService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    @Autowired private SandboxRegistrationPolicy sandboxPolicy;
    @Autowired private ObjectMapper objectMapper;

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

    @Test
    void allowsReRegistrationAfterRevocationPreventsActiveDuplicatesAndIndexesReceipts() throws Exception {
        String postingUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/8200999";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        MvcResult created1 = mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        UUID reg1Id = UUID.fromString(mapper.readTree(created1.getResponse().getContentAsString()).get("id").asText());

        // Active duplicate registration still returns 409 Conflict
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isConflict());

        // Revocation stamps revoked_at and preserves the row intact (never hard-deletes)
        mvc.perform(post("/api/v1/sandbox-registrations/" + reg1Id + "/revoke")
                        .session(devSession)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        assertThat(jdbc.queryForObject(
                "select count(*) from candidate_sandbox_registrations where id = ? and revoked_at is not null",
                Integer.class, reg1Id)).isEqualTo(1);

        // Under V070 partial unique index (where revoked_at is null), re-registering succeeds with a fresh UUID
        MvcResult created2 = mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        UUID reg2Id = UUID.fromString(mapper.readTree(created2.getResponse().getContentAsString()).get("id").asText());
        assertThat(reg2Id).isNotEqualTo(reg1Id);

        // Both historical revoked row and new active row remain preserved
        assertThat(jdbc.queryForObject(
                "select count(*) from candidate_sandbox_registrations where id = ? and revoked_at is not null",
                Integer.class, reg1Id)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from candidate_sandbox_registrations where id = ? and revoked_at is null",
                Integer.class, reg2Id)).isEqualTo(1);

        // Listing returns both registrations so history is observable
        String listed = mvc.perform(get("/api/v1/sandbox-registrations").session(devSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains(reg1Id.toString(), reg2Id.toString());

        // Second concurrent active duplicate while reg2 is active still returns 409 Conflict
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(devSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isConflict());

        // Confirm V070 schema metadata: unconditional 3-column unique constraint dropped,
        // partial unique index active, and submission_receipts(sandbox_registration_id) indexed.
        Integer unconditionalUniqueCount = jdbc.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = 'candidate_sandbox_registrations'::regclass
                  and contype = 'u' and array_length(conkey, 1) = 3
                """, Integer.class);
        assertThat(unconditionalUniqueCount).isZero();

        String partialIndexDef = jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = current_schema()
                  and tablename = 'candidate_sandbox_registrations'
                  and indexname = 'candidate_sandbox_registrations_active_uidx'
                """, String.class);
        assertThat(partialIndexDef)
                .contains("UNIQUE INDEX")
                .contains("(profile_id, ats_platform, posting_url)")
                .containsIgnoringCase("WHERE (revoked_at IS NULL)");

        Integer receiptSandboxIdxCount = jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = current_schema()
                  and tablename = 'submission_receipts'
                  and indexname = 'submission_receipts_sandbox_registration_idx'
                """, Integer.class);
        assertThat(receiptSandboxIdxCount).isEqualTo(1);

        // A different candidate profile can still register the same URL independently.
        mvc.perform(post("/api/v1/sandbox-registrations")
                        .session(otherSession)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void v069UnconditionalUniqueConstraintLocksOutReRegistrationAfterRevocation() throws Exception {
        String schema = "v069_lockout_proof";
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("drop schema if exists " + schema + " cascade");
            st.execute("create schema " + schema);
        }

        FlywayTestSupport.load(Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("classpath:db/migration")
                        .schemas(schema)
                        .placeholders(Map.of("remove_seed_dev_account", "false"))
                        .target("69"))
                .migrate();

        String baseUrl = postgres.getJdbcUrl();
        String v069JdbcUrl = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        DriverManagerDataSource v069Ds = new DriverManagerDataSource(
                v069JdbcUrl, postgres.getUsername(), postgres.getPassword());
        JdbcTemplate v069Jdbc = new JdbcTemplate(v069Ds);
        assertThat(v069Jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(schema);

        UUID v069DevProfileId = v069Jdbc.queryForObject(
                "select p.id from profiles p join users u on u.id = p.user_id where u.email = 'dev@example.local'",
                UUID.class);
        UUID v069OtherProfileId = UUID.fromString("00000000-0000-7000-8000-000000000076");
        v069Jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values ('00000000-0000-7000-8000-000000000077', 'other-sandbox@example.local', '$2a$10$dummyhash000000000000000000000000000000000000000000', 'Other Sandbox User', 'LOCAL')
                on conflict (email) do nothing
                """);
        v069Jdbc.update("""
                insert into profiles (id, user_id, setup_status, phone, location, links)
                values (?, '00000000-0000-7000-8000-000000000077', 'READY', null, null, '{}')
                on conflict (id) do nothing
                """, v069OtherProfileId);

        AtomicReference<UUID> activeProfile = new AtomicReference<>(v069DevProfileId);
        AtomicReference<String> activeActor = new AtomicReference<>("dev@example.local");
        OwnerContext v069Owner = new OwnerContext(null, v069Jdbc) {
            @Override
            public boolean isWorkerRequest() {
                return false;
            }

            @Override
            public UUID requireProfileId() {
                return activeProfile.get();
            }

            @Override
            public String actorOr(String fallback) {
                return activeActor.get();
            }
        };
        AuditLogWriter noopAudit = new AuditLogWriter() {
            @Override
            public void write(AuditEntry entry) {
            }

            @Override
            public boolean existsActionForActor(String action, String actor) {
                return false;
            }
        };
        SandboxRegistrationService v069Service = new SandboxRegistrationService(
                new SandboxRegistrationRepository(v069Jdbc), sandboxPolicy, noopAudit);
        MockMvc v069Mvc = MockMvcBuilders.standaloneSetup(new SandboxRegistrationController(v069Service, v069Owner))
                .setControllerAdvice(new ApiExceptionAdvice())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        String postingUrl = "https://boards.greenhouse.io/robin-sandbox/jobs/8200999";

        MvcResult created = v069Mvc.perform(post("/api/v1/sandbox-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn();
        String regId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        v069Mvc.perform(post("/api/v1/sandbox-registrations/" + regId + "/revoke"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.revokedAt").isNotEmpty());

        Integer revokedRows = v069Jdbc.queryForObject(
                "select count(*) from candidate_sandbox_registrations where id = ? and revoked_at is not null",
                Integer.class, UUID.fromString(regId));
        assertThat(revokedRows).isEqualTo(1);

        // Under V069 unique (profile_id, ats_platform, posting_url), re-registering the revoked URL fails with 409 Conflict.
        MvcResult conflict = v069Mvc.perform(post("/api/v1/sandbox-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isConflict())
                .andReturn();
        assertThat(conflict.getResponse().getContentAsString()).contains("already exists");

        // Confirm V069 metadata matches the documented V070 additive migration assumptions:
        // (1) unconditional 3-column unique constraint exists; (2) submission_receipts(sandbox_registration_id) index is absent in V069.
        Integer unconditionalUniqueCount = v069Jdbc.queryForObject("""
                select count(*) from pg_constraint con
                join pg_class c on c.oid = con.conrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ?
                  and c.relname = 'candidate_sandbox_registrations'
                  and con.contype = 'u'
                  and array_length(con.conkey, 1) = 3
                """, Integer.class, schema);
        assertThat(unconditionalUniqueCount).isEqualTo(1);

        Integer receiptSandboxIdxCount = v069Jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = ?
                  and tablename = 'submission_receipts'
                  and indexname = 'submission_receipts_sandbox_registration_idx'
                """, Integer.class, schema);
        assertThat(receiptSandboxIdxCount).isZero();

        // A different candidate profile can still register the same URL independently.
        activeProfile.set(v069OtherProfileId);
        activeActor.set("other-sandbox@example.local");
        v069Mvc.perform(post("/api/v1/sandbox-registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"atsPlatform":"GREENHOUSE","postingUrl":"%s"}
                                """.formatted(postingUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void populatedV069DatabaseUpgradesCleanlyToV070AndStrictLookupFailsLoudlyWhenMissingOrAmbiguous() throws Exception {
        String schema = "v070_upgrade_proof";
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("drop schema if exists " + schema + " cascade");
            st.execute("create schema " + schema);
        }

        FlywayTestSupport.load(Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("classpath:db/migration")
                        .schemas(schema)
                        .placeholders(Map.of("remove_seed_dev_account", "false"))
                        .target("69"))
                .migrate();

        UUID profileId = jdbc.queryForObject(
                "select id from " + schema + ".profiles limit 1", UUID.class);
        UUID activeRegId = UUID.randomUUID();
        UUID revokedRegId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID appId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();

        jdbc.update("""
                insert into v070_upgrade_proof.candidate_sandbox_registrations
                    (id, profile_id, ats_platform, posting_url, permitted_domain, approved_by, approved_at, revoked_at)
                values (?, ?, 'GREENHOUSE', 'https://boards.greenhouse.io/robin-sandbox/jobs/9001',
                        'boards.greenhouse.io', 'dev@example.local', now() - interval '2 days', null),
                       (?, ?, 'GREENHOUSE', 'https://boards.greenhouse.io/robin-sandbox/jobs/9002',
                        'boards.greenhouse.io', 'dev@example.local', now() - interval '2 days', now() - interval '1 day')
                """, activeRegId, profileId, revokedRegId, profileId);
        jdbc.update("""
                insert into v070_upgrade_proof.job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'upgrade-org', 'Upgrade Org', '{}', 'DISCOVERY_ONLY')
                """, sourceId);
        jdbc.update("""
                insert into v070_upgrade_proof.jobs
                    (id, source_id, external_id, dedup_key, company_name_raw, title, location_raw, description_text,
                     skills_extracted, status, content_hash, application_url)
                values (?, ?, 'up-1', 'up-dedup-1', 'Upgrade Co', 'Engineer', 'Remote', 'Desc',
                        '{}'::text[], 'DISCOVERED', 'h1', 'https://boards.greenhouse.io/robin-sandbox/jobs/9002')
                """, jobId, sourceId);
        jdbc.update("""
                insert into v070_upgrade_proof.applications (id, job_id, profile_id, mode, identity_key, status)
                values (?, ?, ?, 'ASSISTED', 'up-app-1', 'APPLICATION_SUBMITTED')
                """, appId, jobId, profileId);
        jdbc.update("""
                insert into v070_upgrade_proof.automation_plans
                    (id, application_id, profile_id, target_url, status, plan, submit_approved, idempotency_key)
                values (?, ?, ?, 'https://boards.greenhouse.io/robin-sandbox/jobs/9002', 'SUBMITTED',
                        '{"steps":[]}'::jsonb, true, 'up-plan-1')
                """, planId, appId, profileId);
        jdbc.update("""
                insert into v070_upgrade_proof.submission_evidence
                    (id, plan_id, application_id, profile_id, ats_platform, confirmation_category, employer_confirmation_id,
                     observed_signals, evidence_text, worker_principal, recorded_via, raw_evidence_status,
                     raw_evidence_sha256, raw_evidence_ciphertext)
                values (?, ?, ?, ?, 'GREENHOUSE', 'EXPLICIT_CONFIRMATION', 'GH-UP-1',
                        '["GREENHOUSE_SUBMISSION_RESPONSE_2XX"]'::jsonb, 'Application confirmed: GH-UP-1',
                        'worker', 'WORKER_COMPLETE_ENDPOINT', 'STORED_ENCRYPTED', ?, 'cipher')
                """, evidenceId, planId, appId, profileId, "a".repeat(64));
        jdbc.update("""
                insert into v070_upgrade_proof.submission_receipts
                    (id, idempotency_key, plan_id, application_id, profile_id, attempt_number, evidence_id,
                     evidence_sha256, ats_platform, employer_confirmation_id, observed_signals, approval_audit_id,
                     approval_actor, approved_at, worker_principal, sandbox_registration_id, policy_version)
                values (?, 'receipt:up:1', ?, ?, ?, 1, ?, ?, 'GREENHOUSE', 'GH-UP-1',
                        '["GREENHOUSE_SUBMISSION_RESPONSE_2XX"]'::jsonb, ?, 'dev@example.local', now() - interval '2 days',
                        'worker', ?, 'v1')
                """, receiptId, planId, appId, profileId, evidenceId, "b".repeat(64), UUID.randomUUID(), revokedRegId);

        // Migrate from V069 to V070 on populated tables
        FlywayTestSupport.load(Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .locations("classpath:db/migration")
                        .schemas(schema)
                        .placeholders(Map.of("remove_seed_dev_account", "false"))
                        .target("70"))
                .migrate();

        // 1-3: Existing active & revoked rows remain preserved, and historical receipt stays untouched and bound to revokedRegId
        assertThat(jdbc.queryForObject(
                "select count(*) from v070_upgrade_proof.candidate_sandbox_registrations where id = ? and revoked_at is null",
                Integer.class, activeRegId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from v070_upgrade_proof.candidate_sandbox_registrations where id = ? and revoked_at is not null",
                Integer.class, revokedRegId)).isEqualTo(1);
        Map<String, Object> preservedReceipt = jdbc.queryForMap("""
                select sandbox_registration_id, evidence_id, evidence_sha256, employer_confirmation_id
                from v070_upgrade_proof.submission_receipts
                where id = ?
                """, receiptId);
        assertThat(preservedReceipt.get("sandbox_registration_id")).isEqualTo(revokedRegId);
        assertThat(preservedReceipt.get("evidence_id")).isEqualTo(evidenceId);
        assertThat(preservedReceipt.get("evidence_sha256")).isEqualTo("b".repeat(64));
        assertThat(preservedReceipt.get("employer_confirmation_id")).isEqualTo("GH-UP-1");

        // 4-5: Re-registering revoked target now succeeds with 1 active row and 2 total rows
        UUID newRegId = UUID.randomUUID();
        jdbc.update("""
                insert into v070_upgrade_proof.candidate_sandbox_registrations
                    (id, profile_id, ats_platform, posting_url, permitted_domain, approved_by)
                values (?, ?, 'GREENHOUSE', 'https://boards.greenhouse.io/robin-sandbox/jobs/9002',
                        'boards.greenhouse.io', 'dev@example.local')
                """, newRegId, profileId);
        assertThat(jdbc.queryForObject("""
                select count(*) from v070_upgrade_proof.candidate_sandbox_registrations
                where profile_id = ? and ats_platform = 'GREENHOUSE'
                  and posting_url = 'https://boards.greenhouse.io/robin-sandbox/jobs/9002'
                  and revoked_at is null
                """, Integer.class, profileId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from v070_upgrade_proof.candidate_sandbox_registrations
                where profile_id = ? and ats_platform = 'GREENHOUSE'
                  and posting_url = 'https://boards.greenhouse.io/robin-sandbox/jobs/9002'
                """, Integer.class, profileId)).isEqualTo(2);

        // 6: Active duplicate on pre-V070 active row still fails cleanly via partial unique index
        assertThatThrownBy(() -> jdbc.update("""
                insert into v070_upgrade_proof.candidate_sandbox_registrations
                    (id, profile_id, ats_platform, posting_url, permitted_domain, approved_by)
                values (?, ?, 'GREENHOUSE', 'https://boards.greenhouse.io/robin-sandbox/jobs/9001',
                        'boards.greenhouse.io', 'dev@example.local')
                """, UUID.randomUUID(), profileId))
                .hasMessageContaining("candidate_sandbox_registrations_active_uidx");

        // 7-9: Unconditional 3-col unique constraint is gone, partial unique index & receipt FK index exist
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_constraint con
                join pg_class c on c.oid = con.conrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ?
                  and c.relname = 'candidate_sandbox_registrations'
                  and con.contype = 'u'
                """, Integer.class, schema)).isZero();
        String activeIdxDef = jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = ?
                  and tablename = 'candidate_sandbox_registrations'
                  and indexname = 'candidate_sandbox_registrations_active_uidx'
                """, String.class, schema);
        assertThat(activeIdxDef)
                .contains("UNIQUE INDEX")
                .contains("(profile_id, ats_platform, posting_url)")
                .containsIgnoringCase("WHERE (revoked_at IS NULL)");
        assertThat(jdbc.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = ?
                  and tablename = 'submission_receipts'
                  and indexname = 'submission_receipts_sandbox_registration_idx'
                """, Integer.class, schema)).isEqualTo(1);

        // Fail-closed transactional verification against full V070 migration SQL
        String v070Sql = Files.readString(
                Path.of("src/main/resources/db/migration/V070__sandbox_reregistration_and_receipt_index.sql"),
                StandardCharsets.UTF_8);

        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = conn.createStatement()) {
            // A. Missing target table fails loudly
            st.execute("drop schema if exists v070_missing_table cascade; create schema v070_missing_table;");
            conn.setAutoCommit(false);
            st.execute("set local search_path to v070_missing_table");
            assertThatThrownBy(() -> st.execute(v070Sql))
                    .hasMessageContaining("Expected table v070_missing_table.candidate_sandbox_registrations does not exist");
            conn.rollback();
            conn.setAutoCommit(true);

            // B. Missing unique constraint fails loudly and creates no indexes
            st.execute("drop schema if exists v070_missing_guard cascade; create schema v070_missing_guard;");
            st.execute("""
                    create table v070_missing_guard.candidate_sandbox_registrations (
                        id uuid primary key, profile_id uuid not null, ats_platform text not null, posting_url text not null, revoked_at timestamptz
                    );
                    create table v070_missing_guard.submission_receipts (
                        id uuid primary key, sandbox_registration_id uuid not null
                    );""");
            conn.setAutoCommit(false);
            st.execute("set local search_path to v070_missing_guard");
            assertThatThrownBy(() -> st.execute(v070Sql))
                    .hasMessageContaining("Expected exactly one UNIQUE (profile_id, ats_platform, posting_url) constraint");
            conn.rollback();
            conn.setAutoCommit(true);
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_indexes
                    where schemaname = 'v070_missing_guard'
                      and indexname in ('candidate_sandbox_registrations_active_uidx', 'submission_receipts_sandbox_registration_idx')
                    """, Integer.class)).isZero();

            // C. Wrong column order on 3-column unique constraint fails loudly and preserves the constraint
            st.execute("drop schema if exists v070_wrong_order_guard cascade; create schema v070_wrong_order_guard;");
            st.execute("""
                    create table v070_wrong_order_guard.candidate_sandbox_registrations (
                        id uuid primary key, profile_id uuid not null, ats_platform text not null, posting_url text not null, revoked_at timestamptz,
                        constraint uq_wrong_order unique (ats_platform, profile_id, posting_url)
                    );
                    create table v070_wrong_order_guard.submission_receipts (
                        id uuid primary key, sandbox_registration_id uuid not null
                    );""");
            conn.setAutoCommit(false);
            st.execute("set local search_path to v070_wrong_order_guard");
            assertThatThrownBy(() -> st.execute(v070Sql))
                    .hasMessageContaining("Expected exactly one UNIQUE (profile_id, ats_platform, posting_url) constraint");
            conn.rollback();
            conn.setAutoCommit(true);
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_constraint con
                    join pg_class c on c.oid = con.conrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'v070_wrong_order_guard'
                      and con.conname = 'uq_wrong_order'
                    """, Integer.class)).isEqualTo(1);

            // D. Ambiguous matching constraints fail loudly and preserve both constraints
            st.execute("drop schema if exists v070_ambig_guard cascade; create schema v070_ambig_guard;");
            st.execute("""
                    create table v070_ambig_guard.candidate_sandbox_registrations (
                        id uuid primary key, profile_id uuid not null, ats_platform text not null, posting_url text not null, revoked_at timestamptz,
                        constraint uq_one unique (profile_id, ats_platform, posting_url)
                    );
                    alter table v070_ambig_guard.candidate_sandbox_registrations
                        add constraint uq_two unique (profile_id, ats_platform, posting_url);
                    create table v070_ambig_guard.submission_receipts (
                        id uuid primary key, sandbox_registration_id uuid not null
                    );""");
            String ambigBefore = jdbc.queryForObject("""
                    select coalesce(string_agg(con.conname || ':' || con.contype::text, ',' order by con.conname), '')
                    from pg_constraint con
                    join pg_class c on c.oid = con.conrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'v070_ambig_guard'
                      and c.relname = 'candidate_sandbox_registrations'
                    """, String.class);
            assertThat(ambigBefore).contains("uq_one:u").contains("uq_two:u");
            conn.setAutoCommit(false);
            st.execute("set local search_path to v070_ambig_guard");
            assertThatThrownBy(() -> st.execute(v070Sql))
                    .hasMessageContaining("Expected exactly one UNIQUE (profile_id, ats_platform, posting_url) constraint");
            conn.rollback();
            conn.setAutoCommit(true);
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_constraint con
                    join pg_class c on c.oid = con.conrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'v070_ambig_guard'
                      and con.contype = 'u'
                    """, Integer.class)).isEqualTo(2);

            // E. Pre-existing non-partial index with target name fails loudly (no IF NOT EXISTS) and rolls back constraint drop atomically
            st.execute("drop schema if exists v070_conflicting_idx_guard cascade; create schema v070_conflicting_idx_guard;");
            st.execute("""
                    create table v070_conflicting_idx_guard.candidate_sandbox_registrations (
                        id uuid primary key, profile_id uuid not null, ats_platform text not null, posting_url text not null, revoked_at timestamptz,
                        constraint uq_orig unique (profile_id, ats_platform, posting_url)
                    );
                    create unique index candidate_sandbox_registrations_active_uidx
                        on v070_conflicting_idx_guard.candidate_sandbox_registrations (profile_id, ats_platform, posting_url);
                    create table v070_conflicting_idx_guard.submission_receipts (
                        id uuid primary key, sandbox_registration_id uuid not null
                    );""");
            conn.setAutoCommit(false);
            st.execute("set local search_path to v070_conflicting_idx_guard");
            assertThatThrownBy(() -> st.execute(v070Sql))
                    .hasMessageContaining("already exists");
            conn.rollback();
            conn.setAutoCommit(true);
            assertThat(jdbc.queryForObject("""
                    select count(*) from pg_constraint con
                    join pg_class c on c.oid = con.conrelid
                    join pg_namespace n on n.oid = c.relnamespace
                    where n.nspname = 'v070_conflicting_idx_guard'
                      and con.conname = 'uq_orig'
                    """, Integer.class)).isEqualTo(1);
        }
    }
}
