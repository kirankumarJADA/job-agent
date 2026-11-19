# Robin Wave 2 — Definitive Release-Readiness Report

**Date:** 19 November 2026  
**Branch:** `integration/wave-2`  
**Code commit:** `51961ba`  
**Documentation commit at gate entry:** `01c78fa`  
**Prepared at:** the final Wave 2 approval gate (gates 1–9)  
**Scope:** documentation-only. No Java, TypeScript, JavaScript, SQL, migration, configuration or environment
file was modified. No production database was contacted, no backup or export was run, no Render change was
made, nothing was deployed, and nothing was merged into `master`.

---

## 1. Verdict

**Wave 2 is NOT approved for merge as it stands. It is ready for review with two conditions outstanding.**

The previous revision of the acceptance report concluded "fully hardened, verified, and ready for user merge
approval" and recorded Agent 6's verdict as "APPROVED WITHOUT RESERVATIONS". That conclusion cannot be
sustained unchanged: three of its supporting claims were wrong or unsupported, and one safety-relevant
production change was never reviewed. All five automated suites do pass, and the migrations do apply cleanly
to the exact target PostgreSQL minor version — so the work is sound in substance, but the sign-off was
broader than the evidence.

### Conditions to clear before merge

| # | Condition | Why | Where |
| :--- | :--- | :--- | :--- |
| **C1** | Decide on the relaxed safe-deletion guard: add a test for the `MANUAL`-mode empty-listing path, or revert that relaxation. | `BoardDiscoveryService` now treats an empty-but-complete listing as authoritative in `MANUAL` mode and calls `markUnlisted`, so an admin-triggered sync returning zero jobs can mark active inventory deleted. No test covers it, and it landed **after** independent QA sign-off. | §5 below, report §6.1 |
| **C2** | Decide how `applications_follow_up_idx` is built in production: confirm the table is small enough for a brief full lockout, schedule it in a maintenance window, or move it to `CREATE INDEX CONCURRENTLY`. | `V063` holds `AccessExclusiveLock` on `applications` for its whole transaction, so the index build blocks reads **and** writes. Production row count is unknown — the production database was not contacted. | §4 below, report §5.2 |

Both conditions require code or configuration changes, which this gate is not permitted to make.

### Standing limits that merge does not change

- **Phase 20 remains OPEN.** Its live-production verification items are unaffected by Wave 2 and must not be
  inferred from these results.
- **No ATS submission has been externally confirmed.** `REAL_SUBMIT` stays hard-stopped; every ATS adapter is
  evidenced by mocked route interception and captured fixtures only.
- **No synthetic benchmark establishes commercial superiority.** The held-out dataset measures ranking
  mathematics under controlled conditions, nothing more.

---
### Merge mechanics

`integration/wave-2` contains all of `origin/master` — 0 commits behind, 31 ahead, merge-base `779a6be2`
which is the `origin/master` tip. It is current with `master` as of 19 November 2026, so clearing C1 and C2 is
the only thing standing between this branch and a clean merge. Nothing has been merged, pushed or deployed.

---

## 2. Gate 1 — `01c78fa` is documentation-only relative to `51961ba`

**CONFIRMED.** `git diff --name-status 51961ba 01c78fa` returns exactly one path:

```
M	docs/agents/WAVE2_FINAL_REPORT.md
```

One commit, one file, +119/−218. No source, test, SQL, configuration or build file differs. The Wave 2 code
under review is therefore `51961ba` in full, and `01c78fa` changes only how it is described.

---

## 3. Gate 2 — `V001`–`V063` executed against disposable PostgreSQL 17.11

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

## 4. Gate 3 — Migration locking behaviour, including indexes on existing tables

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

---

## 5. Gate 4 — Destructive rollback removed, forward-fix recovery documented

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

## 6. Gate 5 — The 13 skipped tests, confirmed

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

## 7. Gate 6 — ATS external verification, stated separately from mocked tests

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

## 8. Gate 7 — Junior QA ranking regression preserved

**PRESERVED, unmodified.** Acceptance-report §3.1 still records persona `c5` (Junior QA Automation Engineer)
at baseline nDCG@10 `1.0000` → Wave 2 `0.9719`, a **−2.8%** regression, and §3.2 retains the full root-cause
analysis in place: `c5`'s `salaryMin: £32,000` and `experienceLevels: ["ENTRY","JUNIOR","MID"]`; the two
relevant vacancies `hjob-09` (£30k–£38k, `ENTRY`) and `hjob-05` (£45k–£55k, `MID`); and the explanation that
Wave 2 scores `hjob-05` at 0.940 against `hjob-09`'s 0.895 on salary fit and 3-of-4 skill match, reordering
them and incurring the logarithmic discount. The paragraph stating that these synthetic and held-out
benchmarks do **not** establish real-world superiority over commercial competitors is also retained verbatim.

Nothing in this gate shortened, softened or relocated that regression.

---

## 9. Gate 8 — Did Agent 6 review the final code tree?

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

Also: the held-out benchmark that §3 reports was added *after* Agent 6's commit, so Agent 6 never executed it.
Its results in §3 come from the suite run recorded in §6 of this report, not from independent QA.

---

## 10. What was deliberately not verified

- **Production state.** No production database, Render service, environment variable or maintenance-mode
  setting was read or changed. Statements about production health or deployment elsewhere in the docs are
  historical observations from when they were written, not live checks made here.
- **Production table sizes**, hence the real duration of `V063`'s lock on `applications`.
- **Employer-side ATS acceptance**, which `REAL_SUBMIT` forbids testing by design.
- **Phase 20's live-production items**, which remain OPEN and untouched by Wave 2.
- **Live-credential integration probes**, which stay skipped without live API keys.

## 11. Safety boundaries honoured

Documentation-only changes; no Java, TypeScript, JavaScript, SQL, migration, configuration or secret file
touched. No production database contacted. No backup or Supabase export run. No Flyway execution against
Oracle production — the only migration runs were against a disposable local container, which has since been
removed. No Render change. No deployment. Nothing merged into `master`, and no GitHub repository setting
changed.

**Stopping here for explicit approval.**
