package com.personal.jobagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationRecord;
import com.personal.jobagent.notifications.NotificationRepository;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.security.UserRepository;
import com.personal.jobagent.track.TrackRepository;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operator tool for Phase 2 (Oracle staging readiness): exercises the application's real
 * repositories and the database features they depend on against the restored Oracle copy
 * {@code robin_restore}, through the SSH tunnel with {@code sslmode=verify-full}.
 *
 * <p>It changes nothing that survives the run: every write happens inside a transaction that is
 * always rolled back, and no write touches a sequence-backed column (notifications use app-side
 * UUIDv7 ids), so no sequence advances. It refuses any database other than robin_restore.
 *
 * <p>Disabled unless {@code -Drobin.compat.url} is set. The password comes from the environment
 * variable ROBIN_EXTERNAL_DB_PASSWORD only (scripts/db-migration/oracle/run-oracle-staging-checks.ps1
 * sets it for the Maven process), never from a command line or a report.
 */
@EnabledIfSystemProperty(named = "robin.compat.url", matches = ".+",
        disabledReason = "operator tool: set -Drobin.compat.url to run it")
class ExternalDatabaseCompatibilityIT {

    private static final Pattern RESTORE_URL = Pattern.compile(
            "jdbc:postgresql://(127\\.0\\.0\\.1|localhost)(:\\d+)?/robin_restore\\?(.*&)?sslmode=verify-full(&.*)?");

    private final List<String> report = new ArrayList<>();

    @Test
    void applicationDatabaseFeaturesWorkAgainstTheRestoredOracleCopy() throws Exception {
        String url = System.getProperty("robin.compat.url");
        String envPassword = System.getenv("ROBIN_EXTERNAL_DB_PASSWORD");
        String password = envPassword != null && !envPassword.isEmpty() ? envPassword : System.getProperty("robin.compat.password", "");
        assertThat(url).as("only robin_restore through the loopback tunnel, verify-full").matches(RESTORE_URL);

        SingleConnectionDataSource ds = new SingleConnectionDataSource(url, "robin_app", password, true);
        SoftAssertions soft = new SoftAssertions();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

            // 1. Who and where: abort before anything else unless this is robin_restore as robin_app over TLS.
            Map<String, Object> who = jdbc.queryForMap("""
                    select current_database() as db, current_user as usr,
                           (select rolsuper from pg_roles where rolname = current_user) as su,
                           (select rolbypassrls from pg_roles where rolname = current_user) as bypass,
                           coalesce((select ssl from pg_stat_ssl where pid = pg_backend_pid()), false) as ssl,
                           coalesce((select version from pg_stat_ssl where pid = pg_backend_pid()), 'none') as tls,
                           current_setting('server_version_num')::int as ver,
                           current_setting('TimeZone') as tz, now() as server_now
                    """);
            assertThat(who.get("db")).as("connected database").isEqualTo("robin_restore");
            line("PASS", "connection", "db=%s user=%s superuser=%s bypassrls=%s ssl=%s %s server_version_num=%s TimeZone=%s",
                    who.get("db"), who.get("usr"), who.get("su"), who.get("bypass"), who.get("ssl"), who.get("tls"), who.get("ver"), who.get("tz"));
            soft.assertThat(who.get("usr")).as("role").isEqualTo("robin_app");
            soft.assertThat(who.get("su")).as("superuser").isEqualTo(false);
            soft.assertThat(who.get("bypass")).as("bypassrls").isEqualTo(false);
            soft.assertThat(who.get("ssl")).as("TLS").isEqualTo(true);
            soft.assertThat((Integer) who.get("ver")).as("PostgreSQL 17").isBetween(170000, 179999);
            Instant serverNow = ((Timestamp) who.get("server_now")).toInstant();
            long skew = Math.abs(Duration.between(serverNow, Instant.now()).toSeconds());
            check(soft, skew < 300, "clock_skew", "server now() vs this machine: %d s", skew);

            // 2. Flyway V052 as the application ships it.
            Map<String, Object> fw = jdbc.queryForMap("""
                    select count(*)::int as rows, (count(*) filter (where success))::int as ok,
                           max(version) filter (where success) as maxv, (count(*) filter (where type = 'BASELINE'))::int as baseline
                    from flyway_schema_history
                    """);
            Map<String, Object> v052 = jdbc.queryForMap("""
                    select to_regclass('public.outbox_exclusion_batches') is not null as batches,
                           to_regclass('public.outbox_exclusions') is not null as exclusions,
                           to_regclass('public.outbox_pending_non_discovery_idx') is not null as idx,
                           (select count(*)::int from pg_proc where proname in ('outbox_exclude_pending_discovery', 'outbox_revert_discovery_exclusion')) as fns
                    """);
            check(soft, Integer.valueOf(53).equals(fw.get("rows")) && Integer.valueOf(53).equals(fw.get("ok")) && "052".equals(fw.get("maxv"))
                            && Boolean.TRUE.equals(v052.get("batches")) && Boolean.TRUE.equals(v052.get("exclusions"))
                            && Boolean.TRUE.equals(v052.get("idx")) && Integer.valueOf(2).equals(v052.get("fns")),
                    "flyway_v052", "history rows=%s successful=%s current=%s baseline=%s; V052 tables=%s/%s index=%s functions=%s",
                    fw.get("rows"), fw.get("ok"), fw.get("maxv"), fw.get("baseline"), v052.get("batches"), v052.get("exclusions"), v052.get("idx"), v052.get("fns"));

            // 3. Sequences: the next value of every column-owned sequence must be above the column's max.
            List<Map<String, Object>> seqs = jdbc.queryForList("""
                    select s.relname as seq, t.relname as tbl, a.attname as col
                    from pg_class s
                    join pg_namespace n on n.oid = s.relnamespace
                    join pg_depend d on d.objid = s.oid and d.classid = 'pg_class'::regclass and d.deptype in ('a', 'i')
                    join pg_class t on t.oid = d.refobjid
                    join pg_attribute a on a.attrelid = t.oid and a.attnum = d.refobjsubid
                    where s.relkind = 'S' and n.nspname = 'public'
                    order by 1
                    """);
            int seqOk = 0;
            for (Map<String, Object> s : seqs) {
                String seq = (String) s.get("seq"), tbl = (String) s.get("tbl"), col = (String) s.get("col");
                Map<String, Object> st = jdbc.queryForMap("select last_value, is_called from public." + ident(seq));
                Long increment = jdbc.queryForObject("select increment_by from pg_sequences where schemaname = 'public' and sequencename = ?", Long.class, seq);
                Long max = jdbc.queryForObject("select max(" + ident(col) + ")::bigint from public." + ident(tbl), Long.class);
                long last = ((Number) st.get("last_value")).longValue();
                long next = Boolean.TRUE.equals(st.get("is_called")) ? last + (increment == null ? 1 : increment) : last;
                boolean ok = max == null || next > max;
                if (ok) seqOk++;
                check(soft, ok, "sequence_" + seq, "%s.%s: max=%s, last_value=%d is_called=%s, next=%d", tbl, col, max, last, st.get("is_called"), next);
            }
            line("INFO", "sequences", "%d column-owned sequence(s), %d safe for new inserts", seqs.size(), seqOk);

            // 4. Indexes and constraints as restored.
            Map<String, Object> cat = jdbc.queryForMap("""
                    select (select count(*)::int from pg_index i join pg_class c on c.oid = i.indexrelid join pg_namespace n on n.oid = c.relnamespace
                             where n.nspname = 'public' and (not i.indisvalid or not i.indisready)) as bad_indexes,
                           (select count(*)::int from pg_constraint c join pg_namespace n on n.oid = c.connamespace
                             where n.nspname = 'public' and not c.convalidated) as not_valid,
                           (select count(*)::int from pg_trigger t join pg_class c on c.oid = t.tgrelid join pg_namespace n on n.oid = c.relnamespace
                             where n.nspname = 'public' and not t.tgisinternal and t.tgenabled = 'D') as disabled_triggers
                    """);
            check(soft, Integer.valueOf(0).equals(cat.get("bad_indexes")) && Integer.valueOf(0).equals(cat.get("not_valid"))
                            && Integer.valueOf(0).equals(cat.get("disabled_triggers")),
                    "catalog", "invalid or unready indexes=%s, NOT VALID constraints=%s, disabled triggers=%s",
                    cat.get("bad_indexes"), cat.get("not_valid"), cat.get("disabled_triggers"));

            // 5. Generated ids: the application's UUIDv7 and the database's own generator.
            // UuidV7 orders by its 48-bit millisecond timestamp; within one millisecond the rest is
            // random (RFC 9562 allows this), so the embedded timestamps must never go backwards,
            // while two ids from the same millisecond may compare either way.
            long before = System.currentTimeMillis();
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < 1000; i++) ids.add(UuidV7.generate());
            long after = System.currentTimeMillis();
            boolean versions = ids.stream().allMatch(u -> u.version() == 7 && u.variant() == 2);
            boolean nonDecreasing = true;
            for (int i = 1; i < ids.size(); i++) {
                if ((ids.get(i).getMostSignificantBits() >>> 16) < (ids.get(i - 1).getMostSignificantBits() >>> 16)) nonDecreasing = false;
            }
            long firstMs = ids.get(0).getMostSignificantBits() >>> 16, lastMs = ids.get(ids.size() - 1).getMostSignificantBits() >>> 16;
            boolean clockAligned = firstMs >= before && lastMs <= after;
            boolean distinct = new HashSet<>(ids).size() == ids.size();
            UUID dbUuid = jdbc.queryForObject("select gen_random_uuid()", UUID.class);
            check(soft, versions && nonDecreasing && clockAligned && distinct && dbUuid != null && dbUuid.version() == 4,
                    "generated_ids", "1000 UuidV7.generate(): version 7/variant 2=%s, timestamps non-decreasing=%s, timestamps within the generation window=%s, all distinct=%s; gen_random_uuid() version=%d",
                    versions, nonDecreasing, clockAligned, distinct, dbUuid == null ? -1 : dbUuid.version());

            // 6. Text search: the generated tsvector column and the 'english' configuration.
            Map<String, Object> ts = jdbc.queryForMap("""
                    select (select count(*)::int from jobs) as jobs,
                           (select count(*)::int from jobs where search_vector is null) as null_vectors,
                           to_tsvector('english', 'Senior Engineers') @@ plainto_tsquery('english', 'engineer') as stemming
                    """);
            check(soft, Integer.valueOf(0).equals(ts.get("null_vectors")) && Boolean.TRUE.equals(ts.get("stemming")),
                    "full_text_search", "jobs=%s with a null search_vector=%s; 'english' stemming works=%s", ts.get("jobs"), ts.get("null_vectors"), ts.get("stemming"));

            // 7. The application's repositories, read paths, for the profile with the most matches.
            UUID profileId = jdbc.queryForObject("select profile_id from job_matches group by profile_id order by count(*) desc limit 1", UUID.class);
            UUID userId = jdbc.queryForObject("select user_id from profiles where id = ?", UUID.class, profileId);
            JobRepository jobs = new JobRepository(jdbc, mapper);
            NotificationRepository notifications = new NotificationRepository(jdbc, mapper);
            ProfileRepository profiles = new ProfileRepository(jdbc, mapper);
            PreferenceSetRepository preferences = new PreferenceSetRepository(jdbc, mapper);
            UserRepository users = new UserRepository(jdbc);
            TrackRepository track = new TrackRepository(jdbc, mapper);
            boolean profileFound = profiles.findById(profileId).isPresent();
            boolean userFound = users.findById(userId).isPresent();
            boolean prefsFound = preferences.findActiveByProfileId(profileId).isPresent();
            int recent = notifications.findRecent(profileId, 20).size();
            long unread = notifications.countUnread(profileId);
            long allNotifications = notifications.countAll(profileId);
            Long directNotifications = jdbc.queryForObject("select count(*) from notifications where profile_id = ?", Long.class, profileId);
            int reviewQueue = track.reviewQueue(profileId, 20).size();
            UUID matchedJob = jdbc.queryForObject("select job_id from job_matches where profile_id = ? order by score desc, job_id limit 1", UUID.class, profileId);
            Optional<JobRepository.JobMatch> match = jobs.findMatch(profileId, matchedJob);
            check(soft, profileFound && userFound && match.isPresent() && directNotifications != null && allNotifications == directNotifications,
                    "repository_reads", "profile found=%s, user found=%s, active preferences=%s, notifications recent=%d unread=%d all=%d (direct count %s), track review queue=%d, best match score=%s",
                    profileFound, userFound, prefsFound, recent, unread, allNotifications, directNotifications, reviewQueue,
                    match.map(m -> String.valueOf(m.score())).orElse("none"));

            // 8. Keyset pagination of the real feed, every sort: no row skipped, none repeated.
            Long feedTotal = jdbc.queryForObject("select count(*) from jobs where deleted_at is null", Long.class);
            for (String sort : List.of("recent", "match", "posted")) {
                Set<UUID> seen = new HashSet<>();
                int pages = 0, duplicates = 0;
                String cursor = null;
                do {
                    JobRepository.FeedPage page = jobs.findFeed(profileId, null, null, JobRepository.FeedFilters.none(), sort, 100, cursor);
                    for (JobRepository.FeedItem item : page.items()) {
                        if (!seen.add(item.job().id())) duplicates++;
                    }
                    cursor = page.nextCursor();
                    pages++;
                } while (cursor != null && !cursor.isBlank() && pages < 1000);
                check(soft, duplicates == 0 && feedTotal != null && seen.size() == feedTotal,
                        "pagination_" + sort, "%d pages of 100: %d distinct jobs, %d repeated; expected %s", pages, seen.size(), duplicates, feedTotal);
            }
            int searchHits = jobs.findFeed(profileId, null, "engineer", JobRepository.FeedFilters.none(), "recent", 50, null).items().size();
            line("INFO", "feed_search", "findFeed(q=\"engineer\") returned %d job(s) on the first page", searchHits);

            // 9. Writes through the real repository, JSON, timestamps, dedup, constraints: always rolled back.
            Long notificationsBefore = jdbc.queryForObject("select count(*) from notifications", Long.class);
            String dedup = "phase2-compat-" + UUID.randomUUID();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("probe", "phase2 Oracle compatibility");
            metadata.put("unicode", "naïve café – ✓ 日本");
            metadata.put("number", 42.5);
            metadata.put("flag", true);
            metadata.put("list", List.of(1, 2, 3));
            metadata.put("nested", Map.of("k", "v"));
            Instant precise = Instant.parse("2026-11-12T14:30:15.123456Z");
            Map<String, Object> writeResults = tx.execute(status -> {
                status.setRollbackOnly();
                Map<String, Object> r = new LinkedHashMap<>();
                UUID id = UuidV7.generate();
                NotificationRecord saved = notifications.insertIfAbsent(new NotificationRecord(id, "INFO", "SYSTEM",
                        "Phase 2 compatibility probe", "rolled back", null, dedup, metadata, matchedJob, null, null, null, profileId));
                r.put("inserted", saved != null);
                r.put("json_round_trip", saved != null && metadata.equals(saved.metadata()));
                r.put("created_at_set", saved != null && saved.createdAt() != null
                        && Math.abs(Duration.between(saved.createdAt(), serverNow).toMinutes()) < 10);
                r.put("jsonb_operator", metadata.get("unicode").equals(jdbc.queryForObject("select metadata->>'unicode' from notifications where id = ?", String.class, id)));
                r.put("jsonb_containment", Boolean.TRUE.equals(jdbc.queryForObject("select metadata @> '{\"flag\": true}'::jsonb from notifications where id = ?", Boolean.class, id)));
                jdbc.update("update notifications set read_at = ? where id = ?", Timestamp.from(precise), id);
                Instant back = jdbc.queryForObject("select read_at from notifications where id = ?", Timestamp.class, id).toInstant();
                r.put("timestamp_microseconds", precise.equals(back));
                r.put("dedup_second_insert_skipped", notifications.insertIfAbsent(new NotificationRecord(UuidV7.generate(), "INFO", "SYSTEM",
                        "duplicate", "rolled back", null, dedup, Map.of(), null, null, null, null, profileId)) == null);
                r.put("visible_inside_transaction", notifications.findById(profileId, id).isPresent());
                r.put("id", id);
                return r;
            });
            UUID probeId = (UUID) writeResults.get("id");
            boolean goneAfterRollback = notifications.findById(profileId, probeId).isEmpty();
            check(soft, Boolean.TRUE.equals(writeResults.get("inserted")) && Boolean.TRUE.equals(writeResults.get("json_round_trip"))
                            && Boolean.TRUE.equals(writeResults.get("created_at_set")) && Boolean.TRUE.equals(writeResults.get("jsonb_operator"))
                            && Boolean.TRUE.equals(writeResults.get("jsonb_containment")) && Boolean.TRUE.equals(writeResults.get("timestamp_microseconds"))
                            && Boolean.TRUE.equals(writeResults.get("dedup_second_insert_skipped")) && Boolean.TRUE.equals(writeResults.get("visible_inside_transaction"))
                            && goneAfterRollback,
                    "repository_write_rollback", "%s; gone after rollback=%s", writeResults, goneAfterRollback);

            // 10. Constraint enforcement, each in its own transaction that is rolled back.
            String unique = violation(tx, jdbc, """
                    insert into notifications (id, severity, category, title, body, dedup_key, metadata, created_at, profile_id)
                    values (?, 'INFO', 'SYSTEM', 'a', 'a', 'phase2-dup', '{}'::jsonb, now(), ?),
                           (?, 'INFO', 'SYSTEM', 'b', 'b', 'phase2-dup', '{}'::jsonb, now(), ?)
                    """, UuidV7.generate(), profileId, UuidV7.generate(), profileId);
            String foreignKey = violation(tx, jdbc, """
                    insert into notifications (id, severity, category, title, body, metadata, created_at, profile_id, job_id)
                    values (?, 'INFO', 'SYSTEM', 'fk', 'fk', '{}'::jsonb, now(), ?, ?)
                    """, UuidV7.generate(), profileId, UUID.randomUUID());
            String notNull = violation(tx, jdbc, """
                    insert into notifications (id, severity, category, title, body, metadata, created_at, profile_id)
                    values (?, 'INFO', 'SYSTEM', 'nn', 'nn', null, now(), ?)
                    """, UuidV7.generate(), profileId);
            check(soft, "23505".equals(unique) && "23503".equals(foreignKey) && "23502".equals(notNull),
                    "constraints_enforced", "unique=%s foreign_key=%s not_null=%s (expected 23505, 23503, 23502)", unique, foreignKey, notNull);

            // 11. A thrown exception rolls the transaction back.
            UUID thrownId = UuidV7.generate();
            boolean thrown = false;
            try {
                tx.executeWithoutResult(status -> {
                    notifications.insertIfAbsent(new NotificationRecord(thrownId, "INFO", "SYSTEM", "throw probe", "rolled back",
                            null, null, Map.of(), null, null, null, null, profileId));
                    throw new IllegalStateException("rollback probe");
                });
            } catch (IllegalStateException expected) {
                thrown = true;
            }
            boolean thrownGone = notifications.findById(profileId, thrownId).isEmpty();
            check(soft, thrown && thrownGone, "exception_rollback", "exception propagated=%s, row absent afterwards=%s", thrown, thrownGone);

            // 12. Nothing survived the run.
            Long notificationsAfter = jdbc.queryForObject("select count(*) from notifications", Long.class);
            check(soft, notificationsBefore != null && notificationsBefore.equals(notificationsAfter),
                    "no_persistent_change", "notifications before=%s after=%s", notificationsBefore, notificationsAfter);
        } finally {
            ds.destroy();
            writeReport();
        }
        soft.assertAll();
    }

    /** Runs one statement expected to violate a constraint, in a transaction that is always rolled back. */
    private static String violation(TransactionTemplate tx, JdbcTemplate jdbc, String sql, Object... args) {
        try {
            tx.executeWithoutResult(status -> {
                status.setRollbackOnly();
                jdbc.update(sql, args);
            });
            return "none";
        } catch (DataAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            return cause instanceof SQLException sql2 ? sql2.getSQLState() : cause.getClass().getSimpleName();
        }
    }

    private static String ident(String name) {
        if (!name.matches("[a-z_][a-z0-9_]*")) throw new IllegalArgumentException("unexpected identifier " + name);
        return "\"" + name + "\"";
    }

    private void check(SoftAssertions soft, boolean ok, String name, String format, Object... args) {
        line(ok ? "PASS" : "FAIL", name, format, args);
        soft.assertThat(ok).as(name + ": " + String.format(format, args)).isTrue();
    }

    private void line(String status, String name, String format, Object... args) {
        String text = status + "|" + name + "|" + String.format(format, args);
        report.add(text);
        System.out.println(text);
    }

    private void writeReport() throws Exception {
        String path = System.getProperty("robin.compat.report");
        long fails = report.stream().filter(l -> l.startsWith("FAIL|")).count();
        report.add(0, (fails == 0 ? "ROBIN_COMPAT PASS" : "ROBIN_COMPAT FAIL") + " checks=" + report.stream().filter(l -> !l.startsWith("INFO|")).count()
                + " failed=" + fails + " at " + Instant.now().truncatedTo(ChronoUnit.SECONDS));
        if (path != null && !path.isBlank()) {
            Files.writeString(Path.of(path), String.join(System.lineSeparator(), report) + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }
}
