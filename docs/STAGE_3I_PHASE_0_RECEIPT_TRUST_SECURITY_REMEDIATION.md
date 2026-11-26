# ROBIN — Stage 3I (Phase 0): Receipt Trust Security Remediation & Contract Alignment

**Branch:** `integration/wave-3`  
**Pull Request:** `#21` (`OPEN` / `DRAFT` — not merged)  
**Starting Baseline Commit:** `b4fdfa2490db2a4f4ef16f6b5bb67e73fc033f87` (Stage 3H audit)  
**Schema Migrations:** `V001`–`V070` completely untouched (zero new migrations required)

---

## 1. Objective & Scope

Stage 3H (`docs/STAGE_3H_UNIVERSAL_RECEIPT_VERIFICATION_ARCHITECTURE_AUDIT.md`) identified a P0 Managed Mail DKIM bypass vulnerability and five P1 receipt/tracking contract defects that blocked any safe evolution toward universal employer receipt verification.

Stage 3I (Phase 0) executes a narrowly scoped security and correctness remediation across **Gates 0A–0F**. It explicitly does **not** add `UNIVERSAL` to `track/receipt/ReceiptVerificationPolicy.ALLOWED_ATS_PLATFORMS`, does **not** enable unattended live employer submissions, and does **not** implement the full multi-channel Level C2/D verification architecture (Stages 3I Phases 1–3).

---

## 2. Remediated Gates

### Gate 0A (P0) — Fail-Closed Managed Mail DKIM Trust Chain & Server-Owned Employer-Domain Binding

1. **Cloudflare Email Worker MIME & Header Trust (`cloudflare-email-worker/src/mime_parser.js`, `cloudflare-email-worker/src/index.js`)**:
   - Removed the insecure `else if (getHeader(headers, 'dkim-signature')) dkim = 'pass'` header-existence fallback.
   - Added explicit receiver binding via `TRUSTED_AUTHSERV_IDS` (`options.trustedAuthServIds`). Without an explicit allowlist, `dkim`, `dkimDomain`, and `dmarc` fail closed (`""`).
   - Enforced topmost `Authentication-Results` header evaluation (failing closed immediately if an untrusted `authserv-id` appears first), multi-header conflict detection across trusted receiver headers and `Received-SPF` headers, strict extraction of the verified DKIM signing domain (`header.d=` / `header.i=`), and clause-level conflict rejection.
   - Forwarded `dkimDomain`, `dmarc`, `authServId`, `authTrusted`, and `authConflict` explicitly to the backend.

2. **Backend Inbound Validation & Fail-Closed Forwarding (`InboundMailMessage.java`, `CloudflareInboundParser.java`, `ManagedMailSettings.java`, `ManagedInboundService.java`)**:
   - Extended `InboundMailMessage` with validated optional `dkimDomain`, `dmarcResult`, `authServId`, and `authConflict` fields while retaining backward-compatible constructors for Mailgun.
   - Updated `CloudflareInboundParser` to require non-blank validated `dkimDomain` + `authServId` (and `!authConflict && authTrusted`) before retaining `dkim=pass`, never deriving `dkimDomain` from `From:`.
   - Added `app.managed-mail.cloudflare.trusted-authserv-ids` (default empty `""`, fail closed) to `ManagedMailSettings`.
   - Eliminated `message.senderDomain()` aliasing into `dkimDomain` in `ManagedInboundService`. Providers that do not extract a real DKIM `d=` domain (e.g., Mailgun webhooks) and Cloudflare deliveries without a matching configured `authServId` or verified `dkimDomain` fail closed (`dkim = null`, `dkimDomain = null`, never persisted or promoted as `"pass"`).

3. **Server-Owned Employer-Domain Binding (`EmployerDomainPolicy.java`, `track/ReceiptVerificationPolicy.java`, `TrackRepository.java`, `TrackEvidenceRecorder.java`)**:
   - Implemented deterministic `EmployerDomainPolicy` using server-owned URLs (`jobs.application_url`, `jobs.canonical_url`, owner `automation_plans.target_url`) and optional server configuration `app.track.vetted-sender-mappings` (default empty; never read from inbound emails).
   - Binds direct employer career sites by registrable domain (respecting multi-label public suffixes such as `.co.uk`, `.com.au`, and enforcing exact host matching on multi-tenant hosting suffixes such as `.github.io`, `.netlify.app`, `.vercel.app`).
   - Binds third-party ATS URLs to vetted ATS notification domains (e.g., `boards.greenhouse.io` -> `greenhouse.io`, `greenhouse-mail.io`; `jobs.ashbyhq.com` -> `ashbyhq.com`; `jobs.lever.co` -> `lever.co`; `*.myworkdayjobs.com` -> `myworkdayjobs.com`, `myworkday.com`).
   - Rejects unrelated domains (e.g. `unrelated.example` quoting a Greenhouse job URL), job-board-only aggregator URLs without corporate/server mappings, and missing target domains (`SENDER_DOMAIN_NOT_BOUND_TO_EMPLOYER` / `EMPLOYER_TARGET_DOMAIN_UNKNOWN`).
   - Enforced fail-closed defaults when `Evidence.domainBinding` is absent (`null`).

### Gate 0B — Universal Approval Attestation Recognition

- Updated `JdbcReceiptTrustStore.STRICT_APPROVAL_SQL` to include `'UNIVERSAL_SUBMIT_APPROVED'` alongside ATS approval actions (`GREENHOUSE_SUBMIT_APPROVED`, `ASHBY_SUBMIT_APPROVED`, `WORKDAY_SUBMIT_APPROVED`), while keeping all strict owner/actor/plan/payload checks intact.
- Kept `'UNIVERSAL'` excluded from `track/receipt/ReceiptVerificationPolicy.ALLOWED_ATS_PLATFORMS`.

### Gate 0C — Honest `BROWSER_OBSERVED` vs `RECONCILIATION_REQUIRED` Separation

- Added `verificationLevel` (`NOT_ATTEMPTED`, `ATTEMPT_RECORDED`, `BROWSER_OBSERVED`, `SANDBOX_VERIFIED`, `FAILED`) to `ApplicationReceiptService.SubmissionReceipt` (backend DTO, `contracts/submissionReceiptFixtures.json`, `frontend/src/types.ts`, and `frontend/src/test/fixtures/submissionReceiptFixtures.json`).
- Updated `ApplicationReceiptService` so a clean `UNIVERSAL` `SUBMITTED` plan with valid `UNIVERSAL_SUBMIT_APPROVED` attestation, `"worker"` provenance (`RECORDED_VIA_WORKER_COMPLETE`), intact `STORED_ENCRYPTED` evidence, and verified AES-GCM/SHA-256 decryption yields:
  - `verificationLevel = "BROWSER_OBSERVED"`
  - `confirmation.confirmed = false` (truthfully distinct from verified employer/sandbox receipts)
  - `reconciliation.required = false` (no false failure state)
- Preserved fail-closed `reconciliation.required = true` (`UNVERIFIED_RECEIPT_EVIDENCE`) whenever worker evidence is missing, unencrypted (`NOT_PROVIDED`), tampered (`EvidenceIntegrityException`), unapproved, or when ATS sandbox verification fails (`ApplicationReceiptIT` invariants preserved).
- Updated `SubmissionReceiptModal.tsx` to render clean `BROWSER_OBSERVED` receipts in a distinct informational sky-blue banner (`SUBMITTED — BROWSER OBSERVED` / `"Browser-Observed Submission — Awaiting Employer Verification"`), separate from emerald `"Confirmed Submission Proof"` and amber `"Unconfirmed Submission — Reconciliation Required"`.

### Gate 0D — Managed Mail Visibility in `ApplicationTrackingService.mailboxConnected()`

- Injected optional `ManagedMailboxService` into `ApplicationTrackingService` and updated `mailboxConnected()` and `view()` mailbox metadata to recognize an active (`status = 'ACTIVE'`), owner-scoped (`profile_id`), domain-configured `managed_mail_addresses` entry alongside Gmail OAuth connections, failing closed when `DISABLED`, `NOT_CONFIGURED`, or owned by another profile.

### Gate 0E — Universal & Submission Lifecycle Timeline Projection

- Added `UNIVERSAL_FORM_REVIEWED`, `UNIVERSAL_SUBMIT_APPROVED`, `UNIVERSAL_SUBMIT_APPROVAL_REFUSED`, `UNIVERSAL_SUBMIT_QUEUED`, `AUTOMATION_PLAN_SUBMITTED`, and `AUTOMATION_PLAN_RECONCILIATION_REQUIRED` to `TrackRepository.PROJECTED_AUDIT_ACTIONS`.
- Mapped all added actions cleanly in `TrackTimelineMapper#fromAudit` using candidate-safe neutral copy (no internal `"universal"`/`"greenhouse"` platform names or PII).

### Gate 0F — Worker Universal Confirmation Hardening Parity

- Generalized and exported `isDisqualifiedIdentifier` (supporting `planId` and general URL posting IDs alongside Greenhouse paths) and `extraJsonKeys` support in `worker/src/greenhouse_evidence.js`.
- Refactored `worker/src/universal_confirmation.js` (and `worker/src/browser_worker.js` call site passing `jobId`/`applicationId`/`planId`) to reuse shared helpers cleanly:
  - Dropped generic `data.id` confirmation ID fallback.
  - Disqualified `MOCK-*`, `REQ-*`, internal `jobId`/`applicationId`/`planId`, and URL posting IDs.
  - Required genuine application-submission wording without negative/error phrases and rejected generic empty/`"Success!"` confirmation banners.
  - Emitted canonical sorted-key `robin-submission-evidence-v1` JSON via `canonicalJson`, `sanitizeContainerHtml`, and `sanitizeEvidenceText`.

---

## 3. Operational Configuration Notes

- **Cloudflare DKIM trust stays fail-closed by default:** Deployments relying on Cloudflare Email Routing DKIM verification must configure matching `TRUSTED_AUTHSERV_IDS` on the Cloudflare Worker and `app.managed-mail.cloudflare.trusted-authserv-ids` (comma-separated receiver `authserv-id` values) on the backend. When left blank, inbound DKIM claims are never promoted.
- **Custom employer ESP mappings (optional):** `app.track.vetted-sender-mappings` (server-side configuration only, default blank; format `key=domain1,domain2;...`) may authorize verified custom notification domains for an employer corporate domain or ATS tenant (e.g. `greenhouse:acme=acme.com,mail.acme.com`). Inbound emails can never supply mappings.

---

## 4. Local Verification Results

All checks executed against the final Stage 3I code on `integration/wave-3` before push:

- **`cloudflare-email-worker` (`npm test`):** `22` passed, `0` failed, `0` skipped.
- **`worker` (`npm test`):** `384` tests run — `383` passed, `0` failed, `1` pre-existing skipped (no regressions).
- **`frontend` (`npx tsc --noEmit`, `npm test`, `npm run build`):** `49` test files, `483` passed, `0` failed; TypeScript check & Vite build succeeded cleanly.
- **Backend Unit Suite (`mvn verify` / Surefire):** `1,984` tests run — `0` failures, `0` errors, `6` pre-existing skips.
- **Backend Targeted PostgreSQL 16 Testcontainers ITs (`CloudflareManagedMailIT`, `CloudflareRecruiterLifecycleIT`, `ApplicationTrackingIT`, `ApplicationReceiptIT`, `UniversalBrowserE2EIT`, `TrackMigrationCompatibilityIT`):** `65` tests run — `0` failures, `0` errors, `0` skipped.

---

## 5. Remaining Gaps & Next Step

- **Stage 3I Phases 1–3 remain open:** Independent employer receipt verification for arbitrary Universal websites (Level C2 email/webhook evidence correlation, browser confirmation artifact capture, post-submit reconciliation workflows) and live external staging validation (`Stage 3G`, still `BLOCKED` pending operator authorization) are intentionally deferred beyond Phase 0.
- **Recommended next step:** Merge `integration/wave-3` readiness fixes or proceed to **Stage 3I Phase 1 — Universal Multi-Channel Receipt Verification & Reconciliation Engine** once GitHub CI confirms all four jobs green.
