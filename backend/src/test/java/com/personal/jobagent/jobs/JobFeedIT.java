package com.personal.jobagent.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.JobDiscoveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8.0 FIND slice, end to end on real PostgreSQL through the real web
 * layer and authentication: the feed and job-detail endpoints return the
 * snake_case contract the frontend reads, carry ONLY the caller's own match,
 * flag stale postings with the same 30-day rule the review queue enforces,
 * hide soft-deleted postings, keep deduplicated postings single, and a
 * disabled source is never fetched live by the health-check Discover action.
 */
@SpringBootTest(properties = {"spring.flyway.placeholders.remove_seed_dev_account=false",
        "app.discovery.scheduler-enabled=false"})
@AutoConfigureMockMvc
@Testcontainers
class JobFeedIT {

    /** Unique full-text term so assertions only see this test's postings. */
    private static final String TERM = "Zephyrquill";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JobDiscoveryService discovery;
    @Autowired private com.personal.jobagent.profile.ProfileRepository profileRepository;

    private MockHttpSession session;
    private UUID devProfile;
    private UUID otherProfile;
    private UUID sourceId;
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dev@example.local\",\"password\":\"DevPassword123!\"}"))
                .andExpect(status().isOk())
                .andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        devProfile = jdbc.queryForObject("""
                select p.id from profiles p join users u on u.id = p.user_id
                where u.email = 'dev@example.local'
                """, UUID.class);

        UUID otherUser = UuidV7.generate();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, auth_provider)
                values (?, 'other@feed.test', null, 'Other Candidate', 'FIREBASE')
                on conflict (email) do nothing
                """, otherUser);
        UUID otherUserId = jdbc.queryForObject("select id from users where email = 'other@feed.test'", UUID.class);
        otherProfile = profileRepository.findByUserId(otherUserId)
                .map(p -> p.id())
                .orElseGet(() -> profileRepository.createProfile(otherUserId, null, null, null, null, null, null, null));

        sourceId = UUID.fromString("00000000-0000-7000-8000-00000000fe01");
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy)
                values (?, 'GREENHOUSE', 'feed-fixture', 'Feed Fixture (Greenhouse)', '{"discovery": true}', 'DISCOVERY_ONLY')
                on conflict (kind, org_identifier) do nothing
                """, sourceId);
        // No cleanup between tests: every assertion is keyed by the ids or
        // unique titles that test created, and deleting jobs is unsafe once the
        // asynchronous pipeline may have attached applications to them.
    }

    private UUID seedJob(String title, int lastSeenDaysAgo, boolean deleted) {
        UUID id = UuidV7.generate();
        String external = "feed-" + id;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title, location_raw,
                                  remote_type, description_text, skills_extracted, content_hash, status,
                                  first_seen_at, last_seen_at, deleted_at)
                values (?, ?, ?, ?, 'Feed Ltd', ?, 'Manchester', 'HYBRID', ?, '{Java,Spring}', ?, 'DISCOVERED',
                        now() - make_interval(days => ?), now() - make_interval(days => ?),
                        case when ? then now() else null end)
                """, id, sourceId, external, "dedup-" + external, title,
                "Role description mentioning " + TERM, "hash-" + external,
                lastSeenDaysAgo, lastSeenDaysAgo, deleted);
        created.add(id);
        return id;
    }

    private void seedMatch(UUID profile, UUID job, int score, String recommendation) {
        jdbc.update("""
                insert into job_matches (profile_id, job_id, score, recommendation, breakdown)
                values (?, ?, ?, ?, '{}'::jsonb)
                on conflict (profile_id, job_id) do update set score = excluded.score,
                    recommendation = excluded.recommendation
                """, profile, job, score, recommendation);
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).session(session))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode findItem(JsonNode feed, UUID id) {
        for (JsonNode item : feed.get("items")) {
            if (id.toString().equals(item.get("id").asText())) return item;
        }
        return null;
    }

    @Test
    void feedUsesTheSnakeCaseContractAndCarriesOnlyTheCallersOwnMatch() throws Exception {
        UUID scored = seedJob("Platform Engineer", 1, false);
        UUID unscored = seedJob("Data Engineer", 1, false);
        seedMatch(devProfile, scored, 82, "APPLY");
        seedMatch(otherProfile, scored, 12, "SKIP");
        // Another candidate scored this one; the caller has not.
        seedMatch(otherProfile, unscored, 95, "APPLY");

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&limit=50");
        JsonNode item = findItem(feed, scored);
        assertThat(item).isNotNull();
        assertThat(item.get("company_name_raw").asText()).isEqualTo("Feed Ltd");
        assertThat(item.get("location_raw").asText()).isEqualTo("Manchester");
        assertThat(item.get("remote_type").asText()).isEqualTo("HYBRID");
        assertThat(item.get("skills_extracted").size()).isEqualTo(2);
        assertThat(item.get("source_name").asText()).isEqualTo("Feed Fixture (Greenhouse)");
        assertThat(item.get("source_kind").asText()).isEqualTo("GREENHOUSE");
        assertThat(item.has("first_seen_at")).isTrue();
        assertThat(item.has("last_seen_at")).isTrue();
        assertThat(item.has("companyNameRaw")).isFalse();
        // The caller's own score, never the other candidate's 12:
        assertThat(item.get("match_score").asInt()).isEqualTo(82);
        assertThat(item.get("match_recommendation").asText()).isEqualTo("APPLY");
        // Shared filter reasons are never exposed:
        assertThat(item.has("filter_reasons")).isFalse();

        JsonNode other = findItem(feed, unscored);
        assertThat(other).isNotNull();
        assertThat(other.get("match_score").isNull()).isTrue();
        assertThat(other.get("match_recommendation").isNull()).isTrue();
    }

    @Test
    void stalePostingsAreFlaggedWithTheReviewQueueThreshold() throws Exception {
        UUID fresh = seedJob("Fresh Role", 2, false);
        UUID stale = seedJob("Stale Role", JobRepository.STALE_AFTER_DAYS + 10, false);

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&limit=50");
        assertThat(findItem(feed, fresh).get("stale").asBoolean()).isFalse();
        assertThat(findItem(feed, stale).get("stale").asBoolean()).isTrue();
    }

    @Test
    void softDeletedPostingsAreHiddenFromTheFeedButFlaggedOnDetail() throws Exception {
        UUID live = seedJob("Live Role", 1, false);
        UUID removed = seedJob("Removed Role", 1, true);

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&limit=50");
        assertThat(findItem(feed, live)).isNotNull();
        assertThat(findItem(feed, removed)).isNull();

        JsonNode detail = getJson("/api/v1/jobs/" + removed);
        assertThat(detail.get("job").get("removed").asBoolean()).isTrue();
    }

    @Test
    void jobDetailReturnsTheSnakeCaseJobAndTheCallersOwnMatchOnly() throws Exception {
        UUID job = seedJob("Detail Role", 1, false);
        seedMatch(otherProfile, job, 91, "APPLY");

        JsonNode detail = getJson("/api/v1/jobs/" + job);
        assertThat(detail.get("job").get("company_name_raw").asText()).isEqualTo("Feed Ltd");
        assertThat(detail.get("job").get("match_score").isNull()).isTrue();
        assertThat(detail.get("match").isNull()).isTrue();

        seedMatch(devProfile, job, 64, "REVIEW");
        JsonNode mine = getJson("/api/v1/jobs/" + job);
        assertThat(mine.get("match").get("score").asInt()).isEqualTo(64);
        assertThat(mine.get("job").get("match_recommendation").asText()).isEqualTo("REVIEW");
    }

    @Test
    void reIngestingTheSamePostingKeepsASingleFeedEntry() throws Exception {
        JobDiscoveryService.IngestJobCommand cmd = new JobDiscoveryService.IngestJobCommand(
                sourceId, "dup-1", null, "Dup Co", "Duplicate Engineer", "Leeds", null, null, "REMOTE",
                "FULL_TIME", null, null, null, null, "Duplicate posting about " + TERM,
                List.of("Go"), "https://example.test/apply/dup-1", "https://example.test/jobs/dup-1");
        JobDiscoveryService.IngestResult first = discovery.ingestJob(cmd);
        JobDiscoveryService.IngestResult second = discovery.ingestJob(cmd);

        assertThat(first.action()).isEqualTo("INSERTED");
        assertThat(second.action()).isEqualTo("TOUCHED");
        assertThat(second.jobId()).isEqualTo(first.jobId());

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&limit=50");
        int copies = 0;
        for (JsonNode item : feed.get("items")) {
            if ("Duplicate Engineer".equals(item.get("title").asText())) copies++;
        }
        assertThat(copies).isEqualTo(1);
    }

    @Test
    void aDisabledSourceHealthCheckDoesNotFetchOrChangeRecordedState() throws Exception {
        UUID disabled = UUID.fromString("00000000-0000-7000-8000-00000000fe02");
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy, enabled)
                values (?, 'GREENHOUSE', 'feed-disabled', 'Disabled Board', '{"discovery": true}', 'DISCOVERY_ONLY', false)
                on conflict (kind, org_identifier) do nothing
                """, disabled);

        mockMvc.perform(post("/api/v1/sources/" + disabled + "/health-check").session(session).with(csrf()))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "select failure_streak, last_run_at, health::text as health from job_sources where id = ?", disabled);
        assertThat(row.get("failure_streak")).isEqualTo(0);
        assertThat(row.get("last_run_at")).isNull();
        assertThat(String.valueOf(row.get("health"))).isEqualTo("{}");
    }

    @Test
    void anUnauthenticatedCallerCannotReadTheFeed() throws Exception {
        mockMvc.perform(get("/api/v1/jobs")).andExpect(status().isUnauthorized());
    }
}
