# ROBIN — STAGE 3I PHASE 5: CRITICAL SECURITY REMEDIATION, UNIVERSAL SIGNUP RECOVERY & RELIABILITY HARDENING

**Date:** 2026-11-27  
**Repository:** `kirankumarJADA/job-agent`  
**Branch:** `integration/wave-3`  
**Baseline Audited Commit:** `a72bca35d265df6c1b5565af94d92eb4222c10c3` (PR `#21` remains `OPEN` and `DRAFT`, base `master`, `mergedAt: null`)  
**Migration Scope:** Additive `V073__stage3i_phase5_account_state_and_active_lease.sql` only (`V001`–`V072` remain byte-identical)  
**External Validation Decision:** **REMAINS `BLOCKED / NO-GO`** (code/unit remediation implemented locally; PostgreSQL Testcontainers integration suite verification occurs in GitHub Actions `mvn -B verify`, and Stage 3G external provisioning prerequisites remain absent)

---

## 1. Objective & Product Alignment

Stage 3I Phase 5 directly remediates the **6 P1** security, concurrency, secret-isolation, and signup defects (`P1-1` through `P1-6`) plus the high-priority **P2** lifecycle, concurrency, and worker-recovery findings (`P2-1`, `P2-2`, `P2-3`, `P2-4` partial, `P2-5`, `P2-6`) identified in `docs/STAGE_3I_PHASE_4_INDEPENDENT_SECURITY_INTEGRATION_AUDIT.md`, protecting Robin's **APPLY**, **EXCEPTIONS & RESUME**, and **TRACK** stages across arbitrary employer websites without bypassing existing safety gates (`REAL_SUBMIT` approval fences, fail-closed Cloudflare DKIM/SPF/`authserv-id` checks, owner scoping).

---

## 2. Remediations Implemented

### 2.1 Workstream A (`P1-1`): Universal & Workday Pre-Signup vs Confirmed Account Lifecycle
- **Database Migration (`V073__stage3i_phase5_account_state_and_active_lease.sql`):**
  - Widens `applicant_identities.account_status` check constraint dynamically to allow `'PENDING'`, `'ACTIVE'`, and `'DISABLED'` (preserving `'ACTIVE'` default for legacy/explicit `IdentityService` rows).
- **Backend Identity Separation (`RegistrationIdentityService`, `UniversalBrowserPlanService`, `WorkdayExecutionPlanService`):**
  - Removed pre-signup `insert into applicant_identities` from `RegistrationIdentityService.resolveApplicationContactEmail()`.
  - Added `prepareForAccountCreation(ownerProfileId, applicationId, sessionId, employerDomain)` (used by `UniversalBrowserPlanService.authorizeAccountCreation` and `WorkdayExecutionPlanService.authorizeAccountCreation`) and `confirmRegistration(ownerProfileId, employerDomain)` (`'PENDING'` -> `'ACTIVE'`, never modifying `'DISABLED'`).
  - `resolve(...)` and `prepareForAccountCreation(...)` treat `'PENDING'` identities as `alreadyRegistered = false` (`existing: false`), insert new managed rows as `'PENDING'`, and fail closed with `IllegalStateException` on `'DISABLED'`.
- **Worker Confirmation Endpoint (`MailboxVerificationController`, `WorkerEventTokenFilter`, `worker/src/worker.js`, `worker/src/browser_worker.js`):**
  - Added worker-only `POST /api/v1/mailbox-verification/registration-identity/confirm` (allowlisted in `WorkerEventTokenFilter`), promoted automatically when Playwright signup submits cleanly (`browser_worker.js` `identityConfirmer`) and also upon OTP/link lease `/confirm`.

### 2.2 Workstream B (`P1-2`, `P1-6`, `P2-3`, `P2-4`, `P2-5`): Verification Lease Authorization, Concurrency & Retention Hardening
- **PostgreSQL Active-Lease Uniqueness (`V073`):**
  - Created unique partial index `mailbox_verifications_active_app_session_uidx` on `(application_id, coalesce(account_session_id, '00000000-0000-0000-0000-000000000000'::uuid)) where status = 'RESERVED'`.
- **`MailboxVerificationController` Hardening:**
  - Enforces `EmployerCredentialService.bindingFor(ownerProfileId, applicationId)` (`running()` -> `409 Conflict`, `permittedDomains()` -> `403 Forbidden`) across `/registration-identity`, `/registration-identity/confirm`, `/resolve`, `/confirm`, and `/release`.
  - Resolves `applications.account_session_id` via `resolveEffectiveSessionId(...)` when `r.sessionId()` is omitted (`null`), closing the `sessionId = null` bypass of `CLOSED_SESSION_STATES` (`HARD_STOP`/`FAILED`/`COMPLETED`), `expected_domain`, `sessionStart`, and `registered_email`; verifies caller-supplied `sessionId` ownership (`404`) and parity (`409`).
  - Splits Path A active-lease recovery so row locking runs in a short transaction while provider lookup executes outside DB locks, rotates tokens with PostgreSQL `select now()` (`dbNow()`), and retries Path A recovery once on `DuplicateKeyException` when concurrent initial `/resolve` calls race.
  - Enforces `reserved_until` expiry validation on `/release` (returning `409 Conflict` instead of `200 RELEASED`), and records metadata-only `AuditLogWriter` entries (`REGISTRATION_IDENTITY_*`, `MAILBOX_VERIFICATION_*`) without raw OTPs, links, or lease tokens.
- **Automated Verification Readiness Consistency (`ManagedMailboxService`, `ManagedMailVerificationProvider`):**
  - Added `ManagedMailboxService.usableAddressForAutomatedVerification(profileId)` requiring `Readiness.ACTIVE` plus active automated verification readiness (`usableForAutomatedVerification()`); switched `ManagedMailVerificationProvider` (`connected`, `findVerification`, `readReservedVerification`) to enforce it while keeping `ApplicationTrackingService.managedMailboxActive` aligned with Gate 0D inbox acknowledgement tracking (`usableForRegistration()`).
- **Transactional Retention & Scrubbing (`ManagedMailRetentionJob`):**
  - Wraps `purge()` in `TransactionTemplate` (when `PlatformTransactionManager` is available), locks bounded batches (`BATCH = 1,000`) with `FOR UPDATE SKIP LOCKED` CTEs and repeats state/expiry predicates on outer `UPDATE`/`DELETE` `WHERE` clauses (`READ COMMITTED` TOCTOU mitigation).
  - Sets `reserved_until = null` on lease expiry, adds active-lease guards on 30-day `managed_inbound_messages` deletion, prunes terminal `EXPIRED`/`RELEASED` `mailbox_verifications` rows past metadata retention, and scrubs legacy `emails` `VERIFICATION_CODE` bodies/subjects past `contentRetention`.

### 2.3 Workstream C (`P1-3`, `P1-4`): Secret Isolation, Logback Scrubbing & Worker Failsafes
- **Controller & Ingestion Hygiene (`EmailController`, `EmailIntelligenceService`, `ManagedInboundService`):**
  - Retired `POST /api/v1/emails/verification/extract` and `/{id}/resolve` (`403 Forbidden` for candidate sessions, `410 Gone` for worker callers).
  - Enforces `CLOSED_SESSION_STATES` checks, `MAILBOX_CONNECTOR` `sender_authentication` verification, and `VerificationContentExtractor` extraction in `EmailIntelligenceService`; redacts OTPs/URLs from `VERIFICATION_CODE` / managed-connector `emails.subject` via `LogScrubber` + `TrackText.redact` and scrubs forwarded OTP subjects in `ManagedInboundService.shouldRedactForwardedSubject()`.
- **Backend Logback Scrubbing (`LogScrubber`, `ScrubbingMessageConverter`, `ScrubbingThrowableConverter`, `ScrubbingJsonGeneratorDecorator`, `logback-spring.xml`):**
  - Expanded `LogScrubber` for URL userinfo (`https://user:pass@host`), numeric/boolean JSON (`"otp": 482913`), raw `Authorization`/`Cookie`/`Set-Cookie` headers, `Bearer`/`Basic` tokens, and `leaseToken`/`sig`/`jwt`/`sid`/`pin`/`passcode`/`nonce`/`ticket`/`verifier`/`activation_code`/`verification_code` query/key-value pairs.
  - Wired custom message, throwable, and JSON generator converters into both console and JSON Logstash Logback appenders (`logback-spring.xml`).
- **Worker Secret Hygiene (`worker/src/secret_redaction.js`, `universal_confirmation.js`, `store.js`, `worker.js`):**
  - Expanded `secret_redaction.js` (`activation_code=`, `lease_token`, numeric JSON OTPs, `sig`/`jwt`/`sid`/`pin`/`nonce`/`ticket`/`verifier`, non-mutating recursive object/`Error` scrubbing) and scrubbed `serverRejected.errorMessage` in `universal_confirmation.js`.
  - Refuses plaintext `identity.email` persistence in `StateStore.identity()` unless `allowPlaintextEmail: true` (`localTestNetwork` / explicit loopback test fixture); added `assertWorkerSessionKeyConfigured()` in `worker.js` failing closed on startup without a valid 32-byte base64 `ROBIN_WORKER_SESSION_KEY` unless `ROBIN_ALLOW_EPHEMERAL_WORKER_SESSION=1` is explicitly set.

### 2.4 Workstream D (`P1-5`): Backend Socket-Pinned SSRF Defense
- **`AddressGuard` & `SafeHttpClient` (`AddressGuard.checkAndResolve`, `SafeHttpClient.JdkTransport`, `PinnedTunnel`):**
  - Added single-lookup `AddressGuard.checkAndResolve(URI)` returning immutable validated `InetAddress` targets without performing a duplicate DNS lookup (`overridesLegacyCheck` preserves custom `check(URI)` test subclasses while guaranteeing one DNS resolution for standard/pinned guards).
  - Implemented loopback `PinnedTunnel` inside `SafeHttpClient.JdkTransport` (`PinnedTransport`) so JDK `HttpClient` runs full native TLS/SNI/hostname-SAN verification while opening upstream TCP sockets strictly to the pinned `InetAddress` list with zero second DNS resolution.

### 2.5 Workstream E & F (`P2-1`, `P2-2`, `P2-6`): Scheduler Lifecycle & Worker Recovery Hardening
- **`SchedulerDatabaseGuard`, `JobDiscoveryService`, `OutboxProcessor`, `BenchmarkRunner`, `SearchPlansController`, `SearchPlanExecutionCoordinator`, `TestcontainersContextCleanupListener`:**
  - `SchedulerDatabaseGuard` tracks its own `ApplicationContext` (`ApplicationContextAware`) and ignores child `ContextClosedEvent` notifications.
  - `JobDiscoveryService.scheduledDiscoveryMaintenance()` checks `databaseGuard.isReady()` *before* opening `TransactionTemplate(ISOLATION_READ_COMMITTED)` and suppresses shutdown errors when `!databaseGuard.isReady()`.
  - `OutboxProcessor` and `BenchmarkRunner` guard retry/DLQ/failure DB writes against `!databaseGuard.isReady()`; `SearchPlansController`, `SearchPlanExecutionCoordinator`, and `TestcontainersContextCleanupListener` deactivate guards and await bounded executor termination on teardown.
- **Worker Recovery & Redirect Safety (`worker/src/browser_worker.js`, `worker/src/worker.js`):**
  - Persists `SessionVault` before `markStep(..., 'COMPLETED')`, treats `409 Conflict` from `verificationConfirmer` non-fatally when portal verification already succeeded, persists `universalSignupSucceeded` across `#pauseUniversal` + `store.identity` + `SessionVault`, calls `identityConfirmer` after Universal signup succeeds, enforces HTTP status/cross-origin redirect guards on verification links (`ACCOUNT_VERIFY_OTP` and `#runUniversalApply`), and releases active leases cleanly in catch unwind / failed `page.goto(link.href)`.

---

## 3. Verification Evidence & Test Scope

### Local Checks Executed After Final Code Edits (Windows Host)

| Suite | Command | Result | Scope & Limitations |
| :--- | :--- | :--- | :--- |
| Backend Unit & ArchUnit | `mvn -f backend/pom.xml -B test` | **`2,051 run, 0 failures, 0 errors, 6 skipped`** | Skips: `RedisConnectivityDiagnosticsTest` (2), `RedisLettuceAbExperimentTest` (2), `RedisTlsBindingTest` (2). No Docker daemon on Windows host, so PostgreSQL `*IT` integration suites do not run locally here and are verified in GitHub Actions `ubuntu-latest`. |
| Worker (Playwright / Node) | `npm --prefix worker test` | **`404 run, 403 passed, 0 failed, 1 skipped`** | Includes new `worker/test/phase5_security.test.js` & expanded `secret_redaction.test.js`. 1 pre-existing POSIX signal test skipped on `win32`. Local HTTP mock fixtures only. |
| Cloudflare Email Worker | `npm --prefix cloudflare-email-worker test` | **`25 run, 25 passed, 0 failed, 0 skipped`** | Local mock MIME/Headers only; no live Cloudflare delivery. |
| Phase 20 Probe Scripts | `node --test scripts/phase20/*.test.mjs` | **`8 run, 8 passed, 0 failed, 0 skipped`** | Offline mock probe tests. |
| Frontend Unit & Build | `npm --prefix frontend test && npm --prefix frontend run build` | **`49 files, 491/491 passed`; `tsc -b && vite build` clean** | jsdom/Vitest unit mocks + production TS/Vite compilation. |

### Test Updates Required by Intentional Hardening
- Updated existing unit/IT fixtures (`ManagedMailMaintenanceTest`, `ManagedMailVerificationProviderTest`, `Stage3IPhase3UnitTest`, `TestcontainersContextCleanupListenerTest`, `WorkdayExecutionPlanServiceTest`, `ApplicationTrackingIT`, `CloudflareTrustedAuthOtpIT`, `ManagedMailIT`) to align with `prepareForAccountCreation`, `usableAddressForAutomatedVerification`, `7`-statement transactional `FOR UPDATE SKIP LOCKED` retention, `ThreadPoolTaskExecutor` cleanup, `RUNNING` `automation_plans` fixtures under `ApplicationBinding` (`403` wrong domain), and removing the old `sessionId = null` bypass assertion (`ManagedMailIT:530–536`).
- Added new regression & concurrency suites:
  - `Phase5SecurityRemediationUnitTest` (backend `EmailController`, `LogScrubber` + Logback converters, `EmailIntelligenceService` subject redaction, `MailboxVerificationController` closed-session/domain/expired-release/audit/signup-confirm, `SchedulerDatabaseGuard` context identity + `JobDiscoveryService` pre-TX guard)
  - `SafeHttpClientTest.dnsRebindingHostnameResolvesOnceAndPinsSocketTcpTargetWithoutSecondLookup`
  - `RegistrationIdentityServiceFailSafeTest` (`PENDING` vs `ACTIVE` lifecycle + disabled identity fail-closed + read-only contact lookup)
  - `CloudflareTrustedAuthOtpIT.stage3iPhase5_signupConfirmationAndSecurityBoundariesEndToEnd` & `ManagedMailIT.concurrentResolveSharesSingleActiveLeaseAndRetentionProtectsActiveLease` (`ExecutorService` + `CountDownLatch` PostgreSQL 16 concurrency, queued for CI `mvn verify`)
  - `worker/test/phase5_security.test.js` & `worker/test/secret_redaction.test.js` extensions.

---

## 4. Remaining Deferred Items & External Validation Gate

- **Remaining deferred items from Phase 4 audit (explicitly unchanged in Phase 5 scope):**
  - `P2-7`: `ApplicationReceiptService.extractIdentity` fallback to `package.profileFacts` for Universal receipts.
  - `P2-8`: `TrackRepository.snapshotParts` `SUBMITTED`-first ordering.
  - `P3-1`–`P3-5`: `WorkdayReviewPanel` `"managed"` check / `employer_domain` filter, `CloudflareInboundParser` `&amp;` unescaping & bounded body reads, `V072` `ON DELETE` column cleanup polish, `SessionVault.sweep()` orphaned `identity.enc` pruning, `SubmissionCompletionService` unit test.
- **Stage 3G External Validation Decision:** **`BLOCKED / NO-GO`**
  - PR `#21` remains `OPEN` and `DRAFT`.
  - GitHub Actions CI (`mvn -B verify` with PostgreSQL 16 Testcontainers `*IT` + all 4 jobs) must pass on the pushed Phase 5 commit, and Stage 3G external provisioning prerequisites (written operator `authorisation.json`, isolated synthetic staging fixture + driver, live Cloudflare `TRUSTED_AUTHSERV_IDS` verification, and staging `ROBIN_WORKER_SESSION_KEY` secret provisioning) remain external prerequisites before any external execution.
