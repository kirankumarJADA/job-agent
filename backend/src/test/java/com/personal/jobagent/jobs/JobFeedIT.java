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
        "app.discovery.scheduler-enabled=false", "app.security.operator-emails=dev@example.local"})
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

    /** Fuller seeding for Phase 4 filter/sort tests — every field defaults to "not stated" (null). */
    private record JobSpec(String title, String city, String country, String locationRaw, String remoteType,
                           String employmentType, String experienceLevel, java.math.BigDecimal salaryMax,
                           String salaryCurrency, java.time.Instant postedAt) {
        static JobSpec titled(String title) {
            return new JobSpec(title, null, null, null, null, null, null, null, null, null);
        }

        JobSpec city(String v) { return new JobSpec(title, v, country, locationRaw, remoteType, employmentType, experienceLevel, salaryMax, salaryCurrency, postedAt); }
        JobSpec country(String v) { return new JobSpec(title, city, v, locationRaw, remoteType, employmentType, experienceLevel, salaryMax, salaryCurrency, postedAt); }
        JobSpec locationRaw(String v) { return new JobSpec(title, city, country, v, remoteType, employmentType, experienceLevel, salaryMax, salaryCurrency, postedAt); }
        JobSpec remoteType(String v) { return new JobSpec(title, city, country, locationRaw, v, employmentType, experienceLevel, salaryMax, salaryCurrency, postedAt); }
        JobSpec employmentType(String v) { return new JobSpec(title, city, country, locationRaw, remoteType, v, experienceLevel, salaryMax, salaryCurrency, postedAt); }
        JobSpec experienceLevel(String v) { return new JobSpec(title, city, country, locationRaw, remoteType, employmentType, v, salaryMax, salaryCurrency, postedAt); }
        JobSpec salary(java.math.BigDecimal max, String currency) { return new JobSpec(title, city, country, locationRaw, remoteType, employmentType, experienceLevel, max, currency, postedAt); }
        JobSpec postedAt(java.time.Instant v) { return new JobSpec(title, city, country, locationRaw, remoteType, employmentType, experienceLevel, salaryMax, salaryCurrency, v); }
    }

    private UUID seedJobDetailed(JobSpec spec) {
        UUID id = UuidV7.generate();
        String external = "feed-" + id;
        jdbc.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_name_raw, title, city, country,
                                  location_raw, remote_type, employment_type, experience_level, salary_max,
                                  salary_currency, description_text, skills_extracted, content_hash, status,
                                  posted_at, first_seen_at, last_seen_at)
                values (?, ?, ?, ?, 'Feed Ltd', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, 'DISCOVERED', ?, now(), now())
                """, id, sourceId, external, "dedup-" + external, spec.title(), spec.city(), spec.country(),
                spec.locationRaw(), spec.remoteType(), spec.employmentType(), spec.experienceLevel(), spec.salaryMax(),
                spec.salaryCurrency(), "Role description mentioning " + TERM, "hash-" + external,
                spec.postedAt() == null ? null : java.sql.Timestamp.from(spec.postedAt()));
        created.add(id);
        return id;
    }

    private void seedAnalysis(UUID jobId, String sponsorshipStatus) {
        jdbc.update("""
                insert into job_analyses (id, job_id, prompt_version, model_label, sponsorship_status,
                                          sponsorship_confidence, sponsorship_reason)
                values (?, ?, 'v1', 'test-model', ?, 0.9, 'test fixture')
                """, UuidV7.generate(), jobId, sponsorshipStatus);
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
        // Phase 8.2.5 source hiding: the candidate feed no longer names the
        // discovery source (this assertion previously required it). Provenance
        // stays in the database and the operational Sources views.
        assertThat(item.has("source_name")).isFalse();
        assertThat(item.has("source_kind")).isFalse();
        assertThat(item.has("source_id")).isFalse();
        assertThat(item.has("canonical_url")).isFalse();
        assertThat(item.toString()).doesNotContain("Feed Fixture (Greenhouse)", "GREENHOUSE");
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

    // ── Phase 4: discovery/recommendations filters and sort ──────────────

    @Test
    void locationFilterMatchesCityCountryOrRawLocationText() throws Exception {
        UUID london = seedJobDetailed(JobSpec.titled("London Role " + TERM).city("London").country("United Kingdom"));
        UUID berlin = seedJobDetailed(JobSpec.titled("Berlin Role " + TERM).city("Berlin").country("Germany"));
        UUID freeText = seedJobDetailed(JobSpec.titled("Hybrid Role " + TERM).locationRaw("Greater London area"));

        JsonNode byCity = getJson("/api/v1/jobs?q=" + TERM + "&location=London&limit=50");
        assertThat(findItem(byCity, london)).isNotNull();
        assertThat(findItem(byCity, berlin)).isNull();
        // The raw-text record has no structured city, but its free text contains "London".
        assertThat(findItem(byCity, freeText)).isNotNull();

        JsonNode byCountry = getJson("/api/v1/jobs?q=" + TERM + "&location=Germany&limit=50");
        assertThat(findItem(byCountry, berlin)).isNotNull();
        assertThat(findItem(byCountry, london)).isNull();
    }

    @Test
    void remoteTypeFilterIsExactAndAnInvalidValueIsRejected() throws Exception {
        UUID remote = seedJobDetailed(JobSpec.titled("Remote Role " + TERM).remoteType("REMOTE"));
        UUID onsite = seedJobDetailed(JobSpec.titled("Onsite Role " + TERM).remoteType("ONSITE"));

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&remote_type=remote&limit=50"); // lower-case accepted
        assertThat(findItem(feed, remote)).isNotNull();
        assertThat(findItem(feed, onsite)).isNull();

        mockMvc.perform(get("/api/v1/jobs?remote_type=FROM_HOME").session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void employmentTypeAndExperienceLevelFiltersAreCaseInsensitiveSubstringMatchesOnHeterogeneousFreeText() throws Exception {
        // Mirrors real heterogeneous source data (e.g. ArbeitnowAdapter's own
        // comma-joined job_types string) — no controlled vocabulary exists for
        // either field, so an exact-match filter would silently miss records
        // like this one.
        UUID job = seedJobDetailed(JobSpec.titled("Mixed Role " + TERM)
                .employmentType("Mid, fulltime permanent").experienceLevel("Senior executive"));
        UUID other = seedJobDetailed(JobSpec.titled("Other Role " + TERM)
                .employmentType("Internship").experienceLevel("Entry"));

        JsonNode byEmployment = getJson("/api/v1/jobs?q=" + TERM + "&employment_type=permanent&limit=50");
        assertThat(findItem(byEmployment, job)).isNotNull();
        assertThat(findItem(byEmployment, other)).isNull();

        JsonNode byExperience = getJson("/api/v1/jobs?q=" + TERM + "&experience_level=senior&limit=50");
        assertThat(findItem(byExperience, job)).isNotNull();
        assertThat(findItem(byExperience, other)).isNull();
    }

    @Test
    void salaryMinFilterNeverSilentlyQualifiesAJobWithAnUnknownSalary() throws Exception {
        UUID aboveFloor = seedJobDetailed(JobSpec.titled("Paid Role " + TERM)
                .salary(new java.math.BigDecimal("80000"), "GBP"));
        UUID belowFloor = seedJobDetailed(JobSpec.titled("Underpaid Role " + TERM)
                .salary(new java.math.BigDecimal("30000"), "GBP"));
        UUID unknownSalary = seedJobDetailed(JobSpec.titled("Unstated Salary Role " + TERM));

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&salary_min=50000&limit=50");
        assertThat(findItem(feed, aboveFloor)).isNotNull();
        assertThat(findItem(feed, belowFloor)).isNull();
        // The whole point of this test: unknown salary must never silently pass a floor check.
        assertThat(findItem(feed, unknownSalary)).isNull();

        mockMvc.perform(get("/api/v1/jobs?salary_min=-5").session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sponsorshipFilterOnlyMatchesAnalysedJobsAndNeverTreatsAnUnanalysedJobAsSatisfyingIt() throws Exception {
        UUID available = seedJobDetailed(JobSpec.titled("Sponsoring Role " + TERM));
        seedAnalysis(available, "LIKELY_AVAILABLE");
        UUID notAvailable = seedJobDetailed(JobSpec.titled("Non-Sponsoring Role " + TERM));
        seedAnalysis(notAvailable, "NOT_AVAILABLE");
        UUID unanalysed = seedJobDetailed(JobSpec.titled("Unanalysed Role " + TERM));

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&sponsorship=LIKELY_AVAILABLE&limit=50");
        assertThat(findItem(feed, available)).isNotNull();
        assertThat(findItem(feed, notAvailable)).isNull();
        // Never analysed — this filter must not guess it either way.
        assertThat(findItem(feed, unanalysed)).isNull();

        mockMvc.perform(get("/api/v1/jobs?sponsorship=MAYBE").session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postedAfterFilterNeverSilentlyQualifiesAJobWithAnUnknownPostingDate() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        UUID recent = seedJobDetailed(JobSpec.titled("Recent Posting " + TERM).postedAt(now));
        UUID old = seedJobDetailed(JobSpec.titled("Old Posting " + TERM).postedAt(now.minus(java.time.Duration.ofDays(60))));
        UUID unknownDate = seedJobDetailed(JobSpec.titled("Undated Posting " + TERM));

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&posted_after="
                + now.minus(java.time.Duration.ofDays(1)) + "&limit=50");
        assertThat(findItem(feed, recent)).isNotNull();
        assertThat(findItem(feed, old)).isNull();
        assertThat(findItem(feed, unknownDate)).isNull();

        mockMvc.perform(get("/api/v1/jobs?posted_after=not-a-date").session(session))
                .andExpect(status().isBadRequest());
    }

    @Test
    void combiningMultipleFiltersNarrowsResultsConsistentlyAsAnAndNotAnOr() throws Exception {
        UUID matches = seedJobDetailed(JobSpec.titled("Perfect Fit " + TERM)
                .city("London").remoteType("REMOTE").salary(new java.math.BigDecimal("90000"), "GBP"));
        UUID wrongLocation = seedJobDetailed(JobSpec.titled("Wrong City " + TERM)
                .city("Berlin").remoteType("REMOTE").salary(new java.math.BigDecimal("90000"), "GBP"));
        UUID wrongRemote = seedJobDetailed(JobSpec.titled("Onsite Only " + TERM)
                .city("London").remoteType("ONSITE").salary(new java.math.BigDecimal("90000"), "GBP"));

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&location=London&remote_type=REMOTE&salary_min=50000&limit=50");
        assertThat(findItem(feed, matches)).isNotNull();
        assertThat(findItem(feed, wrongLocation)).isNull();
        assertThat(findItem(feed, wrongRemote)).isNull();
    }

    @Test
    void matchSortUsesTheCallersOwnPersistedScoreAndCannotExposeAnotherCandidatesScore() throws Exception {
        UUID high = seedJobDetailed(JobSpec.titled("High Match " + TERM));
        UUID mid = seedJobDetailed(JobSpec.titled("Mid Match " + TERM));
        UUID low = seedJobDetailed(JobSpec.titled("Low Match " + TERM));
        seedMatch(devProfile, high, 90, "APPLY");
        seedMatch(devProfile, mid, 50, "REVIEW");
        seedMatch(devProfile, low, 10, "SKIP");
        // The other candidate's scores are deliberately the OPPOSITE ranking —
        // if this sort ever leaked or mixed in another profile's score, the
        // caller's own ordering below would come out reversed instead.
        seedMatch(otherProfile, high, 5, "SKIP");
        seedMatch(otherProfile, mid, 50, "REVIEW");
        seedMatch(otherProfile, low, 95, "APPLY");

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&sort=match&limit=50");
        List<String> order = new ArrayList<>();
        for (JsonNode item : feed.get("items")) {
            UUID id = UUID.fromString(item.get("id").asText());
            if (id.equals(high) || id.equals(mid) || id.equals(low)) order.add(item.get("title").asText());
        }
        assertThat(order).containsExactly("High Match " + TERM, "Mid Match " + TERM, "Low Match " + TERM);
    }

    @Test
    void matchSortPlacesUnscoredJobsLastRatherThanGuessingTheirRank() throws Exception {
        UUID scored = seedJobDetailed(JobSpec.titled("Scored Role " + TERM));
        seedMatch(devProfile, scored, 40, "REVIEW");
        UUID unscored = seedJobDetailed(JobSpec.titled("Unscored Role " + TERM));

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&sort=match&limit=50");
        List<String> order = new ArrayList<>();
        for (JsonNode item : feed.get("items")) {
            UUID id = UUID.fromString(item.get("id").asText());
            if (id.equals(scored) || id.equals(unscored)) order.add(item.get("title").asText());
        }
        assertThat(order).containsExactly("Scored Role " + TERM, "Unscored Role " + TERM);
    }

    @Test
    void sortingHappensBeforePaginationAndKeysetPagesThroughAMatchSortedFeedWithoutDuplicatesOrGaps() throws Exception {
        List<UUID> jobs = new ArrayList<>();
        // Five jobs, five distinct scores — enough to force two pages at limit=2.
        int[] scores = {95, 80, 60, 40, 20};
        for (int i = 0; i < scores.length; i++) {
            UUID job = seedJobDetailed(JobSpec.titled("Rank " + i + " " + TERM));
            seedMatch(devProfile, job, scores[i], "REVIEW");
            jobs.add(job);
        }

        List<String> collected = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 10; guard++) {
            String encodedCursor = cursor == null ? null
                    : java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8);
            String url = "/api/v1/jobs?q=" + TERM + "&sort=match&limit=2" + (encodedCursor != null ? "&cursor=" + encodedCursor : "");
            JsonNode feed = getJson(url);
            for (JsonNode item : feed.get("items")) {
                UUID id = UUID.fromString(item.get("id").asText());
                if (jobs.contains(id)) collected.add(item.get("title").asText());
            }
            cursor = feed.get("next_cursor").asText();
            if (cursor.isBlank()) break;
        }
        // Every one of the five jobs appears exactly once, in strict score
        // order, across however many pages it took — the defining property
        // of "sort before pagination" with a deterministic tiebreaker.
        assertThat(collected).containsExactly(
                "Rank 0 " + TERM, "Rank 1 " + TERM, "Rank 2 " + TERM, "Rank 3 " + TERM, "Rank 4 " + TERM);
    }

    @Test
    void postedSortOrdersByTheRealTimestampNotDiscoveryOrder() throws Exception {
        java.time.Instant now = java.time.Instant.now();
        UUID newest = seedJobDetailed(JobSpec.titled("Newest Posting " + TERM).postedAt(now));
        UUID oldest = seedJobDetailed(JobSpec.titled("Oldest Posting " + TERM).postedAt(now.minus(java.time.Duration.ofDays(5))));

        JsonNode feed = getJson("/api/v1/jobs?q=" + TERM + "&sort=posted&limit=50");
        List<String> order = new ArrayList<>();
        for (JsonNode item : feed.get("items")) {
            UUID id = UUID.fromString(item.get("id").asText());
            if (id.equals(newest) || id.equals(oldest)) order.add(item.get("title").asText());
        }
        assertThat(order).containsExactly("Newest Posting " + TERM, "Oldest Posting " + TERM);
    }

    @Test
    void anInvalidSortValueIsRejectedAndACursorFromADifferentSortIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/jobs?sort=cheapest").session(session))
                .andExpect(status().isBadRequest());

        UUID job = seedJobDetailed(JobSpec.titled("Cursor Role " + TERM));
        seedMatch(devProfile, job, 77, "REVIEW");
        JsonNode recentFeed = getJson("/api/v1/jobs?q=" + TERM + "&sort=recent&limit=1");
        String recentCursor = recentFeed.get("next_cursor").asText();
        if (!recentCursor.isBlank()) {
            String encoded = java.net.URLEncoder.encode(recentCursor, StandardCharsets.UTF_8);
            mockMvc.perform(get("/api/v1/jobs?sort=match&cursor=" + encoded).session(session))
                    .andExpect(status().isBadRequest());
        }
    }
}
