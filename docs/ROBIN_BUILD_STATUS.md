# Robin Build Status

Persistent engineering handoff. Update this file after EVERY completed phase
and at the end of every session. Never rely on chat memory.

## Current Date
2026-10-06

## Current Git HEAD
`a32c2da` (Phase 4) + Phase 5 working tree — see "Last Verified Commit"

## Completed Phases
| Phase | Commit | Status |
|---|---|---|
| 1 — Hard filtering before semantic matching | `72d18fd` | ✅ 460 tests |
| 2 — Semantic skill matching (three-tier) + V029 cache | `f8008c3` | ✅ 496 tests |
| 3 — Secondary provider parallel fan-out | `aecc7e9` | ✅ 499 tests |
| 4 — Rate-limit / cost / quota engine + V030 | `a32c2da` | ✅ 512 tests |
| 5 — Automatic application decision + V031 | `48b7d56` | ✅ 621 tests |
| 6 — Human review queue + V032 | (this commit) | ✅ 628 tests |
| (earlier production milestones) | `d64f92e`…`f33f410` | CSRF Bearer exemption, worker auth, Phase 16 worker container, Phase 17 Stripe source (V028), NIM priority tests |

## Current Phase
**Phase 6 — Human Review Queue** — committed (see Last Verified Commit).
Next: Phase 7 (auto-approval rule engine).

## Exact Implemented Features (Phase 5, commit 48b7d56)
- `V031__application_decisions.sql` — decision audit table (upsert on
  profile+job; partial index for the Phase 6 review queue).
- `application/ApplicationDecisionService.java` — decision engine:
  MANUAL → always NEEDS_REVIEW; ASSISTED → AUTO_APPLY only at score ≥ 85
  (`HIGH_CONFIDENCE_THRESHOLD`), else NEEDS_REVIEW; CONTROLLED_AUTO →
  AUTO_APPLY for every APPLY match; daily quota exhausted → NEEDS_REVIEW
  regardless of mode; SKIP/REVIEW recommendations never auto-apply.
  Persistence is best-effort (upsert), decisions never block the pipeline.
- `ApplicationPipelineEventHandler.handleMatched` — now routes APPLY matches
  through the decision engine; AUTO_APPLY creates the application; NEEDS_REVIEW
  emits `approval.required` (with profile/job/score/reason) and creates nothing.
- `AutomationMetrics.decisionRecorded` — `robin_application_decisions_total{decision}`.

## Test Counts
- Backend: **628 tests, 0 failures, 0 errors** (Phase 6 full verify).
- Worker: 19/19. Frontend: **173/173** + build green.
- New in Phase 6: `CsrfBearerExemptionIT` 5/5, `ReviewQueueIT` 7/7,
  `ReviewQueuePage.test.tsx` 3/3.

## Known Limitations
- NEEDS_REVIEW decisions currently only produce a notification + audit row;
  the review-queue UI/API is Phase 6.
- Cover letter / answers use `SimulatedProvider` unless `NIM_API_KEY` is set
  on the deployment (registry self-enables eligible NIM models on boot).
- The stale-connection IT failures seen once in this environment were Docker
  Desktop resource pressure, not code (re-run green after freeing Docker).

## Production State
- Render backend live (`job-agent-mwhu.onrender.com`), Vercel frontend live.
- V028 Stripe Greenhouse source scheduled every 15 min; first sweep ingests
  the whole live board (~714 postings, idempotent after).
- OCI worker (`robin-worker` on 140.238.70.151) polls claim-next every 10s;
  claim-next authenticated → 204 when the queue is empty.
- REAL_SUBMIT: INTENTIONALLY DISABLED (hard stop verified by
  `AutomationLifecycleIT`; READY_TO_SUBMIT is terminal).

## Remaining Phases
6 Review queue UI/API · 7 Auto-approval rule engine · 8 Production PDF ·
9 Real Greenhouse submission (gated) · 10 Cross-source dedup · 11 Workday ·
12 Lever · 13 Real mailbox/OTP · 14 User search · 15 URL extraction ·
16 Source catalogue · 17 Region-aware discovery · 18 Recruiter email
intelligence · 19 Follow-ups · 20 Analytics · 21 Dashboard completion ·
22 Bulk ops · 23 Webhooks · 24 PWA/extension/MCP.

## Next Exact Task
Phase 7 — Auto-approval rule engine: configurable per-user rules evaluated in
`ApplicationDecisionService.decide` (threshold, quota, mode) plus the plan
safety preconditions (no HARD_STOP, required fields SUPPORTED_AUTO, artifact
integrity) before any AUTO_APPLY is permitted. Persist user preferences and
respect MANUAL/ASSISTED/CONTROLLED_AUTO.

## Last Verified Commit
`feat: complete automatic application decision` + Phase 6 review queue
commit (see git log). Verify with `git status --short` (must be clean) and
`mvn -q verify` (must be 0 failures).

## Last Verified Test Result
Backend **628/0/0**, worker 19/19, frontend **173/173** + build — all on the
current HEAD.
