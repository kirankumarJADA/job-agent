# Robin Wave 2 — Definitive Release-Readiness Report

**Last updated:** 20 November 2026  
**Branch:** `integration/wave-2` (local only; not pushed)  
**Release candidate:** `ed01408..7108e49` — two commits on top of the 19 November documentation commit `ed01408`:

| Commit | Content |
| :--- | :--- |
| `f609e9fe8cfa726a14d0e809fc003c7160d4f553` | C1 + C2 code: safe-deletion guard, V063/V064 migration split, Flyway session-level lock, regression tests |
| `7108e49748c1e86135d11e72928d0ce72cc79b04` | QA finding F1: test-only correction in `CrossSourceDiscoveryIT` |

**Independent QA:** PASS for both commits and for the combined candidate.  
**Scope of this revision:** documentation only. The commit that carries this text changes no application code, migration or configuration. Nothing has been pushed, merged or deployed; Oracle production, `robin_restore` and Render were not contacted.

---

## 1. Verdict

**The code gates have passed. Production release is NO-GO until F9 is investigated and decided.**

| Gate | Result |
| :--- | :--- |
| C1 — safe-deletion guard (19 November condition) | **Resolved** in `f609e9f` (Option B); follow-up test correction `7108e49` |
| C2 — V063 index lockout (19 November condition) | **Resolved** in `f609e9f` (Option C) |
| Full `mvn -B clean verify` of the exact tree `7108e49` | **Passed** — exit code 0 |
| Read-only validation against disposable PostgreSQL 17.11 | **Passed** — 1/1 |
| Independent QA, two rounds | **PASS** |
| F9 — hourly cleanup soft-deletes jobs with no current observation | **OPEN — unresolved release risk** (§7) |

The 19 November gate withheld approval on two conditions, C1 and C2. Both are now resolved, verified and
independently reviewed. QA found one new risk, F9. It lies outside C1/C2, it has not been investigated, and
its production impact is unknown, so it has to be answered before a production go/no-go decision.

The findings in §6 do not block the code, but they constrain how the release must be run: which connection
to migrate through, when to migrate and how to recover. Most of them are documented rather than tested, and
§5 and §6 say which.

Standing limits this release does not change:

- **Phase 20 remains OPEN.**
- **No ATS submission has been externally confirmed;** `REAL_SUBMIT` stays hard-stopped (§A.7).
- **No synthetic benchmark establishes commercial superiority.**

---

## 2. Resolution of C1 and C2

### C1 — safe-deletion guard: Option B (`f609e9f`)

- `BoardDiscoveryService.run(...)` removes postings only after a listing that is complete, not failed **and
  non-empty**: `listing.complete() && listing.failure() == null && !listing.entries().isEmpty()`. The
  MANUAL-mode bypass that was added after Agent 6's sign-off is reverted, so an empty listing never removes or
  unlists jobs in any of the four modes (`SCHEDULED`, `MANUAL`, `HEALTH_CHECK`, `IMPORT`). Independent QA traced
  all six callers through this single guard.
- The `error_summary` parameter-binding correction from the same earlier change is kept: the failure-path
  update binds four arguments to four placeholders.
- `BoardDiscoveryServiceTest` gained four regression tests: an empty listing in every mode, a failed MANUAL
  listing, an incomplete MANUAL listing, and a complete non-empty MANUAL listing that still synchronises. When
  QA reintroduced the bypass, the all-modes test failed.
- Two integration tests depended on the bypass through an empty authoritative listing. Both now use a complete,
  non-empty listing, and no assertion changed:
  - `anAggregatorDoesNotKeepAJobAliveOnceAuthoritativeSourceClosesIt` failed outright and was corrected in
    `f609e9f`.
  - `aJobStillListedByAnotherAuthoritativeSourceIsNotRemoved` kept passing but no longer tested anything (QA
    finding F1), and was corrected in `7108e49`. This was proved rather than assumed: with the retention clause
    deliberately broken in an isolated export, the original test still passed 13/13 and the corrected test
    failed on exactly that test. QA repeated the experiment in its own export of `7108e49`.

### C2 — V063 index lockout: Option C (`f609e9f`)

- **V063** keeps only its two `ALTER TABLE applications ADD COLUMN` statements.
- **V064** (`V064__application_follow_up_index_concurrent.sql`) builds `applications_follow_up_idx` with
  `CREATE INDEX CONCURRENTLY IF NOT EXISTS`. A guarded block first drops an *invalid* leftover of that name from
  an interrupted build. Its companion `.sql.conf` sets `executeInTransaction=false`.
- **`spring.flyway.postgresql.transactional-lock: false`** in `application.yml`. This was not part of the
  original C2 plan; testing showed it is required. Flyway's default PostgreSQL schema-history lock keeps Flyway's
  own connection idle in a transaction for the whole run, and `CREATE INDEX CONCURRENTLY` waits for that
  transaction forever. Measured: the Flyway CLI hung until it was killed after about 26 minutes, with
  `pg_stat_activity` showing the index build waiting on `Lock/virtualxid` behind Flyway's `idle in transaction`
  session. QA reproduced the same hang independently.
- **Hand-built Flyway instances.** Five integration tests build Flyway directly and so never read Spring
  properties. One of them, `FlywayBootstrapRecoveryStrategyIT`, hung a full verification until the run's
  45-minute timeout killed it. (That run's log was later overwritten; QA reproduced the mechanism.) The five now
  load through the test helper `FlywayTestSupport`, which applies the same session-level lock. The ArchUnit test
  `FlywayHandBuiltLockGuardTest` fails the build on any other `FluentConfiguration.load()` or
  `new Flyway(Configuration)`; before the five tests were rewired it reported exactly 11 violations.
- **Index name.** `applications_follow_up_idx` is the only real name. `idx_applications_follow_up_due`, which
  the withdrawn down-migration script named, never existed.
- **V063's checksum changed.** Wave 2 has never been deployed, so production never applied the old V063. Any
  database that did must be repaired before it migrates again (§5).

---

## 3. Verification evidence for the release candidate

### 3.1 Full build of the exact tree `7108e49`

One `mvn -B clean verify`, run on 20 November 2026. Before the run the tree was recorded as commit `7108e49`,
tree `c60a3d3d3c6e50a2e32b9b2d4569da9dfa64876e`, working tree clean; it was identical afterwards.

| Item | Result |
| :--- | :--- |
| Maven exit code | **0** |
| Build result | `BUILD SUCCESS`, total time **16m33s** |
| Surefire | **1,644 tests, 0 failures, 0 errors, 6 skipped** (191 report files, all written by this run) |
| Failsafe | **327 tests, 0 failures, 0 errors, 7 skipped** (52 report files, all written by this run) |
| Integration classes | **52 of 52 completed** |

- The 13 skipped tests are environment gates, not defects. Six Surefire tests need a Redis server on
  `localhost:6379`. Seven Failsafe tests need a system property: four need `live.discovery`, and three need an
  external database URL — one of those, `ExternalDatabaseFlywayIT`, then ran separately (§3.2).
- Every one of the 52 integration-test databases logged V064 as applied `[non-transactional]`, with no
  advisory-lock errors.
- Surefire grew from 1,636 tests at `51961ba` to 1,644: four C1 regression tests and four Flyway lock-guard
  tests.
- The frontend, worker and email-worker suites (439, 262 and 17 tests) passed on 19 November against the same
  frontend and worker code; neither later commit touches them.

### 3.2 Read-only validation against disposable PostgreSQL 17.11

`ExternalDatabaseFlywayIT` in its read-only `validate` mode, against a throwaway local container reporting
`17.11 (Debian 17.11-1.pgdg13+2)`:

| Item | Result |
| :--- | :--- |
| Exit code | 0, `BUILD SUCCESS` |
| Test | **1/1 passed**, 0 skipped — it executed rather than skipping |
| Reported | `applied=63`, `current=064`, `pending=0`, `invalid=0`, `failed=0`, `shipped=63` |

### 3.3 Migration rehearsal on PostgreSQL 17.11 (Flyway CLI 10.20.1, session-level lock)

| Phase | Result |
| :--- | :--- |
| Fresh V001–V064 | 63 migrations, 0 failed, in 0.619s; V064 ran `[non-transactional]` in 18 ms; all indexes valid and ready; no invalid index anywhere in the schema |
| Upgrade from the Wave 1 schema (V060) | V061–V064 (4 migrations) in 0.061s; index valid; defaults correct |
| Interrupted concurrent build | Index forced invalid; re-running V064 dropped it and left exactly one valid copy |
| Failed non-transactional migration | Recorded with `success=false`; `flyway repair` cleared it; `validate` then passed for 63 migrations |
| Locks | V063 now holds only `AccessExclusiveLock`, with no index build inside it. `CREATE INDEX CONCURRENTLY` is refused inside a transaction block. On a 3,000,000-row table it holds `ShareUpdateExclusiveLock`, and a concurrent `INSERT` succeeded during the build |

The rehearsal ran the Flyway CLI with the session-lock flag, not the application. The application's own
migration path is covered by the 52 integration-test databases in §3.1.

**All of §3 is evidence from disposable databases, not production evidence.** No migration has been run
against Oracle production, and production table sizes, traffic and connection path were not measured.

---

## 4. Independent QA

A separate QA agent, which did not write the code, reviewed the committed git objects in two rounds.

| Round | Commit | Verdict |
| :--- | :--- | :--- |
| 1 | `f609e9f` (C1 + C2) | **PASS**, with one finding to fix before merge (F1) |
| 2 | `7108e49` (F1 correction), and the combined `ed01408..7108e49` | **PASS**; F1 resolved |

QA checked the main claims itself rather than relying on the author's evidence. It:

- traced every caller through the guard, in all four modes;
- mutation-tested the C1 tests and the F1 correction;
- reproduced the V064 hang by removing the session-lock setting;
- confirmed with `javap` that Spring Boot 3.3.4 applies `spring.flyway.postgresql.transactional-lock`;
- checked that every test report was written by the verified run and that the tree hashes matched.

Its non-blocking findings are recorded in §6 (F2, F3, F5, F6), §7 (F9) and §8. QA did **not** verify:
production state, table sizes and connection path; migrating from the packaged jar; a database that already
ran the old V063 going through `flyway repair` to V064 (reasoned, not run); or connection-pooler behaviour.

---

## 5. Corrected recovery guidance

**Two statements in the 19 November documents were wrong. They are corrected here and in
`docs/agents/WAVE2_FINAL_REPORT.md` §5.3.**

1. **"Roll back the application, not the schema: the previous build runs unchanged against a V063 schema."
   This is false for this application.** At startup, `FlywayBootstrapRecoveryPolicy` requires the database's
   applied migrations to be an unbroken prefix of the migrations the jar ships (`isUnbrokenPrefix`). A build
   that ships only up to V060 therefore refuses to start against a database that has applied V061–V064. The
   check predates Wave 2; the earlier guidance overlooked it. **Once V061–V064 are applied, redeploying the
   previous build is not a recovery option.**
2. **"A harmful index can be removed by a V064." V064 is now taken.** Any later change to
   `applications_follow_up_idx`, including removing it, must be a new migration numbered **V065 or later**.
   `flyway_schema_history` stays append-only: never delete rows from it.

Recovery once V061–V064 have been applied:

| Approach | Status |
| :--- | :--- |
| **Fix forward:** a corrected build whose schema fixes are new migrations (V065 and later) | The intended path. The migration mechanics are rehearsed (§3.3); a real fix-forward release has not been exercised |
| **Disable the faulty feature in the application layer**, leaving the schema in place | Depends on the feature; not exercised |
| **Fail-safe rollback build:** the previous application code packaged with the V061–V064 migration files, so the applied chain is again a prefix of the shipped chain | **Unverified.** It should start, because V061–V064 are purely additive (new nullable columns or columns with constant defaults, one new table, indexes) and the earlier code ignores them. It has never been built or tested and must be rehearsed before anyone relies on it |
| **Restore from a backup taken before the migrations** | **Destroys** everything written afterwards, including bookmarks, follow-up state and health history. Only with explicit owner approval and a verified backup; not rehearsed for Wave 2 |
| **A database that already ran the old V063** (a developer or staging copy of Wave 2; never production) | Realign its checksums with `flyway repair` before the application starts. The next migration (which must use the session-level lock) then finds the index already valid, and V064 records without rebuilding it. Reasoned by QA; not run |

A schema-reversal script is still not published, for the reasons given in §A.5.

---

## 6. Outstanding findings: non-blocking for the code, binding for operations

| ID | Finding | Tested | Not verified, or required before release |
| :--- | :--- | :--- | :--- |
| **F2** | **The session-level lock does not work through a transaction-mode connection pooler.** Flyway 10.20.1 takes the lock with `pg_try_advisory_lock` and releases it with `pg_advisory_unlock`. Through a transaction-mode pooler the unlock can reach a different backend: Flyway then fails ("Unable to release PostgreSQL advisory lock"), the lock leaks on the pooled backend, and two migrators are no longer kept apart. `DEPLOYMENT.md` and `application-prod.yml` still offer Supabase's transaction pooler (port 6543). | Flyway's lock implementation was inspected (QA, `javap`). The Phase 2 production target is a direct connection. | Never tested through a pooler. **Migrations must run over a direct or session-mode connection.** The pooler guidance in those two files should be corrected before the next deployment. |
| **F3** | **V064's concurrent index build waits for every older transaction in the whole database**, not only those touching `applications`, including `pg_dump` and any session left idle in a transaction. No `lock_timeout` or `statement_timeout` is set, so application startup can block until such a session ends. | The wait itself was reproduced: it is the mechanism behind the original hang. | Production transaction patterns are unknown. **Migrate outside backup windows, watching `pg_stat_activity` for long-running and idle-in-transaction sessions.** Adding timeouts was deliberately excluded from this change. |
| **F5** | **V063 still takes `AccessExclusiveLock` on `applications`.** Its two `ADD COLUMN` statements rewrite nothing, so the lock is held only briefly, but acquiring it waits behind any in-flight transaction on the table, and every later query on `applications` queues behind that waiting request. | Measured: V063 holds only `AccessExclusiveLock` (§3.3). | Production queueing is unknown, and the comment in V063 does not mention it. Same handling as F3. V061 does a similar in-transaction index build on the small `job_sources` table. |
| **F6** | **Any Flyway run outside the application needs the same session-level lock** (`postgresql.transactional.lock=false`), or V064 hangs. The repository has **no dedicated repair tool**: `ExternalDatabaseFlywayIT` supports `validate`, `validate-restore`, `migrate`, `migrate-restore` and `migrate-clean`, but not `repair`. | The hang without the setting and success with it are both measured. `flyway repair` was run with the Flyway CLI in the rehearsal (§3.3). | Operator runbooks must set the flag. A repair procedure for production has not been written or rehearsed. |

**F4**, also non-blocking, concerns V064's repair block. Its plain `DROP INDEX` needs `AccessExclusiveLock` and
runs without a `lock_timeout`; its catalog check is not schema-qualified; and an orphaned concurrent build that
finishes while the `DROP` waits would be dropped and rebuilt for nothing. Adding `lock_timeout` was excluded
from this change by the owner.

---

## 7. F9 — unresolved release risk: the hourly cleanup soft-deletes unrelated jobs

**Status: OPEN. Not investigated, not fixed, and not covered by C1. A production go/no-go needs a separate
investigation and decision first.**

**What the code does.** The hourly cleanup in `JobDiscoveryService` expires stale aggregator observations.
Whenever it expires at least one (`expiredObs > 0`, `JobDiscoveryService.java:428`), it then runs:

```sql
update jobs j set deleted_at = now()
where j.deleted_at is null
  and not exists (
      select 1 from job_source_observations o
      where o.job_id = j.id and o.source_status = 'SEEN'
  )
```

That statement is **not limited to the jobs whose observations just expired.** It sets `deleted_at` on every
live job in the table that has no current `SEEN` observation from any source, which can include jobs unrelated
to the expiry. The behaviour arrived with Wave 2 commit `8d14072` ("discovery: source-aware removal authority,
run history and churn reduction"), which is not on `master`.

**Why it matters.** It is triggered by time, not by a listing, so the C1 guard does not touch it. The first
hourly run that expires any aggregator observation could soft-delete every live job lacking a current
sighting. Rows are not removed, but jobs with `deleted_at` set are treated as removed, so they would disappear
from what candidates see until something restores them.

**What is unknown:**

- how many production jobs have no `SEEN` observation — for example legacy rows, manually created jobs, or jobs
  whose every source has gone `MISSING` or `CLOSED`. Production was not queried;
- whether any automatic path restores jobs deleted this way;
- whether the statement's breadth is intended.

**What is needed before a production decision:** a separate, authorised investigation that measures the
affected rows on a safe copy of production data, establishes the intended behaviour, and decides whether to
fix, narrow or accept it. No such investigation has been carried out.

---

## 8. Other QA observations (informational)

- **F7 (a consequence of Option B).** A board the employer has genuinely emptied is never reconciled by any
  sync; its jobs are archived only after 30 days, and only while their status is `DISCOVERED`. There is no
  operator override.
- **F8 (pre-existing).** The guard only checks that a listing has entries. A complete listing whose entries are
  all invalid — for example after a feed changes shape — still marks every known posting missing.
- **F10.** One of the new C1 tests overlaps an existing test.
- **F11.** The ArchUnit guard can be bypassed with a method reference, reflection or a Spring property
  override; QA judged this acceptable.
- **F13.** Nothing tests that the `.sql.conf` is picked up when migrating from the packaged jar, although the
  jar contains both files. The "Flyway 9.14 or newer" comment is unverified.
- **Build metadata.** Builds in this worktree record the main checkout's commit (`0825275`) rather than the
  worktree's, so the tree identity in §3.1 rests on recorded hashes, not on build metadata.

---

## 9. Unresolved release risks

1. **F9** — the hourly cleanup may soft-delete unrelated jobs, with unknown impact (§7). **Blocks the
   production go/no-go.**
2. **Rollback** — the previous build cannot start against a V064 database; the fail-safe rollback build is
   unverified, and restoring a backup destroys post-migration data (§5).
3. **F2** — transaction-mode poolers are incompatible with the session-level lock, and the repository's pooler
   guidance still offers one.
4. **F3 and F5** — V064 can wait on older transactions database-wide, V063 can queue traffic behind its lock
   request, and no timeouts are set.
5. **F6** — Flyway runs outside the application need the session-lock setting, and no production repair
   procedure exists or has been rehearsed.
6. **Untested operations** — migrating from the packaged jar, a real fix-forward release, and the repair path
   from an old-V063 database.
7. **Unmeasured production** — table sizes, traffic, long-transaction patterns and the connection path.
8. **Standing limits** — Phase 20 remains OPEN, and no ATS submission has been externally confirmed.

---

## Appendix A — Record of the 19 November 2026 approval gate

The sections below are the 19 November gate as it was recorded at the time, against code commit `51961ba`.
They are kept as evidence. Headings are renumbered A.1–A.11 and cross-references adjusted to match; otherwise
the wording is as recorded, apart from the dated notes, which mark statements that have since been resolved or
corrected.

Original header: dated 19 November 2026; branch `integration/wave-2`; code commit `51961ba`; documentation
commit at gate entry `01c78fa`; prepared at the final Wave 2 approval gate (gates 1–9). Scope:
documentation-only — no Java, TypeScript, JavaScript, SQL, migration, configuration or environment file was
modified; no production database was contacted; no backup or export was run; no Render change was made;
nothing was deployed; and nothing was merged into `master`.

### A.1 Verdict as issued on 19 November 2026 (superseded by §1)

**Wave 2 is NOT approved for merge as it stands. It is ready for review with two conditions outstanding.**

The previous revision of the acceptance report concluded "fully hardened, verified, and ready for user merge
approval" and recorded Agent 6's verdict as "APPROVED WITHOUT RESERVATIONS". That conclusion cannot be
sustained unchanged: three of its supporting claims were wrong or unsupported, and one safety-relevant
production change was never reviewed. All five automated suites do pass, and the migrations do apply cleanly
to the exact target PostgreSQL minor version — so the work is sound in substance, but the sign-off was
broader than the evidence.

#### Conditions to clear before merge

| # | Condition | Why | Where |
| :--- | :--- | :--- | :--- |
| **C1** | Decide on the relaxed safe-deletion guard: add a test for the `MANUAL`-mode empty-listing path, or revert that relaxation. | `BoardDiscoveryService` now treats an empty-but-complete listing as authoritative in `MANUAL` mode and calls `markUnlisted`, so an admin-triggered sync returning zero jobs can mark active inventory deleted. No test covers it, and it landed **after** independent QA sign-off. | §A.5 below, report §6.1 |
| **C2** | Decide how `applications_follow_up_idx` is built in production: confirm the table is small enough for a brief full lockout, schedule it in a maintenance window, or move it to `CREATE INDEX CONCURRENTLY`. | `V063` holds `AccessExclusiveLock` on `applications` for its whole transaction, so the index build blocks reads **and** writes. Production row count is unknown — the production database was not contacted. | §A.4 below, report §5.2 |

Both conditions require code or configuration changes, which this gate is not permitted to make.

#### Standing limits that merge does not change

- **Phase 20 remains OPEN.** Its live-production verification items are unaffected by Wave 2 and must not be
  inferred from these results.
- **No ATS submission has been externally confirmed.** `REAL_SUBMIT` stays hard-stopped; every ATS adapter is
  evidenced by mocked route interception and captured fixtures only.
- **No synthetic benchmark establishes commercial superiority.** The held-out dataset measures ranking
  mathematics under controlled conditions, nothing more.

---
#### Merge mechanics

`integration/wave-2` contains all of `origin/master` — 0 commits behind, 31 ahead, merge-base `779a6be2`
which is the `origin/master` tip. It is current with `master` as of 19 November 2026, so clearing C1 and C2 is
the only thing standing between this branch and a clean merge. Nothing has been merged, pushed or deployed.

*Note, 20 November 2026: C1 and C2 have since been cleared (§2). The branch is now 34 commits ahead of, and 0 behind,
the locally known `origin/master`; F9 (§7) now stands in the way of a release decision.*

---

### A.2 Gate 1 — `01c78fa` is documentation-only relative to `51961ba`

**CONFIRMED.** `git diff --name-status 51961ba 01c78fa` returns exactly one path:

```
M	docs/agents/WAVE2_FINAL_REPORT.md
```

One commit, one file, +119/−218. No source, test, SQL, configuration or build file differs. The Wave 2 code
under review is therefore `51961ba` in full, and `01c78fa` changes only how it is described.

---

### A.3 Gate 2 — `V001`–`V063` executed against disposable PostgreSQL 17.11

**PASSED — executed, not inferred.** The previous revision rehearsed on PostgreSQL **18.4** and reasoned about
17.11 from SQL syntax. The migrations have now been run against a throwaway 17.11 container whose
`server_version` reports `17.11 (Debian 17.11-1.pgdg13+2)`, matching the target Oracle cluster's minor
version.

| Property | Measured value |
| :--- | :--- |
| Flyway | 10.20.1 (the version pinned in `backend/pom.xml`) |
| Migrations applied | **62** — `V001`–`V063`, with `V058` absent from the repository (an expected version gap, not a failure) |
| Flyway result | `Successfully applied 62 migrations ... now at version v063`, exit code **0** |
| Execution time | 0.711 s on an empty schema |
| `flyway_schema_history` | 62 rows, **62 successful, 0 failed**, min `001`, max `063` |
| Placeholder | `remove_seed_dev_account=true`, required by `V024` |

Schema verified on the same instance after the run: `job_sources.health_status` NOT NULL DEFAULT
`'HEALTHY'::text`, `consecutive_failures` NOT NULL DEFAULT `0`, `last_successful_run_at` and `error_summary`
nullable; `applications.follow_up_status` NOT NULL DEFAULT `'NONE'::text`, `follow_up_due_at` nullable;
`job_bookmarks` present; `job_sources_health_idx`, `applications_follow_up_idx` and `job_bookmarks_profile_idx`
all created.

The instance was a local disposable container. **The Oracle production cluster was not contacted and no
migration was run against it.**

---

### A.4 Gate 3 — Migration locking behaviour, including indexes on existing tables

**REVIEWED, WITH ONE RISK TO DECIDE (C2).** Flyway wraps each migration in a single transaction — no
`*.sql.conf` disables it — so every lock a script takes is held until that script commits. Lock modes were
measured by replaying `V061`–`V063` against a 17.11 database held at `V060` and reading `pg_locks` before each
`COMMIT`.

| Migration | Table | Locks held at commit | Effect |
| :--- | :--- | :--- | :--- |
| `V061` | `job_sources` | `AccessExclusiveLock`, `ShareLock` | Blocks all access, reads included |
| `V062` | `profiles`, `jobs` | `ShareRowExclusiveLock` | Blocks writes to both FK parents |
| `V063` | `applications` | `AccessExclusiveLock`, `ShareLock` | Blocks all access, reads included |

1. `ADD COLUMN ... NOT NULL DEFAULT <constant>` does **not** rewrite the table (PostgreSQL 11+ keeps the
   default in `pg_attribute`), so it is fast — but it is **not** "non-blocking" as previously claimed: it takes
   `ACCESS EXCLUSIVE`.
2. **Indexes on existing tables are the exposure.** Both `job_sources_health_idx` and
   `applications_follow_up_idx` are plain `CREATE INDEX`; `CONCURRENTLY` appears nowhere in the 62-file
   migration set. Because the preceding `ALTER TABLE` in the same transaction already holds `ACCESS EXCLUSIVE`,
   the index build time becomes a full read-and-write lockout rather than a write-only block.
3. `job_sources` is a small seeded catalogue (`V002`, `V028`, `V044`, `V045`, `V048`), so `V061` is negligible.
   `applications` grows per candidate, so `V063`'s lockout scales with row count — **and the production row
   count is unknown, because production was not contacted.**
4. No `lock_timeout` or `statement_timeout` is set for migrations: a conflicting long-running transaction makes
   the migration wait indefinitely while queueing every later request to that table.

Recommended (not applied — documentation-only gate): a short `lock_timeout` for the migration session; and, if
`applications` is large, `applications_follow_up_idx` moved to its own `CREATE INDEX CONCURRENTLY` migration
with `executeInTransaction=false`.

*Note, 20 November 2026: the second recommendation was implemented as C2 — V064, in `f609e9f` — and also
required `spring.flyway.postgresql.transactional-lock=false` (§2). `lock_timeout` remains unapplied (F3 and F5, §6).*

---

### A.5 Gate 4 — Destructive rollback removed, forward-fix recovery documented

**DONE.** The down-migration script in §5.2 of the acceptance report has been deleted. It would have:

- dropped `job_bookmarks` (`CASCADE`), destroying every candidate bookmark;
- dropped `applications.follow_up_due_at` / `follow_up_status`, destroying follow-up tracking state;
- dropped the `job_sources` health columns, destroying circuit-breaker history;
- deleted rows from `flyway_schema_history`, breaking Flyway's append-only record; and
- named `idx_applications_follow_up_due`, an index this release never creates — the real name is
  `applications_follow_up_idx`, so the script would not even have dropped the index it named.

§5.3 now documents forward-fix recovery instead: roll back the application rather than the schema (the three
migrations are purely additive, so the previous build runs unchanged against a `V063` schema); disable a
misbehaving feature in the application layer; correct a wrong column, default or index with a new `V064`+
migration; drop a harmful index with `DROP INDEX CONCURRENTLY` in its own non-transactional migration; and
preserve `job_bookmarks`, `follow_up_*` and the health counters as user data throughout. No schema-reversal
procedure is published; any future reversal needs its own review, a fresh verified backup and written
acceptance of the data loss.

**Correction, 20 November 2026.** Two parts of that guidance are wrong. Redeploying the previous build is not a
recovery step: the application refuses to start when the database has applied migrations its jar does not
ship (`FlywayBootstrapRecoveryPolicy.isUnbrokenPrefix`). And V064 is now taken, so corrective migrations start
at **V065**. The corrected guidance is in §5.

This also restores consistency with the project's own deployment documentation, which already states plainly
that "There is no down-migration" and that taking a database backup before the first deploy of a version is
"part of the change, not an optional extra" (`docs/DEPLOYMENT.md`). The published Wave 2 down-migration was
the outlier, not the norm. (`docs/DEPLOYMENT.md` does contain a `drop table public.flyway_schema_history`,
but it is a fail-closed bootstrap-recovery block guarded on `to_regclass('public.users') is null` with exactly
one baseline history row, and it raises an exception otherwise. It is not a production rollback, and it was
left untouched.)

No other document in `docs/` contains destructive DDL for this release: a repository-wide search for
`DROP TABLE`, `DROP COLUMN` and `DELETE FROM flyway_schema_history` returns only the guarded block above and
`docs/qa/WAVE1_INTEGRATION_REVIEW.md`, which asserts the *absence* of destructive DDL.

---

### A.6 Gate 5 — The 13 skipped tests, confirmed

**CONFIRMED by re-execution. The counts hold exactly; one stated reason was wrong and has been corrected.**

`mvn -B verify` on this tree returned `BUILD SUCCESS`, exit code **0**, in 14:54:

| Runner | Measured now | Acceptance report | Match |
| :--- | :--- | :--- | :--- |
| Backend Surefire | `tests=1636 failures=0 errors=0 skipped=6` | 1,636 / 1,630 / 6 | ✅ |
| Backend Failsafe | `tests=327 failures=0 errors=0 skipped=7` | 327 / 320 / 7 | ✅ |
| Frontend Vitest | 439 passed, 44 files, 0 skipped | 439 / 0 | ✅ |
| Worker suite | 262 tests, 262 pass, 0 skipped | 262 / 0 | ✅ |
| Cloudflare email worker | 17 tests, 17 pass, 0 skipped | 17 / 0 | ✅ |
| **Total** | **2,681 run, 2,668 passed, 13 skipped, 0 failures, 0 errors** | 2,681 / 2,668 / 13 | ✅ |

**The 6 Surefire skips are not what the report said they were.** They were described as "unit-isolated tests
that explicitly require external Docker/Testcontainers context". In fact all six are gated on a **local Redis
being reachable on `localhost:6379`**, two of them reporting exactly that message:

- `RedisLettuceAbExperimentTest` — `abMatrixAgainstRealRedis`, `failureControlsOnUnreachableTargets`
  (*"local Docker Redis on localhost:6379 is not reachable"*)
- `RedisConnectivityDiagnosticsTest` — `authenticatedProtocolComparisonRunsAllThreeModesAgainstDisposableRedis`,
  `credentiallessFailureDoesNotPreventPasswordOnlyModeFromRunning`
- `RedisTlsBindingTest` — `sharedSpringFactoryAcquiresAndPingsAgainstLocalRedis`,
  `customFactoryAcquiresAndPingsAgainstLocalRedis`

**The 7 Failsafe skips are class-level `@EnabledIfSystemProperty` gates**, and the report's "live API
credentials (e.g. live Adzuna API keys)" covers only four. Three are operator tools needing an external
database URL, not an API key:

- `-Drobin.migration.verify-actual-events.url` — `ExternalDatabaseActualReviewIT`
- `-Drobin.compat.url` — `ExternalDatabaseCompatibilityIT`
- `-Drobin.flyway.external.url` — `ExternalDatabaseFlywayIT`
- `-Dlive.discovery=true` — `AdzunaLiveIngestionIT`, `ArbeitnowLiveIngestionIT`, `FreeHireLiveIngestionIT`,
  `PlatformLiveIT`

**All 13 are environment gates, not defects.** Nothing is `@Disabled`, and nothing skipped because it failed.
One caveat worth recording: the Failsafe skip count depends on the Docker daemon. With Docker running the
Testcontainers integration tests execute and only these 7 externally gated tests skip; without it, far more
skip. The 7 figure is therefore only reproducible with Docker available.

The full named list with reasons is now also in the acceptance report, §2.1.

---

### A.7 Gate 6 — ATS external verification, stated separately from mocked tests

**CONFIRMED: no ATS submission is externally verified. The prior claim is withdrawn.**

The previous revision recorded Greenhouse as `EXTERNALLY CONFIRMED (HTTP 200/302 + DOM receipt)`, citing an
"authorised sandbox verified in Phase 19/20 (`boards.greenhouse.io` sandbox token)". Nothing in this
repository supports that, and the repository's own documents contradict it:

- `docs/ROBIN_GAP_REGISTER.md`: no ATS adapter is assigned `SUBMISSION_VERIFIED` **by design**; the registry's
  rules forbid real-employer test submissions; Greenhouse's guarded submit path is tested against fixtures
  only; and any messaging implying independently confirmed submission "would be false".
- `docs/PHASE8_FIND_WORKFLOW.md` §11.11: `REAL_SUBMIT` is hard-stopped — no endpoint submits an application.
- **There is no Phase 19 document in `docs/`.** `docs/ROBIN_PHASE20_VERIFICATION.md` records no Greenhouse
  sandbox submission; it lists an authentic candidate sign-in on a supported ATS as an *outstanding* step.
- `docs/ROBIN_BUILD_ROADMAP.md` still carries "Greenhouse/Ashby must first be tested only against a controlled
  sandbox/test posting the candidate owns" as a goal.
- `docs/ROBIN_TSENTA_SCORECARD.md` labels Greenhouse "fixture-only — never `SUBMISSION_VERIFIED` by the
  registry's own design".

What does exist, and passed at this commit, is mocked evidence: `worker/test/greenhouse.test.js` (5 `route()`
interceptions), `ashby.test.js` (2), `workday.test.js` (5), `confirmation.test.js`, `lever.test.js`,
`zoho_recruit.test.js`, plus the backend form fixture `ats/greenhouse-apply-form-fixture.html`. That
establishes the adapters behave correctly against mocks. It establishes nothing about employer acceptance.

Acceptance-report §4 has been rewritten accordingly: every row now reads "Authorised sandbox / live
submission: **None**".

---

### A.8 Gate 7 — Junior QA ranking regression preserved

**PRESERVED, unmodified.** Acceptance-report §3.1 still records persona `c5` (Junior QA Automation Engineer)
at baseline nDCG@10 `1.0000` → Wave 2 `0.9719`, a **−2.8%** regression, and §3.2 retains the full root-cause
analysis in place: `c5`'s `salaryMin: £32,000` and `experienceLevels: ["ENTRY","JUNIOR","MID"]`; the two
relevant vacancies `hjob-09` (£30k–£38k, `ENTRY`) and `hjob-05` (£45k–£55k, `MID`); and the explanation that
Wave 2 scores `hjob-05` at 0.940 against `hjob-09`'s 0.895 on salary fit and 3-of-4 skill match, reordering
them and incurring the logarithmic discount. The paragraph stating that these synthetic and held-out
benchmarks do **not** establish real-world superiority over commercial competitors is also retained verbatim.

Nothing in this gate shortened, softened or relocated that regression.

---

### A.9 Gate 8 — Did Agent 6 review the final code tree?

**PARTIALLY — and the gap is condition C1.**

Agent 6's sign-off commit `51bdf15` (`claude/wave2-agent6-qa`) **is** an ancestor of the final code commit
`51961ba`, and the seven adversarial classes are unchanged between them, so the adversarial results do
describe the final tree. Five files changed after that sign-off:

| File | Nature |
| :--- | :--- |
| `backend/.../discovery/platform/BoardDiscoveryService.java` | **Production code**, +30/−25 |
| `backend/.../discovery/platform/BoardDiscoveryServiceTest.java` | Mock arity updated to match |
| `backend/.../jobs/HeldoutMatchingBenchmarkTest.java` | New benchmark, 150 lines |
| `backend/src/test/resources/matching/heldout_evaluation_dataset.json` | New dataset, 488 lines |
| `docs/agents/WAVE2_FINAL_REPORT.md` | The acceptance report |

The production change does two things, neither covered by Agent 6's "APPROVED WITHOUT RESERVATIONS":

1. **A latent bug is fixed.** The failure-path `job_sources` update previously declared four placeholders but
   bound only three arguments, leaving `error_summary = (?::jsonb)->>'error'` unbound. It is now split into
   explicit success and failure branches with the summary bound directly, and the unit test's mock arity was
   updated to suit. This is an improvement — but it is an unreviewed change to the failure path.
2. **The safe-deletion guard is relaxed.** Previously an empty-but-complete listing never marked jobs removed
   in any mode. Now an empty listing is authoritative in `MANUAL` mode and calls `markUnlisted`, so an
   admin-triggered or integration-test sync returning zero jobs can mark active inventory deleted.
   `anEmptyCompleteListingNeverRemovesExistingJobsSafeDeletionGuard` exercises `SCHEDULED` mode only, so **the
   relaxed path has no test coverage in either commit**.

Also: the held-out benchmark that acceptance-report §3 reports was added *after* Agent 6's commit, so Agent 6
never executed it. Its results in acceptance-report §3 come from the suite run recorded in §A.6 of this report,
not from independent QA.

*Note, 20 November 2026: the relaxed guard was reverted by C1 in `f609e9f`. Independent QA reviewed `f609e9f` and
`7108e49`, including this `BoardDiscoveryService` change, and returned PASS (§2, §4).*

---

### A.10 What was deliberately not verified

- **Production state.** No production database, Render service, environment variable or maintenance-mode
  setting was read or changed. Statements about production health or deployment elsewhere in the docs are
  historical observations from when they were written, not live checks made here.
- **Production table sizes**, hence the real duration of `V063`'s lock on `applications`.
- **Employer-side ATS acceptance**, which `REAL_SUBMIT` forbids testing by design.
- **Phase 20's live-production items**, which remain OPEN and untouched by Wave 2.
- **Live-credential integration probes**, which stay skipped without live API keys.

### A.11 Safety boundaries honoured

Documentation-only changes; no Java, TypeScript, JavaScript, SQL, migration, configuration or secret file
touched. No production database contacted. No backup or Supabase export run. No Flyway execution against
Oracle production — the only migration runs were against a disposable local container, which has since been
removed. No Render change. No deployment. Nothing merged into `master`, and no GitHub repository setting
changed.

**Stopping here for explicit approval.**
