package com.personal.jobagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The production half of the V024 correction, over the real HTTP surface.
 *
 * <p>A deployed environment gets {@code remove_seed_dev_account=true} — the
 * application.yml default, which application-local.yml is the only file to opt
 * out of — so its database must not be able to authenticate as
 * {@code dev@example.local}, whose password is published in V003's comment. This
 * class pins that end to end: a real login request is refused, and a genuinely
 * hashed account is not caught in the crossfire.
 *
 * <p>Its counterpart is AuthControllerIT, which pins the local value and proves
 * the seeded login still works where it is meant to. Between the two, the
 * configuration that keeps a usable development credential and the configuration
 * that removes it are both covered.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=true", "app.discovery.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class SeededDevCredentialRemovalIT {

    private static final String SEED_EMAIL = "dev@example.local";
    private static final String REAL_USER_EMAIL = "real.operator@example.test";
    private static final String REAL_USER_PASSWORD = "a-real-users-own-password";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void thePublishedDevelopmentPasswordNoLongerAuthenticates() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@example.local","password":"DevPassword123!"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid credentials"));
    }

    @Test
    void theSeededAccountIsNeutralizedRatherThanDeletedSoNoStoredDataIsLost() {
        // The credential is gone...
        assertThat(jdbcTemplate.queryForObject(
                "select password_hash from users where email = ?", String.class, SEED_EMAIL))
                .isNull();

        // ...while the account, its profile and the seed data remain, so a
        // deployment that had written real data on this account loses nothing.
        assertThat(jdbcTemplate.queryForObject(
                "select display_name from users where email = ?", String.class, SEED_EMAIL))
                .isEqualTo("Dev User");
        assertThat(jdbcTemplate.queryForObject("select count(*) from profiles", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from preference_sets", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aRealAccountIsUntouchedAndCanStillSignIn() throws Exception {
        // A genuine account, hashed with the application's own encoder, written
        // into the already-corrected database — the operator's situation.
        UUID realUserId = UUID.randomUUID();
        jdbcTemplate.update("insert into users (id, email, password_hash, display_name) values (?, ?, ?, ?)",
                realUserId, REAL_USER_EMAIL, passwordEncoder.encode(REAL_USER_PASSWORD), "Real Operator");
        jdbcTemplate.update("insert into profiles (id, user_id, headline) values (?, ?, ?)",
                UUID.randomUUID(), realUserId, "Real Operator");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"real.operator@example.test","password":"a-real-users-own-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(REAL_USER_EMAIL));

        // And the account keeps working after signing in, i.e. the session the
        // login established is a normal one.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"real.operator@example.test","password":"a-real-users-own-password"}
                                """))
                .andExpect(status().isOk());

        assertThat(passwordEncoder.matches(REAL_USER_PASSWORD, jdbcTemplate.queryForObject(
                "select password_hash from users where email = ?", String.class, REAL_USER_EMAIL)))
                .isTrue();
    }

    @Test
    void aWrongPasswordForTheSeededAccountIsRefusedInTheSameShape() throws Exception {
        // No new signal for an attacker probing the account, and no 500 from the
        // now-null hash: the response is identical to any other bad login.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"dev@example.local","password":"anything-else"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Invalid credentials"));
    }

    @Test
    void anUnauthenticatedRequestStillGets401() throws Exception {
        // The removal must not have opened anything up in place of the account.
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isUnauthorized());
    }
}
