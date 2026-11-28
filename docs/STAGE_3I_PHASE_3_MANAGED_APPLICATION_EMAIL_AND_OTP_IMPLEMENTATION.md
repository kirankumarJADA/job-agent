# ROBIN — STAGE 3I PHASE 3: PRIVATE APPLICATION EMAIL, UNIVERSAL EMPLOYER SIGN-UP & OTP RESUME RELIABILITY

**Date:** 2026-11-27  
**Repository:** `kirankumarJADA/job-agent`  
**Branch:** `integration/wave-3`  
**PR:** `#21` — OPEN / DRAFT, unmerged, base `master` (state preserved)  
**Baseline:** `b02f8c82f19bbfcb934d84d0b01a82c144fe6de6` (Stage 3I Phase 2 durable receipt linking & reconciliation)

No production database was touched, nothing was deployed, no live Cloudflare email routing secrets were invented, and no real employer portal account or submission was executed. All verification runs executed against local unit/Chromium fixtures; PostgreSQL 16 Testcontainers integration tests compile cleanly and run on GitHub Actions `ubuntu-latest` CI (Docker Engine is unavailable on this Windows host).

---

## 0. Summary of Implemented Scope

| Area | Key Deliverables |
| :--- | :--- |
| **Migration (`V072`)** | Added `V072__managed_mail_verification_lease_and_auth.sql` (`V001`–`V071` untouched): adds `dkim_domain`, `dmarc_result`, `auth_serv_id`, `auth_trusted`, bounded reservation columns + index on `managed_inbound_messages`, and lease columns (`status`, `reserved_until`, `reservation_token_sha256`) + partial active-lease unique indexes on `mailbox_verifications`. |
| **Separate Email Identities** | `RegistrationIdentityService` eliminates the `alreadyRegistered` check-then-insert race via `inserted == 0`, enforces active status on `ROBIN_MANAGED` identities, fails closed on disabled non-managed identities, and removes silent personal-email fallback when managed mailbox readiness is `ACTIVE`. `IdentityService#create` refuses overwriting a `ROBIN_MANAGED` email. `ApplyController#createPackage` catches `ManagedMailboxNotReadyException` as `409 MANAGED_MAILBOX_NOT_READY` (with audit). `ManagedMailSettings` validates `addressPrefix` against `^[a-z][a-z0-9+_-]{0,47}$`. |
| **Cloudflare Auth & Separated Readiness** | `ManagedMailSettings#verificationMissing` reports missing `APP_MANAGED_MAIL_CLOUDFLARE_TRUSTED_AUTHSERV_IDS` without breaking registration/transport readiness. `ManagedMailboxService.Status` adds `transportReadiness`, `senderAuthenticationReadiness`, `employerVerificationReadiness`, `usableForAutomatedVerification`, `verificationMissing`, scopes `domainEvidence`/`recordSignedDelivery` by `provider_id`, and supports `robin+<alias>` subaddressing (`matchesAddress`). `ManagedInboundService` persists `dkim_domain`, `dmarc_result`, `auth_serv_id`, `auth_trusted`. `CloudflareInboundParser` drops `envelopeSender` fallback on ambiguous `From:` headers and drops untrusted `"spf": "pass"`. `ManagedMailVerificationProvider` enforces `auth_trusted = true`, SPF/DMARC non-fail, DKIM-to-sender domain alignment, active-lease filtering, and keyset pagination (`PAGE_SIZE = 25`, `MAX_SCAN = 200`). `EmailIntelligenceService` disables `body_text` retention for Managed Mail connector messages (`cloudflare`, `mailgun`, `managed`). |
| **Bounded OTP / Link Lease** | `MailboxVerificationController` converts `POST /api/v1/mailbox-verification/resolve` to acquire a bounded `RESERVED` lease (with constant-time SHA-256 token verification, idempotent same-application/session crash recovery with lease-token rotation, and concurrent cross-application refusal without erasing `content_encrypted` before use), adds `POST /api/v1/mailbox-verification/confirm` (permanent `CONSUMED` + `content_encrypted` erasure) and `POST /api/v1/mailbox-verification/release` (`RELEASED` retaining `content_encrypted`), protected in `WorkerEventTokenFilter`. `ManagedMailRetentionJob` expires overdue leases and protects active unexpired leases. `WorkdayExecutionPlanService#checkAccountVerificationStatus` treats active `RESERVED` or `CONSUMED` rows as `VERIFICATION_MESSAGE_MATCHED`. |
| **Universal Account Auth & Correlation** | Added `POST /api/v1/apply/applications/{id}/universal-account` (`ApplyController.createUniversalAccount`, catching `ManagedMailboxNotReadyException` as `409 MANAGED_MAILBOX_NOT_READY` and `ExecutionPackageBlockedException` as `409 EXECUTION_PACKAGE_BLOCKED` with audit `UNIVERSAL_ACCOUNT_CREATION_REFUSED`) and `UniversalBrowserPlanService#authorizeAccountCreation` (refusing unaccepted terms, saved credentials, or existing identities; keeping `accountCreationAuthorized = false` by default). Normalized `employerDomain` & propagated open `sessionId` into steps, package, and correlation. Hardened `BrowserObservationPolicy` to require non-null matching `(planId, applicationId, jobId)`. |
| **Worker Sign-up, OTP Resume & Hygiene** | `worker/src/universal_form.js` separates email OTP/link verification from authenticator 2FA and detects verified signup & auth-switch controls cleanly. `worker/src/browser_worker.js` wires `verificationConfirmer`/`verificationReleaser`, handles Universal email OTP / same-origin link verification + fail-closed release on rejection/mismatch, executes explicit `accountCreationAuthorized` sign-up without duplicating existing identities, and saves/resumes encrypted cookies & identity in `SessionVault` (`identity.enc`, `session.enc`). `worker/src/worker.js` maps `ApprovalRequiredError` to `HUMAN_REQUIRED` (no dead-lettering) and forwards Universal auth flags in `planFromClaim`. `worker/src/store.js` and `worker/src/secret_redaction.js` strip raw OTPs, lease tokens, passwords, emails, and `?code=/key=/magic=/email=/username=` query secrets. |
| **Frontend Truthfulness** | Updated `RobinMailboxCard.tsx` (with amber `Alert` `tone="warn"` in `ui.tsx`) to show transport, sender auth, and employer verification readiness and warn clearly when Cloudflare transport is active while automated OTP verification is paused (`TRUSTED_AUTHSERV_REQUIRED`). |
| **Testcontainers & Scheduler Lifecycle** | Added `SchedulerDatabaseGuard` and database lifecycle guards across `@Scheduled` jobs (`AutomationRecoveryScheduler`, `OutboxProcessor`, `DiscoveryScheduler`, `SearchPlanScheduler`, `ManagedMailRetentionJob`, `JobDiscoveryService`) plus `TestcontainersContextCleanupListener` (`META-INF/spring.factories`) so per-class `@Testcontainers` contexts halt schedulers before container teardown without weakening production recovery. |

---

## 1. Local & CI Verification Summary

| Suite | Environment | Command | Observed Result |
| :--- | :--- | :--- | :--- |
| Backend unit (Surefire) | Windows host | `mvn -f backend/pom.xml -B test` | **2,042 run, 0 failures, 0 errors, 6 skipped** (includes `Stage3IPhase3UnitTest` 10/10, `AutomationRecoverySchedulerMaintenanceTest` 5/5, `TestcontainersContextCleanupListenerTest` 1/1, `ArchModuleBoundaryTest` 4/4; 6 Redis diagnostic/AB/TLS tests skipped) |
| Worker (Node + Chromium) | Windows host | `npm --prefix worker test` | **398 run, 397 passed, 0 failed, 1 skipped** (includes new `universal_account_otp_recovery.test.js` 4/4; 1 POSIX-only SIGTERM test skipped on Windows) |
| Frontend typecheck, unit & build | Windows host | `npx tsc -b`, `npm test`, `npm run build` | `tsc -b` clean; **49 files, 491 tests passed**; production build succeeded |
| Cloudflare email worker & probes | Windows host | `npm --prefix cloudflare-email-worker test`; `node --test scripts/phase20/isolation-probe.test.mjs` | **25 / 25** passed; **8 / 8** passed |
| Backend integration (Failsafe, Testcontainers PostgreSQL 16) | GitHub Actions (`ubuntu-latest`) | `mvn -B verify` (CI job) | Test sources (`267` files, including updated `ManagedMailIT`, `ApplicationTrackingIT`, `CloudflareTrustedAuthOtpIT`, and `TestcontainersContextCleanupListener`) compiled locally; Docker Engine is unavailable on this Windows host (`dockerDesktopLinuxEngine` pipe absent) so live PostgreSQL 16 IT execution is gated on the GitHub Actions run for the pushed commit. |

---

## 2. Database & Security Hygiene

- `git diff b02f8c82f19bbfcb934d84d0b01a82c144fe6de6 --stat -- backend/src/main/resources/db/migration` confirms `V001`–`V071` are completely untouched and only `V072__managed_mail_verification_lease_and_auth.sql` was added.
- `trusted-authserv-ids` remains empty (`""`) by default in config/env so unconfigured deployments fail closed on automated Cloudflare OTP reading while preserving transport readiness honestly.
- PR `#21` remains `OPEN` and `DRAFT`.
