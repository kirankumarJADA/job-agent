# ROBIN — Current State

**Prepared:** 2026-11-03, from direct source inspection (31 backend packages, 51 migrations, frontend, both workers), local Git state, and the live GitHub repository (PR #9, Actions run #60, open PR list). No production dashboard (Render/Cloudflare/Vercel/Supabase) access in this session.

## Architecture (confirmed from source)

Java 21 / Spring Boot 3.3.4 modular monolith (`backend/pom.xml`), React 18.3.1 / TypeScript / Vite SPA (`frontend/package.json`), separate Node/Playwright browser-automation worker (`worker/`), separate Cloudflare Email Worker (`cloudflare-email-worker/`, no runtime npm dependencies — Web Crypto only), optional Python/Scrapling sidecar (`sidecar/scrapling/`). PostgreSQL via Flyway, 51 migrations `V001`–`V051` confirmed present on disk. Package map (`backend/src/main/java/com/personal/jobagent/*`) has exactly the 31 packages the handover claimed — verified by directory listing, not assumed.

## Repository / production status

- **Updated 2026-11-04:** all Phase 20 work through `b2be7e0` is pushed to `origin/master` (local and remote SHAs verified after each push). CI evidence per commit is kept in one place: `ROBIN_PHASE20_VERIFICATION.md` §3.1.
- CI now has four jobs — `backend`, `worker`, `frontend`, `cloudflare-email-worker` (added in `6c3a72a`; also runs the Phase 20 operator-probe tests since `b2be7e0`).
- **Production lags `master`:** Render still serves the build from `2026-11-03T21:20:48Z`, which predates the Firebase revocation hardening and reply-thread linking. Render auto-deploy is off; backend deploys are manual. The Cloudflare Worker was redeployed on 2026-11-04 23:36 UTC (version `579c2681…`, tag `170af8b`) and now forwards `In-Reply-To`/`References`; the backend deploy is still pending. Procedure: `ROBIN_PHASE20_VERIFICATION.md` §3.3–§3.4.
- 3 stale open PRs (#1, #7, #6 — earlier phase branches) exist on GitHub but their content is already folded into `master` via commit `1b49773`; not blocking.
- PR #11 (`phase21-candidate-knowledge-integrity`, 26 commits, based on `6c3a72a`) holds the Phase 21 implementation. A trial merge into current `master` conflicts only in `docs/ROBIN_PHASE20_VERIFICATION.md`; it is reconciled after Phase 20 closes.
- PR #9's own description states inbound Cloudflare mail delivery is explicitly unproven and Phase 20 must stay open.

## Confirmed COMPLETE (code-level, tested, matches its own documentation)

- Cloudflare inbound-mail HMAC pipeline: Worker → `CloudflareInboundController` → `CloudflareSignatureVerifier` → `ManagedInboundService` — full replay/duplicate/idempotency handling, AES-256-GCM content encryption, correct response-code contract (200/401/406/503) matched on both sides of the Worker/backend boundary.
- `ManagedMailboxService` readiness model (`NOT_CONFIGURED` / `PENDING_RECEIVING_VERIFICATION` / `ACTIVE`) — evidence-based, requires an actual signed delivery before claiming `ACTIVE`, not just correct config.
- CORS allowlist: fail-closed, exact-match, loopback-origin-stripping in prod (`SecurityConfig.java`).
- Frontend build-time API-base-URL guard (`apiBaseContract.ts`) — added specifically after a documented prior incident where a malformed `VITE_API_BASE_URL` caused opaque production CORS failures.
- Application receipts: real SHA-256/byte-size/confirmation-evidence fields exist (`ApplicationReceiptService`), with an explicit "not confirmed" fallback rather than a false positive.

## Confirmed INCOMPLETE / OVERSTATED (code-level evidence, not speculation)

- **Workday capability was overstated to the candidate — FIXED this session, commit `610d574`.** `WorkdayExecutionPlanService`'s own module Javadoc states it *only* verifies a saved credential against the real sign-in page and *never* reaches, inspects, or fills the actual multi-step application form. The `AtsCapabilityRegistry.WORKDAY` row previously set `formFilling = FULL`, making `GET /api/v1/ats-capabilities` report Workday as form-fill-capable when the actual service never touches the form. Commit `610d574` sets `formFilling` (and the other form/submission dimensions) to `NONE`, so `AtsCapabilities.summary()` now correctly returns `SIGN_IN_READINESS_ONLY` for Workday, matching the frontend's `ui.tsx`/`JobsFeedPage.test.tsx` expectations, which already anticipated this value. **See `ROBIN_GAP_REGISTER.md` §1 for the original finding; it is marked resolved there.** The fix is committed locally but **not yet pushed** to `origin/master`.
- No ATS adapter (including Greenhouse) is ever assigned `SUBMISSION_VERIFIED` — this is enforced *by design* in `AtsCapabilityRegistry`'s own class comment ("Phase 10's own rules... explicitly forbid ever submitting a real application as a test"). Greenhouse's real-submit path is implemented and worker-validated, but only against fixtures.
- Lever's real submission is permanently blocked by hCaptcha (enforced two independent ways — backend plan-type gate + worker plan validator); this is architecture, not a bug to fix.
- Zoho Recruit: partial identity-field filling only; most custom questions surfaced as gaps, never submits.
- Phase 20 (managed mail) is implementation-complete and **has its first real production proof point as of 2026-11-03**: a real inbound email completed the full Cloudflare → Worker → Render → Postgres chain, encrypted at rest, visible in Robin (see `ROBIN_PHASE20_VERIFICATION.md` §4). The earlier `APP_SECRETS_KEY`-missing state is resolved — the operator generated and added a new key and restarted the backend. Phase 20 is **still not fully closed**: duplicate-delivery idempotency, email-to-application association, automatic OTP extraction, and the full recruiter-communication lifecycle remain production-unverified (`ROBIN_PHASE20_VERIFICATION.md` §4.3).

## Known blockers (require your action, not more code reading)

1. Encryption-key continuity for `employer_credentials`/`managed_inbound_messages` is resolved — a pre-provisioning count found zero rows in both tables before the new `APP_SECRETS_KEY` was added, so no row can depend on a lost prior key (`ROBIN_PHASE20_VERIFICATION.md` §2.2).
2. Duplicate-delivery idempotency, email-to-application association, automatic OTP extraction, and the recruiter-communication lifecycle are still not production-verified — see `ROBIN_PHASE20_VERIFICATION.md` §4.3.
3. The three code/doc fixes plus this documentation set are committed locally but not pushed — awaiting your go-ahead, since a push to `master` is production-adjacent.
