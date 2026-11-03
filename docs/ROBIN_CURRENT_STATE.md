# ROBIN — Current State

**Prepared:** 2026-11-03, from direct source inspection (31 backend packages, 51 migrations, frontend, both workers), local Git state, and the live GitHub repository (PR #9, Actions run #60, open PR list). No production dashboard (Render/Cloudflare/Vercel/Supabase) access in this session.

## Architecture (confirmed from source)

Java 21 / Spring Boot 3.3.4 modular monolith (`backend/pom.xml`), React 18.3.1 / TypeScript / Vite SPA (`frontend/package.json`), separate Node/Playwright browser-automation worker (`worker/`), separate Cloudflare Email Worker (`cloudflare-email-worker/`, no runtime npm dependencies — Web Crypto only), optional Python/Scrapling sidecar (`sidecar/scrapling/`). PostgreSQL via Flyway, 51 migrations `V001`–`V051` confirmed present on disk. Package map (`backend/src/main/java/com/personal/jobagent/*`) has exactly the 31 packages the handover claimed — verified by directory listing, not assumed.

## Repository / production status

- Local `master` HEAD = `origin/master` HEAD = `5d893245937e0956fc90b69790ec7b62e12eb79f` (PR #9 merge commit), now one commit ahead locally (`e56c2a9`, uncommitted-to-remote `wrangler.toml` fix — see `ROBIN_PHASE20_VERIFICATION.md`).
- GitHub Actions run #60 on that commit: "completed successfully" (backend/worker/frontend CI jobs). **`cloudflare-email-worker` has no CI job at all** in `.github/workflows/ci.yml` — its test-pass claims were never CI-verified, only run locally/manually.
- 3 stale open PRs (#1, #7, #6 — earlier phase branches) exist on GitHub but their content is already folded into `master` via commit `1b49773`; not blocking.
- PR #9's own description states inbound Cloudflare mail delivery is explicitly unproven and Phase 20 must stay open.

## Confirmed COMPLETE (code-level, tested, matches its own documentation)

- Cloudflare inbound-mail HMAC pipeline: Worker → `CloudflareInboundController` → `CloudflareSignatureVerifier` → `ManagedInboundService` — full replay/duplicate/idempotency handling, AES-256-GCM content encryption, correct response-code contract (200/401/406/503) matched on both sides of the Worker/backend boundary.
- `ManagedMailboxService` readiness model (`NOT_CONFIGURED` / `PENDING_RECEIVING_VERIFICATION` / `ACTIVE`) — evidence-based, requires an actual signed delivery before claiming `ACTIVE`, not just correct config.
- CORS allowlist: fail-closed, exact-match, loopback-origin-stripping in prod (`SecurityConfig.java`).
- Frontend build-time API-base-URL guard (`apiBaseContract.ts`) — added specifically after a documented prior incident where a malformed `VITE_API_BASE_URL` caused opaque production CORS failures.
- Application receipts: real SHA-256/byte-size/confirmation-evidence fields exist (`ApplicationReceiptService`), with an explicit "not confirmed" fallback rather than a false positive.

## Confirmed INCOMPLETE / OVERSTATED (code-level evidence, not speculation)

- **Workday capability is overstated to the candidate.** `WorkdayExecutionPlanService`'s own module Javadoc states it *only* verifies a saved credential against the real sign-in page and *never* reaches, inspects, or fills the actual multi-step application form. But `AtsCapabilityRegistry.WORKDAY`'s data row sets `formFilling = FULL`, and `AtsCapabilities.summary()` returns `FORM_FILL_AVAILABLE` whenever `formFilling != NONE` — so the candidate-facing endpoint `GET /api/v1/ats-capabilities` currently reports Workday as form-fill-capable when the actual service never touches the form. This is an internal inconsistency inside `AtsCapabilityRegistry.java` itself (its own class-level Javadoc at lines 47-62 correctly describes the limitation; the `WORKDAY` row's data values at line 143 and prose at lines 144-149 contradict it). **See `ROBIN_GAP_REGISTER.md` §1 — this is the single highest-severity finding of this audit.**
- No ATS adapter (including Greenhouse) is ever assigned `SUBMISSION_VERIFIED` — this is enforced *by design* in `AtsCapabilityRegistry`'s own class comment ("Phase 10's own rules... explicitly forbid ever submitting a real application as a test"). Greenhouse's real-submit path is implemented and worker-validated, but only against fixtures.
- Lever's real submission is permanently blocked by hCaptcha (enforced two independent ways — backend plan-type gate + worker plan validator); this is architecture, not a bug to fix.
- Zoho Recruit: partial identity-field filling only; most custom questions surfaced as gaps, never submits.
- Phase 20 (managed mail) is implementation-complete but production-unverified: no evidence of a real inbound email completing the full chain, and the backend currently reports `APP_SECRETS_KEY` as missing to at least one candidate (per your report) — root cause not yet isolated (see `ROBIN_PHASE20_VERIFICATION.md`).

## Known blockers (require your action, not more code reading)

1. No DB access to confirm whether `employer_credentials`/`managed_inbound_messages` already hold ciphertext dependent on a lost key.
2. No Render/Cloudflare/Vercel dashboard access to confirm live env var values.
3. The `wrangler.toml` fix is committed locally but not pushed — awaiting your go-ahead, since a push to `master` is production-adjacent.
