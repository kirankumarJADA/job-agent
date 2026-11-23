# Phase 2A Remediation: Provider Query Routing & ATS Harvesting Architecture

## 1. Executive Summary & Root Cause Analysis

### 1.1 The Baseline Defect (P0-2)
In the Phase 2 baseline ([`fb3f606`](https://github.com/kirankumarJADA/job-agent/commit/fb3f606)), `SearchPlanExecutionCoordinator` created an `ExpandedQueryPlan` (containing expanded target titles, skill keywords, location terms, and company filters) via `SearchPlanQueryExpander`. However, when initiating crawler runs, it invoked:
```java
// DEFECTIVE BASELINE IMPLEMENTATION (fb3f606)
BoardDiscoveryService.SourceRunReport report = boardDiscoveryService.run(sourceId, BoardDiscoveryService.Mode.SCHEDULED);
```
None of the expanded query parameters, target titles, or locations were passed to the discovery adapter. The external crawler executed an unparameterized crawl, ignoring candidate search parameters.

---

## 2. Remediated Architecture: Aggregator Query Routing vs. ATS Board Harvesting

The remediation cleanly differentiates two fundamentally distinct categories of discovery platforms:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        SearchPlanCoordinator                           │
│                     (ExpandedQueryPlan Facets)                         │
└──────────────────┬─────────────────────────────────┬───────────────────┘
                   │                                 │
                   ▼                                 ▼
   ┌───────────────────────────────┐ ┌───────────────────────────────────┐
   │       Aggregators (ADZUNA)    │ │   Direct Employer ATS Boards      │
   │                               │ │   (GREENHOUSE, LEVER, ASHBY, etc.)│
   │ • Query params passed via API │ │ • Company-wide vacancies harvested│
   │ • 'what' = title/keywords     │ │ • Downstream filtering via        │
   │ • 'where' = location          │ │   MultiStageReranker              │
   └───────────────────────────────┘ └───────────────────────────────────┘
```

### 2.1 Aggregator Providers (Adzuna)
Aggregators index millions of jobs across thousands of employers and expose search endpoints requiring search parameters.
- **`AdzunaAdapter` ([`AdzunaAdapter.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/main/java/com/personal/jobagent/discovery/platform/adapters/AdzunaAdapter.java)):**
  Added `buildUri(country, page, what, where)`:
  ```java
  public URI buildUri(String country, int page, String what, String where) {
      StringBuilder sb = new StringBuilder("https://api.adzuna.com/v1/api/jobs/")
              .append(country).append("/search/").append(page)
              .append("?app_id=").append(appId)
              .append("&app_key=").append(appKey);
      if (what != null && !what.isBlank()) {
          sb.append("&what=").append(URLEncoder.encode(what.trim(), StandardCharsets.UTF_8));
      }
      if (where != null && !where.isBlank()) {
          sb.append("&where=").append(URLEncoder.encode(where.trim(), StandardCharsets.UTF_8));
      }
      return URI.create(sb.toString());
  }
  ```
  In `list(board, context)`, `what` and `where` are extracted from `board.params()` and supplied to `buildUri`.

### 2.2 Direct Employer ATS Boards (Greenhouse, Lever, Ashby, Workday, Rippling)
Direct employer ATS platforms do not support keyword search parameters in their public board APIs. Instead, they expose the public career board for a single company (e.g. `/v1/boards/stripe/jobs`).
- **Harvesting Strategy:** Full board crawl of the configured employer.
- **Candidate-Specific Filtering:** Executed downstream deterministically by `MultiStageReranker` (Stage 1 Hard Eligibility Gate and Stage 2 Semantic Skill Matching).
- **Transparency:** The coordinator explicitly evaluates and logs facet support per provider kind, informing the candidate whether facets are filtered upstream at the provider API level or downstream during intelligent matching.

### 2.3 BoardDiscoveryService Parameter Passing
[`BoardDiscoveryService.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/main/java/com/personal/jobagent/discovery/platform/BoardDiscoveryService.java) was extended with overloaded methods accepting query parameters:
```java
public SourceRunReport run(UUID sourceId, Mode mode, Map<String, String> queryParams) {
    ...
    BoardRef board = new BoardRef(kind, source.orgIdentifier(), queryParams != null ? queryParams : Map.of());
    return runInternal(source, adapter, board, mode);
}
```

### 2.4 Coordinator Parameter Routing
[`SearchPlanExecutionCoordinator.java`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/backend/src/main/java/com/personal/jobagent/discovery/plan/SearchPlanExecutionCoordinator.java) now builds query parameters for aggregator sources:
```java
Map<String, String> queryParams = new HashMap<>();
if (source.kind() == PlatformKind.ADZUNA) {
    if (!expandedPlan.targetTitles().isEmpty()) {
        queryParams.put("what", expandedPlan.targetTitles().get(0));
    } else if (!expandedPlan.expandedQueries().isEmpty()) {
        queryParams.put("what", expandedPlan.expandedQueries().get(0));
    }
    if (!expandedPlan.locations().isEmpty()) {
        queryParams.put("where", expandedPlan.locations().get(0));
    }
}
BoardDiscoveryService.SourceRunReport report = boardDiscoveryService.run(
        source.id(), BoardDiscoveryService.Mode.SCHEDULED, queryParams);
```

### 2.5 Candidate UI Transparency ([`SearchPlansPage.tsx`](file:///c:/Users/jadak/Documents/antigravity/peaceful-chandrasekhar/.claude/worktrees/remediation-discovery-hardening/frontend/src/pages/SearchPlansPage.tsx))
The Search Plans UI was updated with an informative **Provider Capability & Facet Routing Breakdown** card explaining how candidate search plans route queries:
- **Aggregator Boards (e.g. Adzuna):** Queries, titles, and locations are routed upstream to external search APIs.
- **Direct Employer ATS (e.g. Greenhouse, Lever, Ashby, Workday, Rippling):** Harvests open vacancies for the employer, applying target titles, seniority, and skill filters downstream in the multi-stage reranker.

---

## 3. Verification & Test Evidence

1. **`AdzunaAdapterTest.buildUriIncludesQueryParametersWhenPresent`**:
   - Verifies URI generation with `&what=Java+Spring` and `&where=London`.
   - Verifies default URI generation without query parameters.
2. **`AdzunaAdapterTest.listPassesQueryParamsFromBoardRef`**:
   - Executes listing crawl with `BoardRef(ADZUNA, "gb", Map.of("what", "Rust Systems", "where", "London"))`.
   - Verifies HTTP request recorded contains `what=Rust+Systems` and `where=London`.
3. **`SearchPlanExecutionCoordinatorTest`**:
   - Verifies query parameters are extracted from `ExpandedQueryPlan` and passed to `BoardDiscoveryService.run(sourceId, Mode.SCHEDULED, queryParams)`.
   - Verifies facet support evaluation returns `SUPPORTED` for Adzuna and `DOWNSTREAM_ONLY` for ATS platforms.
