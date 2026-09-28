package com.personal.jobagent.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V024 driven through a real Flyway run against real PostgreSQL, one fresh
 * schema per scenario.
 *
 * <p>V024 exists because V002/V003 seed {@code dev@example.local} with a password
 * published in V003's own comment, and Flyway applies the same chain in every
 * environment — so the hosted deployment held a publicly-known credential.
 * Editing V002/V003 is not an option (they are applied and immutable), so the
 * correction is forward-only, and this class is what proves it is correct:
 *
 * <ul>
 *   <li>a fresh database built with the production default never has a usable
 *       seeded credential;</li>
 *   <li>an existing database sitting at V023 — the state the hosted deployment is
 *       actually in — is corrected when it upgrades, and nothing else in it
 *       changes;</li>
 *   <li>local development (the local profile opts out) keeps its seeded login;</li>
 *   <li>the correction is idempotent and scoped to the published hash, so a
 *       rotated password is never touched.</li>
 * </ul>
 *
 * <p>One deliberate exclusion: this migration is an UPDATE, not a DELETE. The
 * seeded row carries a profile (and possibly real data attributed to it by
 * V022's backfill), and everything under {@code profiles} cascades from
 * {@code users}, so a delete could destroy production data. The "account
 * survives with its data" assertions below are the point of that choice.
 */
@Testcontainers
class SeededDevCredentialMigrationIT {

    private static final String SEED_EMAIL = "dev@example.local";
    /**
     * The hash V003 published, and the match key V024 scopes itself to. It is
     * quoted here — and in the migration — precisely so the correction can be
     * limited to the credential that leaked.
     */
    private static final String PUBLISHED_SEED_HASH =
            "$argon2id$v=19$m=19456,t=2,p=1$F9uqoBCrDE3/mNdpD/bz7w$N/dY7+nj/TzP1oUNVRW55nb+/YJcHrWUqyKbu8t63ic";
    private static final String PUBLISHED_SEED_PASSWORD = "DevPassword123!";

    private static final String REAL_USER_EMAIL = "real.user@example.test";
    private static final String REAL_USER_PASSWORD = "a-real-users-own-password";

    private static final String MIGRATION_SCRIPT = "db/migration/V024__neutralize_seeded_dev_credential.sql";

    /** The V024 script verbatim, placeholder still unsubstituted. */
    private static final String CORRECTION_SCRIPT_TEMPLATE = readMigrationScript();

    private static String readMigrationScript() {
        try {
            return new ClassPathResource(MIGRATION_SCRIPT).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + MIGRATION_SCRIPT, e);
        }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    private JdbcTemplate jdbc;

    @BeforeEach
    void freshSchema() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("drop schema public cascade");
        jdbc.execute("create schema public");
    }

    /** Mirrors how Spring Boot configures Flyway, placeholder included. */
    private Flyway flyway(String removeSeedDevAccount) {
        return flyway(removeSeedDevAccount, null);
    }

    /**
     * @param target the highest version to apply, or null for the whole chain.
     *               Used to stop at V023, which is exactly where the hosted
     *               database sits today, before completing the upgrade.
     */
    private Flyway flyway(String removeSeedDevAccount, String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .baselineVersion("0")
                .placeholders(Map.of("remove_seed_dev_account", removeSeedDevAccount));
        if (target != null) {
            configuration = configuration.target(target);
        }
        return configuration.load();
    }

    /** The V024 script with its placeholder substituted, as Flyway would run it. */
    private String correctionScript(String removeSeedDevAccount) {
        assertThat(CORRECTION_SCRIPT_TEMPLATE)
                .as("the correction must stay placeholder-driven")
                .contains("${remove_seed_dev_account}");
        return CORRECTION_SCRIPT_TEMPLATE.replace("${remove_seed_dev_account}", removeSeedDevAccount);
    }

    private String seedHash() {
        return jdbc.queryForObject("select password_hash from users where email = ?", String.class, SEED_EMAIL);
    }

    private void assertSeedCredentialIsGone() {
        assertThat(seedHash())
                .as("the published development password must be gone")
                .isNull();
        assertThat(encoder.matches(PUBLISHED_SEED_PASSWORD, seedHash()))
                .as("a null hash is a failed match, which the login path turns into 401")
                .isFalse();
    }

    // ── 1. A fresh database, built with the production default ─────────────

    @Test
    void aFreshDatabaseBuiltWithTheDefaultHasNoUsableSeededCredential() {
        flyway("true").migrate();

        assertThat(seedHash()).isNull();
        assertThat(encoder.matches(PUBLISHED_SEED_PASSWORD, seedHash())).isFalse();

        // Neutralized, not deleted: the account and the data that hangs off it
        // are still there, which is what keeps this safe on a real deployment.
        assertThat(jdbc.queryForObject(
                "select display_name from users where email = ?", String.class, SEED_EMAIL))
                .isEqualTo("Dev User");
        assertThat(jdbc.queryForObject(
                "select auth_provider from users where email = ?", String.class, SEED_EMAIL))
                .isEqualTo("LOCAL");
        assertThat(jdbc.queryForObject("select count(*) from profiles", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from preference_sets", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }

    // ── 2. The hosted database's actual state: at V023, with real data ─────

    @Test
    void upgradingAnExistingDatabaseAtVersion023RemovesOnlyThePublishedCredential() {
        // Today's production state: every migration up to V023 applied, so the
        // seeded credential is live.
        flyway("true", "23").migrate();
        assertThat(seedHash())
                .as("the pre-fix state this migration exists to correct")
                .isEqualTo(PUBLISHED_SEED_HASH);
        assertThat(encoder.matches(PUBLISHED_SEED_PASSWORD, seedHash())).isTrue();

        // Real data written before the upgrade: a genuinely hashed local
        // account with a profile of its own, and a Firebase-provisioned account
        // (no local password at all).
        UUID seedProfile = jdbc.queryForObject("select id from profiles", UUID.class);
        UUID realUserId = UUID.randomUUID();
        String realUserHash = encoder.encode(REAL_USER_PASSWORD);
        jdbc.update("insert into users (id, email, password_hash, display_name) values (?, ?, ?, ?)",
                realUserId, REAL_USER_EMAIL, realUserHash, "Real User");
        jdbc.update("insert into profiles (id, user_id, headline) values (?, ?, ?)",
                UUID.randomUUID(), realUserId, "Real headline");
        jdbc.update("""
                insert into users (id, email, password_hash, display_name, firebase_uid, auth_provider)
                values (?, ?, null, ?, ?, 'FIREBASE')
                """, UUID.randomUUID(), "firebase.user@example.test", "Firebase User", "firebase-uid-1");

        List<Map<String, Object>> before = usersSnapshot();

        // The upgrade: the same jar, now able to reach V024.
        flyway("true").migrate();

        assertSeedCredentialIsGone();

        // The real account is untouched, still hashed, and still usable.
        assertThat(jdbc.queryForObject(
                "select password_hash from users where email = ?", String.class, REAL_USER_EMAIL))
                .isEqualTo(realUserHash);
        assertThat(encoder.matches(REAL_USER_PASSWORD, jdbc.queryForObject(
                "select password_hash from users where email = ?", String.class, REAL_USER_EMAIL)))
                .as("a real user's own password must keep working")
                .isTrue();

        // Exactly one credential changed, on exactly one row. (The snapshot
        // compares email and password_hash; the seeded row's updated_at is also
        // bumped by V024, which is bookkeeping rather than a credential.)
        List<Map<String, Object>> after = usersSnapshot();
        assertThat(after).hasSameSizeAs(before);
        assertThat(changedCells(before, after))
                .as("V024 must touch only the seeded row's password_hash")
                .containsExactly("dev@example.local.password_hash");

        // Foreign keys and the seeded profile are intact, so nothing cascaded.
        assertThat(jdbc.queryForObject("select count(*) from profiles", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select user_id from profiles where id = ?", UUID.class, seedProfile))
                .isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from users where firebase_uid = 'firebase-uid-1'", Integer.class))
                .isEqualTo(1);
    }

    // ── 3. Local development ───────────────────────────────────────────────

    @Test
    void localDevelopmentKeepsTheSeededCredentialItSignsInWith() {
        // application-local.yml sets the placeholder to false; the seeded login
        // that local inspection mode uses must survive that configuration.
        flyway("false").migrate();

        assertThat(seedHash())
                .as("local development keeps a working seeded login")
                .isEqualTo(PUBLISHED_SEED_HASH);
        assertThat(encoder.matches(PUBLISHED_SEED_PASSWORD, seedHash())).isTrue();
        assertThat(jdbc.queryForObject(
                "select auth_provider from users where email = ?", String.class, SEED_EMAIL))
                .isEqualTo("LOCAL");
    }

    // ── 4. Idempotence and scope ───────────────────────────────────────────

    @Test
    void theCorrectionIsIdempotentAndLeavesARotatedPasswordAlone() {
        flyway("true").migrate();

        // Re-running the correction against an already-corrected database is a
        // no-op, not an error — restores happen, and this must stay safe to run.
        assertThatCode(() -> jdbc.execute(correctionScript("true")))
                .as("re-running the correction must be harmless")
                .doesNotThrowAnyException();
        assertSeedCredentialIsGone();

        // A password the operator set themselves is not the published
        // credential, so it must be left exactly as it is.
        String rotatedHash = encoder.encode("a-password-the-operator-chose");
        jdbc.update("update users set password_hash = ? where email = ?", rotatedHash, SEED_EMAIL);

        jdbc.execute(correctionScript("true"));

        assertThat(seedHash())
                .as("V024 is scoped to the published hash and must not break a rotated credential")
                .isEqualTo(rotatedHash);
        assertThat(encoder.matches("a-password-the-operator-chose", seedHash())).isTrue();
    }

    @Test
    void aMisspelledPlaceholderFailsTheMigrationLoudly() {
        // The whole correction hinges on the placeholder, so a silent
        // misconfiguration must not be possible. Flyway itself refuses to run a
        // script whose placeholder has no value; the guard statement in the
        // script rejects a *wrong* value, such as a well-meaning "yes".
        assertThatCode(() -> jdbc.execute(correctionScript("yes")))
                .as("a wrongly valued placeholder must refuse to run")
                .hasMessageContaining("remove_seed_dev_account");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private List<Map<String, Object>> usersSnapshot() {
        return jdbc.queryForList("select email::text as email, password_hash from users order by email");
    }

    /** "email.column" for every cell that differs between two snapshots. */
    private List<String> changedCells(List<Map<String, Object>> before, List<Map<String, Object>> after) {
        List<String> changed = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(before.size(), after.size()); i++) {
            for (String column : List.of("email", "password_hash")) {
                Object beforeValue = before.get(i).get(column);
                Object afterValue = after.get(i).get(column);
                if (beforeValue == null ? afterValue != null : !beforeValue.equals(afterValue)) {
                    changed.add(before.get(i).get("email") + "." + column);
                }
            }
        }
        return changed;
    }
}
