# ROBIN — Build Roadmap

Evidence → Tsenta comparison → prioritisation → implementation → verification → phase closure. A phase is not declared complete until its exit criteria are evidenced and its status is recorded accurately.

## Phase 20 — Managed-mail production verification — **OPEN**

**Goal:** prove the Cloudflare inbound-mail chain end-to-end with a real test email.

**Verified:** one real email completed Cloudflare → Worker → Render → Postgres, encrypted, persisted and visible in Robin. Encryption-key continuity was checked before provisioning the new key. The managed-mail unit and PostgreSQL integration suites pass.

**Still not production-verified:** duplicate webhook redelivery/idempotency on the live Cloudflare→Render path, email-to-application association, automatic OTP extraction/use in a real supported employer verification flow, and the full recruiter-communication lifecycle. Do not claim Phase 20 closed based on a single delivered email or test-container coverage. See `ROBIN_PHASE20_VERIFICATION.md` §4.3.

### Phase 20.1 — Fix Workday capability-registry truthfulness — **DONE**

Commit `610d574` corrects Workday to `SIGN_IN_READINESS_ONLY`; Workday's actual service checks saved credential readiness and does not inspect or fill the multi-step application form. The fix is present in the `master` baseline used by PR #10. Production behavior was not independently re-tested in this pass.

### Phase 20.2 — Add CI coverage for `cloudflare-email-worker` — **DONE**

PR #10 merged to `master` as `6c3a72a`. The new Node 22 job runs `npm test --prefix cloudflare-email-worker`. GitHub Actions run #62 completed all four jobs successfully (backend, browser worker, frontend, Cloudflare email worker). This resolves the former CI coverage gap; see the merged workflow.

## Phase 21 — Candidate Knowledge System Integrity — **VERIFIED IN PR #11; PENDING MERGE**

**Goal:** verify that profile facts are sourced from the authenticated candidate's stored records, that CV/cover-letter preparation cannot silently invent candidate claims, and that unsupported claims are blocked or visibly escalated.

**Evidence:** `docs/ROBIN_PHASE21_VERIFICATION.md` records the source trace and limitations. The PostgreSQL-backed `PrepWorkflowIT` test traces a stored Java skill and Café Systems work-history record through deterministic CV generation, provenance and `fact-validator-2`; it then injects an unsupported PhD claim and asserts an `UNSUPPORTED_QUALIFICATION` BLOCKER.

**Integrity corrections:** completed-month experience calculation; unit/currency-preserving figure checks; structured right-to-work/sponsorship contradiction checks; employment evidence limited to actual work-history records; expanded candidate evidence context for cover letters; validator-version migration and stale-document handling in API and PREP UI.

**Verification:** PR #11 CI run #75 passed all four jobs. Backend Surefire: 1,397 tests, 0 failures, 6 skipped. Backend Failsafe: 272 tests, 0 failures, 25 skipped. Frontend type-check/tests/build, browser worker and Cloudflare email worker jobs passed. Skipped tests are not counted as executed coverage. The PR is not merged; Phase 21 is not closed until the change is reviewed and merged. See the verification report for limits and test detail.

## Phase 22 — Discovery evidence pass (after Phase 21 closes)

**Goal:** measure, don't assume. Read `JobDeduplicationService`, `DiscoveryScheduler`, `BoardDiscoveryService` fully; run existing discovery tests; establish real freshness/dedup numbers from authoritative fixtures or audit evidence.

**Exit criteria:** evidence-backed duplicate and stale-job rates, not estimates.

## Phase 23 — Matching/ranking evidence pass

**Goal:** read `JobMatchService`, `HardFilterService`, `SemanticSkillMatcher` fully; prove hard filters cannot be overridden by score and match explanations derive from computed sub-scores.

**Exit criteria:** a passing adversarial test proving a hard-excluded job cannot reach `AUTO_APPLY` regardless of AI score.

## Phase 24 — ATS execution reliability (Greenhouse/Ashby first; Workday scope decision second)

**Goal:** explicitly decide whether Robin will ever attempt Workday's multi-step application form or remain login-readiness-only. Greenhouse/Ashby must first be tested only against a controlled sandbox/test posting the candidate owns.

**Exit criteria:** a controlled submission against a candidate-owned sandbox/test posting with complete receipt evidence before any adapter is called `SUBMISSION_VERIFIED`.

## Phase 25+ — Tracking loop closure, automation intelligence, UX audit, ecosystem expansion, commercial readiness

Sequence these only after the earlier evidence passes close. Do not expand the roadmap into speculative work before then.

---

**Testing requirement:** run the actual command and report actual exit code and pass/fail/skip counts. Never state a result that was not executed.

**Production-safety requirement:** no real employer submissions, live duplicate webhook replay, or production secret/config changes without explicit operator authorisation.
