# ROBIN — Database migration, Phase 2: backup-first plan

**Status: PLANNING.** Stage A is complete: the backup and restore-test tooling is built, and it passed a dry run on synthetic data on 6 Nov (§2). **No production backup exists yet.**

**Revised on 7 Nov 2026 (§10).** Supabase will not be upgraded, the target is PostgreSQL 17 on Oracle Cloud, planned downtime is acceptable, and the egress quota must not be exceeded. Where §10 differs from §0–§9, §10 applies. The earlier sections are kept unchanged as the record. Nothing in this document has been executed against production except the read-only `SELECT`s listed in §1, with their UTC times. Migration readiness is **not** claimed. It cannot be claimed until a backup has been restored into an isolated PostgreSQL 17 instance and verified (Stage C).

**Prepared:** 2026-11-06. Sources: the Supabase SQL editor (read-only catalog and aggregate queries as `postgres`), the Supabase usage page, source inspection, and vendor documentation fetched the same day. This repository is public, so identifiers that would locate the production project are deliberately left out: the project ref, organisation id, pooler host, user names and connection strings.

Stages are planned separately and will be implemented and approved separately:

- **A.** Backup preparation (§2)
- **B.** Backup execution and verification (§3)
- **C.** Isolated restoration test (§4)
- **D.** Oracle infrastructure planning (§5)
- **E.** Cutover and rollback planning (§6)

## 0. Why the backup comes first

| Fact | Evidence |
|---|---|
| The organisation's grace period ends on **12 Nov 2026**. | Usage-page notice, 6 Nov: *"Your organization went over its quota in the previous billing cycle (Egress Exceeded). You can continue with your projects until your grace period ends on 12 Nov, 2026 … If restrictions are applied, requests to your project will return a 402 status code."* |
| It is undocumented whether direct Postgres connections survive the restriction. | The Supabase billing and cost-control docs (fetched 6 Nov) don't say. **Planning assumption:** after 12 Nov the database may be unreachable. |
| The organisation stays over quota whatever happens to egress. | Current cycle (18 Oct–18 Nov): database size 1.616 / 0.5 GB (323 %); egress 3.50 / 5 GB (70 %). |
| The disk is the immediate hazard. | Supabase docs (database size page): the Free-plan disk is fixed at 2 GB and *"does not autoscale"*. *"When disk usage reaches 95%, Supabase puts your database into read-only mode."* Read-only mode is *"turned off automatically"* below 95 %. *"If the disk becomes full, Postgres shuts down."* |
| The remaining headroom is small. | An earlier analytic query in this phase failed with SQLSTATE 53100 `No space left on device` while writing temp files. Since the 2 Nov restart the server has written 23 temp files totalling 249 MiB (`pg_stat_database`). |

**Target:** Stages A, B and C complete by **9 Nov 2026**, three days before the grace period ends.

## 1. Evidence gathered (completed investigations)

All queries were single `SELECT` statements. Before each run, a check confirmed the statement was unchanged and contained no write keywords. On the large tables the queries used only `count`/`sum` aggregates or partial-index lookups. Nothing used `DISTINCT`, joins over large tables, or sorts that could spill to disk.

### 1.1 Production write state: P0 root cause confirmed

Two catalog-only samples were taken on 6 Nov at 13:38:35 and 13:45:50 UTC.

- `default_transaction_read_only = on`, with source `configuration file`.
  - `pg_conf_load_time()` returned 13:31:17.656 UTC.
  - The only role-level override found is Supabase's own `supabase_read_only_user`.
- The newest outbox row was created at 13:31:17.445 UTC, 0.2 s before that configuration reload.
- Between the two samples, no row was inserted, updated or deleted anywhere: the `pg_stat_database` tuple counters did not change.
- Between the samples there were +6,667 committed transactions, all reads.
- There were also **+5,036 rolled-back transactions, about 11.6 per second**. The running backend keeps attempting writes and failing.
- Supabase documents that read-only mode switches itself off below 95 % disk use, and that *"background jobs and scheduled tasks resume writes automatically"*. That explains the pattern we see: writes succeed in windows, then read-only mode returns.
  - Pending `job.discovered` events rose from 770,720 (about 13:11) to 771,389 (about 13:27).
  - The dispatcher also moved its head forward: the oldest pending event changed from one created on 2 Nov at 18:45:50 to one created at 18:46:02. Then the window closed at 13:31:17.
  - 60,488 of the still-pending events were created on 6 Nov.
- **Conclusion:** the P0 *"production database is rejecting writes"* is caused by Supabase Free-plan read-only mode. The 2 GB disk sits at the 95 % threshold. Phase 1 had already confirmed `default_transaction_read_only = on`; the toggling is new.
- What filled the disk: the database restarted at 2 Nov 18:07:09 UTC. Since then the cumulative counters show:
  - 60,113 job inserts and 716,083 job updates;
  - 774,110 outbox inserts;
  - 4,900 consumed-event inserts.
- Table data (heap plus TOAST) and index sizes for the largest tables:

  | Table | Table data | Indexes |
  |---|---:|---:|
  | jobs | 545.0 MB | 107.4 MB |
  | outbox_events | 464.8 MB | 73.2 MB |
  | consumed_events | 137.8 MB | 105.7 MB |
  | job_source_observations | 32.3 MB | 57.4 MB |
  | job_identity_keys | 22.7 MB | 24.9 MB |

  Every other table is under 7 MB. `pg_database_size` = 1,600,629,907 bytes.

### 1.2 Egress, the budget for the dump

The usage page shows every byte as *Shared Pooler Egress*, which is the backend reaching the database through Supavisor.

| Day | Egress | Still-pending outbox rows created that day | Database state |
|---|---:|---:|---|
| 2 Nov | 76.8 MB | 74,210 | restarted 18:07 |
| 3 Nov | 379.4 MB | 318,690 | mostly writable |
| 4 Nov | 408.1 MB | 316,160 | mostly writable |
| 5 Nov | 1.82 GB | 1,864 | mostly read-only |
| 6 Nov (to about 13:00) | 608.8 MB | 60,488 | mixed |

A read-only day costs about 4.5 times the egress of a writable day, because retry loops re-read their inputs on every cycle. For example, the outbox dispatcher re-reads a 50-row batch every 2 s and fails on the first event.

The dump's own egress is **estimated** at 1.0–1.6 GB:

- About 1.21 GB of table data sits on disk.
- Compressed TOAST values travel uncompressed.
- The generated column `jobs.search_vector` is not dumped.

About 1.5 GB of allowance remains, and the backend alone uses 0.4–1.8 GB a day. So this cycle will pass 5 GB whether or not we take a backup.

The Free plan is not billed for overage, and database size alone already keeps the organisation over quota. **The backup is not to be delayed to save egress.** It should still succeed on the first attempt, which is why Stage A includes a full dry run.

### 1.3 Backup scope (constraint 1)

Catalog pre-flight, 6 Nov 13:52:51 UTC:

- **Schemas with tables:**

  | Schema | Tables | Size |
  |---|---:|---:|
  | public | 70 | 1.21 GB |
  | auth | 27 | 213 KB |
  | storage | 8 | 98 KB |
  | realtime | 3 | 16 KB |
  | vault | 1 | 8 KB |

- **Readability:** `postgres` can read every table and all 3 sequences; none is unreadable.
- **Blockers:** there are 0 large objects, 0 replication slots, 0 prepared transactions, and 0 transactions older than 5 minutes.
- **No application data outside `public`:**
  - `auth.users` = 0;
  - `storage.objects` = 0 and `storage.buckets` = 0;
  - `vault.secrets` = 0, so no Supabase Vault root key is needed to use the backup.
- **Extensions:**
  - `citext` 1.6 in `public`;
  - `pgcrypto` 1.3, `uuid-ossp` 1.1 and `pg_stat_statements` 1.11 in `extensions`;
  - `supabase_vault` 0.3.1 in `vault`;
  - `plpgsql`.
- **Supabase-specific objects:** one publication (`supabase_realtime`) and seven Supabase event triggers.
- **Ownership:** `postgres` owns every relation in `public`.
- **Defaults:** no column default in `public` references the `extensions` schema.
- **Generated columns:** the only one is `jobs.search_vector`.
- **pgcrypto at runtime:** none. Only migration V027 used `digest()`, in a one-off backfill. `gen_random_uuid()` is part of PostgreSQL core.

**Decision:**

- Dump the **whole database**, which is the complete backup.
- Restore-test the application scope (`public`) into vanilla PostgreSQL 17.
- Prove that the data blocks of the Supabase-internal schemas are readable.

### 1.4 RLS enforcement for the production application role (constraint 8)

Catalog-only queries ran at about 13:24 UTC on 6 Nov. Nothing was changed.

- RLS is enabled on all 70 tables in `public`.
  - 0 tables use `FORCE ROW LEVEL SECURITY`.
  - 0 policies exist.
  - All 70 tables are owned by `postgres`.
- The `postgres` role has `rolsuper = false` and `rolbypassrls = true`. `row_security_active('public.users')` returns `false` for `postgres`.
- The backend connects as `postgres` through Supavisor, holding 10 sessions.
- **Conclusion: RLS is enabled but not enforced for the production application role, for two independent reasons:**
  - `postgres` owns the tables, and owners bypass RLS that is not forced.
  - `postgres` has `BYPASSRLS`.

  With no policies, RLS today only denies Supabase's Data API roles (`anon` and `authenticated`, via the PostgREST session seen in `pg_stat_activity`). Tenant isolation in Robin is enforced by owner-scoped application SQL, not by RLS.
- **For the backup:** pg_dump sets `row_security = off` and throws an error if its role cannot bypass RLS (PostgreSQL docs, `--enable-row-security`). `postgres` bypasses RLS, so every row is dumped.
- **For the target:** `robin_app` will own every restored object. Owners bypass RLS, there is no `FORCE`, and no `BYPASSRLS` grant is needed. The effective behaviour is identical, and RLS stays enabled. The Oracle backup job must run as the owner or as a role with `BYPASSRLS`. **RLS is not disabled anywhere in this plan.**

### 1.5 The pending outbox events (constraint 7)

These are read-only aggregates from 13:25–13:30 UTC on 6 Nov. Heavier analysis is deferred to the restored copy (§4).

- **Count:** 771,412 pending. Phase 1 counted 770,720 pending `job.discovered` events at about 13:11. The count grew during the writable window that ended at 13:31:17.

  | Event type | Pending |
  |---|---:|
  | `job.discovered` | 771,389 |
  | `job.matched` | 11 |
  | `verification.completed` | 11 |
  | `signup.completed` | 1 |

  The `job.discovered` events break down as TOUCHED 656,694, UPDATED 56,355 and INSERTED 58,340.
- **Age:** the oldest pending event was created on 2 Nov at 18:46:02 UTC (sequence 898,346). The newest was created on 6 Nov at 13:31:17 UTC (sequence 1,670,005). The per-day creation counts are in §1.2.
- **Status:**
  - Every pending row has `attempt_count = 0` and no `last_error`, so none reached the DLQ (`MAX_ATTEMPTS = 5`).
  - As of about 13:27, 898,295 events had been published. They were created between 23 Sep and 2 Nov 18:45, and the latest `published_at` was 6 Nov 07:00. A few more were published before 13:31:17, because the head moved (§1.1).
  - Consumption: `notification-fanout` 898,295 and `application-pipeline` 898,264. `application-pipeline` subscribes only to `job.*` events, which accounts for the gap.
- **Retries: `attempt_count = 0` does not mean the events were never tried.**
  - `OutboxProcessor` records a failure with an `UPDATE` in its `catch` block.
  - In read-only mode that `UPDATE` fails too. The exception escapes `dispatchPending`, and the same head event is retried 2 s later with no trace left.
  - So the head event has most likely been attempted thousands of times without a record.
- **Throughput:**
  - Since the 2 Nov restart there have been 774,110 outbox inserts but only 2,450 outbox updates and 4,900 consumed-event inserts. That means at most about 2,450 events were dispatched, each to both consumers.
  - 4,900 = 2 × 2,450, which is consistent with no pending event having been half-consumed.
  - On writable days, events were created at about 317,000 a day.
  - **Hypothesis, to be checked on the restored copy:** every `@Scheduled` job shares Spring's default scheduler, which has a single thread because no pool size is configured. A long discovery sweep would therefore block the 2-second dispatcher.
- **Idempotency (from the code):**
  - `InProcessEventPublisher` skips any consumer that has already consumed an event, and records consumption with `ON CONFLICT DO NOTHING`.
  - Handler writes are idempotent:
    - `applications`: `ON CONFLICT (profile_id, job_id) DO NOTHING` for live statuses;
    - `job_matches`: upsert;
    - `application_decisions`: `ON CONFLICT`;
    - `skill_similarity_cache`: upsert;
    - notifications: `dedup_key`.
  - `onJobIngested` ignores the event's action and re-scores the job's *current* state. For any one job, only its newest pending `job.discovered` event matters. The 656,694 TOUCHED events are re-observations of jobs that had not changed.
- **Side effects of a replay:**
  - Notifications are in-app only; the notifications package has no e-mail or push channel.
  - `job.discovered` notifications are unowned and deduplicated per job.
  - However, re-scoring can emit `job.matched`. That leads to application decisions, which can create applications and automation plans.
  - **Before any resume, every profile's autonomy policy must be checked on the restored copy. No employer is contacted without explicit approval.**
- **Resume options.** The decision comes later. No data changes without approval, and nothing is changed on Supabase.
  - **R1, replay in order.** Correct, but it means about 771,000 re-scorings. These include LLM skill-similarity calls for pairs not yet cached. At the observed throughput this takes days to weeks.
  - **R2, coalesced replay.** Process the 23 events that are not `job.discovered` first. Then process only the newest `job.discovered` event per job: at most about 61,000, with the exact number taken from the restored copy. Superseded events are marked published with an audit marker. **Rows are kept, not deleted.**
  - **R3, hold.** Keep dispatch paused on the new database until the emission volume and the scheduler issue are fixed, then run R2.
  - **Recommendation: R3, then R2.** Today, starting the backend against *any* database resumes dispatch at once: `fixedDelay` runs at startup and there is no dispatch switch. A dispatch kill switch is therefore a cutover prerequisite (§6).
- **What "preserve all pending events" means in this plan:**
  - Every row pending at the dump snapshot is in the archive.
  - It is restored unchanged, then counted and fingerprinted against the source at the same snapshot.
  - Nothing is deleted or marked.

### 1.6 Local tooling (input to Stage A)

- **PostgreSQL client tools:** versions 15 and 18 are installed. Neither is usable:
  - pg_dump 15 cannot dump a 17 server.
  - The output of pg_dump 18 is *"not guaranteed"* to load into 17 (PostgreSQL docs).
  - **pg_dump 17 is required.**
- **Docker Desktop** is installed but stopped. The image `postgres:17.11-bookworm` provides pg_dump and pg_restore 17.11 and the isolated PostgreSQL 17 server, all in one image.
- **Other tools:** gpg 2.4.5 (Git Bash) and OpenSSL 3.2.3. C: has 72 GB free. The machine has 15.2 GB of RAM, of which 2 GB was free at the time of the check.
- **Storage hazards:**
  - Documents and Desktop are redirected to OneDrive.
  - C: reports no BitLocker protection.
  - **Plaintext backup data therefore stays in RAM (container `tmpfs`) and never lands on the host disk.**

### 1.7 PostgreSQL 17 on ARM64, Oracle resources and CI (constraint 9)

- **PostgreSQL 17:** current minor 17.11, supported until 8 Nov 2029 (postgresql.org/support/versioning).
- **Docker official image:** the tags `17.11-bookworm` and `17.11` (trixie) list `arm64v8` as well as `amd64` (docker-library/official-images).
- **PGDG apt:**
  - The `noble-pgdg` Release file (dated 5 Nov 2026) lists `amd64 arm64 ppc64el`.
  - The package pool contains `postgresql-17_17.11-1.pgdg24.04+2_arm64.deb` (13 Aug 2026).
  - The contrib extensions Robin needs (`citext`, `pgcrypto`) ship with the server package.
- **GitHub Actions:** hosted `ubuntu-24.04-arm` runners are *"free and unlimited on public repositories"*, and this repository is public.
- **Oracle Always Free** (docs fetched 6 Nov):
  - **Compute:** A1 gets 1,500 OCPU-hours and 9,000 GB-hours a month, which is *"2 OCPUs and 12 GB of memory"* for Always Free tenancies.
  - **Storage:** 200 GB of block storage (boot and block volumes combined) and 5 volume backups.
  - **Object Storage:** 20 GB (Always Free-only accounts) and 50,000 requests a month.
  - **Region:** Always Free compute must be created in the home region.
  - **Idle reclamation:** an instance can be reclaimed when, over 7 days, CPU p95, network and (on A1) memory are all below 20 %. The page doesn't say whether Pay As You Go accounts are exempt.
  - **Capacity:** "Out of host capacity" errors are possible: retry, try another availability domain, or upgrade to Pay As You Go.
- **CI today:**
  - 40 Testcontainers integration tests pin `postgres:16`.
  - `infra/docker-compose.yml` uses `postgres:16`.
  - CI runs on `ubuntu-latest` (x86_64) while production runs 17.6. See §7.

## 2. Stage A: backup preparation (complete, 6 Nov 2026)

### Result

No production system was contacted in Stage A. The tooling is in `scripts/db-migration/` (commit `ea17205`), and its README explains how to use it.

- **A1. Tooling.**
  - The Docker engine is 29.0.1.
  - The image is `postgres:17.11-bookworm`, id `sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994`. It contains pg_dump, pg_restore, psql and postgres 17.11, plus gpg 2.2.40. The scripts refuse any other image id.
  - Docker Desktop first failed to start because of a stale socket left from its 3 Nov session. I moved the folder aside to `%LOCALAPPDATA%\Docker\run.stale-20261103`; nothing was deleted.
- **A2. Location.**
  - `C:\RobinBackups` exists.
  - The scripts refuse any folder inside OneDrive or inside the repository.
  - Dry-run output goes to `C:\RobinBackups\dryrun\`.
- **A3. Scripts.** The implementation refines the design below: the orchestration is in Windows PowerShell 5.1, and every PostgreSQL and gpg step runs inside the pinned container. As a result, no binary data crosses a host pipe and encryption happens inside the container.
  - **Backup:** `backup-supabase.ps1` with `container/backup.sh` and `sql/snapshot-session.sql`. This is the Stage B procedure in §3:
    - one read-only snapshot covers the source facts, the fingerprints, the inventory and `pg_dump --snapshot`;
    - the plaintext exists only in the container's `tmpfs`;
    - gpg encrypts with AES-256 inside the container;
    - the gates in §3 run;
    - only the encrypted copy and the metadata reach the run folder.
  - **Restore test:** `restore-test.ps1` with `container/restore.sh`. This is the Stage C procedure in §4.
  - **SQL:** `sql/table-fingerprints.sql`, `sql/inventory.sql` and `sql/outbox-analysis.sql`.
  - **Flyway harness:** `ExternalDatabaseFlywayIT`, a backend test that is disabled unless `-Drobin.flyway.external.url` is set. It runs Flyway with the application's configuration.
    - `validate` is used for Stage C.
    - `migrate` is used only for the dry-run source, and needs a loopback URL, the marker comment and an explicit opt-in.
- **A4. Dry run.** `dry-run.ps1` ran on 6 Nov from 14:51 to 14:53 UTC (131 s).
  - The synthetic source has the same shape as production:
    - `postgres` is not a superuser but has `BYPASSRLS`;
    - `citext` is in `public`, and the other extensions are in `extensions`;
    - the `auth`, `storage`, `realtime` and `vault` schemas exist;
    - the `supabase_realtime` publication exists;
    - Supabase's `ensure_rls` event trigger is installed.
  - The application's own guarded Flyway strategy migrated it: a baseline at version 0, then V001–V051, giving 52 history rows as in production.
  - It then received 2,500 synthetic jobs and 60,023 outbox events, of which 30,023 were pending.

**Dry-run results.** The evidence is in `C:\RobinBackups\dryrun\20261106T145234Z-dryrun\`.

| Stage | Checks |
|---|---|
| B (backup) | 14/14 PASS |
| C (restore) | 13/13 PASS |

What Stage B confirmed:

- The server is 17.11. pg_dump exited with 0 and its log is clean.
- 77 tables were fingerprinted in the dump's snapshot.
- The archive's table of contents for `public` matches the production inventory: 70 table-data entries, 70 row-security entries, 12 triggers, 1 view, 1 sequence and 175 indexes.
- Archive rows equal snapshot rows for all 77 tables.
- The decryption round trip and the SHA-256 of the copy on this machine both match.

What Stage C confirmed:

- The restore ran as `robin_app` in one transaction, with no warnings.
- All 21 inventory keys are equal, and no constraint is `NOT VALID`.
- 70 tables with 122,614 rows have equal fingerprints.
- The data outside `public` is readable (130 rows).
- RLS behaves as planned: the owner sees all 60,023 outbox rows, and a role without `BYPASSRLS` sees none.
- All 30,023 pending events are preserved.
- Flyway validate passed: 52 validated, 0 pending, 0 failed.

**Findings carried forward:**

- Flyway 10.10.0 logs *"PostgreSQL 17.11 is newer than this version of Flyway … The latest supported version of PostgreSQL is 16."* Validation still succeeds. Upgrading Flyway remains a separate change (§7).
- The restore test creates `pgcrypto` in `public`, whereas production has it in `extensions`. The application does not call pgcrypto at runtime (§1.3). Its placement on Oracle is decided in Stage D.
- The dry run could not exercise:
  - TLS to the pooler;
  - the pooler's session limit and idle timeouts;
  - the interactive prompts;
  - the production data volume.

  The checks run at run time cover them, and the README lists what to do if they fail.

**Exit criteria met:**

- The dry run passed, with counts and fingerprints equal before and after the restore.
- The image id is recorded.
- Approvals 1–3 were given on 6 Nov.

### Planned deliverables

Kept for reference. The implementation refined A3, as described above.

- **A1. Tooling.**
  - Start Docker Desktop.
  - Pull `postgres:17.11-bookworm` and record its digest.
  - Confirm `pg_dump --version` reports 17.11.
- **A2. Location.** Create `C:\RobinBackups\`. It is outside the repository and outside OneDrive. It will hold only encrypted files and a manifest that contains no personal data.
- **A3. Scripts** in `scripts/db-migration/`. They contain no secrets and are reviewed, committed and pushed. They run in Git Bash because its pipes are binary-safe.
  - **`backup-supabase.sh`:**
    1. Start a disposable `postgres:17.11` container with a RAM-only `tmpfs` at `/dump`.
    2. Prompt for the connection details and password *inside the container*. The password is never placed in a file, an environment variable or the shell history on the host.
    3. Open a `REPEATABLE READ READ ONLY` session and call `pg_export_snapshot()`.
    4. Run `pg_dump --format=custom --snapshot=<id> --verbose` into the `tmpfs`.
    5. In that same snapshot, compute exact row counts and fingerprints for every table.
    6. Run `pg_restore --list` and the TOC checks.
    7. Record the plaintext SHA-256.
    8. Stream the archive out through `gpg --symmetric --cipher-algo AES256 --no-symkey-cache --compress-algo none`.
    9. Decrypt it once more to compare SHA-256s.
    10. Remove the container, which also wipes the `tmpfs`.
  - **`restore-test.sh`:** runs Stage C (§4).
  - **`table-fingerprints.sql`:** per table, computes `count(*)` and `sum` of the first 60 bits of `md5((to_jsonb(t) - 'search_vector')::text)`.
    - The sum does not depend on row order, so no sort is needed and there is no temp-file risk on the source.
    - The session time zone is set to UTC, and the generated column is excluded.
- **A4. Dry run.**
  - Run A3's full pipeline end to end against a local PostgreSQL 17 database migrated by Flyway (V001–V051) and filled with synthetic rows.
  - This proves the dump, encryption, verification, restore and fingerprint comparison work before production is touched. It matters because a failed real run costs about 1–1.6 GB of egress.
- **A5. Connection details (user).**
  - Copy the **Session pooler** host and user from the dashboard (Connect → Session pooler). Supabase recommends the session pooler for dumps when IPv6 is unavailable.
  - Optionally download Supabase's CA certificate so the dump can use `sslmode=verify-full` instead of `require`.
- **A6. Passphrase (user).**
  - Generate it in your password manager.
  - Type it only into gpg's prompt. It is never shared with me.

Exit criteria:

- The dry run passes, with counts and fingerprints equal before and after restore.
- The image digest is recorded.
- Approvals 1–3 in §8 have been given.

## 3. Stage B: backup execution and verification (one read-only pg_dump, run by you)

- **B1. Pre-flight (read-only, catalog only):**
  - read-only state;
  - client sessions;
  - no transactions older than 5 minutes;
  - outbox sequence value;
  - a free pooler slot: the backend holds 10 Supavisor sessions, and the dump needs 2.
- **B2. Run `backup-supabase.ps1` (you).** The exact command is in `scripts/db-migration/README.md`.
  - You type the database password at its prompt inside the container.
  - After the dump, you type the encryption passphrase twice at the same kind of prompt.
  - Expect a run of tens of minutes. The time is set by pooler throughput.
- **B3. Verification. Every item below is a pass/fail gate:**
  1. pg_dump exits with code 0, and its log contains no `error`.
  2. `pg_restore --list` succeeds on the plaintext in `tmpfs`, and the TOC is complete for `public`:
     - `TABLE DATA` for all 70 tables;
     - `SEQUENCE SET` for the outbox sequence;
     - the 12 triggers;
     - the view `provider_cost_summary`;
     - index and constraint entries covering the 175 indexes;
     - `ROW SECURITY` entries for all 70 tables.

     The entries for the Supabase-internal schemas are listed and counted in the manifest.
  3. Row counts and fingerprints for every table come from **the dump's own snapshot**. These are the reference values for Stage C.
  4. Outbox totals at the snapshot are recorded, and published plus pending must equal the total row count.
     - Pending is expected to be at least 770,720 `job.discovered` (Phase 1) plus the 23 other events.
     - Any shortfall must be matched by events published during a writable window, never by missing rows.
  5. Both SHA-256 values are recorded: plaintext and `.gpg`. A decrypt round trip reproduces the plaintext SHA-256.
  6. `flyway_schema_history` has 52 rows (the baseline plus V001–V051), and all are `success = true`.
- **B4. Manifest.** Written next to the encrypted file. It holds:
  - times and versions;
  - the image digest;
  - sizes and SHA-256 values;
  - TOC counts;
  - per-table counts and fingerprints;
  - outbox numbers;
  - egress before and after the dump, read from the usage page, which refreshes about hourly.

  It contains no credentials and no personal data.
- **B5. Offsite copy.** Make a second copy of the **encrypted** file in a place you choose.
- **B6. Not part of B:**
  - no changes to production;
  - no pausing of production work;
  - no Supabase settings changed.

Exit criteria: every check in B3 passes. **Even then the backup is "taken", not "verified"**; it is only verified once Stage C passes.

## 4. Stage C: isolated restoration test

- **C1. Instance.**
  - A separate `postgres:17.11-bookworm` container, bound to `127.0.0.1` only.
  - It has a random local password and is not on any shared network.
  - `PGDATA` goes on `tmpfs` if about 3 GB of RAM is free. The fallback is a throwaway Docker volume. In that case the data rests unencrypted inside the Docker disk until teardown, because C: has no BitLocker.
- **C2. Load the archive.** The `.gpg` file is decrypted as a stream into the container's `tmpfs`; it is never written to the host disk.
- **C3. Prepare the target in the shape planned for Oracle.**
  - Create the role `robin_app` with `LOGIN NOSUPERUSER NOBYPASSRLS`.
  - Create the database `robin_restore`, owned by `robin_app`.
  - Create the extensions `citext` and `pgcrypto`.
- **C4. Restore.**
  - `pg_restore --no-owner --no-privileges --exit-on-error` runs as `robin_app`, using a TOC list filtered to the `public` schema.
  - Supabase-only entries are excluded by name, and the excluded list is written to the report:
    - event triggers and their helper functions;
    - the `supabase_realtime` publication;
    - all `EXTENSION` entries (`citext` and `pgcrypto` are created beforehand; `supabase_vault`, `uuid-ossp` and `pg_stat_statements` are not needed);
    - default ACLs for Supabase roles.
- **C5. Prove the Supabase-internal schemas are readable.**
  - Run `pg_restore --data-only -n auth -n storage -n realtime -n vault -f /dev/null`; it must exit with code 0.
  - The `COPY` row counts per table must match the manifest.
- **C6. Verification. Every item is a gate:**
  1. **Inventory equals the source:**
     - 70 tables, including `flyway_schema_history`;
     - 175 indexes;
     - the `outbox_events` sequence, at the snapshot's value;
     - 1 view;
     - 12 triggers;
     - the Robin functions;
     - `citext`.
  2. **Constraints:** all are present, and there are 0 rows in `pg_constraint` with `convalidated = false`.
  3. **Data:** exact row counts and fingerprints for every table equal the manifest from B3, including all pending outbox rows.
  4. **Flyway:**
     - `validate` passes against the restored database with the repository's migrations.
     - 52 applied, 0 pending, 0 checksum mismatches.
     - This is run through an integration test gated by an environment variable, with the application's exact Flyway configuration. It is **not** done by starting the backend, which would start schedulers and outbound integrations against real user data.
  5. **RLS:**
     - 70/70 tables have RLS enabled, 0 forced, 0 policies.
     - As `robin_app` (the owner), every row is visible.
     - As a non-owner role without `BYPASSRLS`, 0 rows are visible. This proves RLS is preserved and inert for the owner, exactly as today.
- **C7. Outbox analysis**, where heavier queries are safe:
  - pending events by type and action;
  - **distinct jobs** among pending events, which sizes R2;
  - partially consumed events;
  - hourly `created_at` and `published_at`, which show the writable windows and the dispatch rate;
  - the autonomy and auto-apply settings for each profile.
- **C8. Teardown.**
  - `docker rm -f -v`.
  - Confirm `C:\RobinBackups\` holds only `.gpg` files and the manifest.
  - Write a report containing no personal data.

Exit criteria: every check in C6 passes. **Only then is the backup "verified".** Migration readiness additionally requires Stages D and E.

## 5. Stage D: Oracle infrastructure planning (planning only, nothing provisioned)

- **Target:** PostgreSQL **17**, on the current 17.x minor (17.11 today). It is the same major version as the source, so restoring a dump stays within one major version.
  - Runs on Ubuntu 24.04 LTS (aarch64) with PGDG packages; arm64 builds are verified in §1.7.
  - The alternative is the official Docker image (`arm64v8`). Native packages are recommended for the database server: a systemd service, minor updates through apt, and no container layer.
  - Locale `en_US.UTF-8`, the same as the source, so text ordering is the same.
- **Compute:** `VM.Standard.A1.Flex` with 2 OCPU and 12 GB, which is the whole Always Free A1 allowance. The current source is a Supabase NANO instance, so this is ample.
- **Storage** (150 GB of the free 200 GB):

  | Volume | Size |
  |---|---:|
  | Boot volume | 50 GB |
  | Block volume for `PGDATA` and WAL | 100 GB |

  Today's database is 1.6 GB including indexes. At today's discovery behaviour the outbox alone grows by about 100 MB a day (estimated at about 336 bytes per row on disk). The size fits the volume, but the growth has to be handled: stop emitting a `job.discovered` event for unchanged jobs, and keep published outbox and consumed-event rows only for a set retention period. Both are separate, later changes.
- **Starting configuration:**
  - `shared_buffers=3GB`;
  - `effective_cache_size=8GB`;
  - `work_mem=16MB`;
  - `maintenance_work_mem=512MB`;
  - `max_connections=40` (HikariCP uses 10);
  - `wal_compression=zstd`;
  - `password_encryption=scram-sha-256`;
  - `ssl=on`;
  - `idle_in_transaction_session_timeout=10min`;
  - `log_min_duration_statement=1000`;
  - `pg_stat_statements`.
- **Network and security:**
  - A VCN security list allows Postgres only from Render's published outbound ranges for the service's region. Those ranges are shared by everything Render runs in that region, so the allowlist is necessary but not sufficient.
  - SSH is allowed only from the admin's IP, or through OCI Bastion.
  - The host firewall must also allow the Postgres port, because Oracle's Ubuntu images ship restrictive iptables rules.
  - `pg_hba` permits only `hostssl robin robin_app <render ranges> scram-sha-256`. There is no remote superuser login.
  - TLS uses a server certificate, and the JDBC client uses `sslmode=verify-full`.
- **Roles:**
  - `robin_app` owns all objects; it has no `SUPERUSER` and no `BYPASSRLS` (see §1.4).
  - Backups run as the owner, or as a role granted `BYPASSRLS` by the VM's own superuser.
- **Backups on Oracle, from the first day:**
  - Nightly pg_dump, encrypted to a **public key** so the VM can never decrypt its own backups, stored in a private OCI Object Storage bucket with 7–14 days' retention, within the free tier.
  - Weekly block-volume backups, using the 5 free ones.
  - A restore test every month, following the Stage C procedure.
  - Optionally, WAL archiving for point-in-time recovery, checked against the limit of 50,000 requests a month.
- **Monitoring:**
  - OCI metrics for CPU, memory and network, including the reclamation thresholds;
  - an alert at 70 % disk;
  - the Postgres log;
  - the backend's `/actuator/health`.
- **Risks to settle before provisioning:**
  - **Idle reclamation.** It is unknown whether memory held by Postgres counts as "used" in OCI's memory metric. Watch the metric in week 1. Upgrading to Pay As You Go keeps Always Free resources free (Oracle docs), but its exemption from reclamation is not documented.
  - **A1 capacity** in the home region.
  - **Latency** from the Render region to the Oracle home region.

## 6. Stage E: cutover and rollback planning

**Prerequisites:**

- Stage C has passed.
- Stage D is provisioned and hardened, and a rehearsal restore has been verified **on the Oracle instance**: the Stage C checks on ARM64 PostgreSQL 17, plus latency measured from Render.
- The backend can start with **dispatch paused**. This is a small code change: a property such as `app.events.dispatch-enabled`, checked inside `dispatchPending`.
- Discovery can be paused with the existing `app.discovery.scheduler-enabled`.
- The outbox resume policy has been decided.
- The autonomy policies have been checked.

**Freeze and final copy** (separate approval, production change):

1. Stop all writers: suspend the backend and worker services on Render.
2. Confirm the source is quiet: the `pg_stat_database` tuple counters stay unchanged for 5 minutes.
3. Take a **final dump** with the Stage B procedure.
4. Restore it to Oracle as `robin_app` and verify it with the Stage C checks.
5. Run `ANALYZE`.

The final dump costs another 1.0–1.6 GB of egress, and it may be impossible after 12 Nov. If cutover cannot happen before then, the Stage B archive becomes the migration source. Writes made on Supabase after Stage B would then be lost; they are quantified from the source if it is still readable. **This is your decision when the time comes.** Options:

- cut over before 12 Nov;
- accept the Stage B data as final;
- move to a paid Supabase plan as a bridge. Supabase's docs say an upgrade gives a disk of at least 8 GB, which ends read-only mode.

**Switch:**

1. Change the three `SPRING_DATASOURCE_*` variables on Render (approval needed then).
2. Deploy with dispatch and discovery paused.
3. Check `/actuator/health` and `/actuator/info`.
4. Smoke-test the write paths:
   - the sign-in session exchange;
   - the worker's `claim-next`;
   - the managed-mail webhook, using the Phase 20 probes (`ROBIN_PHASE20_VERIFICATION.md` §6.0–§6.4).
5. Resume the worker.
6. Then resume outbox processing according to the approved policy.
7. Then resume discovery, once the emission fix has landed.

**Rollback:**

- **Before real traffic writes to Oracle:** point the datasource variables back at Supabase and redeploy. Supabase is untouched, so no data is lost, although the app returns to its present half-read-only state.
- **After Oracle has taken writes:** Supabase is not a viable write target, because it is read-only and soon restricted. Failures are fixed forward on Oracle, or Oracle is restored from its own backups.
- Supabase and the encrypted archives remain the **immutable reference copy** for data recovery.
- **Triggers for rollback:**
  - health checks failing for more than 15 minutes;
  - any count or fingerprint mismatch;
  - authentication failures;
  - p95 latency regressing badly against the rehearsal baseline.
- **Supabase is kept intact:** no deletion, no configuration changes, no data changes. It may be paused or restricted by Supabase itself. From 12 Nov the **encrypted archives are the dependable rollback source**.

## 7. Aligning CI with PostgreSQL 17 (separate change, needs approval)

1. Add one test constant, for example `TestPostgres.IMAGE`.
   - It reads the system property `robin.test.postgres-image` and defaults to `postgres:17.11-bookworm`.
   - It replaces the 40 literals `new PostgreSQLContainer<>("postgres:16")`, a mechanical change.
2. Change `infra/docker-compose.yml` to use `postgres:17.11-bookworm`.
3. Pin the minor version in that one place, and bump it together with the Oracle server's PGDG package.
4. Add a CI job on `ubuntu-24.04-arm` that runs the backend integration tests, or at least the migration-focused ones, against the `arm64v8` image.
5. Add a dump/restore round-trip integration test:
   1. Migrate an empty PostgreSQL 17 database.
   2. Dump it with pg_dump 17.
   3. `pg_restore` it with Stage C's flags into a second database owned by a non-superuser.
   4. Run Flyway `validate`.

   This guards the restore procedure without using any production data.
6. Spring Boot 3.3.4 manages Flyway 10.10.0, which is older than PostgreSQL 17. Production already runs it against 17.6. Upgrading Flyway or Spring Boot is a separate change.

Exit criteria: CI passes on x86_64 and on arm64 against PostgreSQL 17.11.

## 8. Approvals needed

**Now, to start Stage A** (given on 6 Nov, and done; see §2):

1. Start Docker Desktop and pull the official image `postgres:17.11-bookworm`, a download of about 150 MB.
2. Create `C:\RobinBackups\`, outside the repository and OneDrive, or name another location.
3. Write the Stage A scripts (§2 A3) in `scripts/db-migration/`, dry-run them on synthetic data (A4), then commit and push.

**For Stage B:**

4. You run the backup script and type the database password, and later the gpg passphrase, at the prompts. I never see either.
5. I run read-only catalog checks in the SQL editor before and after the dump, as was done today.
6. Choose an offsite location for the encrypted copy.

**Later, as separate decisions:**

7. The outbox resume policy (R1, R2 or R3, §1.5), decided after Stage C's analysis.
8. **Pausing production background work.** This is a production change and has **not** been done. It would stop:
   - about 11.6 failed write transactions a second;
   - 0.4–1.8 GB of egress a day;
   - writes landing at the full-disk edge.

   The options are suspending the Render services, or setting the existing property `app.discovery.scheduler-enabled=false` through a Render environment variable. Pausing the outbox dispatcher needs the code change in §6.
9. **Stage D:** the Oracle tenancy and its home region, the Render service region, a Pay As You Go decision, and then approval to provision.
10. **Stage E:** the dispatch kill switch, the freeze window, the datasource change and the cutover.
11. **CI alignment** (§7).
12. **Optional, billing:** changing the Supabase plan. This is your call and is not required by this plan.

## 9. What is not done and not claimed

- The Stage A tooling exists and passed a dry run on synthetic data. It has not been run against production.
- There is no production backup yet, and no production data has been restored.
- **No migration readiness is claimed.**
- Oracle has not been provisioned.
- Render environment variables are unchanged.
- Supabase configuration, permissions and data are unchanged.
- Outbox processing has been neither paused nor resumed, and no row has been modified.
- Phase 20 remains **open**. Its live probes stay blocked until production writes are reliable.

## 10. Revision, 7 Nov 2026: Oracle without a Supabase upgrade

### 10.1 Decisions (yours, 7 Nov)

- Supabase is **not** upgraded. The target is PostgreSQL 17 on Oracle Cloud.
- The project has no active users, so **planned downtime is acceptable**.
- The egress quota is **not** to be exceeded intentionally.
- The full backup is not skipped.
- Supabase stays intact (no deletion, no data or settings changes) until the Oracle migration is fully verified.
- No cutover yet.

This replaces §1.2's *"the backup is not to be delayed to save egress"* and §6's paid-plan bridge option.

### 10.2 Facts at 16:02 UTC on 7 Nov

**Usage page** (cycle 18 Oct–18 Nov; the grace period ends 12 Nov):

| Item | Used | Limit | |
|---|---:|---:|---:|
| Egress | 4.197 GB | 5 GB | 84 % |
| Log ingestion | 0.925 GB | 1 GB | 93 % |
| Database size | 1.616 GB | 0.5 GB | 323 % |
| Cached egress | 0 | 5 GB | 0 % |

**Background use, with no backup running:**

- About 27 MB an hour of egress (3.829 GB at 02:22, 4.171 at 14:41, 4.197 at 16:02).
- About 16 MB an hour of log ingestion.
- Both come from the backend's retry loops against the read-only database (§1.1 and §1.2).
- **If nothing changes:**
  - log ingestion passes 1 GB at about 21:00 UTC on 7 Nov;
  - egress passes 5 GB at about 22:00 UTC on 8 Nov.

**Supabase billing FAQ** (fetched 7 Nov):

- Restrictions *"due to usage limits are lifted once your quota refills at the start of the next billing cycle"*, possibly after *"a short delay"*.
- After the grace period, an organisation that exceeds again receives no new grace period.
- Restrictions can include pausing projects, read-only databases and *"a 402 status code for all API requests"*.
- The FAQ still does not say whether Postgres connections are affected. §0's planning assumption stands: **from 12 Nov the database may be unreachable**, and database size alone keeps the organisation over quota.

### 10.3 Why one complete backup cannot be taken before 12 Nov within the allowance

- **The complete dump needs about 1.07 GB of egress (0.95–1.25 GB):**
  - 1.008 GB of table data, sampled read-only as `pg_dump` sends it;
  - row framing about 0.018 GB;
  - catalogue queries 0.01–0.02 GB;
  - TLS and TCP up to about 0.03 GB.
- **At most 0.803 GB is left** in this cycle.
- **No route reduces the transfer without losing data:**
  - `pg_dump`'s compression happens on this machine, after the transfer;
  - the PostgreSQL protocol and TLS 1.3 carry no compression;
  - every connection out of Supabase is metered as egress;
  - Supabase's own Free-plan export (`supabase db dump`) is the same transfer, written in plaintext.
- **Measured locally on 7 Nov** (dry run, synthetic data): the container received 51.8 MB for a 6.6 MB compressed archive. Egress follows the uncompressed data, not the archive size.

### 10.4 Revised sequence

| Step | When | What | Egress | Approval |
|---|---|---|---:|---|
| R1 | now | **Suspend the Render backend service.** It is Robin's only Render service: no worker service or cron job is deployed (dashboard, 7 Nov). This stops the retry loops, and with them about 27 MB an hour of egress, about 16 MB an hour of logs and the failed writes. The database is unchanged: it is already read-only. | 0 | yes |
| R2 | after R1, before 12 Nov | **Window 1: partial backup.** The reviewed Stage B procedure, with `-DeferTableData public.outbox_events` and `-EgressCapMB` set to the fresh remaining allowance minus 60 MB. Every other table's rows are in it; `outbox_events` keeps its definition, sequence value, counts and fingerprint. | about 0.53 GB (0.47–0.62), never above the cap | yes; you run it |
| R3 | after R2 | **Restore test of Window 1** (Stage C, local) and a rehearsal into the Oracle-shaped server. | 0 | no; local only |
| R4 | when you decide | **Oracle VM:** provision with `oracle/provision-postgres17.sh` (§10.8), then rehearse the Window 1 restore on it through an SSH tunnel. | 0 (Oracle ingress is free) | yes |
| R5 | from 18 Nov, after a fresh usage reading | **Window 2: complete backup** with the unchanged reviewed procedure and a cap of about 1,600 MB of the new 5 GB. `verify-backup.ps1 -CompareWith <Window 1>` must show every table's count and fingerprint unchanged. | about 1.07 GB | yes; you run it |
| R6 | after R5 | Stage C on Window 2, then restore Window 2 into `robin` on Oracle and verify. | 0 | yes |
| R7 | later | Cutover (§10.6 and §10.7). Supabase stays untouched. | 0 | yes |

**Notes on the sequence:**

- **Window 1 is insurance, not the complete backup.** Window 2 is the complete backup.
- **The database is frozen**, because it is read-only and has no writers once R1 is done. So Window 2 must equal Window 1, plus the outbox rows; `-CompareWith` proves it.
- **If Supabase is unreachable from 12 Nov and stays so after 18 Nov,** Window 1 becomes the migration source. It would lack the 1,597,314 outbox rows:
  - 898,295 published events, which are history;
  - 771,389 pending `job.discovered` events, which re-scoring the jobs can supersede (§1.5 R2);
  - **23 other pending events** (11 `job.matched`, 11 `verification.completed`, 1 `signup.completed`), which would be lost.

  That would be your decision then. You could also ask Supabase support whether the grace period can be extended while you export and migrate; this plan does not rely on it.
- **Inactivity pausing:** with no traffic, Supabase may pause the Free project for inactivity before 18 Nov. Restoring it from the dashboard does not change its data; it would be a separate decision.

**Egress budget for this cycle, if R1 happens by about 18:00 UTC:**

| | GB |
|---|---:|
| Used at 16:02 | 4.197 |
| Background until R1 | about 0.05 |
| Window 1 | about 0.53 (at most the cap) |
| **Expected total** | **about 4.78 of 5** |

### 10.5 Tooling changes in this revision (tested 7 Nov)

**`backup-supabase.ps1` and `container/backup.sh`:**

- `-EgressCapMB` is mandatory in live mode. A watchdog inside the container samples the bytes received every 0.1 s, from before the first connection. Past the cap it stops `pg_dump` and `psql`, and the run fails. The measured transfer is recorded in the manifest as `network_rx_bytes`.
- `-DeferTableData` produces a partial backup, which is labelled PARTIAL everywhere.
- The host and user prompts reject `@`, URLs, ports and paths, and ask again. The first live attempt on 7 Nov failed on a leading `@`.

**Other scripts:**

- `container/restore.sh` and `restore-test.ps1`:
  - partial archives (the deferred tables must restore empty);
  - a remote target, prepared by the provisioning script and reached over TLS `verify-full` as `robin_app`; an empty target is required.
- `verify-backup.ps1`: the independent post-run verifier, with `-CompareWith`.
- `oracle/provision-postgres17.sh` and `oracle/test-oracle-local.ps1`.

**Default behaviour is unchanged:** no deferral, and the restore target is local.

**Review before R2 (7 Nov, 17:00–17:30 UTC).** Four gaps were found and fixed:

1. **Cap enforcement depended on a watchdog whose liveness was checked only when `pg_dump` started.** `pg_dump` now runs under a dead man's switch: a heartbeat older than 5 s stops it (`FAIL egress_meter`). Tested with a fake `pg_dump`: the heartbeat stopped at 3 s, and the dump was stopped at 9 s with `watchdog.lost` recorded.
2. **`egress_within_cap` reported PASS without comparing the final total with the cap.** Now:
   - it passes only at or under the cap;
   - if the last session bytes arrive after `pg_dump` has finished and cross the cap, the complete archive is kept with `NOTE egress_over_cap`;
   - if `pg_dump` itself was stopped, nothing is kept.
3. **An interrupted run (window closed, crash) could leave the container, and plaintext in its RAM, indefinitely.** Now:
   - the container ends itself after 4 hours (`--rm`, `sleep 14400`);
   - a new backup or restore refuses to start next to a leftover container. Tested: exit 1, the removal command printed, no run folder created.
4. **`verify-backup.ps1` relied on the backup's own comparison file, and ignored "deferred" lines even in a run claiming to be complete.** It now recomputes every table's archive rows against the snapshot itself. Tested:

   | Case | Result |
   |---|---|
   | real partial run | PASS, labelled PARTIAL |
   | real complete run | PASS |
   | run stopped by the cap | stops: no archive |
   | partial run with its deferred list deleted | 4 FAIL |
   | partial run forged as complete | 3 FAIL |
   | complete run with a table missing from the archive while its own checks said PASS | FAIL |
   | truncated encrypted file | FAIL |

**Regression on the reviewed code (17:06–17:12 UTC):**

- Partial plus Oracle-shaped rehearsal: PASS. The verifier gave 25 PASS (PARTIAL), with 35.2 MB received.
- Complete: PASS. The verifier gave 24 PASS, with 51.8 MB received.
- 20 MB cap: stopped at 32 MB with nothing kept.
- No containers were left behind.

| Test (synthetic, 7 Nov) | Result |
|---|---|
| Complete, cap 500 MB (final code) | backup 15/15 PASS (51.8 MB received), restore 13/13 PASS including Flyway validate |
| Partial (`outbox_events` deferred), cap 500 MB (final code) | backup 16/16 PASS (35.2 MB received; TOC 69 table-data entries; outbox sequence value kept), restore 11 PASS + 2 NOTE including Flyway validate; `verify-backup.ps1` 24 PASS, 0 FAIL |
| Cap 20 MB | stopped with `FAIL egress_cap`: no archive, no container left. Overshoot on the local network: 46 MB at 0.5 s sampling, 35 MB at 0.1 s (final code). Over the internet, throughput and so overshoot are far lower; the README still requires a margin of at least 60 MB. |
| `verify-backup.ps1 -CompareWith`, two different synthetic sources | `same_data_as_other_run` FAIL as it must (12 tables differ) |
| Oracle-shaped rehearsal | PASS (§10.12) |

**Second review before R2 (8 Nov, 04:13–04:45 UTC; commit `c25cfac`).** One gap was found and fixed:

- **The plaintext could reach the Docker VM's swap disk.** The VM has a 2 GB swap partition (swappiness 60). The backup container ran with no swap limit (`memory.swap.max = max`), so under memory pressure its `tmpfs`, and the memory of `pg_dump`, `psql` and `gpg`, could have been swapped out to that disk. Now:
  - the container runs with `--memory 5g --memory-swap 5g`, which gives it `memory.swap.max = 0` (cgroup v2);
  - `backup.sh` first checks that `/dump` is a `tmpfs` and that `memory.swap.max` is 0 (`plaintext_in_ram`). Otherwise it stops before it asks for any secret or connects;
  - `verify-backup.ps1` requires that check.
- **Writable source, made an explicit decision.** A live run whose source was not read-only still fails `source_read_only`. `-AcceptWritableSource` turns that into a WARN.
- **Still open:**
  - Windows can page any process's memory, the Docker VM's included, to `C:\pagefile.sys`, and drive C: is not BitLocker-protected. The mitigation is about 3 GB of available RAM during the run.
  - The restore containers (Stage C) do not have the swap guard yet.

| Test (synthetic, 8 Nov, on `c25cfac`) | Result |
|---|---|
| Partial (`outbox_events` deferred), cap 500 MB, plus the Oracle-shaped rehearsal | PASS: backup 17/17 including `plaintext_in_ram`; 35 MB received; 60,023 rows deferred; 69 table-data entries |
| Complete, cap 500 MB | PASS |
| Cap 20 MB | stopped with `FAIL egress_cap` at 29 MB: no archive, no plaintext on the host |
| Swap guard: the container as it was started before (`tmpfs`, swappable) | refused with `FAIL plaintext_in_ram`; nothing written to `/dump` |
| Swap guard: `/dump` on the container disk | refused with `FAIL plaintext_in_ram` (overlayfs) |
| Capped run without network counters | refused with `FAIL egress_meter`: no archive, no container left |
| Dead man's switch (fake `pg_dump`) | stopped at 9 s, exit 143, `watchdog.lost` recorded |
| Interrupted run (the harness killed mid-dump, as closing the window would) | see below |
| Verifier | 14 cases, see below |
| Files | the 12 scripts were byte-identical before and after the tests (SHA-256 recorded) |

The interrupted run:

- The container stays: `AutoRemove=true`, `sleep 14400`, `/dump` on `tmpfs`, `memory.swap.max` 0, 0 bytes swapped.
- Its disk (8,010 files) holds no pg_dump archive. The same scan finds `/dump/robin.dump` (positive control).
- The host run folder holds 0 files.
- The next backup refuses to start. `docker rm -f -v` removes the container, and `--rm` deletes a container once its sleep ends.

The 14 verifier cases:

| Case | Result |
|---|---|
| real partial run | 25 PASS, labelled PARTIAL |
| real complete run | 24 PASS |
| run stopped by the cap | stops: no archive |
| deferred list deleted | 4 FAIL |
| partial run forged as complete | 3 FAIL |
| a table missing from the archive while the run's own checks said PASS | FAIL `row_counts` |
| truncated `.gpg` | FAIL `sha256` |
| no `plaintext_in_ram` proof | FAIL |
| a file with the `PGDMP` signature in the folder | FAIL `no_plaintext_archive` |
| failed decryption round trip | 3 FAIL |
| AES-128 header | FAIL `encryption_header` |
| writable source | FAIL; WARN only with `-AcceptWritableSource` |
| `-CompareWith`, same snapshot | PASS |
| `-CompareWith`, different source | FAIL |

Two assertions in the test harness itself were wrong and were corrected:

- a `-like` pattern containing `[ ]` (the interrupted-run test, rerun 8/8 PASS);
- a watcher that missed one in-container view because the container had already gone. That run's own `plaintext_in_ram` PASS and `docker inspect` cover it.

### 10.6 Render variables at cutover (names only; values never in this repository)

| Variable | Change |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://<Oracle host>:5432/robin?sslmode=verify-full&sslrootcert=/etc/secrets/robin-pg-ca.crt` |
| `SPRING_DATASOURCE_USERNAME` | `robin_app` |
| `SPRING_DATASOURCE_PASSWORD` | `robin_app`'s password (password manager) |
| Secret file `robin-pg-ca.crt` (new) | the provisioning script's `/etc/robin/robin-pg-ca.crt`; public, but mounted as a Render secret file |
| `SPRING_APPLICATION_JSON` (temporary) | `{"app":{"discovery":{"scheduler-enabled":false,"identity-backfill-enabled":false}}}` for the first start, removed when discovery resumes |

**Unchanged:**

- `SPRING_PROFILES_ACTIVE`;
- the Redis, Firebase and CORS variables;
- `WORKER_EVENT_TOKEN`;
- the invite-code, LLM and managed-mail variables;
- the worker's `WORKER_*` URLs, which point at the backend, not the database;
- the frontend's `VITE_*`.

**Rollback:** record the current three `SPRING_DATASOURCE_*` values in the password manager before changing them.

### 10.7 Background work that must be paused

| Component | Writes | Switch today |
|---|---|---|
| `OutboxProcessor.dispatchPending`, every 2 s; also runs immediately at startup | publishes events, marks rows published, re-scores jobs, can create applications | **none**: needs the dispatch kill switch (§6), a code change |
| `OutboxProcessor.reconcileDlq`, every 5 min | DLQ notifications | none (same switch) |
| `DiscoveryScheduler.runDueSources`, every 60 s | discovery runs, jobs, outbox events | `app.discovery.scheduler-enabled` |
| `JobDiscoveryService.scheduledDiscoveryMaintenance`, hourly | archives jobs not seen for 30 days (they will age while discovery is paused) | **none** (proposed in the same code change) |
| `JobIdentityBackfill`, at startup | identity keys for up to 10,000 jobs | `app.discovery.identity-backfill-enabled` |
| `AutomationRecoveryScheduler`, every 60 s | resets stale RUNNING plans | none; low risk |
| `ManagedMailRetentionJob`, every 10 min | deletes expired managed-mail content (retention) | none; keep it running |
| Render worker service (`worker/`) | none directly; it would call the backend (`claim-next`, events) | **not deployed**: Robin has one Render service, the backend (Docker, Frankfurt region; dashboard checked 7 Nov) |
| Render cron jobs | — | none in any of your Render workspaces |
| Anything outside Render that calls the backend (uptime monitors) | none known in this repository | check before cutover |

### 10.8 Oracle PostgreSQL 17: preparation

`scripts/db-migration/oracle/provision-postgres17.sh` implements §5 for a fresh Ubuntu 24.04 VM:

- PGDG `postgresql-17`, after checking the signing-key fingerprint;
- the cluster on the block volume (UTF-8, `en_US.UTF-8`, data checksums);
- the §5 configuration;
- a private CA and a server certificate;
- `pg_hba`: TLS + SCRAM only, `robin_app` only, Render's ranges only, everything else rejected;
- `robin_app` (`NOSUPERUSER NOBYPASSRLS`) owns `robin` and `robin_restore` (rehearsals, loopback only);
- the host firewall opened for the same ranges.

The password is set at psql's `\password` prompt.

**Local test, 7 Nov:** passed (§10.12).

**Still required before provisioning (§8, item 9):**

- the tenancy and home region. The backend runs in Render's Frankfurt region, so a home region near Frankfurt keeps latency low; Always Free compute must be in the home region (§1.7);
- Render's published outbound ranges for Frankfurt (the `--allow` list);
- a decision on Pay As You Go;
- a `/srv/pgdata` block volume;
- the VCN security list;
- your approval.

**Nightly encrypted backups on the VM** (§5) are written but not yet scripted. They are a prerequisite for cutover, not for provisioning.

### 10.9 Health checks, application tests and rollback

**Before cutover:**

1. Window 2 passes `verify-backup.ps1`, including `-CompareWith` against Window 1.
2. Stage C passes locally and on the Oracle VM (`restore-test.ps1 -Target remote`).
3. Flyway validate passes over JDBC with `verify-full` (the local rehearsal does this).
4. The backend integration tests pass on PostgreSQL 17 (§7).
5. The dispatch kill switch is merged.

**At cutover:**

1. The backend is suspended (already done in R1).
2. Restore Window 2 into `robin` on Oracle and verify it.
3. Change the §10.6 variables.
4. Deploy with dispatch and discovery paused.
5. Check `/api/v1/system/health` (Render's health check), `/actuator/health` (components: database, Redis) and `/actuator/info` (build).
6. Confirm that the Flyway log reports the schema up to date at version 051.
7. On Oracle, `pg_stat_ssl` shows TLS for every backend connection, and the connection count is at most HikariCP's 10.
8. Before any write, row counts equal the Window 2 manifest.
9. Run the smoke tests in `DEPLOYMENT.md` §6 and the Phase 20 probes.
10. Resume outbox dispatch under the approved policy (R3, then R2), then discovery. The worker service, when it is deployed, points at the backend and needs no database change.

**Rollback:**

- **Before Oracle takes writes:** restore the three recorded `SPRING_DATASOURCE_*` values and redeploy. Supabase is untouched, though it may be read-only or restricted.
- **After Oracle takes writes:** fix forward, or restore Oracle from its own backups.
- **In every case,** the encrypted archives and the untouched Supabase project are the reference copies.
- **Triggers:** as in §6.

### 10.10 Risks

| Risk | Mitigation |
|---|---|
| Supabase unreachable from 12 Nov, and still restricted after 18 Nov | Window 1 before 12 Nov; the outbox consequences are stated in §10.4 |
| An interrupted Window 1 run spends allowance | the cap; the B1 pre-flight; about 3 GB of free RAM; no sleep, lid open; a passphrase of at least 16 characters saved first |
| Overshoot past the cap | 0.1 s sampling and a margin of at least 60 MB |
| The usage page lags by up to an hour | read it at least 1 h after R1, and include the margin |
| Inactivity pause before 18 Nov | restore it from the dashboard if needed (separate decision) |
| A lost passphrase makes an archive unrecoverable | password manager plus a second copy; an offsite copy of each `.gpg` |
| The first start on Oracle replays 771k events | the dispatch kill switch is a cutover prerequisite (§6) |
| Oracle reclaims an idle instance or lacks A1 capacity | §5 risks; watch the metrics in week 1 |

### 10.11 Not done and not claimed (7 Nov)

- No production backup exists. R2–R7 have not started (R1 is recorded in §10.13). *Update, 8 Nov: the partial backup R2 exists and is verified (§10.13); R3–R7 have not started.*
- Supabase and Oracle are unchanged, and no production data was read except the counts in §10.2 and §10.13.
- The tooling changes are tested on synthetic data only.

### 10.12 Oracle-shaped rehearsal, 7 Nov: PASS

The run was `dry-run.ps1 -EgressCapMB 500 -DeferTableData public.outbox_events -OracleRehearsal`, on synthetic data, from 16:30 to 16:34 UTC.

**Provisioning.** `provision-postgres17.sh` ran in a fresh `ubuntu:24.04` container (amd64):

- PostgreSQL 17.11 (PGDG `17.11-1.pgdg24.04+2`), after the signing-key fingerprint check;
- `ssl=on` and `password_encryption=scram-sha-256`;
- `robin_app` with superuser, bypassrls and createdb all false;
- `citext` 1.6 and `pgcrypto` 1.3 in `public`, and `pg_stat_statements` 1.11 in `extensions`.

**Access rules,** tested from another container:

| Attempt | Result |
|---|---|
| `robin_app` to `robin` with `verify-full` | connected, TLS 1.3 |
| the same without TLS | refused by `pg_hba` ("no encryption") |
| `postgres` over the network | refused |
| `robin_app` to database `postgres` | refused |
| a host name that is not in the certificate | refused by `verify-full` |

**Remote restore** (`restore-test.ps1 -Target remote`): the archive was decrypted in the client container's `tmpfs` and restored into `robin` over TLS `verify-full` as `robin_app`, in one transaction. The target was verified as owned by `robin_app` and empty before the restore.

- All 21 inventory keys are equal, and 0 constraints are `NOT VALID`.
- Counts and fingerprints are equal for 70 tables (62,591 rows), with the deferred table restored empty.
- The archive's row counts are identical to the backup-time counts.
- The data outside `public` is readable.
- RLS is enabled on 70/70 tables, 0 are forced, and the owner sees all rows.
- **Flyway validate, over JDBC with `sslmode=verify-full`** (the backend's own connection path): 52 history rows, 0 pending, 0 invalid.

**Not exercised locally:**

- the OCI security list;
- Oracle's iptables rules (step 8 is skipped in container mode);
- systemd;
- ARM64;
- the block volume;
- latency from Render.

These are part of R4 on the real VM.

### 10.13 Progress

**R1 done, 7 Nov at about 16:51 UTC (your approval; done in the Render dashboard).**

The backend web service was suspended. It is Robin's only Render service. Render shows *"Suspended by you"*, and **Resume Web Service** undoes it.

Read-only checks before and after, in the SQL editor (`pg_stat_database` and `pg_stat_activity`):

| Window | Failed (rolled-back) transactions | Commits | Rows changed | Backend's pooler sessions |
|---|---:|---:|---:|---:|
| Before: 16:48:25–16:49:10 (44 s) | 427 (9.6/s) | 601 | 0 | 10 |
| After: 16:52:53–16:53:42 (49 s) | **0** | 28 (Supabase's own services) | 0 | **0** |

- `default_transaction_read_only` is still `on`.
- The database has no `pg_cron` extension and no `cron.job` table, so no scheduled job runs inside it. Only Supabase's idle launcher process is present.
- The usage page refreshes about hourly. Its egress and log figures should now stop rising. The cap for R2 is to be set from a reading taken at least one hour after R1.

**R2 preparation, 7 Nov at about 17:57 UTC. Not executed. Two checks did not pass.**

| Check | Result |
|---|---|
| Render backend | still suspended (dashboard 17:14; 0 pooler connections at 17:57) |
| Egress (usage page, 17:56) | 4.247 / 5 GB, so **753 MB left**. Proposed cap **690 MB** (753 − 60, rounded down). Expected R2 transfer about 530 MB (470–620), so R2 fits under the cap. |
| Log ingestion | 0.969 / 1 GB, 31 MB left. The backup adds well under 1 MB: `log_statement=ddl`; slow-statement, connection and pgaudit logging are off; `auto_explain` logs only statements over 10 s. |
| Database | PostgreSQL 17.6; no open transactions; outbox sequence 1,670,005 and size 1,600,629,907 bytes, both unchanged; **no rows changed since at least 16:48**. |
| **Read-only mode** | **off since 17:00:38 UTC.** Supabase reloaded its configuration with `default_transaction_read_only = off` (`pg_conf_load_time`; source: configuration file), as its docs describe once disk use falls below 95 %. Nobody here changed it. The database is writable but idle: there are no writers. |
| **Laptop RAM** | **0.50 GB free at 17:58** (1.88 GB at 17:13), with memory compression active. The plaintext stays in RAM, so it would risk being paged to the unencrypted disk. Target: at least 2.5 GB free before R2. |
| Disk, Docker, image | C: 69.6 GB free; engine 29.0.1; image id as reviewed; no leftover containers |
| Partial scope (dry-run archives) | exactly one table-of-contents difference from the complete archive: `TABLE DATA public outbox_events`. Its table, key, default, RLS, sequence and sequence value, and grants are kept, and its snapshot counts and fingerprint are recorded. |

**R2 blockers, 8 Nov, 04:13–04:45 UTC. R2 not executed.**

| Blocker | Evidence | Status |
|---|---|---|
| Verifier | the 14 cases in §10.5 | resolved |
| Laptop RAM | see below | **open: needs 3 GB available** |
| Production writers | see below | resolved: no writers |
| Render backend | "Suspended by you" at 04:35 | unchanged |
| Egress | see below | resolved |
| Scripts | `c25cfac`. The R2 files are byte-identical to the tested copies. Plaintext stays only in a `tmpfs` that cannot swap, and is removed with the container on success or failure, or within 4 h of an interrupted run. | resolved |

**Laptop RAM.**

- 1.49 GB was available at 04:13.
- Three steps were taken:
  - the six idle local dev containers were stopped (`docker stop`, data kept);
  - OneDrive was shut down;
  - the Docker VM's clean page cache was dropped.
- Committed memory fell by about 2.5 GB, and the page file from 4.3 to 3.0 GB. Available RAM stayed at 1.5 GB (04:45).
- The rest is used by Chrome (3.2 GB) and applications that may hold unsaved work. Closing those is the owner's decision.

**Production writers.** Two read-only readings, at 04:22:35 and 04:44:44 UTC, single `SELECT` statements in the SQL editor:

- The next transaction id was 7,197,259 both times. No transaction wrote anything in between.
- No other session was active, in a transaction, or holding a transaction id. There were no prepared transactions and 0 pooler connections.
- Rows changed (3,500,858) and rollbacks (1,696,082) are unchanged since 7 Nov 16:48–16:53.
- The outbox sequence (1,670,005) and the database size (1,600,629,907 bytes) are unchanged.
- Read-only mode is `off`. There has been no configuration reload since 7 Nov 17:00:38 and no restart since 2 Nov.
- Commits rise by about 0.84/s. These are read-only transactions of Supabase's own services.

**Egress** (04:34 UTC):

- 4.247 / 5 GB used, unchanged since 7 Nov 17:56. Log ingestion is 0.969 / 1 GB, also unchanged.
- 753 MB remain, so the cap is **690 MB**: 60 MB of margin, rounded down.
- Expected transfer: about 530 MB (470–620).

**R2 done, 8 Nov 2026, 15:54–16:23 UTC (your approval). Partial backup taken and verified; not yet restore-tested.**

Run folder: `C:\RobinBackups\20261108T155443Z-live-partial` (on the laptop only, not in this repository).

*Before the run (15:09–15:51 UTC).*

- RAM. The laptop restarted at 15:09. Afterwards:
  - with your approval, WhatsApp was closed;
  - background Edge and OneDrive were shut down again;
  - the local dev containers stayed stopped;
  - Chrome was closed for the run.
  - Available RAM read 3.59 and 3.76 GB (15:43–15:44), then 3.75 and 4.15 GB (15:51). The pre-launch check passed: the scripts are byte-identical to the tested `c25cfac`, plus the image, the swap guard, no leftover container, disk and the charger.
- Egress: 4.247 / 5 GB, so the cap stayed at 690 MB.
- Render backend: suspended.
- Production: no open transactions; Robin's `public` rows unchanged since 7 Nov. Supabase's own Storage API reconnected at about 15:34 and updated its own `storage.migrations` rows. That table is Supabase-owned, and it changed before the snapshot.

*The run.*

| Step (UTC) | Result |
|---|---|
| 15:54:45 | `plaintext_in_ram` PASS; database password entered in the container |
| 15:56:41 | one `REPEATABLE READ, READ ONLY` snapshot; 110 tables fingerprinted |
| 15:59:14–16:00:40 | `pg_dump` in that snapshot: **628.9 MB received** (cap 690 MB), exit 0 |
| 16:00–16:23 | archive checks, passphrase entered twice, AES-256 encryption, decryption round trip |
| 16:22:57 | `PARTIAL BACKUP OK`: 17/17 backup checks PASS, 0 NOTE |

*Independent verification (16:27–16:41 UTC).*

- **Verifier.** `verify-backup.ps1 -AcceptWritableSource` gave 24 PASS, 0 FAIL, 2 WARN. Without the switch, `source_read_only` is the only FAIL. The two WARNs:
  - `source_read_only`: the database was writable but idle, and the archive is one snapshot.
  - `outbox_pending_known`: the plan's 771,412 was counted at about 13:27 on 6 Nov. The database stayed writable until 13:31:17, so the final 771,659 includes the last four minutes. The newest pending event is from 13:31:17, and the sequence matches.
- **Encrypted archive.** `robin.dump.gpg` is 141,897,821 bytes, SHA-256 `c56297ff5c50919e79d3ae3d95b8c732c64cad2e584fa707f385bc447d7272cd`. That hash was recomputed with `sha256sum` and `certutil` and matches the `.sha256` file, the manifest and the host check.
- **Encryption.** The header shows AES-256, an iterated and salted SHA-512 key derivation (65,011,712) and integrity protection.
- **Passphrase.** You typed the passphrase you kept at a hidden prompt, in a container with no network and no swap.
  - gpg accepted it, and its integrity check passed.
  - The decrypted stream starts with `PGDMP`.
  - Its SHA-256 `0f0d05a7…a11ac` equals the one recorded before encryption.
  - Nothing decrypted was stored.
- **Scope.**
  - The snapshot has 110 tables, and the archive has 109 table-data entries. The only table without one is `public.outbox_events`.
  - Its table, grants, sequence, sequence value, default, primary key and row security are all in the archive.
  - All 109 dumped tables have exactly their snapshot row counts (3,749,966 rows fingerprinted).
  - The table-of-contents counts for `public` match the plan: 69, 70, 12, 1, 1, 175. Flyway history: 52 rows, latest 051.
- **Deferred rows.** 1,670,004 rows of `public.outbox_events` are recorded with a content fingerprint for the R5 comparison. The figure matches the manifest and the comparison file, and the archive holds 0 rows of the table.
  - 898,345 are published and 771,659 pending: `job.discovered` 771,636, `job.matched` 11, `verification.completed` 11, `signup.completed` 1.
  - The sequence last value is 1,670,005.
- **Cleanup.**
  - No backup container is left, and no Docker volume was created.
  - No file in any live run folder is a plaintext archive.
  - The Docker VM's swap shows 0 used.

*After the run (16:41 UTC), read-only.*

- The next transaction id is unchanged since 15:35, so nothing wrote anything during or after the backup.
- No session is active or in a transaction, no backup session remains, and there are 0 pooler connections.
- Rows, outbox sequence and size are unchanged. Rollbacks rose by one: that is `pg_dump` ending its read-only transaction by disconnecting.
- Render backend: "Suspended by you".
- The usage page had not refreshed yet (still 4.247 GB). Expected after the refresh: about 4.88 / 5 GB, so roughly 120 MB of egress is left until the 18 Nov reset.

*Still open.*

- R2 is partial: the `outbox_events` rows exist only in the source until R5.
- No restore test (R3) and no offsite copy (B5) yet.
- Supabase-owned tables such as `storage.migrations` can change whenever Supabase's services start. R5's `-CompareWith` should be judged on Robin's `public` tables, and differences in Supabase-owned tables explained.
- The restore containers still lack the swap guard. *Fixed in `bf2c1d8`; see R3 below.*
- The dev containers, OneDrive and WhatsApp are still to be restarted.

**Second encrypted copy, 8 Nov 16:58 UTC.**

- All 16 files of the R2 folder were copied to `C:\RobinBackupsCopy\20261108T155443Z-live-partial`, outside the original folder, outside OneDrive and outside this repository.
- Every file's SHA-256 is equal to the original's; the archive is `c56297ff…d7272cd`. No file in the copy is a plaintext archive.
- The original was neither moved nor changed.
- This laptop has one SSD and no other drive, so the copy protects against an accidental deletion, not against losing the disk.
- An offsite copy needs your choice and approval. The options:
  - an external USB drive;
  - OneDrive, the encrypted `.gpg` and its metadata only. This is an upload to Microsoft. The tooling refuses OneDrive for run folders because they could hold plaintext; the ciphertext alone is a separate decision.
  - Oracle Object Storage, once R4 exists;
  - another machine.

**R3 preparation, 8 Nov (commit `bf2c1d8`, tested).**

- The restore containers now cannot swap either:
  - the local server runs with `--memory` equal to `--memory-swap` (7 GB with the default data tmpfs);
  - the remote target's client-tools container runs with 5 GB.
- `restore.sh` first checks `plaintext_in_ram`: `/restore`, and locally PGDATA, must be tmpfs, and `memory.swap.max` must be 0. Otherwise it stops before asking for the passphrase.
- The local server runs with `wal_level=minimal` and `max_wal_senders=0`, so the single-transaction restore writes almost no WAL into RAM.
- Tests (synthetic, 10/10 PASS):

| Test | Result |
|---|---|
| Partial dry run, with local and Oracle-shaped remote restore | local 12 PASS, 0 FAIL, 2 NOTE; remote 12 PASS, 0 FAIL, 2 NOTE; Flyway validate PASS for both |
| Complete dry run | 14 PASS, 0 FAIL |
| Swappable container, or PGDATA on the container disk | `FAIL plaintext_in_ram`, nothing decrypted |
| Verifier on both new backups | PASS |
| The R2 archive | unchanged |

**R3 done, 8 Nov 18:02–18:04 UTC: local PostgreSQL 17 restore of the partial archive, PASS.**

Run against the verified second copy, so the original R2 folder stays untouched (16 files, no sub-folder). Available RAM before the run: 4.76 and 5.04 GB, with Chrome closed. You typed the passphrase at a hidden prompt. No production system was contacted.

| Check | Result |
|---|---|
| RAM only | `/restore` and PGDATA on tmpfs, `memory.swap.max` 0 |
| Decryption | SHA-256 `0f0d05a7…a11ac`, equal to the one recorded before encryption |
| Target | `robin_restore` owned by `robin_app` (NOSUPERUSER, NOBYPASSRLS), with citext and pgcrypto |
| Restore | 519 of 1,235 table-of-contents entries (application scope) restored as `robin_app` in one transaction, in 48 s; `pg_restore.log` empty |
| Schema inventory | all 21 keys equal the snapshot: 70 tables, 175 indexes, constraints (70 primary key, 101 foreign key, 25 unique, 74 check), 12 triggers, 7 functions, 1 view, 1 sequence |
| Constraints | 0 left NOT VALID |
| Sequence | `outbox_events_sequence_id_seq` last value 1,670,005 |
| Rows | 70 public tables, 2,079,709 rows: counts and content fingerprints equal the snapshot. `public.outbox_events` restored empty, as deferred. |
| Archive counts | identical to backup time |
| Data outside `public` (auth, realtime, storage, vault) | readable, 253 rows |
| Permissions | every object owned by `robin_app`. RLS enabled on 70 tables, none forced: the owner sees all 1,796,659 rows of `consumed_events`, and a role without BYPASSRLS sees 0. Supabase's grants (264 ACL and 24 default-ACL entries) and platform-only entries are left out by design, because the Oracle target has no Supabase roles. |
| Flyway validate | application configuration, over JDBC: 52 history rows, 51 applied, 1 baseline, 0 failed, 0 pending, 0 invalid, current version 051 |

- RAM during the run:
  - minimum available 2.45 GB;
  - Docker VM peak 2.45 GB;
  - restore container peak 1.31 GB;
  - tmpfs peaks: 940 MB data directory, 136 MB `/restore`;
  - 0 samples with swap in use.
- Cleanup: no container, no new volume, port 55439 closed, Docker VM swap 0 used, no plaintext anywhere in the copy folder or report. Both archives are unchanged (`c56297ff…`).
- The report is in the copy folder: `restore-test-20261108T180216Z`.

**Supabase from 12 Nov: what the docs say (read 8 Nov).**

| Topic | What the docs say | Source |
|---|---|---|
| Grace period | It ends on 12 Nov, as the usage page says: *"Projects will be restricted from 12 Nov, 2026 if your organization remains over quota"*. After a grace period, *"you will not receive another grace period"*. | [Billing FAQ](https://supabase.com/docs/guides/platform/billing-faq) |
| What a restriction is | Listed: databases switched to read-only, and 402 responses for all API requests. Whether direct database connections through the pooler are blocked is **not stated**. | [Billing FAQ](https://supabase.com/docs/guides/platform/billing-faq) |
| When it lifts | When the quota refills at the next billing cycle, or at once by upgrading | [Billing FAQ](https://supabase.com/docs/guides/platform/billing-faq) |
| Database size | The 500 MB quota is measured as *"the average daily database size over the billing period"*. This database is 1.616 GB, so the organization stays over quota in the cycle that starts on 18 Nov as well: **the reset does not end the overage.** | [Database size](https://supabase.com/docs/guides/platform/database-size) |
| Disk | Fixed 2 GB. At 95 % the database becomes read-only. If the disk fills, *"Postgres shuts down. It stays down until you upgrade to the Pro Plan."* WAL can fill a 2 GB disk. The database alone is about 1.6 GB of that disk. | [Database size](https://supabase.com/docs/guides/platform/database-size) |
| Inactivity | Free projects pause after a week of inactivity. Robin's own activity stopped on 7 Nov, when the backend was suspended; whether dashboard queries count as activity is not stated. A paused project can be restored from the dashboard for a year; a backup download is offered only after that window. | [Upgrading](https://supabase.com/docs/guides/platform/upgrading) |

Conclusion:

- Access after 12 Nov is not guaranteed, and 18 Nov does not guarantee it either.
- Read-only would not stop a backup. A pooler block, a full disk or a pause would.
- The only reliable windows are now until 12 Nov, with about 120 MB of egress left, and from 18 Nov only if the database is still reachable.

**The 1,670,004 deferred outbox rows.**

| Group | Rows | What they are | Can they be rebuilt without Supabase? |
|---|---:|---|---|
| Published | 898,345 | dispatched history. Their consumption is recorded in `consumed_events` (1,796,659 rows, in R2). | No; audit value only |
| Pending `job.discovered` | 771,636 | pipeline triggers. The payload is `{job_id, action, source_id}` (`JobDiscoveryService.emitDiscovered`). The handler calls `ApplicationPipelineService.onJobIngested(job_id, …)`, which re-reads the job row and every profile. | **Yes**: one trigger per job reproduces them. R2 has all 60,960 jobs, about 12.7 pending triggers per job. |
| Pending, other types | 23 | `job.matched` 11, `verification.completed` 11, `signup.completed` 1: user-facing | Not exactly; tiny to export |

Egress needed for an exact export: about 520 MB for all rows uncompressed, from the R2 estimate of the table's size on the wire. That is about 280 MB for published rows, about 239 MB for pending rows, and a few KB for the 23 others.

**Fallback if Supabase becomes unreachable (decision tree; nothing below has been run).**

1. **Now until 12 Nov** (your approval needed; tiny egress): export the 23 user-facing pending events exactly, plus a count summary of the outbox by type, status and day. Use a small purpose-built extraction with the same safeguards: one read-only snapshot, RAM only, encrypted with your passphrase, capped. Estimated well under 1 MB.
2. **After cutover, local work:** regenerate the pending pipeline triggers from the restored jobs, one per job and rate-limited behind the dispatch kill switch (§6). This needs no Supabase access.
3. **From 18 Nov, if reachable:** first a one-row connection probe. Then either an outbox-only backup (about 0.55 GB, with `-DeferTableData` listing the other tables; no code change) or the planned complete R5 (cap about 1.6 GB), each checked with `verify-backup.ps1 -CompareWith` against R2 on Robin's `public` tables.
4. **If unreachable after 18 Nov:** Robin can still be migrated from R2, plus step 1 if done, plus step 2. Only the exact outbox history is lost. Recovering it would need a paid upgrade, which you have ruled out, or Supabase support. That decision is yours.

*Superseded the same evening by §10.14:* all 1,670,004 rows can be exported exactly before 12 Nov, in about 86 MB.

### 10.14 Accelerated route, 8 Nov evening

#### Fresh readings, 20:50–21:10 UTC (read-only)

Usage page, organization view, cycle 18 Oct – 18 Nov:

| Item | Reading |
|---|---|
| Egress | 4.247 / 5 GB (85 %), unchanged since 17:06. The daily chart ends on 7 Nov, so R2's 629 MB on 8 Nov is **not counted yet**. |
| Database size | 1.616 / 0.5 GB (323 %) |
| **Log ingestion** | **0.969 / 1 GB (97 %)**: new, and close to its own quota |
| Cached egress | 0 / 5 GB |

- The banner says the previous cycle went over on **egress**, and that projects *"will be restricted from 12 Nov, 2026 if your organization remains over quota"*. The database size keeps the organization over quota.
- Remaining egress by our own count: 5,000 − 4,247 − 629 (R2) ≈ **124 MB**, less anything else on 8 Nov. It is confirmed only when the 8 Nov bar appears.

Three read-only queries in the SQL editor, returning 7, 6 and 5 rows, with no write keywords:

- database size 1,600,736,403 bytes, byte-identical to the R2 snapshot;
- `public.outbox_events`: 538 MB in total, of which 465 MB is heap;
- every pending row has `attempt_count` 0 and no `last_error`, so none is dead-lettered;
- `work_mem` 2,184 kB, `hash_mem_multiplier` 2;
- pgcrypto (schema `extensions`) compresses with zlib.

Compressed size per row, from 20,000-row samples at the start and middle of each group:

| Encoding | Pending | Published |
|---|---:|---:|
| `to_jsonb` text | 69.1 B | 74.5–75.0 B |
| record text | 67.2 B | 73.0 B |
| **compact v1** (`sql/outbox-encode-rows.sql`) | **46.5 B** | **51.3 B** |

So the pending parts cost about 38 MB and the published part about 48 MB: **about 86 MB for every row**, inside the remaining egress.

#### Priorities 1 and 3: an exact export of all 1,670,004 outbox rows (built and tested)

`export-outbox.ps1` (README, "Outbox export"):

- one read-only snapshot;
- the table's shape checked first;
- compact rows compressed on the source;
- a clean stop before the cap, plus the hard watchdog;
- every chunk decoded and checked against the source (SHA-256, rows, fingerprint) in a RAM-only container;
- the archive restored and fingerprinted again;
- the passphrase proven on the R2 archive before any connection;
- gpg like R2.

`verify-outbox-export.ps1` then proves that the runs add up to R2's whole-table fingerprint.

Tests, synthetic only (`sql/dryrun-outbox-source.sql`):

| Test | Result |
|---|---|
| Edge cases, all parts, 500-row chunks: non-v7 ids; payloads with extra keys, non-string actions, odd sources, a different job_id, control characters, Unicode and large numbers; NULL and empty strings; published before created; far past and future times; sequence gaps; aggregates outside `jobs` | exact: fingerprint equal to the source; an independent restore equals the source row for row (7,076 rows, 0 differences) |
| Every-table fingerprints against R2 | PASS when nothing changed. A changed `public.jobs` row gives FAIL; a changed `storage.migrations` row gives a NOTE. |
| Full scale: 60,960 jobs, 771,636 + 23 pending, 899,243 published | pending run 68 s, 28.65 MB, container peak 0.83 GB. Published run 81 s, 35.58 MB, peak 0.93 GB. Together they equal the whole table (1,670,902 rows, fingerprint equal). |
| A 25 MB cap on the full-scale table | a clean stop before B1 chunk 16, at 22.64 MB received (the next chunk could have needed 3.26 MB). Everything that arrived is verified and archived. The status is PARTIAL, not failed. |
| `verify-outbox-export.ps1` on the two full-scale runs | PASS: 771,659 + 899,243 rows, and the fingerprints add up to the table's. FAIL against a different R2 record. |
| A corrupted byte in one chunk; one row changed after decoding | both detected (`chunk_decode`, `archive_restores_exactly`) |
| The wrong passphrase | refused **before connecting** |
| An extra column on the source table | refused before any row is read |
| Swappable container; data directory on disk | `FAIL plaintext_in_ram`, nothing read |
| Live mode with test hooks, without a cap, or without TLS; free RAM below the minimum | refused |
| R2 original and copy | unchanged throughout |

The final run of the suite: **19 PASS, 0 FAIL** (8 Nov 21:48–21:56 UTC). The test driver is kept outside the repository, like R3's. No production system was contacted.

The production runs (each needs you at the terminal):

| Run | Parts | Estimate | Cap | When |
|---|---|---:|---:|---|
| E1 | D, A1, S, B1, plus every-table fingerprints (`-FingerprintAllTables`) | about 38 MB | 48 MB | now: before 12 Nov |
| E3 | D, B2 | about 48 MB | the fresh remaining egress minus 25 MB | once the 8 Nov bar shows E1 and R2 on the usage page |

If the budget check stops E1 or E3 early, the chunks that arrived are kept and verified, and a later run fetches the rest.

#### Priority 2: rebuilding the 771,636 pending `job.discovered` events (evidence)

From the code:

1. **Exactly two consumers.** Only `ApplicationPipelineEventHandler` (consumer `application-pipeline`) and `NotificationEventHandler` (`notification-fanout`) handle `job.discovered`; they are the only two `EventHandler` implementations.
2. **`application-pipeline` ignores the event's details.** `ApplicationPipelineService.onJobIngested(jobId, action)` never reads `action`. It re-reads the job and, for every profile, does the following, all of it a function of the current rows (no clock is read):
   - runs the hard filter;
   - `persistFilterRejection` writes only `where status = 'DISCOVERED'`;
   - `JobMatchService.evaluateMatch` upserts `job_matches` on (profile, job);
   - it sets `SCORED` only from `DISCOVERED` or `ANALYSED`;
   - it emits `job.matched` for APPLY.
3. **`notification-fanout` keeps one notice per job.** Its dedup key is `job-discovered:<job id>` (`insertIfAbsent` on `notifications_dedup_key_uq`). The owner is NULL: a system notice that no user query returns.
4. **What follows `job.matched` is idempotent per (profile, job):**
   - decisions are upserted on (profile, job), and only while not terminal (`ApplicationDecisionService.record`);
   - one live application per (profile, job), from a pre-check plus `applications_profile_job_live_uq`;
   - an unused quota reservation is released;
   - notifications are deduplicated per job.
5. **`consumed_events` protects only the same event id.** A rebuilt event has a new id, so its safety comes from points 2–4, not from `consumed_events`.

What differs between replaying a job's k events and replaying one:

- bookkeeping:
  - `consumed_events`;
  - k − 1 more `job.discovered` rows, and k − 1 more `job.matched` rows per APPLY profile;
  - `published_at`;
- timestamps (`scored_at`, `updated_at`);
- the stored payload of the invisible `job-discovered` notice;
- one audit row per repeated `job.matched` for a cross-source duplicate refusal;
- metrics.

The rebuilt trigger must be limited to jobs that **have** pending events (part S lists them, with each job's last pending sequence_id), and placed at that last position. A trigger for every job in R2 would **not** be equivalent: it would re-score jobs that have nothing pending.

Empirical test: `OutboxBacklogCompactionEquivalenceIT`. It runs the real ingestion, the real `OutboxProcessor` and both real consumers, from one database snapshot:

- once with the whole backlog;
- once with one rebuilt event per job.

It then compares jobs, matches, decisions, applications, the application timeline, notifications and every other table's row count.

The fixture:

- three profiles: one auto-applying, one assisted, one without preferences;
- 14 jobs, from two sources;
- first sight, 1–12 re-crawls, edits, and the same posting on another board;
- two user-facing events interleaved.

**Result, 8 Nov 21:58 UTC: PASS** (1 test, 0 failures, 46 s).

| | Whole backlog | One rebuilt event per job |
|---|---:|---:|
| Pending `job.discovered` at the start | 109 (14 jobs) | 14 |
| Dispatcher rounds to drain | 8 | 5 |
| **Business state** (jobs, 38 matches, 17 decisions, 17 applications with 85 timeline rows, 118 notifications, row counts of the 66 other tables) | **identical** | **identical** |
| `job.matched` events produced | 137 | 17 |
| `application.created` / `application.prepared` | 17 / 17 | 17 / 17 |
| `consumed_events` rows | 596 | 166 |
| Audit rows | 118 | 118 |
| Dead letters | 0 | 0 |

So the rebuild is functionally equivalent, under the conditions above: only jobs with pending events, each at its last position. It drains in fewer rounds and produces 8× fewer downstream events. With the exact rows exported (E1), the cutover can keep every original row and supersede the redundant ones instead of rebuilding anything.

**Throughput.** The dispatcher handles at most 50 events per 2-second poll. At that rate:

- 771,636 events take at least 8.6 hours, and hold the 23 user-facing events, and anything new, behind them;
- one event per job (about 61,000) takes at least 41 minutes.

**Recommendation for cutover** (needs your approval, and its own test first):

- keep every exported row;
- mark each job's earlier pending `job.discovered` rows as superseded: record them in a `robin_migration.superseded_events` table and set `published_at`;
- leave only each job's last original event pending.

Nothing is deleted, and the history stays complete.

#### Priority 4: R4 decisions that are yours

| Decision | Recommendation | Reason |
|---|---|---|
| Oracle Cloud account | you create it (Always Free sign-up; it asks for a card to verify identity) | account creation is yours |
| Home region (**permanent**) | Germany Central (Frankfurt) | the backend runs in Render's Frankfurt region, and Always Free compute must be in the home region |
| Pay As You Go | not at first; switch only if "Out of host capacity" persists | Always Free resources stay free on PAYG, but its exemption from reclamation is not documented |
| Shape | `VM.Standard.A1.Flex`, 2 OCPU, 12 GB, Ubuntu 24.04 aarch64 | the whole Always Free A1 allowance; `provision-postgres17.sh` requires Ubuntu 24.04 |
| Storage | boot volume 50 GB; block volume 100 GB at `/srv/pgdata` | 150 of the free 200 GB |
| Public address | a reserved public IPv4 | the certificate and Render's allowlist stay valid across restarts |
| Port 5432 | open only to Render's Frankfurt outbound ranges (Render dashboard) | they are shared ranges, so TLS + SCRAM + `robin_app` only (already in the script) |
| SSH | OCI Bastion, no open port 22; or your IP only | |
| Certificate name | the reserved IP (no DNS needed), or a DNS name you own | |
| Backups on the VM | a private Object Storage bucket, plus a backup **public** key you generate; keep the private key offline | the VM can never decrypt its own backups |

#### Priority 5: offsite copy (built and tested)

`copy-offsite.ps1` (README, "Offsite copy"):

- copies only ciphertext and metadata;
- checks every archive against its recorded hash;
- verifies every copy byte for byte and marks it read-only;
- writes a receipt;
- verifies, instead of copying, on a second run;
- never overwrites a differing copy;
- refuses the same volume and OneDrive unless you allow them.

Tests 9/9 PASS:

- copy and verify;
- a comma-separated list of folders through `powershell -File`;
- re-run verifies;
- a damaged copy is reported and not overwritten;
- same volume refused;
- OneDrive refused;
- a folder with an unencrypted archive refused;
- an archive that no longer matches its hash refused;
- both R2 folders unchanged.

**Your decision:** the destination. An external USB drive is recommended. OneDrive means uploading the ciphertext to Microsoft, and Object Storage needs R4.

#### Priority 6: end-to-end execution plan (shortest safe route)

With R2 plus E1 and E3 verified against R2's whole-table fingerprints, and E1's every-table fingerprints proving `public` unchanged, the complete backup no longer depends on 18 Nov or on reaching Supabase after 12 Nov. R5 becomes an optional confirmation. This changes your earlier rule *"Do not skip the full backup"* in form, not in substance: the full backup is assembled from verified parts. **It needs your agreement.**

| Step | What | Depends on | Your part | Supabase egress |
|---|---|---|---|---:|
| E1 | outbox export, pending parts + every-table fingerprints | tools (done) | type host, user, password, passphrase | about 38 MB |
| E2 | usage reading that includes 8 Nov | the day's bar | none (read-only) | 0 |
| E3 | outbox export, published part | E1, E2 | type the secrets again | about 48 MB |
| E4 | `verify-outbox-export.ps1` on E1 + E3 | E3 | none | 0 |
| O1 | offsite copy of R2, E1, E3 | your destination; E3 | plug in the drive | 0 |
| K1 | code: dispatch kill switch, maintenance pause, supersession step, with tests (§6, §10.7) | your approval | approve, review | 0 |
| V1 | Oracle account and home region | you | sign up | 0 |
| V2 | VM, volumes, VCN, reserved IP, security list, Bastion | V1 | console, or me in the browser with your approval | 0 |
| V3 | `provision-postgres17.sh` on the VM | V2, Render's ranges | run over SSH | 0 |
| V4 | rehearsal on Oracle: R2 + E1 + E3 into `robin_restore`; Stage C checks; outbox fingerprints; Flyway validate; latency from Render | V3, E4 | passphrase | 0 |
| V5 | nightly encrypted backups on the VM | V3 | approve; key pair | 0 |
| C1 | cutover: restore into `robin`, supersession step, Render variables (§10.6), deploy with dispatch and discovery paused, smoke tests, resume dispatch, then discovery | V4, V5, K1 | approve each change | 0 |
| C2 | week 1: OCI metrics (reclamation, disk), backups, restore test; Supabase kept intact | C1 | — | 0 |
| R5 | optional: complete backup from 18 Nov, compared with R2 | reachable | approve | about 1.1 GB |

- **Critical path:** E1 → E3 → E4 → V4 → C1. V1–V3 and K1 run alongside.
- **Earliest cutover:** about 11 Nov, if the Oracle account exists by 9 Nov. Robin has been offline since R1, so earlier is better.
- No step after E3 reads anything from Supabase.

#### Risks, updated

| Risk | Mitigation |
|---|---|
| Restriction from 12 Nov blocks E3 | E1 first (the pending rows and the job list); E3 as soon as the usage page allows; a budget stop keeps partial progress |
| Log ingestion at 97 % | three SQL editor queries tonight; the export is one session; no polling |
| R2 metered higher than our count | E3's cap comes from the usage page, with a 25 MB margin |
| A1 capacity, or idle reclamation | another availability domain, then PAYG (your decision); metrics in week 1 |
| A forgotten passphrase | E1 and E3 refuse any passphrase that does not open R2 |

### 10.15 Fast-track execution, 8–9 Nov

#### E1 done, 8 Nov 23:10–23:17 UTC: OUTBOX EXPORT OK

You ran it and typed the secrets. Run folder: `C:\RobinBackups\20261108T231043Z-live-outbox-pending`.

| Check | Result |
|---|---|
| Passphrase | it opens the R2 archive: one passphrase for both |
| Source | PostgreSQL 17.6; one REPEATABLE READ, READ ONLY transaction; no temp files on the source |
| Every table against R2 | **all 110 tables equal R2's row count and fingerprint**, so nothing changed since R2 |
| Part A1 | the **23** pending user-facing events, exact: job.matched 11, verification.completed 11, signup.completed 1 |
| Part B1 | **771,636** pending `job.discovered` rows in 20 chunks, exact |
| Part S | 60,723 jobs carry them. 237 of the 60,960 jobs have no pending event, so a trigger for every job would not be equivalent (§10.14). |
| Pending total | 771,659 rows, as in R2; fingerprint 445324708623384356660241 |
| Archive | restored into a fresh database and fingerprinted again: equal. gpg round trip PASS; 54,050,844 bytes, SHA-256 `717a8990…5c840`. |
| Egress | **34.97 MB** received (cap 48 MB) |

On the wire the transfer cost 1.27 × the chunks' size (27.6 MB of chunks). This ratio is used for E3 below.

**Independent checks, after the run (no secrets needed):**

- the archive's SHA-256 equals both the recorded hash and the manifest's;
- E1's 110 table fingerprints are identical to R2's, by `diff`;
- the pending counts by type and the database size (1,600,736,403 bytes) equal R2's;
- no plaintext file and no container was left behind.

**Restore test with your passphrase (R2 + E1 → one PostgreSQL 17 database), 8 Nov 23:50 UTC: PASS.** Report: `C:\RobinBackupsCopy\20261108T155443Z-live-partial\restore-test-20261108T235013Z`. 0 FAIL; the 3 NOTEs are expected (partial scope; the outbox empty before the load; B2 not loaded yet).

- Every R3 check passed again on the backup: inventory (21 keys), 70 tables / 2,079,709 rows by fingerprint, RLS, archive counts.
- The 771,659 pending rows loaded in 4 s as `robin_app`. They equal E1's manifest and R2's snapshot.
- **Every pending event's job, profile and user exists** (60,723 jobs).
- No pending event has a `consumed_events` row.
- The sequence cannot collide (1,670,005).
- Flyway validate PASS: 52 history rows, version 051, 0 pending.
- The pending `job.discovered` rows by action: TOUCHED 656,990, INSERTED 58,292, UPDATED 56,354.

**Egress, 9 Nov 00:05 UTC.** The dashboard still shows 4.247 GB, with daily bars to 7 Nov only; log ingestion is at 0.969 GB, and 8 Nov added 70.7 KB. By our measured counts:

| | MB |
|---|---:|
| Dashboard total | 4,247 |
| R2 on 8 Nov | 628.9 |
| E1 on 8 Nov | 35.0 |
| Dashboard queries, upper bound | 1 |
| **Left** | **about 88** |

E3 needs about 46 MB. **Cap 55 MB**: that is 20 % headroom, and it leaves at least 33 MB by our count. The one assumption left: Supabase meters R2 and E1 no higher than we received them. The 8 Nov bar will settle it.

**E3 exporter.** The 10 files are byte-identical to the 19/19-tested version that E1 ran, with no change since `99b6b08`; the image is the same.

#### Tooling added for R6, rehearsed end to end

`restore-test.ps1 -OutboxRunDir <E1>[,<E3>]` (with `container/restore.sh` step 10):

1. It decrypts the outbox exports with the backup's passphrase, in the same RAM-only container.
2. It runs every R3 check on the backup first.
3. It loads each export data-only into the restored `public.outbox_events`: R2's own definition, keys and defaults, as `robin_app`, in one transaction.
4. It then checks:
   - the table against each export's manifest;
   - the pending rows against the snapshot;
   - with E3 loaded, the **whole table** against R2's record of it (rows and fingerprint);
   - that every pending event's job, profile and user exists;
   - that no pending event was ever consumed;
   - that the outbox sequence cannot collide.

It works the same way against Oracle (`-Target remote`), which makes it the R6 tool.

Rehearsal on a Flyway-migrated, Supabase-shaped synthetic source, in the production order (partial backup, pending export, restore, published export, restore), 8 Nov 23:23–23:25 UTC: **9/9 PASS**.

| Step | Result |
|---|---|
| Partial backup, outbox rows deferred | PASS |
| Pending export, passphrase proven on that backup | PASS (17 checks) |
| Restore: backup + pending export | 19 PASS, 0 FAIL, Flyway validate PASS. The pending rows equal the export and the snapshot; the NOTE says the published part is not loaded yet. |
| Published export; `verify-outbox-export.ps1` | PASS; 30,023 + 30,000 = 60,023 rows, and the fingerprint equals the snapshot's |
| Restore: backup + both exports | 22 PASS, 0 FAIL, Flyway validate PASS. **`outbox_equals_snapshot`: all 60,023 rows, one fingerprint, equal to the snapshot's record of the table.** |
| An export with a failed check, or a changed archive | refused before anything starts |

The synthetic fixture's 23 other events carry placeholder ids, so `outbox_references` is a NOTE there; on production the real references are checked.

#### E3 prepared, not run (needs your approval)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\jadak\Documents\antigravity\peaceful-chandrasekhar\scripts\db-migration\export-outbox.ps1 -Parts published -EgressCapMB 55 -FingerprintAllTables
```

`-FingerprintAllTables` proves again, at E3's own snapshot, that every table still equals R2. It costs about 2.5 minutes of reads on the source and a few KB of egress.

- Expected size, on the wire:
  - the job dictionary (0.95 MB) plus 898,345 published rows at about 39 bytes each;
  - that is about 36 MB of chunks, × 1.27 on the wire: **about 46 MB**.
- Budget by our count: 5,000 − 4,247 (dashboard, 7 Nov) − 629 (R2) − 35 (E1) − 1 (dashboard queries) = **about 88 MB**.
- **Cap: 55 MB** (updated 9 Nov; it was 60). The budget check stops cleanly before a chunk that could cross it, and even the full cap leaves about 33 MB by our count.
- Conditions, all still open:
  1. E1 passes the restore test above.
  2. A fresh usage reading shows the 8 Nov bar. It should be about 665 MB (R2 + E1); if it is higher, the cap is lowered by the difference.
  3. You approve.

#### After E3: proving R2 + E1 + E3 is the whole database

1. **E3's own checks:**
   - OUTBOX EXPORT OK;
   - `public_unchanged_since_r2` (110 tables);
   - `part_B2` (898,345 rows);
   - `archive_restores_exactly`;
   - `egress_within_cap`.
2. **Independent host-side checks,** as for E1: the archive's hash against the recorded and manifest hashes, E3's table fingerprints against R2's by `diff`, and no plaintext or container left.
3. **The fingerprints add up** (metadata only, no secret):

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\jadak\Documents\antigravity\peaceful-chandrasekhar\scripts\db-migration\verify-outbox-export.ps1 -RunDir C:\RobinBackups\20261108T231043Z-live-outbox-pending,<E3 folder>
   ```

   Expected `equals_r2_snapshot`: 771,659 + 898,345 = 1,670,004 rows, fingerprint 963152130790551762785771.
4. **Everything restores as one database** (your passphrase, once):

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\jadak\Documents\antigravity\peaceful-chandrasekhar\scripts\db-migration\restore-test.ps1 -RunDir C:\RobinBackupsCopy\20261108T155443Z-live-partial -OutboxRunDir C:\RobinBackups\20261108T231043Z-live-outbox-pending,<E3 folder>
   ```

   Expected: every R3 check, `outbox_equals_snapshot` (the whole table, one fingerprint) and Flyway validate.
5. **Offsite copy** of the three folders with `copy-offsite.ps1`, to the destination you choose.

#### Dispatch pause (built and tested; nothing deployed)

| Switch | Effect | Default |
|---|---|---|
| `app.events.dispatch-enabled=false` | `OutboxProcessor.dispatchPending` and `reconcileDlq` neither read nor write `outbox_events`: nothing is published, retried or dead-lettered. One warning is logged. | `true` |
| `app.discovery.maintenance-enabled=false` | the hourly sweep does not archive jobs as "not seen for 30 days" while discovery is paused | `true` |

They join the existing `app.discovery.scheduler-enabled` and `app.discovery.identity-backfill-enabled`.

Tests:

- `OutboxDispatchSwitchTest` and `JobDiscoveryMaintenanceSwitchTest`: unit tests, no interaction at all when paused;
- `OutboxDispatchPausedIT`: the full application, with schedulers firing every 200 ms for 2.5 s. Nothing moves: no publish, no retry, no dead letter, no `consumed_events` row, no archived job.
- Regression checks, with the defaults:
  - `DiscoveryToApplicationIT` (3), `OutboxPipelineIT` and the equivalence test all pass;
  - the 7 existing `JobDiscoveryServiceTest` tests pass.

BUILD SUCCESS: 11 unit tests and 7 integration tests.

#### Render at cutover (prepared; nothing changed)

As §10.6, with one change: the temporary `SPRING_APPLICATION_JSON` for the first start becomes

```json
{"app":{"events":{"dispatch-enabled":false},"discovery":{"scheduler-enabled":false,"identity-backfill-enabled":false,"maintenance-enabled":false}}}
```

Steps:

1. Before the first start, record the current three `SPRING_DATASOURCE_*` values in the password manager. They are the rollback.
2. After the start:
   - `/api/v1/system/health`, `/actuator/health` (database, Redis) and `/actuator/info` respond;
   - the log shows *"Outbox dispatch is PAUSED"*;
   - `select count(*) from outbox_events where published_at is null` stays at 771,659;
   - `pg_stat_ssl` shows TLS for every backend connection.
3. Then the smoke tests of §10.9.
4. Dispatch is turned on by removing `"dispatch-enabled":false` and redeploying, under the backlog policy you approve (§10.14).

#### R4: Oracle, step by step (all steps are yours or need your approval; Always Free only)

In the Oracle Cloud console:

1. **Sign up** for Always Free with home region **Germany Central (Frankfurt)**. The home region cannot be changed later. Turn on MFA.
2. **Compartment** `robin`.
3. **Networking:**
   - the VCN wizard "Create VCN with Internet Connectivity": `robin-vcn`;
   - a **reserved public IPv4**: `robin-db-ip`.
4. **Compute instance** `robin-db`:
   - image Canonical Ubuntu 24.04 (aarch64);
   - shape `VM.Standard.A1.Flex`, 2 OCPU, 12 GB;
   - the public subnet;
   - boot volume 50 GB;
   - your SSH public key, generated on your laptop; the private key never leaves it.

   Afterwards, move the VNIC's public IP from ephemeral to `robin-db-ip`.
5. **Block volume** `robin-pgdata`: 100 GB, Balanced. Attach it to `robin-db` as paravirtualized, read/write, with the consistent device path `/dev/oracleoci/oraclevdb`.
6. **Security list** of the public subnet:
   - ingress TCP 5432 from each of Render's Frankfurt outbound ranges (the "Connect → Outbound" panel of the Render service; it is not shown while the service is suspended);
   - ingress TCP 22 from your IP only, or none if you use OCI Bastion.

On the VM, over SSH:

```bash
sudo bash prepare-vm.sh --device /dev/oracleoci/oraclevdb --mount /srv/pgdata
sudo bash provision-postgres17.sh --data-dir /srv/pgdata --server-ip <reserved IP> --server-ip 127.0.0.1 --allow <your IP>/32
sudo bash install-backup.sh --public-key backup-public.asc --par-url-file par.txt --run-now
# at cutover, once Render's ranges are visible:
sudo bash allow-client.sh add <Render range>
```

Added 9 Nov and tested in containers (results below):

- `prepare-vm.sh` formats and mounts the volume, and never touches a disk that holds anything.
- `allow-client.sh` adds Render's ranges later. This is because Render shows them only while the service runs, so provisioning no longer waits for them.
- `new-backup-key.ps1` makes the backup key pair on the laptop.
- `install-backup.sh` and `robin-backup.sh` take the nightly encrypted backups.

Tests, 9 Nov 00:17–00:32 UTC, local containers only: **32/32 PASS**.

| Script | Tested |
|---|---|
| `prepare-vm.sh` (loop devices, privileged container), 7/7 | Formats, labels, mounts and adds to fstab. A second run is "already prepared". It refuses, writing nothing: an existing filesystem (left intact), a partition table, a non-block path, a non-empty or already-used mount point. |
| Backup on provisioned PostgreSQL 17 (Ubuntu 24.04, PGDG 17.11), 12/12 | Install: root-only settings, key, service, timer. Three runs: retention keeps 2; every archive and manifest uploaded (to an HTTP PUT target standing in for the pre-authenticated request), with equal hashes. **The uploaded archive, decrypted with the private key and restored on a "laptop" container: 50,000 rows, equal to the source.** A secret key is refused, both at install and at backup. A failed dump leaves nothing. A failed upload is reported, the local copy is kept and retention does not run. No plaintext dump on the VM. |
| `new-backup-key.ps1`, 4/4 | Public key with an encryption subkey. A message encrypted to it opens with the private key and its passphrase, and not with a wrong one. An existing key folder is never overwritten. |
| `allow-client.sh` on provisioned PostgreSQL 17, 7/7 | A range not allowed is refused. After `add` it connects over TLS verify-full, from a line placed before the reject rules. Adding it again changes nothing. After `remove` it is refused again. 0.0.0.0/0, invalid addresses and non-CIDRs are refused. |

Not exercised locally: the OCI console, iptables persistence on the real image, systemd timers firing, aarch64, and a real pre-authenticated request.

What the script sets (reviewed again on 8 Nov):

- PGDG PostgreSQL 17 after checking the signing-key fingerprint;
- the cluster on the volume, with data checksums;
- the §5 configuration;
- TLS 1.2 minimum, from a private CA with a server certificate issued for the reserved IP;
- SCRAM only, and `robin_app` only (no superuser, no BYPASSRLS), from the allowed ranges; `robin_restore` from loopback only;
- every other address, IPv6 included, rejected;
- iptables opened for the same ranges, persistently.

`robin_app`'s password is set at a hidden `\password` prompt.

After provisioning:

- copy `/etc/robin/robin-pg-ca.crt` to the laptop; it is public;
- keep the CA private key root-only, or move it offline; it is needed only to renew the server certificate.

#### R6 and cutover: the commands

1. **Rehearsal on Oracle** (`robin_restore`, through an SSH tunnel; `pg_hba` allows it from loopback only):

   ```bash
   ssh -N -L 15432:127.0.0.1:5432 ubuntu@<reserved IP>
   ```

   ```powershell
   powershell -NoProfile -ExecutionPolicy Bypass -File C:\Users\jadak\Documents\antigravity\peaceful-chandrasekhar\scripts\db-migration\restore-test.ps1 -RunDir C:\RobinBackupsCopy\20261108T155443Z-live-partial -OutboxRunDir <E1 folder>,<E3 folder> -Target remote -TargetHost <reserved IP> -TargetHostAddr host.docker.internal -TargetPort 15432 -TargetDb robin_restore -CaCert <path to robin-pg-ca.crt>
   ```

   Expected: every R3 check, plus `outbox_equals_snapshot` (1,670,004 rows, fingerprint 963152130790551762785771) on ARM64 PostgreSQL 17. Then run Flyway validate over the tunnel, and measure latency from Render.
2. **Final restore:** the same command with `-TargetDb robin`, which must be empty.
3. **Switch** (your approval): Render variables as above → deploy → health checks and smoke tests → dispatch on under the approved backlog policy → discovery on.
4. **Supabase stays untouched** until you accept the migration.

#### Inputs needed from you

| When | What |
|---|---|
| ~~now~~ | ~~type the passphrase once for the R2 + E1 restore test~~ (done, PASS) |
| now | approve E3 (cap 55 MB): now, or once the 8 Nov usage bar confirms the meter |
| after E3 | type the passphrase once for the R2 + E1 + E3 restore test |
| R4 | the Oracle sign-up (Frankfurt, MFA); your SSH key pair; Always Free or PAYG; SSH through Bastion or your IP; the backup key's passphrase (`new-backup-key.ps1`); the Object Storage bucket and its write-only pre-authenticated request; `robin_app`'s password (password manager) |
| cutover | Render's Frankfurt outbound ranges (shown while the service runs); approve the backlog policy (whole replay, or supersede the earlier duplicates, §10.14); approve the Render change and the deploy |

**PHASE 2 STATUS: PLANNING**
