package com.personal.jobagent.discovery.platform;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.profile.ProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9 on real PostgreSQL: region-aware source selection and
 * {@code discovery_daily} quota enforcement/isolation, through the exact
 * public path {@link PreferredSourcesDiscoveryService} uses —
 * {@link BoardDiscoveryService#run(UUID, BoardDiscoveryService.Mode)}'s real
 * DB-row-to-adapter lookup via {@link PlatformRegistry}, not a fixture adapter
 * injected directly (the pattern {@code CrossSourceDiscoveryIT} uses for its
 * own, ingestion-focused tests).
 *
 * <p><b>Why zero real network happens here.</b> Every pre-existing seeded
 * source (the production Greenhouse/Stripe row, Tsenta, FreeHire, Arbeitnow)
 * is disabled in {@link #setUp} before each test, and the only sources this
 * test ever inserts are Adzuna rows. This test's Spring context never
 * configures {@code app.discovery.adzuna.app-id}/{@code app-key}, so
 * {@code AdzunaAdapter#parseIdentifier} refuses every one of them with
 * {@link com.personal.jobagent.discovery.platform.BoardConfigException}
 * BEFORE any HTTP call is made (see that class's own javadoc) — this is a
 * deterministic, network-free {@code CONFIGURATION} outcome, not a live
 * fetch, so this test needs no {@code -Dlive.discovery=true} opt-in.
 */
@SpringBootTest(properties = {"app.discovery.scheduler-enabled=false", "app.quota.discovery-daily-calls=1"})
@Testcontainers
class PreferredSourcesDiscoveryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PreferredSourcesDiscoveryService service;
    @Autowired private ProfileRepository profiles;

    @BeforeEach
    void setUp() {
        // Every pre-existing seeded source (production Greenhouse, Tsenta,
        // FreeHire, Arbeitnow, and the real V048 Adzuna/gb row) is disabled so
        // this test's own inserted rows are the only ones selectSources() can
        // ever reach — see this class's own javadoc on why that guarantees no
        // real network call happens anywhere in this test.
        jdbc.update("update job_sources set enabled = false");
    }

    /**
     * {@code country} only needs to be distinct per test (the real V048 seed
     * already owns the literal {@code (ADZUNA, gb)} identifier, so a fresh
     * suffix avoids the unique constraint) — this test's app_id/app_key are
     * never configured, so {@code parseIdentifier} always refuses with
     * {@code CONFIGURATION} on the credential check alone, before the
     * country value is even considered. See this class's own javadoc.
     */
    private UUID seedAdzunaSource(String country, String region) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                insert into job_sources (id, kind, org_identifier, display_name, capabilities, policy,
                                          rate_limit_per_min, schedule_cron, enabled, health, region)
                values (?, 'ADZUNA', ?, 'Adzuna test', '{}'::jsonb, 'DISCOVERY_ONLY', 20, '0 0 */6 * * ?', true, '{}'::jsonb, ?)
                """, id, country + "-" + id, region);
        return id;
    }

    private UUID seedProfileWithCountries(String... countries) {
        UUID userId = UuidV7.generate();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, 'Test Candidate', 'FIREBASE')",
                userId, "preferred-discovery-" + userId + "@test.local");
        UUID profileId = profiles.createProfile(userId, null, null, null, null, null, null, null);
        // profiles carry no default preference_sets row (only the dev seed
        // account gets one, via V002) — this test inserts its own active row,
        // exactly as PreferencesController's own PUT would on first save.
        StringBuilder array = new StringBuilder("{");
        for (int i = 0; i < countries.length; i++) array.append(i > 0 ? "," : "").append(countries[i]);
        array.append("}");
        jdbc.update("""
                insert into preference_sets (id, profile_id, scoring_weights, extra_filters, is_active)
                values (?, ?, '{}'::jsonb, jsonb_build_object('countries', ?::text[]), true)
                """, UuidV7.generate(), profileId, array.toString());
        return profileId;
    }

    @Test
    void aRegionScopedSourceIsSelectedOnlyForACandidateWhoseCountriesIncludeIt() {
        seedAdzunaSource("gb", "GB");
        seedAdzunaSource("us", "US");
        UUID gbProfile = seedProfileWithCountries("GB");

        var summary = service.runForProfile(gbProfile);

        assertThat(summary.quotaExceeded()).isFalse();
        assertThat(summary.sourcesQueried()).isEqualTo(1);
        // Blank app_id/app_key in this test context: a deterministic,
        // network-free CONFIGURATION outcome — never a fabricated success.
        assertThat(summary.sourcesRequireConfiguration()).isEqualTo(1);
        assertThat(summary.sourcesSucceeded()).isZero();
        assertThat(summary.sourcesFailed()).isZero();
    }

    @Test
    void aCandidateWithBothCountriesReachesBothRegionScopedSources() {
        seedAdzunaSource("gb", "GB");
        seedAdzunaSource("us", "US");
        UUID bothProfile = seedProfileWithCountries("GB", "US");

        var summary = service.runForProfile(bothProfile);

        assertThat(summary.sourcesQueried()).isEqualTo(2);
        assertThat(summary.sourcesRequireConfiguration()).isEqualTo(2);
    }

    @Test
    void aGlobalSourceReachesEveryCandidateRegardlessOfTheirCountrySelection() {
        seedAdzunaSource("gb", "GLOBAL"); // a hypothetically global-scoped row — selection logic only cares about the region value
        UUID deOnlyProfile = seedProfileWithCountries("DE");

        var summary = service.runForProfile(deOnlyProfile);

        assertThat(summary.sourcesQueried()).isEqualTo(1);
    }

    @Test
    void discoveryDailyQuotaIsEnforcedPerProfileAndNeverStarvesAnotherProfile() {
        seedAdzunaSource("gb", "GB");
        UUID profileA = seedProfileWithCountries("GB");
        UUID profileB = seedProfileWithCountries("GB");

        var first = service.runForProfile(profileA);
        assertThat(first.quotaExceeded()).isFalse();
        assertThat(first.sourcesQueried()).isEqualTo(1);

        // The quota limit (app.quota.discovery-daily-calls=1) is now spent for A...
        var second = service.runForProfile(profileA);
        assertThat(second.quotaExceeded()).isTrue();
        assertThat(second.sourcesQueried()).isZero();

        // ...but B has never used its own quota and is completely unaffected.
        var forB = service.runForProfile(profileB);
        assertThat(forB.quotaExceeded()).isFalse();
        assertThat(forB.sourcesQueried()).isEqualTo(1);

        Integer usedForA = jdbc.queryForObject(
                "select used::int from usage_quotas where profile_id = ? and quota_key = 'discovery_daily' and period_start = current_date",
                Integer.class, profileA);
        Integer usedForB = jdbc.queryForObject(
                "select used::int from usage_quotas where profile_id = ? and quota_key = 'discovery_daily' and period_start = current_date",
                Integer.class, profileB);
        assertThat(usedForA).isEqualTo(1);
        assertThat(usedForB).isEqualTo(1);
    }

    @Test
    void aCandidateWhoHasNeverSetAnyCountryDefaultsToGbNotToEveryRegion() {
        seedAdzunaSource("gb", "GB");
        seedAdzunaSource("us", "US");
        UUID userId = UuidV7.generate();
        jdbc.update("insert into users (id, email, password_hash, display_name, auth_provider) values (?, ?, null, 'No Prefs', 'FIREBASE')",
                userId, "no-prefs-" + userId + "@test.local");
        UUID profileId = profiles.createProfile(userId, null, null, null, null, null, null, null);
        // extra_filters left at its column default ('{}') — never set countries.

        var summary = service.runForProfile(profileId);

        assertThat(summary.sourcesQueried()).isEqualTo(1); // only the GB row — never both, never neither
    }
}
