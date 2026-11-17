# Robin — Multi-agent Wave 1: architecture contract and file ownership

**Owner:** Agent 0, the Principal Architect and Integration Lead.
**Prepared:** 2026-11-17.
**Baseline:** `master` at `779a6be2aa475e86c6870439a3aef611bb70ff95` (the PR #15 merge).
**Integration branch:** `integration/wave-1`.

Every agent reads this document before changing anything. It decides who owns which files, which files are protected, the shared interfaces, the migration slots, the safety rules and what each agent delivers. **Implementation runs in parallel; architectural decisions do not.** A change outside your ownership needs Agent 0's approval: send one message to Agent 0's session and wait.

---

## 1. Wave 1 scope

- **In scope:** priorities 1 to 4: discovery, matching, ATS execution and application lifecycle, plus independent QA and security review.
- **Out of scope:** priority 5 (product experience and commercial readiness), and the Oracle migration, which remains a separate workstream.
- **Increment size:** each agent delivers a focused, verified increment, not its whole priority. There are no giant pull requests. Agent 1 delivers two stacked commits (§6.1).
- **"Done" means** implemented, tested and verified with the actual commands and results reported. A capability that is not tested is not complete.

## 2. Branching and commits

- **`integration/wave-1`** is cut from the baseline plus this document. Only Agent 0 merges into it.
- **Each agent** has its own git worktree and branch, cut from `integration/wave-1`; the app chooses the exact branch name.
- **Push your own branch only.** Never push to `master` or `integration/wave-1`. Never force-push or rewrite another branch. Never pop another session's stash.
- **Commit messages** are imperative and scoped (for example `discovery: …`) and end with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Pull requests are optional:** `gh` is not installed on this machine. Report your branch and commit SHAs. Nothing is merged automatically, and nothing reaches `master` without the user's approval.
- **Never discard uncommitted work,** your own or anyone else's.

## 3. File ownership

Ownership is exclusive. Tests follow the class they test unless this section says otherwise. "Main" means `backend/src/main/java/com/personal/jobagent/`, and "test" means `backend/src/test/java/com/personal/jobagent/`.

| Agent | Owns (create, edit) | Migration slots | Report file |
|---|---|---|---|
| **1 Discovery Intelligence** | main `discovery/**`; main `apply/ApplicationIdentityService.java`; test `discovery/**`; test `apply/ApplicationIdentityServiceTest.java`; `backend/src/test/resources/discovery/**` | `V055`, `V056` | `docs/agents/AGENT1_DISCOVERY_REPORT.md` |
| **2 Matching Intelligence** | main `jobs/**`, `preferences/**`, `application/ApplicationPipelineService.java`; test `jobs/**`, `preferences/**`, `application/ApplicationPipelineServiceTest.java`; new `backend/src/test/resources/ranking/**` | `V057` | `docs/agents/AGENT2_MATCHING_REPORT.md` |
| **3 ATS Execution** | `worker/src/**`, `worker/test/**`; main `automation/**`, `ats/**`, `qa/**` (the application-answers package); main `apply/**` except `ApplicationIdentityService.java`; matching tests, including `apply/ApplyWorkflowIT.java` | `V058` | `docs/agents/AGENT3_ATS_REPORT.md` |
| **4 Application Lifecycle** | main `events/**`, `track/**`, `email/**`, `mailbox/**`, `gmail/**`, `notifications/**`; main `application/**` except `ApplicationPipelineService.java`; `cloudflare-email-worker/**`; matching tests, except those protected in §3.1 | `V059`, `V060` | `docs/agents/AGENT4_LIFECYCLE_REPORT.md` |
| **5 QA and Security** | new test package `adversarial/**`; test `security/UserDataIsolationIT.java` (test code only); `docs/qa/**`. **No main-code edits** and no edits to other agents' tests: findings go to the owner through Agent 0. | none | `docs/qa/WAVE1_BASELINE_QA.md`, later `docs/qa/WAVE1_INTEGRATION_REVIEW.md` |

Note that main `qa/**` is the existing application-answers feature, owned by Agent 3. Agent 5's tests live in the separate `adversarial` package.

### 3.1 Protected: no edits without Agent 0 approval

- **Build and CI:** `backend/pom.xml`, `worker/package.json` and its lockfile, `frontend/**`, `.github/**`, `scripts/**`.
- **Configuration:** `backend/src/main/resources/application*.yml`.
- **Existing migrations:** `V001` to `V054`. These are never edited.
- **Main packages:** `documents/**` (including `DocumentFactValidator`), `resume/**`, `coverletter/**`, `prep/**`, `profile/**`, `resumeimport/**`, `llm/**`, `security/**`, `config/**`, `common/**`, `system/**`, `identity/**`, `audit/**`, `benchmark/**`, `cli/**`, `mcp/**`, `orchestrator/**`, `sources/**`.
- **Cross-cutting regression gates:** test `application/ApplicationPipelineIT.java`, `application/DiscoveryToApplicationIT.java` and `crossfeature/CrossFeatureIntegrationIT.java`.
- **Oracle-migration guards:** `application/AutoApplyBatchMigrationGuardTest.java`, `application/MigrationManualReviewGuard*`, `application/ExternalDatabaseActualReviewIT.java` and `config/ExternalDatabase*IT.java`.
- **Living documents:** `docs/ROBIN_*.md`. Agent 0 updates these at integration.

If your change makes a protected gate fail, stop and report it to Agent 0 with the failing assertion. Do not edit the gate.

## 4. Shared interface contracts

### 4.1 Job identity

`discovery/platform/JobIdentity` is owned by Agent 1. Its consumers are `apply/ApplicationIdentityService` (Agent 1), `jobs/JobsController` (Agent 2), `track/EmailApplicationMatcher` (Agent 4) and `ats` (Agent 3).

| Method | Rule |
|---|---|
| `atsKey(url)` | For every URL form that returns a key today, the returned string stays **byte-identical**. These strings are pinned by `JobIdentityTest` and stored in `job_identity_keys` and `applications.identity_key`. New URL forms and platforms may be added. |
| `employerKey(name)` | **Unchanged** in wave 1. TRACK uses it to match requisition numbers. |
| `isAggregatorUrl(url)` | **Unchanged** semantics in wave 1. It decides which application URLs are hidden from candidates (`JobsController.candidateApplicationUrl`) and nulled at ingestion. New predicates may be added under new names. |
| `normalizeUrl(url)` | May change only with **dual-read**. Identity resolution and application-duplicate detection must still match rows stored under the previous rules, by also looking up the legacy form, proven by tests. TRACK's ITs must stay green. |
| `keys(…)` | `src:` and `req:` key strings are unchanged. `ats:` and `url:` keys follow the rules above. |

### 4.2 Events

Agent 4 owns the outbox, the processor and the handlers. Agent 1 owns `job.discovered` emission, and Agent 2 owns `ApplicationPipelineService.onJobIngested`.

- **Names and payloads stay the same:** `job.discovered {job_id, action ∈ INSERTED|UPDATED|TOUCHED, source_id}`, `job.matched` and `application.*`. An additive payload field needs Agent 0 approval.
- **Consumers stay idempotent.** `onJobIngested` keeps ignoring `action`; do not make it depend on it.
- **Agent 1 may rate-limit `TOUCHED` emission** (§6.1). `INSERTED` and `UPDATED` are always emitted.
- **`ApplicationPipelineEventHandler` belongs to Agent 4.** It dispatches `job.discovered` to Agent 2's service and `application.created` to Agent 3's plan services. Agents 2 and 3 change behaviour behind those service methods, not in the handler.

### 4.3 The shared catalogue row (`jobs`)

- **Additive, nullable columns only,** through your assigned migration slot. No renames, drops or type changes.
- **`deleted_at`, `last_seen_at`** and the `job_source_observations` lifecycle belong to Agent 1.
- **The matching statuses** (`ANALYSED`, `SCORED`, `FILTERED_OUT`) and `filter_reasons` belong to Agent 2.
- **The `ARCHIVED` sweep** belongs to Agent 1 and is not changed in wave 1.

### 4.4 Migrations

- **Slots:** `V055` and `V056` for Agent 1, `V057` for Agent 2, `V058` for Agent 3, `V059` and `V060` for Agent 4. An unused slot stays unused; Flyway accepts gaps.
- **Every migration must be** additive and backward compatible, safe on a populated database (no table rewrite of `jobs` or `outbox_events`, and no unbounded backfill inside a migration), and exercised by Testcontainers PostgreSQL 16 tests.
- **Label each one "MIGRATION PROPOSAL"** in your report. An agent never applies a migration to a real database. Production application waits for the user's approval of the merged result.

### 4.5 Configuration, metrics, logging and dependencies

- **New settings** are `@Value("${app.<area>.<name>:<default>}")` in classes you own. Defaults keep current behaviour unless changing it is the task, in which case document it. Do not edit `application*.yml`.
- **New meters** go in your own package, for example `discovery/DiscoveryMetrics`. Do not edit `common/AutomationMetrics`.
- **Never log** credentials, tokens, cookies, candidate personal data, email bodies, or URLs carrying secrets.
- **New Maven or npm dependencies** need Agent 0 approval.

## 5. Safety rules for all agents

- **Architecture:** preserve the Java 21 / Spring Boot modular monolith and the existing module boundaries.
- **Candidate-fact validation (Phase 21):** preserve it. `DocumentFactValidator` (`fact-validator-2`) is protected. `DocumentFactValidatorTest`, `CoverLetterReviewTest` and `PrepWorkflowIT` must keep passing.
- **Candidate data:** never invent candidate information. Fixtures use synthetic candidates only, never real personal data.
- **Submission approval:** require explicit user approval before any application submission. Strengthen the existing review and approval gates with tests; never weaken them.
- **No submissions this wave:** no real employer submissions, and no live ATS or sandbox submissions in wave 1. Phase 24 requires the operator's explicit approval at the moment of action.
- **Access controls:** never bypass CAPTCHA, hCaptcha, MFA or bot challenges. Blocked platforms stay blocked.
- **Infrastructure:** no production database, Supabase, Oracle, OCI, Render, Vercel or Cloudflare changes. No backups, exports, or migrations against a real database. No deployment.
- **Live tests:** no live-network test runs (`-Dlive.discovery=true`, the external-database ITs, the Oracle scripts) without the user's explicit approval. Use fixtures. Reading public documentation or repositories for research is fine.
- **AIHawk:** study the architecture and ideas from its public repository and documentation, and record what you studied in your report. **Do not copy its code**, because its licence carries obligations; implement in Robin's own design.
- **Tsenta:** use its public product information and `docs/ROBIN_TSENTA_SCORECARD.md`.
- **Evidence:** never state a result you did not execute. Label measurements as fixture-based or test-only where that is what they are.

## 6. Wave 1 deliverables per agent

### 6.1 Agent 1: Discovery Intelligence (priority 1)

Read `docs/PHASE22_DISCOVERY_EVIDENCE_AUDIT.md` first; its finding IDs (P1-1 and so on) are used below. Deliver two commits on your branch, each green on its own.

**Commit 1a: identity and normalisation (no schema change).**

- **One canonical URL normaliser,** used by `JobIdentity`, `JobDeduplicationService` and `ApplicationIdentityService` (fixes P2-1). Agent 0's rules:
  - lowercase the scheme and host;
  - drop the default port, the fragment and any trailing slash;
  - treat `http`/`https` and a leading `www.` as equivalent in identity keys;
  - decode percent-encoded unreserved characters (RFC 3986 §6.2.2.2);
  - use one tracking list: today's list plus `trk`, `in_iframe` and `oga`. `ref`, `source` and `src` stay on it; document that risk;
  - keep non-tracking query parameters with **their original value case**, and sort them.
- **Dual-read** (§4.1), proven by tests: a job and an application stored under the old rules are still matched.
- **New ATS keys,** with existing formats byte-identical:
  - the Greenhouse embed URL `boards.greenhouse.io/embed/job_app?for={board}&token={id}` maps to the existing `greenhouse:{board}:{id}`;
  - Recruitee, and Phenom (derived from fixtures);
  - the employer-hosted `?gh_jid=` form, **only if** a sound key exists without the board. Otherwise document why not.
- **Aggregator ranking:** a new predicate classifies aggregator and job-board hosts (Tsenta, Adzuna for all country domains, Arbeitnow, FreeHire, SEEK and similar) so that their URLs rank below employer pages, letting an employer or ATS URL from a cross-source match replace them (P1-4). `isAggregatorUrl` is unchanged.
- **Generic URL path (P1-6):** derive a missing external id with the canonical normaliser, so query identifiers are kept. Add an IT for the `?gh_jid=111` vs `?gh_jid=222` case.

**Commit 1b: source-aware lifecycle and measurement (migration proposals `V055` and `V056`).**

- **Removal authority (P1-1). Agent 0's decision: the employer's own ATS source is authoritative.**
  - (a) An aggregator observation not re-seen for `app.discovery.aggregator-observation-ttl-days` (default 7) becomes `MISSING`.
  - (b) Once an authoritative (non-aggregator) source has marked a job `MISSING` or `CLOSED`, aggregator `SEEN` observations no longer keep it alive, and an aggregator re-observation does not clear its `deleted_at`.
  - (c) An authoritative source that lists the job again still restores it.
  - Reproduce the keep-alive and resurrection cases as ITs that fail on the baseline and pass after the change.
- **Run history (P1-2):** a `discovery_runs` table (`V055`), written once per board run with source, kind, mode, start and finish times, status, failure category, the counters JSON and the complete flag. Add a per-kind run metric in a discovery-owned class, and document read-only report queries.
- **Re-observation churn (P1-3):** emit `TOUCHED` at most once per job per `app.discovery.touched-event-interval-hours` (default 24), using a nullable `jobs.last_discovered_event_at` (`V056`). Test the outbox row counts across repeated runs.
- **Deferred to wave 2:** detail-platform content re-fetch (P1-5), `ARCHIVED` semantics (P2-4) and the observation uniqueness change (P2-6).

**Gates.** These must stay green:
- all discovery tests;
- `ApplicationIdentityServiceTest`, `JobFeedIT`, `ReviewQueueIT`, `OutboxDiscoveryExclusionIT`, `ApplyWorkflowIT` and `DiscoveryToApplicationIT`;
- TRACK's `CloudflareManagedMailIT` and `CloudflareRecruiterLifecycleIT`.

### 6.2 Agent 2: Matching Intelligence (priority 2)

- **Audit and study:** audit `JobMatchService`, `HardFilterService`, `SemanticSkillMatcher`, `SkillSynonymRegistry`, `ApplicationPipelineService` and the feed search in `JobRepository`. Study AIHawk's job scoring and ranking approach, ideas only.
- **Evaluation:**
  - pure, unit-tested implementations of `nDCG@K` (default K = 10), `Precision@K` and `Recall@K`;
  - an evaluation harness over a labelled synthetic dataset (synthetic candidates and jobs with graded relevance) in `backend/src/test/resources/ranking/`;
  - the current ranker's baseline numbers, labelled as synthetic-fixture results.
- **Hard eligibility (the Phase 23 exit criterion):** adversarial tests proving that a hard-excluded job can never be recommended `APPLY` or reach auto-apply, whatever its score. Fix any bypass you find.
- **Job-title synonyms and search variations:** a curated title-synonym registry, used for title relevance and for feed-search expansion. It must never widen a hard exclusion or relax eligibility; test both directions.
- **Multi-stage reranking,** only where the evaluation shows a measured gain over the baseline on the fixture set. It must be deterministic, with no LLM calls in tests and no LLM reranking in wave 1. Report before-and-after metrics.
- **Lead to resolve:** a per-profile hard-filter rejection writes the shared catalogue row (`jobs.status = 'FILTERED_OUT'` and `jobs.filter_reasons`, `ApplicationPipelineService.java:162`). Catalogue state must not depend on one candidate's preferences. Use `V057` if a schema change is needed.
- **Gates:** jobs and preferences tests, `ApplicationPipelineServiceTest`, `ApplicationPipelineIT`, `DiscoveryToApplicationIT`, `CrossFeatureIntegrationIT` and `JobFeedIT`.

### 6.3 Agent 3: ATS Execution (priority 3)

- **Audit and study:** audit the worker (`browser_worker.js`, `plan.js`, `outcome.js`, `confirmation.js` and the platform modules) and the backend `automation/**`, `ats/**` and `qa/**`. Study AIHawk's documented application-automation approach, ideas only.
- **Form detection and field mapping:** robust detection by label, aria attributes and name; required-field validation; unknown questions surfaced, never guessed. Build local fixture forms for each supported ATS.
- **Candidate-fact validation of form answers:** every filled value must trace to stored candidate facts or to an answer the candidate explicitly approved. Unsupported claims are blocked or escalated. Use `DocumentFactValidator`'s public API; do not modify it.
- **Controlled submission verification:** a local, deterministic fixture-ATS harness with no network. It runs plan → fill → approval gate → simulated submit → confirmation and receipt evidence. Prove that approval is required, and that CAPTCHA and MFA paths stop safely.
- **Repeatability:** the same plan run twice gives identical field values and outcomes, and a double submit is prevented.
- **Gates:** worker `npm test`; automation, ats, qa and apply tests; `ApplyWorkflowIT`; `AtsCapabilityRegistryTest`. No capability is upgraded to `SUBMISSION_VERIFIED`.

### 6.4 Agent 4: Application Lifecycle (priority 4)

- **Event idempotency:** audit `OutboxProcessor`, `consumed_events`, `ApplicationPipelineEventHandler` and `NotificationEventHandler`. Test duplicate delivery, concurrent dispatch, retry and the dead-letter queue. Add idempotency per business key for `job.matched` and `application.created`.
- **Application state transitions:** an explicit allowed-transition model in `ApplicationStatusService`. Illegal transitions are rejected and recorded, with tests.
- **Recruiter email association:** `EmailApplicationMatcher` and `TrackEmailClassifier`. Thread Gmail-ingested mail by `threadId` and RFC Message-ID (gap-register P2). Use only `JobIdentity`'s public API (§4.1); do not change it.
- **Duplicate webhooks:** concurrent deliveries with the same token, or with the same Message-ID, store exactly one message (IT).
- **Receipts and tracking:** in `ApplicationReceiptService`, a foreign application id on the receipts list answers 404, like every other application-scoped GET (gap-register P2), with isolation tests.
- **Gates:** your owned tests, `CloudflareManagedMailIT`, `CloudflareRecruiterLifecycleIT`, `ReviewQueueIT`, `ApplicationPipelineIT` and `CrossFeatureIntegrationIT`. Run the email worker's `npm test` if you touch it.

### 6.5 Agent 5: Independent QA and Security

**Phase A (now, against `integration/wave-1`).** Do an independent audit, and build an adversarial suite in the `adversarial` test package covering:

- **cross-tenant isolation:** make `UserDataIsolationIT` run against a local Testcontainers PostgreSQL so that CI can run it, and never against a real database;
- **candidate-data integrity:** attempts to invent facts and attempts to bypass the validator;
- **concurrency and duplicate processing:** parallel ingestion, duplicate events and duplicate webhooks;
- **reproductions** of the Phase 22 findings P1-1 and P1-6.

A test that fails on the baseline is committed as `@Disabled("KNOWN DEFECT <id>: <one line>")`, and the defect goes in the report, so the build stays green. Report: `docs/qa/WAVE1_BASELINE_QA.md`.

**Phase B (later, when Agent 0 asks).** Review the combined `integration/wave-1`:
- run the full suites;
- re-enable the known-defect tests that are now fixed;
- review regressions and security.

Report: `docs/qa/WAVE1_INTEGRATION_REVIEW.md`.

## 7. Verification

- **Backend, targeted.** Run from `backend/`. Docker and a cached `postgres:16` image are available, so Testcontainers ITs run locally:

  ```bash
  mvn -o -B -ntp verify -Dmaven.gitcommitid.skip=true "-Dtest=<pattern>" -Dsurefire.failIfNoSpecifiedTests=false "-Dit.test=<pattern>" -Dit.failIfNoSpecifiedTests=false
  ```

  `-o` means offline. If a dependency is missing offline, report it rather than changing `pom.xml`. Run long commands in the background.
- **Before reporting,** run your packages' unit tests and ITs, plus every gate listed for you in §6.
- **The full backend suite** (`mvn -B verify`, about 15 minutes) runs on the integration branch under Agent 0. You may also run it yourself.
- **Worker tests:** `cd worker && npm test`.
- **Report** the exact commands, the counts (run, failures, errors, skipped) and the times. A known pre-existing failure: frontend `npm run lint` fails because the repository has no ESLint config.

## 8. Report template

Each agent's report must cover, in this order:

1. Architecture findings, including what you studied from AIHawk and Tsenta.
2. Files changed.
3. Files deliberately left untouched.
4. Implementation summary.
5. Tests executed, with the commands.
6. Actual results, with counts.
7. Known limitations.
8. Dependencies on other agents.
9. Migration proposals.
10. Commit SHAs.
11. Branch.

Your final chat message repeats the branch, the SHAs and the test counts in its first lines.

## 9. Integration procedure (Agent 0)

1. **Merge** agent branches into `integration/wave-1` in dependency order: Agent 1, then 2, then 4, then 3. Agent 5's tests go last. Use merge commits; do not squash or rebase agent work.
2. **Resolve conflicts** so that both intents survive, and record every conflict.
3. **Run** the full backend `mvn -B verify`, the worker, frontend and email-worker tests.
4. **Hand over** to Agent 5 for Phase B.
5. **Write** `docs/agents/WAVE1_INTEGRATION_REPORT.md`, covering agent summaries, decisions, changed files, interface changes, conflicts, test results, security review, performance measurements, limitations, branches and SHAs, and the next milestone.
6. **Stop:** nothing is merged to `master` without the user's approval.
