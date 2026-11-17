# Agent 1 (Discovery Intelligence) — Wave 1 Report

## 1. Architecture Findings

### 1.1 Study of AIHawk and Tsenta Approaches
- **AIHawk Analysis (Architectural Concepts Only):** AIHawk implements an automated search loop across job listings by relying primarily on platform query strings and scraper pagination with local deduplication. However, AIHawk lacks cross-source authoritative lifecycle resolution: if an aggregator continues listing an expired job, AIHawk has no concept of an authoritative ATS origin revoking the listing. In Robin Wave 1, we established an authoritative hierarchy where direct ATS feeds (Greenhouse, Ashby, Lever, Workday, etc.) hold absolute removal authority over aggregators (Tsenta, FreeHire, Arbeitnow, Adzuna).
- **Tsenta Scorecard & Evidence Audit (P1-1, P1-2, P1-3, P1-4, P1-6, P2-1):**
  - **P1-1 (Removal Authority):** Previously, an aggregator observation with `source_status = 'SEEN'` could keep a job live even after the employer's ATS closed or unlisted it. Furthermore, lagging aggregators could resurrect closed jobs. We resolved this by ensuring employer ATS observations override aggregators on removal and closure, and aggregator re-observations cannot clear `deleted_at` when an authoritative source has closed the posting.
  - **P1-2 (Run History):** Board discovery previously overwrote `last_run` in place on `job_sources`. Migration `V055__discovery_runs.sql` and `DiscoveryMetrics` now capture every run's execution metrics, mode, counters, and status.
  - **P1-3 (Re-observation Churn):** Repeated crawls of unchanged jobs produced high outbox churn. Migration `V056__jobs_last_discovered_event_at.sql` enables rate-limiting `TOUCHED` events.
  - **P1-4 & P2-1 (URL Normalisation & ATS Keys):** Unified URL normalisation into `JobIdentity.normalizeUrl`, added ATS keys for Greenhouse embed (`boards.greenhouse.io/embed/job_app?for={board}&token={id}`), Recruitee (`recruitee:{company}:{slug}`), and Phenom (`phenom:{host}:{jobId}`). Added aggregator/job-board ranking so direct employer pages (rank 2) and ATS pages (rank 3) supersede job boards (rank 1).
  - **P1-6 (Generic URL Path Collisions):** Fixed `DiscoveryOrchestrator.toCommand` fallback to use canonical URL normalisation preserving query identifiers (e.g. `?gh_jid=111` vs `?gh_jid=222`).

---

## 2. Files Changed

### Commit 1a (`3bd9d6c`):
- `backend/src/main/java/com/personal/jobagent/discovery/platform/JobIdentity.java`
- `backend/src/main/java/com/personal/jobagent/discovery/JobDeduplicationService.java`
- `backend/src/main/java/com/personal/jobagent/discovery/DiscoveryOrchestrator.java`
- `backend/src/main/java/com/personal/jobagent/discovery/JobDiscoveryService.java`
- `backend/src/main/java/com/personal/jobagent/apply/ApplicationIdentityService.java`
- `backend/src/test/java/com/personal/jobagent/discovery/platform/JobIdentityTest.java`
- `backend/src/test/java/com/personal/jobagent/apply/ApplicationIdentityServiceTest.java`

### Commit 1b:
- `backend/src/main/resources/db/migration/V055__discovery_runs.sql`
- `backend/src/main/resources/db/migration/V056__jobs_last_discovered_event_at.sql`
- `backend/src/main/java/com/personal/jobagent/discovery/DiscoveryMetrics.java`
- `backend/src/main/java/com/personal/jobagent/discovery/JobDiscoveryService.java`
- `backend/src/main/java/com/personal/jobagent/discovery/platform/BoardDiscoveryService.java`
- `backend/src/test/java/com/personal/jobagent/discovery/platform/CrossSourceDiscoveryIT.java`
- `docs/agents/AGENT1_DISCOVERY_REPORT.md`

---

## 3. Files Deliberately Left Untouched

- `backend/pom.xml` (Protected)
- `frontend/**` (Protected)
- `worker/**` (Protected)
- `backend/src/main/resources/application*.yml` (Protected)
- `backend/src/main/resources/db/migration/V001__...` through `V054__...` (Protected baseline migrations)
- `backend/src/test/java/com/personal/jobagent/events/OutboxDiscoveryExclusionIT.java` (Protected regression gate)
- `backend/src/test/java/com/personal/jobagent/application/DiscoveryToApplicationIT.java` (Protected regression gate)
- `backend/src/test/java/com/personal/jobagent/application/ApplicationPipelineIT.java` (Protected regression gate)
- `backend/src/test/java/com/personal/jobagent/application/ReviewQueueIT.java` (Protected regression gate)
- `backend/src/test/java/com/personal/jobagent/jobs/JobFeedIT.java` (Protected regression gate)
- `backend/src/test/java/com/personal/jobagent/apply/ApplyWorkflowIT.java` (Protected regression gate)

---

## 4. Implementation Summary

1. **Canonical URL Normalisation & Dual-Read:**
   - Lowercases scheme and host; drops default ports (80/443), fragments, and trailing slashes.
   - Decodes RFC 3986 §6.2.2.2 unreserved characters (`ALPHA`, `DIGIT`, `-`, `.`, `_`, `~`).
   - Filters tracking parameters (`utm_*`, `mc_*`, `lever-*`, `gclid`, `fbclid`, `msclkid`, `ref`, `source`, `src`, `gh_src`, `tk`, `tracking`, `campaign`, `trk`, `in_iframe`, `oga`).
   - Retains original query parameter value case and sorts query parameters by key.
   - Dual-read: `JobIdentity.lookupKeys` checks both canonical and pre-Wave-1 legacy URLs during advisory locking and resolution; `ApplicationIdentityService` dual-reads legacy `identity_key` values.
2. **Platform Identity & Ranking:**
   - Added ATS keys for Greenhouse embed, Recruitee, and Phenom.
   - Added `isJobBoardUrl` predicate covering Tsenta, Adzuna, Arbeitnow, FreeHire, SEEK, and Indeed.
   - `applicationUrlRank`: Aggregators = 0, Job boards = 1, Employer career pages = 2, ATS URLs = 3.
3. **Authoritative Removal & Lifecycle (P1-1):**
   - In `BoardDiscoveryService` and `JobDiscoveryService`, non-aggregator ATS sources are authoritative.
   - Aggregator observations cannot prevent deletion when an authoritative source marks a posting `MISSING` or `CLOSED`.
   - Aggregator re-observations cannot clear `deleted_at` if an authoritative source closed the job.
   - Authoritative sources listing the job again restore it.
   - Aggregator observations older than `aggregatorTtlDays` (default 7 days) are expired during scheduled maintenance.
4. **Run History Telemetry (P1-2):**
   - Implemented `discovery_runs` table (`V055`) and `DiscoveryMetrics` component tagged by `kind`, `mode`, and `status`.
5. **Re-observation Churn Rate Limiting (P1-3):**
   - Added nullable `jobs.last_discovered_event_at` (`V056`).
   - `INSERTED` and `UPDATED` are always emitted; `TOUCHED` events are rate-limited to once per configured interval.

---

## 5. Tests Executed and Commands

1. **Commit 1a Baseline & Targeted Suite:**
   ```bash
   mvn -B test -Dtest="ApplicationIdentityServiceTest,JobIdentityTest" -f backend/pom.xml
   ```
2. **All Discovery Unit Tests:**
   ```bash
   mvn -B test -Dtest="com.personal.jobagent.discovery.**.*Test" -f backend/pom.xml
   ```
3. **Cross-Source Discovery Integration Tests (V055, V056, Removal Authority, Run History):**
   ```bash
   mvn -B verify "-Dtest=CrossSourceDiscoveryIT" "-Dit.test=CrossSourceDiscoveryIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.failIfNoSpecifiedTests=false" -f backend/pom.xml
   ```
4. **Outbox Exclusion & Pipeline End-to-End Gates:**
   ```bash
   mvn -B verify "-Dtest=OutboxDiscoveryExclusionIT,DiscoveryToApplicationIT" "-Dit.test=OutboxDiscoveryExclusionIT,DiscoveryToApplicationIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.failIfNoSpecifiedTests=false" -f backend/pom.xml
   ```
5. **Job Feed, Review Queue & Apply Workflow Gates:**
   ```bash
   mvn -B verify "-Dtest=JobFeedIT,ReviewQueueIT,ApplyWorkflowIT" "-Dit.test=JobFeedIT,ReviewQueueIT,ApplyWorkflowIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.failIfNoSpecifiedTests=false" -f backend/pom.xml
   ```
6. **Cloudflare Managed Mail & Recruiter Lifecycle Gates (TRACK):**
   ```bash
   mvn -B verify "-Dtest=CloudflareManagedMailIT,CloudflareRecruiterLifecycleIT" "-Dit.test=CloudflareManagedMailIT,CloudflareRecruiterLifecycleIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.failIfNoSpecifiedTests=false" -f backend/pom.xml
   ```

---

## 6. Actual Results

- `ApplicationIdentityServiceTest, JobIdentityTest`: **49 tests run, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)
- `com.personal.jobagent.discovery.**.*Test`: **322 tests run, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)
- `CrossSourceDiscoveryIT`: **13 tests run, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)
- `OutboxDiscoveryExclusionIT, DiscoveryToApplicationIT`: **11 tests run, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)
- `JobFeedIT, ReviewQueueIT, ApplyWorkflowIT`: **38 tests run, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)
- `CloudflareManagedMailIT, CloudflareRecruiterLifecycleIT`: **12 tests run, 0 failures, 0 errors, 0 skipped** (BUILD SUCCESS)

Total Tests Run: **445 tests executed across all discovery suites and regression gates, 0 failures, 0 errors**.

---

## 7. Known Limitations

- **Aggregator URL mapping:** Generic job-board URLs that do not embed ATS query tokens or specific slug formats cannot be mapped to ATS platforms without scraping the detail page (deferred to Wave 2 P1-5).
- **ARCHIVED status semantics (P2-4):** Scheduled maintenance archives jobs older than 30 days; enhanced soft-archival sweeps remain scheduled for Wave 2.

---

## 8. Dependencies on Other Agents

- **Agent 2 (Matching):** Consumes `JobIdentity.normalizeUrl` and `JobIdentity.isAggregatorUrl` in `JobsController`. Agent 2's `ApplicationPipelineService.onJobIngested` is triggered by `job.discovered` events emitted by Agent 1.
- **Agent 4 (Lifecycle):** Consumes `job.discovered` outbox events. TRACK's `EmailApplicationMatcher` consumes `JobIdentity.normalizeUrl` and `JobIdentity.employerKey`.

---

## 9. Migration Proposals

### `V055__discovery_runs.sql`
- Creates `discovery_runs` table with columns: `id`, `source_id`, `kind`, `mode`, `started_at`, `finished_at`, `duration_ms`, `status`, `failure_category`, `detail`, `counters`, `complete`.
- Indexes: `discovery_runs_source_idx(source_id, started_at desc)`, `discovery_runs_kind_idx(kind, started_at desc)`.
- Verified via Testcontainers PostgreSQL 16.

### `V056__jobs_last_discovered_event_at.sql`
- Adds nullable column `last_discovered_event_at timestamptz` to `jobs`.
- Partial index: `jobs_last_discovered_event_at_idx on jobs(last_discovered_event_at) where last_discovered_event_at is not null`.
- Verified via Testcontainers PostgreSQL 16.

---

## 10. Commit SHAs

- Commit 1a: `3bd9d6c` (`discovery: canonical URL normalisation, ATS keys and dual-read identity`)
- Commit 1b: *(pending commit)*

---

## 11. Branch

- `claude/agent1-discovery-a27a8f`
