# ROBIN — Build Roadmap

Evidence → Tsenta comparison → prioritisation → implementation → verification → phase closure, one phase at a time. No phase starts before the previous one's exit criteria are met.

## Phase 20 (current, open) — Managed-mail production verification
**Goal:** prove the Cloudflare inbound-mail chain end-to-end with a real test email.
**Blocked on:** your access to Render/Supabase/Cloudflare dashboards (see `ROBIN_PHASE20_VERIFICATION.md` §6 for the exact 3 lookups needed). Not blocked on more code.
**Exit criteria:** the 10-item checklist in `ROBIN_PHASE20_VERIFICATION.md` §5, all green.

## Phase 20.1 (new, zero-dependency, can run in parallel with Phase 20's blockers) — Fix the Workday capability-registry truthfulness bug
**Why first:** it's a one-file, fully-test-covered correction (`AtsCapabilityRegistry.java` + `AtsCapabilityRegistryTest.java`) with no production dependency, no DB/Cloudflare/Render access needed, and it fixes a live truthfulness gap in exactly the dimension this whole product claims to compete on. Doing this costs nothing and removes the single worst finding in the Gap Register.
**Acceptance criteria:** `AtsCapabilities.summary()` for Workday returns `SIGN_IN_READINESS_ONLY`, not `FORM_FILL_AVAILABLE`; `AtsCapabilityRegistryTest` updated and passing; `mvn -f backend/pom.xml test -Dtest=AtsCapabilityRegistryTest` green.
**Candidate value:** a candidate opening a Workday job is told the truth about what Robin will do for them.

## Phase 20.2 — Add CI coverage for `cloudflare-email-worker`
**Why:** the one package in the Phase 20 critical path currently has zero CI gate.
**Acceptance criteria:** new job in `.github/workflows/ci.yml` running `npm test --prefix cloudflare-email-worker`; green on the next PR.

## Phase 21 (after Phase 20 closes) — Candidate knowledge system integrity pass
**Goal:** verify (not assume) that profile facts/CV data/eligibility/preferences are actually enforced consistently — read `ProfileController`, `DocumentFactValidator`, and the resume/cover-letter generation path end-to-end; confirm "never invent a fact" is actually enforced in the LLM prompt/validation layer, not just claimed in comments.
**Exit criteria:** a documented trace of one real profile fact from storage → CV generation → fact-validation check, with the validator's actual rejection behavior demonstrated against an injected false claim (test, not assumption).

## Phase 22 — Discovery evidence pass
**Goal:** measure, don't assume. Read `JobDeduplicationService`, `DiscoveryScheduler`, `BoardDiscoveryService` fully; run the existing discovery tests; establish real freshness/dedup numbers against whatever fixtures or sampled live boards already exist in the test suite (e.g. `PlatformCoverageAuditTest`, if that's the authoritative current-state source the handover referenced).
**Exit criteria:** a real number for "duplicate rate" and "stale-job rate" from actual test/audit evidence, not estimation.

## Phase 23 — Matching/ranking evidence pass
**Goal:** read `JobMatchService`, `HardFilterService`, `SemanticSkillMatcher` fully; confirm hard filters genuinely cannot be overridden by score (write a test that tries); confirm match explanations are generated from real computed sub-scores, not templated text.
**Exit criteria:** a passing adversarial test proving a hard-excluded job cannot reach `AUTO_APPLY` regardless of AI score.

## Phase 24 — ATS execution reliability (Greenhouse/Ashby first, Workday application-form scope decision second)
**Goal:** this is the biggest lever per your own roadmap. Before writing new adapter code, decide explicitly: does Robin attempt Workday's actual multi-step form, or keep it permanently scoped to login-verification-only (and market it that way)? That's a product decision, not an engineering one — recommend deciding this *before* Phase 24 starts, since it changes the phase's whole scope.
**Exit criteria:** at least one ATS (recommend Greenhouse, since it already has the most mature guarded-submit path) completes a controlled test submission against a sandbox/test posting you control, with full receipt evidence — the first time any ATS in this codebase reaches genuinely `SUBMISSION_VERIFIED`.

## Phase 25+ — Tracking loop closure, automation intelligence, UX audit, ecosystem expansion, commercial readiness
Sequenced exactly per your own §6 items G–K. Not detailed further here until Phase 24 closes — detailing them now would be exactly the kind of premature, unverified planning this audit is designed to avoid.

---

**Testing requirement for every phase above:** run the actual command, report the actual exit code and pass/fail count. Never state a result I have not executed.
**Deployment requirement:** no phase above requires a production deployment except the final step of Phase 20 (the real test email) and eventually Phase 24 (a real sandbox ATS submission) — both require your explicit go-ahead at the moment of action, not before.
