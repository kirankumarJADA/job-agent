package com.personal.jobagent.preferences;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import com.personal.jobagent.profile.ProfileRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end regression for the PUT /api/v1/preferences 500, through real
 * JSON binding, the controller's ownership lookup, and a real PostgreSQL —
 * the full path the browser exercises.
 *
 * <h2>What broke and why this shape of test</h2>
 * The real UI's compact save payload omits {@code experienceLevels},
 * {@code companySizePref}, {@code industryPref} and {@code extraFilters};
 * null binding for those reached {@code JdbcConversions.toSqlArray} and NPE'd
 * (HTTP 500 on every save). A pure unit test on the record pins the binding;
 * this class additionally proves the request survives the whole stack:
 * Jackson → controller → repository → Postgres → response — and that the
 * saved row reads back identically (the reload half of "save + reload
 * works").
 *
 * <h2>Setup</h2>
 * Uses the real seeded dev account (V003's credential, enabled here via the
 * same V024 placeholder override {@code AuthControllerIT} pins) so the exact
 * seeded profile/preferences rows the UI talks to are under test. Runs under
 * failsafe ({@code *IT} naming); needs Docker via Testcontainers.
 */
@SpringBootTest(properties = "spring.flyway.placeholders.remove_seed_dev_account=false")
@AutoConfigureMockMvc
@Testcontainers
class PreferencesApiIT {

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
    private JdbcTemplate jdbc;

    @Autowired
    private ProfileRepository profileRepository;

    /** The UI's exact save payload — the one that used to 500. */
    private static final String COMPACT_SAVE = """
            {
              "titles": ["Backend Engineer", "Platform Engineer"],
              "keywordsInclude": [],
              "keywordsExclude": [],
              "requiredSkills": ["Java", "Kotlin"],
              "locationsAllowed": ["UK"],
              "remoteTypes": ["REMOTE", "HYBRID"],
              "employmentTypes": ["FULL_TIME"],
              "salaryMinGbp": 65000,
              "sponsorshipPolicy": "SHOW_ALL",
              "applicationMode": "ASSISTED",
              "scoringWeights": {
                "skill": 30, "experience": 15, "visa": 20, "location": 10,
                "salary": 10, "career": 10, "difficulty": 5
              }
            }
            """;

    @Test
    void compactSave_succeeds200_persists_andReadsBackIdentically() throws Exception {
        MockHttpSession session = loginAndGetSession();

        // 1. The save itself: 200, not 500.
        mockMvc.perform(put("/api/v1/preferences").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPACT_SAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titles[0]").value("Backend Engineer"))
                .andExpect(jsonPath("$.salaryMinGbp").value(65000));

        // 2. Reload semantics: a fresh GET returns the saved values —
        //    the "reload the page and see your changes" guarantee.
        mockMvc.perform(get("/api/v1/preferences").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titles[0]").value("Backend Engineer"))
                .andExpect(jsonPath("$.titles[1]").value("Platform Engineer"))
                .andExpect(jsonPath("$.requiredSkills[0]").value("Java"))
                .andExpect(jsonPath("$.remoteTypes[0]").value("REMOTE"))
                .andExpect(jsonPath("$.salaryMinGbp").value(65000))
                .andExpect(jsonPath("$.applicationMode").value("ASSISTED"))
                // Omitted collections persisted as the schema defaults.
                .andExpect(jsonPath("$.experienceLevels").isEmpty())
                .andExpect(jsonPath("$.extraFilters").isEmpty());

        // 3. The row itself: exactly one active preference set for the seeded
        //    profile, updated in place (no duplicate row was created).
        Integer activeRows = jdbc.queryForObject("""
                select count(*) from preference_sets ps
                join profiles p on p.id = ps.profile_id
                join users u on u.id = p.user_id
                where u.email = 'dev@example.local' and ps.is_active = true
                """, Integer.class);
        assertThat(activeRows).isEqualTo(1);

        // 4. The contract's audit trail entry was written. The controller
        //    writes entity_id = the preference set's id, not the profile's.
        Integer auditRows = jdbc.queryForObject("""
                select count(*) from audit_logs a
                join preference_sets ps on a.entity_id = ps.id
                join profiles p on ps.profile_id = p.id
                join users u on u.id = p.user_id
                where a.action = 'PREFERENCES_UPDATED' and u.email = 'dev@example.local'
                """, Integer.class);
        assertThat(auditRows).isGreaterThanOrEqualTo(1);
    }

    @Test
    void invalidWeights_stillRejectedWith400AndUserFacingDetail() throws Exception {
        MockHttpSession session = loginAndGetSession();

        // Sum 99: the documented validation, unchanged by the fix.
        mockMvc.perform(put("/api/v1/preferences").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPACT_SAVE.replace("\"difficulty\": 5", "\"difficulty\": 4")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "scoringWeights must sum to 100, got 99"));

        // Unknown extra category: still refused, nothing silently accepted.
        mockMvc.perform(put("/api/v1/preferences").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPACT_SAVE.replace(
                                "\"difficulty\": 5", "\"difficulty\": 5, \"style\": 0")))
                .andExpect(status().isBadRequest());

        // Missing weights entirely (nulls bind to an empty map): still a 400
        // with the contract message — not a 500, not a fabricated default.
        // (The category list's order comes from Set.of, so match on the stable
        // prefix rather than the exact rendered order.)
        mockMvc.perform(put("/api/v1/preferences").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titles\":[\"x\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                        "scoringWeights must contain exactly these categories")));

        // And nothing was persisted by any of the refused attempts.
        Integer activeRows = jdbc.queryForObject("""
                select count(*) from preference_sets ps
                join profiles p on p.id = ps.profile_id
                join users u on u.id = p.user_id
                where u.email = 'dev@example.local' and ps.is_active = true
                  and jsonb_extract_path_text(ps.scoring_weights, 'difficulty') = '4'
                """, Integer.class);
        assertThat(activeRows).isZero();
    }

    @Test
    void preferencesAreInvisibleAndUnwritableFromAnotherAccount() throws Exception {
        // A second account with its own profile and preference set.
        UUID userB = UUID.randomUUID();
        // Unique email per run: the test database is reused between runs.
        String emailB = "stranger-" + userB + "@preferences.test";
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, ?, null, 'Stranger', 'FIREBASE') on conflict (email) do nothing
                """, userB, emailB);
        UUID userBResolved = jdbc.queryForObject(
                "select id from users where email = ?", UUID.class, emailB);
        // Through the repository (as UserDataIsolationIT does) so every NOT NULL
        // column the profiles table demands is satisfied properly.
        UUID profileB = profileRepository.createProfile(
                userBResolved, null, null, null, null, null, null, null);
        jdbc.update("""
                insert into preference_sets (id, profile_id, titles, scoring_weights)
                values (?, ?, array['Stranger Only'], ?::jsonb)
                """, UUID.randomUUID(), profileB,
                "{\"skill\":30,\"experience\":15,\"visa\":20,\"location\":10,\"salary\":10,\"career\":10,\"difficulty\":5}");

        // From B's session (no HTTP session machinery involved — the same
        // principal-based context the filter chain builds), the controller
        // resolves B's OWN profile: B sees B's row, never the dev account's.
        asUser(userBResolved);
        MvcResult bView = mockMvc.perform(get("/api/v1/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titles[0]").value("Stranger Only"))
                .andReturn();
        assertThat(bView.getResponse().getContentAsString()).doesNotContain("Backend Engineer");

        // A PUT from B writes a marker title to B's row only. The dev
        // account's row can never contain B's marker — timing-independent,
        // unlike comparing updated_at timestamps across test methods.
        mockMvc.perform(put("/api/v1/preferences").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(COMPACT_SAVE.replace(
                                "[\"Backend Engineer\", \"Platform Engineer\"]", "[\"Stranger Save\"]")))
                .andExpect(status().isOk());

        Integer bRowsWithMarker = jdbc.queryForObject("""
                select count(*) from preference_sets where profile_id = ?
                  and titles @> array['Stranger Save']
                """, Integer.class, profileB);
        assertThat(bRowsWithMarker).isEqualTo(1);

        Integer devContamination = jdbc.queryForObject("""
                select count(*) from preference_sets ps
                join profiles p on p.id = ps.profile_id
                join users u on u.id = p.user_id
                where u.email = 'dev@example.local' and ps.titles @> array['Stranger Save']
                """, Integer.class);
        assertThat(devContamination).isZero();

        SecurityContextHolder.clearContext();
    }

    /** The principal the controller resolves ownership from (session-free). */
    private void asUser(UUID userId) {
        String email = jdbc.queryForObject(
                "select email::text from users where id = ?", String.class, userId);
        var record = new com.personal.jobagent.security.UserRecord(
                userId, email, null, "Stranger", null, "FIREBASE");
        var principal = new com.personal.jobagent.security.AppUserDetails(record);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private MockHttpSession loginAndGetSession() throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
