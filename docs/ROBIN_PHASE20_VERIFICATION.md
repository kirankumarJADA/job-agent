# ROBIN — Phase 20 Verification Report

**Prepared:** 2026-11-03, updated 2026-11-04 with recruiter-lifecycle integration evidence, CI verification and a production safety audit, then (22:16 UTC) with the Firebase revocation-hardening review, deployment-identity findings and operator probes.  
**Scope:** Managed-mail (Cloudflare) production readiness, encryption-key safety, Worker/backend reconciliation, email intelligence, application tracking, database cascade/trigger analysis, recruiter communication lifecycle, and revocation-aware authentication.  
**Verdict:** IMPLEMENTATION COMPLETE AND CI-VERIFIED (run #89 on `b2be7e0`: backend unit + ArchUnit + Testcontainers ITs, worker, frontend, email worker and probe tests all green). First genuine inbound-mail production test PASSED (one message, encrypted, persisted, mailbox ACTIVE). Phase 20 remains **OPEN**: production still runs the 2026-11-03 build without the Firebase revocation hardening (§3.3–§3.4), and live tenant isolation, duplicate-delivery idempotency, application association, employer OTP handling and recruiter communications are not yet verified in production (§5, §6).

---

## 1. Configuration Investigation

### 1.1 Exact Variable Names (Confirmed Against Source, Not Documentation)

| Variable | Spring Property | Consumer | Confirmed At |
|---|---|---|---|
| `APP_MANAGED_MAIL_DOMAIN` | `app.managed-mail.domain` | `ManagedMailSettings` | `application.yml:83` |
| `APP_MANAGED_MAIL_PROVIDER` | `app.managed-mail.provider` | `ManagedMailSettings` | `application.yml:84` — **default is `mailgun` if unset** |
| `APP_MANAGED_MAIL_ADDRESS_PREFIX` | `app.managed-mail.address-prefix` | `ManagedMailSettings` | `application.yml:85` |
| `CLOUDFLARE_EMAIL_WORKER_SECRET` | `app.managed-mail.cloudflare.webhook-secret` | `ManagedMailSettings.cloudflareWebhookSecret()` | `application.yml:98` |
| `APP_SECRETS_KEY` | `app.secrets.encryption-key` | `SecretCipher` | `application.yml:75` |

`CLOUDFLARE_EMAIL_WORKER_SECRET` (HMAC webhook signature) and `APP_SECRETS_KEY` (AES-256-GCM encryption at rest) are two **entirely separate** Spring beans/properties (`ManagedMailSettings` vs. `SecretCipher`). No code path in the repository conflates them.

### 1.2 Why the Backend Previously Reported `APP_SECRETS_KEY` as Missing — RESOLVED

`backend/src/main/java/com/personal/jobagent/security/SecretCipher.java:31-38`:

```java
public SecretCipher(@Value("${app.secrets.encryption-key:}") String configuredKey) {
    this.key = configuredKey == null || configuredKey.isBlank()
            ? null : deriveKey(configuredKey.trim());
}
public boolean configured() { return key != null; }
```

`deriveKey()` (lines 85-99) is deliberately forgiving: a non-Base64 value falls back to raw UTF-8 bytes, and any decoded length other than exactly 32 bytes is SHA-256-hashed into a 32-byte key. **There is no format-validation failure path.** The only way `configured()` is `false` is if the environment variable resolves to null/empty on the running JVM — i.e. one of:

1. The variable was never added to the Render service that is actually serving traffic.
2. It was added, but the service has not restarted since (Spring reads env once at JVM boot).
3. A name mismatch (case, extra/missing character, stray space) in Render's dashboard.
4. It is set on a different Render service/environment than the one the custom domain currently routes to.

**Resolved 2026-11-03:** The operator independently generated a new `APP_SECRETS_KEY` and added it to the Render service, then restarted the backend. Render health checks reported the backend `UP` after restart, and the Applications page subsequently recognized the managed-mail configuration (see §4.1).

### 1.3 Provider Default Risk — RESOLVED by Observed Behavior

`ManagedMailSettings.missing()` (`ManagedMailSettings.java:134-146`):

```java
if (!PROVIDER_MAILGUN.equals(provider) && !PROVIDER_CLOUDFLARE.equals(provider)) {
    missing.add("APP_MANAGED_MAIL_PROVIDER=mailgun");
} else if (PROVIDER_MAILGUN.equals(provider)) {
    if (mailgunSigningKey.isEmpty()) missing.add("MAILGUN_WEBHOOK_SIGNING_KEY");
} else if (PROVIDER_CLOUDFLARE.equals(provider)) {
    if (cloudflareWebhookSecret.isEmpty()) missing.add("CLOUDFLARE_EMAIL_WORKER_SECRET");
}
```

Had `APP_MANAGED_MAIL_PROVIDER` never been explicitly set to `cloudflare` on Render, the backend would silently default to `mailgun` and the mailbox would never reach `ACTIVE` via a Cloudflare-routed message. Because the mailbox **did** reach `ACTIVE` and **did** receive and persist a real Cloudflare-routed message (§4.1–§4.2), this is behaviorally confirmed resolved — the live provider value is `cloudflare` with `CLOUDFLARE_EMAIL_WORKER_SECRET` configured.

---

## 2. Encryption-Key Safety (APP_SECRETS_KEY)

### 2.1 Complete Inventory of Ciphertext Columns

Only two classes in the entire backend call `SecretCipher.encrypt`/`decrypt` — confirmed exhaustively by grep across `backend/src/main/java`:

| Table | Column(s) | Migration | Writer |
|---|---|---|---|
| `employer_credentials` | `username_encrypted`, `password_encrypted` | `V040__employer_credentials_and_mailbox_verification.sql:16-17` | `EmployerCredentialService.java` |
| `managed_inbound_messages` | `content_encrypted` | `V042__managed_mail_receiving.sql:77` | `ManagedMessageContent.java` |

No other table, service, or column depends on `APP_SECRETS_KEY`. (Gmail OAuth tokens use a **separate** key, `APP_GMAIL_TOKEN_KEY` via a different cipher class — confirmed not a third consumer.)

### 2.2 Do Existing Rows Depend on a Previous Key? — RESOLVED by Pre-Provisioning Baseline Read

**Timeline, as reported by the operator:** A read-only Supabase count query was run against **both** `employer_credentials` and `managed_inbound_messages` *before* the new `APP_SECRETS_KEY` was generated and added to Render. **Both tables returned zero rows at that time.** The operator then generated and deployed the new, independent `APP_SECRETS_KEY`; a real inbound message was subsequently received and stored encrypted (§4.2).

This closes the continuity question completely: there is no "previous key" to be orphaned, because **nothing existed to encrypt under one**. Every row either table holds today was necessarily written after the current key was already in place.

- `employer_credentials`: Pre-provisioning count was **0**. Any rows present now were written under the current key, by construction. **No legacy-ciphertext risk exists for this table.**
- `managed_inbound_messages`: Pre-provisioning count was **0**, consistent with the post-provisioning count of **1** captured in §4.2 (one real message, received and encrypted after the key was live). `content_encrypted` is written inside `ManagedInboundService.accept()`, which is `@Transactional` — if `SecretCipher.encrypt()` throws because no key is configured, the insert never commits, so no partial/corrupted state was ever possible here either way.

### 2.3 Explicit Instruction Compliance

No key has been generated, rotated, or changed by any automated session. The `APP_SECRETS_KEY` generation and Render configuration described in §1.2/§4.1 was performed independently by the operator. This report only establishes what is true in source and what the operator has reported as directly observed.

---

## 3. Worker / Backend Reconciliation, CI Pipeline & Deployment Identity

### 3.1 Confirmed Git & CI State (2026-11-04, 22:16 UTC)

- **Branch**: `master`. **Code head**: `b2be7e04260023c864a03c20d39fc05fb11b49d1` — pushed `484c5c5..b2be7e0`; local `HEAD`, `origin/master` and `git ls-remote` all matched after the push.
- **CI run #89** (id `41922733784`) on `b2be7e0` — all four jobs **success**: `backend` (`mvn -B verify`: unit + ArchUnit + Testcontainers ITs, 22:09:34→22:15:53), `worker`, `frontend`, `cloudflare-email-worker` (including the new *Phase 20 operator probe tests* step).
- **Earlier runs**: #88 on `484c5c5` — all four jobs success. #87 on `a442f9b` — `backend` failed in `mvn verify`; fixed by `484c5c5`. #85 on `d113f97` — all four success.
- **Local runs (2026-11-04)**: backend `mvn -o test` — 1,435 tests, 0 failures, 0 errors, 1 skipped (160 classes); `cloudflare-email-worker` `npm test` — 15/15; `node --test scripts/phase20/*.test.mjs` — 8/8. Testcontainers ITs run only in CI (no Docker daemon locally).

### 3.2 The wrangler.toml Configuration — Reconciled & Pushed

`cloudflare-email-worker/wrangler.toml` is synchronized on `master`:

```toml
workers_dev = false
addresses = ["robin@mail.aplivenosi.com"]
...
[vars]
ROBIN_BACKEND_URL = "https://job-agent-mwhu.onrender.com"
```

The dedicated GitHub Actions CI gate (`cloudflare-email-worker`) validates this worker automatically on every commit.

### 3.3 Render Production Deployment Identity & Build Provenance

Investigation of live backend at `https://job-agent-mwhu.onrender.com`:
- **Liveness & Health Status**: `/actuator/health` and `/api/v1/system/health` return HTTP 200 with status `UP`. All subsystems (`db`, `redis`, `diskSpace`, `livenessState`, `readinessState`, `ping`) are healthy.
- **Routing Instance**: Response header `rndr-id: 7c62f136-86b7-45e1` confirms traffic is handled by Render's production routing tier.
- **Build Timestamp vs. Git Commit Info**:
  - `/actuator/info` reports:
    ```json
    {
      "build": {
        "artifact": "jobagent",
        "name": "job-agent-backend",
        "time": "2026-11-03T21:20:48.844Z",
        "version": "0.1.0-SNAPSHOT",
        "group": "com.personal"
      },
      "git": {}
    }
    ```
  - **Why `git: {}` is Empty**: `backend/Dockerfile` builds the application container by copying only `pom.xml` and `src`, intentionally omitting the `.git` directory (`failOnNoGitDirectory=false` in `pom.xml:265`). Consequently, Spring Boot's `git-commit-id-plugin` cannot embed git properties into `app.jar` during Docker container creation.
  - **Build Provenance**: The build timestamp `2026-11-03T21:20:48.844Z` indicates the currently running container was built on Nov 3, immediately following the push of commit `dac5c4e` (committed at 2026-11-03 21:08:29 UTC).
  - **Application Source Parity — no longer holds (corrected 2026-11-04)**: through `373e048` no commit after `dac5c4e` touched `backend/src/main`. Since then `a442f9b` and `484c5c5` (Firebase revocation hardening) and `e3f19c4`/`5cd8238` (this session) changed production code. **The running backend is therefore NOT the code on `master`**: it predates every Firebase revocation change.
  - **Render Deployment State (re-checked 2026-11-04 21:48 UTC)**: `build.time` is still `2026-11-03T21:20:48.844Z` after nine further pushes to `master`, so Render auto-deploy is evidently off and every backend deploy is manual. Render deploy IDs and logs remain dashboard-only.

### 3.4 Firebase Revocation Hardening — Review, Fixes and Deployment

**Reviewed commits:** `a442f9b` (verify ID tokens with `checkRevoked=true`; classify revoked/disabled/expired; verify the bearer even when a session exists; invalidate a session belonging to a different user, or to a revoked/disabled account) and `484c5c5` (fixes two regressions `a442f9b` introduced: a second constructor that stopped Spring from instantiating `FirebaseAdminTokenVerifier` — CI run #87's backend job failed in `mvn verify` — and worker-token requests being routed into Firebase verification). CI run #88 on `484c5c5`: all four jobs passed.

**Defects found and fixed in `e3f19c4`:**

| Revocation-lookup outcome | Before `e3f19c4` | After |
|---|---|---|
| Quota exhausted (`RESOURCE_EXHAUSTED`), Google `INTERNAL` error | 401 as `INVALID_SIGNATURE`, DEBUG log only | `Unavailable` → 503 at session exchange, WARN log; an existing session is kept |
| Service account refused (`PERMISSION_DENIED`, `UNAUTHENTICATED`), project config missing | 401 as `INVALID_SIGNATURE`, DEBUG log only — every login fails silently | `Unavailable` → 503, ERROR log naming the codes |
| Account deleted in Firebase (`USER_NOT_FOUND`) | 401, but an existing session for that user survived | `ACCOUNT_DELETED`: request unauthenticated **and** session invalidated, like revoked/disabled |
| Unrecognised code | 401, DEBUG log | 401 (still fail-closed), WARN log |

The permission case matters for the first deploy specifically: `checkRevoked=true` makes verification depend on an authenticated `accounts:lookup` call that the old path never made, so a service account without Firebase Authentication read access would previously have signed every user out with no operator-visible signal.

**Deployment implications (to watch after the deploy):** the SPA attaches `Authorization: Bearer` to every API call (`frontend/src/api/client.ts`), so every authenticated request now costs one Identity Toolkit lookup — expect added per-request latency. Cookie-only requests are not revocation-checked (gap register, P2).

**Deployment verification procedure (operator — Render dashboard access required):**
1. Render → `job-agent` backend service → **Manual Deploy → Deploy latest commit** (branch `master`).
2. When the deploy is live: `curl -s https://job-agent-mwhu.onrender.com/actuator/info` — `deployment.commit` must equal `git rev-parse origin/master` (field added by `5cd8238`; absent means the old build is still serving).
3. `curl -s https://job-agent-mwhu.onrender.com/actuator/health` — `status` and every component `UP`.
4. Sign in to Robin in the browser and open Applications. Success confirms the service account can perform revocation lookups. If sign-in returns 503 *"Firebase Admin credentials could not complete token verification"*, grant the service account Firebase Authentication read access (the default `firebase-adminsdk` account has it) — do not revert the hardening.
5. Optional cut-off check, on the **test** account only: while signed in as it, disable it in the Firebase console (Authentication → Users → ⋮ → Disable account), reload Robin, and confirm the next API request is refused and the session does not survive (`USER_DISABLED` path). Re-enable the account afterwards.

---

## 4. Evidence Matrix: Automated vs Production Verification

### 4.1 Production Evidence Directly Observed (2026-11-03)

1. Render backend health check returned `UP`.
2. Health sub-components (`db`, `redis`, `diskSpace`, `livenessState`, `readinessState`, `ping`) all reported `UP`.
3. The Render deployment started successfully.
4. Flyway reported 51 migrations applied successfully on Supabase (schema version `051`).
5. `APP_SECRETS_KEY` provisioned and verified in Render production environment.
6. Robin Applications page recognized managed-mail configuration as active.
7. Candidate reserved unique email alias (`robin+...`).
8. Real email sent from candidate Gmail to alias.
9. Mailbox readiness transitioned to `ACTIVE`.
10. Stored row in `managed_inbound_messages`: 1 received, 1 with `content_encrypted`, 0 purged.
11. Inbound message visible in Robin frontend recent-messages view.

### 4.2 Automated Integration Test Verification (Testcontainers & MockMvc)

Automated test suites executed against real PostgreSQL 16 Testcontainers with all 51 Flyway migrations applied:

| Test Area | Suite / Test Method | Status | What Is Verified |
|---|---|---|---|
| Webhook Security | `CloudflareSignatureVerifierTest` | ✅ PASSED (6/6) | HMAC-SHA256 signature checking, timestamp freshness, replay refusal |
| Recipient Routing | `CloudflareRecipientRoutingTest` | ✅ PASSED (7/7) | Strict envelope recipient (`RCPT TO`) routing, alias parsing |
| Controller Response Mapping | `CloudflareInboundControllerTest` | ✅ PASSED (8/8) | Exact JSON responses for stored (`200 {"status":"stored"}`), same-token duplicate (`200 {"status":"duplicate"}`), same-message-ID duplicate (`200 {"status":"duplicate"}`), altered payload replay (`406 {"status":"rejected"}`), unknown recipient (`406 {"status":"rejected"}`), unauthenticated signature (`401 {"status":"unauthenticated"}`), stale timestamp (`406 {"status":"stale"}`), unconfigured (`503 {"status":"not_configured"}`) |
| Verification Provider | `ManagedMailVerificationProviderTest` | ✅ PASSED (9/9) | OTP extraction, link resolution, session security |
| Inbound Persistence | `CloudflareManagedMailIT` | ✅ PASSED (6/6) | Address reservation, receiving evidence transition to `ACTIVE`, encryption at rest |
| App Association & Isolation | `CloudflareManagedMailIT#inboundCloudflareWebhookApplicationAssociationAndIsolation` | ✅ PASSED | Matching by job URL and requisition, creation of `email_application_links` (`LINKED`, `STRONG`), strict multi-tenant candidate isolation, weak-signal refusal |
| Recruiter Lifecycle E2E | `CloudflareRecruiterLifecycleIT#scenario1_recruiterInterviewInvitationLifecycle` | ✅ PASSED | Inbound recruiter interview invitation, `TrackEmailClassifier` categorization, `EMPLOYER_MESSAGE_RECEIVED` timeline event, candidate review confirmation, stage transition to `Stage.INTERVIEW` (`CANDIDATE_CONFIRMED`) |
| Thread Continuity | `CloudflareRecruiterLifecycleIT#scenario2_recruiterReplyAndThreadContinuity` | ✅ PASSED | Multiple messages linked to single application (`linkedMessages = 2`), `In-Reply-To`/`References`, strict chronological timeline ordering ($t_1 < t_2$) |
| Tenant Isolation | `CloudflareRecruiterLifecycleIT#scenario3_candidateIsolationAndCrossTenantProtection` | ✅ PASSED | Candidate B application and timeline unaffected, cross-tenant review manipulation blocked (`NoSuchElementException`) |
| Message Filtering | `CloudflareRecruiterLifecycleIT#scenario4_nonRecruiterAndAmbiguousMessagesHandling` | ✅ PASSED | Generic newsletter classified `UNRELATED_OR_AMBIGUOUS` (`UNMATCHED`), ambiguous recruiter classified `RECRUITER_FOLLOW_UP` (`PENDING_REVIEW`), neither auto-links to timeline, both surface in candidate review queue |

### 4.3 Webhook Idempotency, Replay & Error Handling Specifications

#### Exact Response Mapping Matrix

| Delivery Scenario | HTTP Status | Response JSON Body | Backend Outcome Enum | Database Footprint Delta |
|---|---|---|---|---|
| **First delivery** | `200 OK` | `{"status":"stored"}` | `Outcome.STORED` | +1 token in `tokens`<br>+1 message in `messages`<br>+1 email in `emails`<br>+1 link in `links` |
| **Same-token duplicate** | `200 OK` | `{"status":"duplicate"}` | `Outcome.DUPLICATE_REQUEST` | **0 new rows** (token PK conflict, payload SHA matches) |
| **Same-message-ID duplicate** | `200 OK` | `{"status":"duplicate"}` | `Outcome.DUPLICATE_MESSAGE` | +1 token in `tokens`<br>**0 message rows** (address+message_id unique conflict) |
| **Altered-payload replay** | `406 Not Acceptable` | `{"status":"rejected"}` | `Outcome.REPLAYED_TOKEN` | **0 rows** (token seen, payload SHA mismatch) |
| **Altered-payload (Tampered Sig)** | `401 Unauthorized` | `{"status":"unauthenticated"}` | N/A (`INVALID_SIGNATURE`) | **0 rows** (HMAC verification failed) |
| **Unknown recipient** | `406 Not Acceptable` | `{"status":"rejected"}` | `Outcome.UNKNOWN_RECIPIENT` | **0 rows** (recipient alias not in ACTIVE status) |
| **Stale timestamp** | `406 Not Acceptable` | `{"status":"stale"}` | N/A (`STALE`) | **0 rows** (timestamp older than maxAge = 900s) |
| **Unconfigured provider/secret** | `503 Service Unavailable` | `{"status":"not_configured"}` | N/A | **0 rows** (service not configured for Cloudflare) |

The exact behaviors for Scenarios A through F across the Cloudflare Worker and Spring backend are confirmed from source:

#### Scenario A: Same timestamp, token, payload, and signature delivered twice
- **Spring Backend Behavior**:
  - `CloudflareSignatureVerifier` passes timestamp freshness and HMAC signature verification.
  - `ManagedInboundService.accept()` attempts `INSERT INTO managed_mail_webhook_tokens (token_sha256, provider_id, received_at, payload_sha256)`.
  - Primary key conflict on `token_sha256` causes the insert to return 0 rows.
  - Service queries existing row: `SELECT payload_sha256 FROM managed_mail_webhook_tokens WHERE token_sha256 = ?`.
  - Because payload SHA-256 matches, returns `Outcome.DUPLICATE_REQUEST` (`outcome.accepted() == true`).
  - `CloudflareInboundController` maps any accepted duplicate outcome (`DUPLICATE_REQUEST` or `DUPLICATE_MESSAGE`) to HTTP **`200 OK {"status":"duplicate"}`**.
- **Database Footprint**: **0 new rows inserted** across all tables. No message stored, `EmailIntelligenceService` not invoked.
- **Worker Behavior**: Worker receives 200 OK, logs completion, and returns cleanly.

#### Scenario B: Same token with modified payload
- **Spring Backend Behavior**:
  - If HMAC signature was recomputed over modified payload: HMAC verification passes, but token conflict check in `managed_mail_webhook_tokens` detects that `payloadSha256.equals(seenPayloadSha256)` is false.
  - Service returns `Outcome.REPLAYED_TOKEN` (`outcome.accepted() == false`).
  - `CloudflareInboundController` maps unaccepted outcomes to HTTP **`406 Not Acceptable {"status":"rejected"}`**.
  - If HMAC signature was not recomputed, `CloudflareSignatureVerifier` rejects with HTTP **`401 Unauthorized {"status":"unauthenticated"}`**.
- **Database Footprint**: **0 rows inserted**. Request refused.
- **Worker Behavior**: Worker receives 406 or 401, logs error, and returns cleanly without throwing.

#### Scenario C: Same Message-ID delivered with a fresh valid token
- **Spring Backend Behavior**:
  - Fresh token is inserted into `managed_mail_webhook_tokens` (1 row inserted).
  - Recipient is routed to active candidate mailbox.
  - `INSERT INTO managed_inbound_messages` encounters conflict on unique index `(address_id, message_id_sha256)`. Insert returns 0 rows.
  - Returns `Outcome.DUPLICATE_MESSAGE`.
  - `CloudflareInboundController` maps this to HTTP **`200 OK {"status":"duplicate"}`**.
  - `stored == 0`, so `EmailIntelligenceService` is **NOT** invoked.
- **Database Footprint**: Exactly **1 row inserted in `managed_mail_webhook_tokens`**. **0 rows inserted** in `managed_inbound_messages`, `emails`, or `email_application_links`.

#### Scenario D: Cloudflare Worker retry after backend 5xx
- **Worker Implementation (`cloudflare-email-worker/src/index.js`)**:
  - When backend responds with any status other than 200 or 406 (e.g. 500 Internal Server Error, 502/503 Bad Gateway, or fetch timeout), the Worker logs `throw new Error("Robin backend returned status " + response.status)`.
  - In Cloudflare Email Routing runtime, an unhandled exception thrown in the Worker signals transient delivery failure to Cloudflare's MTA.
  - Cloudflare queues the message and retries delivery with exponential backoff.

#### Scenario E: Cloudflare Worker handling of backend 406
- **Worker Implementation (`cloudflare-email-worker/src/index.js`)**:
  - When backend returns HTTP `406 Not Acceptable` (terminal rejection: unknown alias, untrusted domain, replayed token, or unroutable envelope), the Worker logs `console.error("Backend rejected email: " + errorText)`.
  - The Worker **returns normally** (`return;`).
  - Returning cleanly signals to Cloudflare Email Routing that message processing has concluded, so Cloudflare **does NOT retry**.

#### Scenario F: Unknown recipient delivery
- **Spring Backend Behavior**:
  - HMAC signature and timestamp freshness verify successfully.
  - Recipient routing via `ManagedMailboxService.routeEnvelopeRecipient()` returns empty because the envelope recipient is unreserved or not in `ACTIVE` status in `managed_mail_addresses`.
  - `ManagedInboundService.accept()` returns `Outcome.UNKNOWN_RECIPIENT` (`outcome.accepted() == false`).
  - `CloudflareInboundController` maps this unaccepted outcome to HTTP **`406 Not Acceptable {"status":"rejected"}`**.
- **Database Footprint**: **0 rows inserted** in `managed_inbound_messages`, `emails`, or `email_application_links`.
- **Worker Behavior**: Worker receives 406, logs rejection, and returns cleanly without throwing (no retry).

### 4.4 Production Database Side Effects, Cascades & Triggers

Analysis of Flyway migrations `V001` through `V051` and JPA/JDBC repositories reveals the full side-effect map:

1. **`managed_mail_webhook_tokens`**:
   - Independent token table (`token_sha256` PK).
   - Stored key: `token_sha256 = TrackText.sha256(raw_token)` (64-character hex).
   - Has no foreign keys to other tables. Rows are retained for replay defense.
2. **`managed_inbound_messages`**:
   - Independent raw message vault.
   - `message_id_sha256` = `TrackText.sha256(cleanMessageId)` where `cleanMessageId` is normalized by stripping `<` and `>` (e.g. `probe-1@test.robin.internal`).
   - `profile_id` references `profiles(id) ON DELETE CASCADE`.
   - `address_id` references `managed_mail_addresses(id, profile_id) ON DELETE CASCADE`.
   - **Crucial Architectural Boundary**: There is **NO foreign key** between `managed_inbound_messages` and `emails`. Deleting a raw message row from `managed_inbound_messages` does **NOT** delete or cascade to `emails` or `email_application_links`.
3. **`emails`**:
   - Stores normalized message evidence parsed by `EmailIntelligenceService`.
   - `message_id` stores the unbracketed normalized Message-ID.
   - `profile_id` references `profiles(id) ON DELETE CASCADE`.
   - Referenced by:
     - `email_application_links.email_id` (`ON DELETE CASCADE`)
     - `application_events.email_id` (`ON DELETE CASCADE`)
     - `application_receipts.email_id` (`ON DELETE SET NULL`)
4. **`application_events`**:
   - Append-only timeline table.
   - Guarded by trigger `application_events_guard_mutation`:
     ```sql
     if pg_trigger_depth() <= 1 then
         raise exception 'application_events is append-only: direct DELETE is forbidden' using errcode = '55000';
     end if;
     ```
   - **Direct `DELETE FROM application_events` is FORBIDDEN** by the database trigger.
   - Cascade deletion is only permitted when triggered by parent deletion (`pg_trigger_depth() > 1`), such as `DELETE FROM emails WHERE id = ...` or `DELETE FROM applications WHERE id = ...`.
5. **`notifications` & `outbox_events`**:
   - Ingestion emits events to `outbox_events` (`payload.dedup_key = 'track-email:' || email_id`).
   - Asynchronous fan-out consumer writes to `notifications` table (`dedup_key = ownerProfileId || ':track-email:' || email_id`).
   - Notifications have no foreign key cascade to `emails`. Must be deleted by exact `dedup_key` or primary key.
6. **Review Queue Impact (`GET /api/v1/track/reviews`)**:
   - Any email ingested with `status = 'UNMATCHED'` is returned by `TrackRepository.reviewQueue(profileId)` because the query checks `where l.profile_id = ? and l.status in ('PENDING_REVIEW', 'UNMATCHED', 'CONFLICT')`.
   - Consequently, sending any test email to a real candidate's address directly affects that candidate's review queue.

---

## 5. Phase 20 Exit Criteria — Comprehensive Status Matrix

| Phase 20 Requirement | Automated Tests (Testcontainers) | Live Production Status |
|---|---|---|
| Managed mail configuration & credentials | ✅ Managed-mail unit classes pass; full backend unit suite 1,435 tests, 0 failures (local run, 2026-11-04) | ✅ Verified on Render (`APP_SECRETS_KEY` loaded, `ACTIVE` status) |
| Cloudflare Worker HMAC signing & delivery | ✅ 6 worker tests pass in CI | ✅ Verified live (Gmail → Cloudflare → Render delivered) |
| Firebase revocation-aware authentication | ✅ `FirebaseAdminTokenVerifierTest` (29), `FirebaseAuthenticationFilterTest` (9), `FirebaseSessionExchangeTest` (16) | ❌ **NOT DEPLOYED** — production build predates `a442f9b` (§3.3); deploy and verify per §3.4 |
| Webhook idempotency & replay protection | ✅ Tested in `CloudflareManagedMailIT`; probe contract tested in CI | ❌ **NOT YET VERIFIED IN PRODUCTION** (Procedure in §6.1) |
| AES-GCM encryption at rest | ✅ Tested in `CloudflareManagedMailIT` | ✅ Verified in Supabase (`content_encrypted` populated) |
| Email-to-application association | ✅ Tested in `CloudflareRecruiterLifecycleIT` | ❌ **NOT YET VERIFIED IN PRODUCTION** (Procedure in §6.2) |
| Employer OTP extraction & handling | ✅ Tested in `ManagedMailVerificationProviderTest` | ❌ **NOT YET VERIFIED IN PRODUCTION** (Procedure in §6.3) |
| Recruiter communication & timeline tracking | ✅ Tested in `CloudflareRecruiterLifecycleIT` | ❌ **NOT YET VERIFIED IN PRODUCTION** (Procedure in §6.4) |
| Candidate multi-tenant isolation | ✅ Tested in `CloudflareRecruiterLifecycleIT` (scenario 3) | ❌ **NOT YET VERIFIED LIVE** — owner-scoped schema and queries are structural evidence only; authenticated check of the dedicated test candidate in §6.0 |
| CI Pipeline & Build gates | ✅ All 4 jobs green on run #89 (`b2be7e0`) | ⚠️ Backend healthy on Render but serving the 2026-11-03 build, behind `master` (§3.3) |

**Phase 20 Verdict**: IMPLEMENTATION COMPLETE. Automated coverage is green across all four communication-lifecycle scenarios and the revocation-aware authentication paths. Phase 20 remains **OPEN** until the operator runs, in order: the Render deploy and identity check (§3.4) → tenant isolation (§6.0) → duplicate-delivery idempotency (§6.1) → application association (§6.2) → recruiter lifecycle (§6.4). Employer OTP handling (§6.3) triggers a real employer verification email and needs separate explicit authorization; it may be scope-deferred instead, with the reason recorded here.

---

## 6. Safe Live Verification Procedures (Operator Approval Required)

> [!IMPORTANT]
> The following procedures are designed for production safety. No emails, webhooks, or employer portal interactions will be initiated without explicit operator authorization and without establishing a dedicated test identity.

### 6.0 Authenticated Isolation Verification — Dedicated Test Candidate (run before §6.1)
- **Prerequisite**: a dedicated test candidate — a separate Firebase account with its own Robin profile and reserved managed alias, **not** listed as an operator. Every probe in §6.1 targets this account's alias only.
- **Objective**: confirm in production that the test candidate cannot read another candidate's resources, before any synthetic traffic is sent to its alias.
- **Tool**: `scripts/phase20/isolation-probe.mjs` (tested in CI: `Phase 20 operator probe tests`). GET requests only; response bodies are never printed; it stops after `GET /auth/me` if the token belongs to any account other than `--expect-email`.
- **Procedure**:
  1. As the **real** candidate, open Applications with DevTools → Network open, select the `GET /api/v1/applications` request, and copy one `items[].id` from its response (the UI does not display ids). Optionally take a cover-letter id and a tailored-CV version id the same way.
  2. As the **test** candidate, in a separate browser profile, copy its Firebase ID token: DevTools → Application → IndexedDB → `firebaseLocalStorageDb` → `firebaseLocalStorage` → entry value → `stsTokenManager.accessToken` (valid about one hour). Keep it in your own shell only.
  3. Run:
     ```bash
     export ROBIN_TEST_ID_TOKEN='<test candidate ID token>'
     node scripts/phase20/isolation-probe.mjs --expect-email <test candidate email> \
       --foreign-application <real candidate application id> \
       [--foreign-cover-letter <id>] [--foreign-cv-version <id>]
     ```
- **Expected**: `Signed in as: <test candidate email>`; every application-scoped GET `PASS … -> 404` except `/receipts`, which answers `PASS … -> 200 (owner-filtered, empty)`; `/track/reviews` and `/managed-mail/messages` `PASS (no foreign id present)`; exit code `0`. The last line prints the test candidate's own alias for §6.1. Exit `1` = isolation failure (stop and report); exit `3` = inconclusive (e.g. expired token, 5xx).
- **Production side effects**: none.

### 6.1 Direct Render Endpoint Duplicate Webhook Idempotency Verification
- **Prerequisite**: §6.0 passed. Never target a real candidate's mailbox.
- **Distinction**: Directly invokes the live Render backend endpoint with synthetic HMAC-signed payloads. Does not involve Cloudflare or send real emails.
- **Tool**: `cloudflare-email-worker/scripts/idempotency-probe.mjs`. A CI-run parity test proves it signs byte-for-byte like the deployed Worker's handler. It reads `CLOUDFLARE_EMAIL_WORKER_SECRET` from the environment and never prints it, accepts only a reserved alias (`prefix+16-character token@mail.aplivenosi.com`), and is a dry run unless `--execute` is passed.
- **Default mode `quarantined`**: the probe payload is spam-flagged, so `ManagedInboundService` still runs both idempotency gates (token primary key, `(address_id, message_id_sha256)` unique index) but keeps no content and never calls `EmailIntelligenceService` — no `emails`, link, notification, outbox or timeline row is created and the test candidate's review queue is untouched. `--mode full` exercises the full ingestion pipeline with the expected state and cleanup below.
- **Exact Test Procedure**:
  ```bash
  cd cloudflare-email-worker
  node scripts/idempotency-probe.mjs --recipient <test candidate alias>            # dry run: prints the plan
  export CLOUDFLARE_EMAIL_WORKER_SECRET='<value from the Worker/Render secret store>'
  node scripts/idempotency-probe.mjs --recipient <test candidate alias> --execute
  ```
- **Expected Results** (printed as PASS/FAIL per request; exit `0` only if all match):
  - Request 1, first delivery: `200 {"status":"stored"}`.
  - Request 2, byte-identical repeat: `200 {"status":"duplicate"}`.
  - Request 3, same Message-ID with a fresh token: `200 {"status":"duplicate"}`.
  - Request 4, first token replayed with an altered, validly signed payload: `406 {"status":"rejected"}`.
  - Request 5, tampered signature: `401 {"status":"unauthenticated"}`.
- **Database check**: the probe prints read-only verification SQL with the exact token and Message-ID digests. Quarantined mode expects 2 token rows, 1 `managed_inbound_messages` row (`spam_flagged = true`, no content), 0 `emails` rows; an optional exact-match cleanup is printed with it. Requests 1 and 3 also advance `last_inbound_at` on the existing `managed_mail_domains` row for `mail.aplivenosi.com` (no new row).
- **Expected Database State (`--mode full`)**:
  - `managed_mail_webhook_tokens`: exactly 2 rows inserted ($K_1$ and $K_2$).
  - `managed_inbound_messages`: exactly 1 row inserted for the probe's Message-ID.
  - `emails`: exactly 1 row inserted.
  - `email_application_links`: exactly 1 row inserted with status `UNMATCHED`.
  - `application_events`: **0 rows inserted** (no application was matched).
- **Exact Two-Step Cleanup Procedure (Pre-Inspection then Targeted Deletion)**:
  
  **Step 1: Inspect and capture exact primary keys before deleting:**
  ```sql
  -- A. Find webhook token rows by exact SHA-256 PK:
  SELECT token_sha256, provider_id, signed_at, payload_sha256
  FROM managed_mail_webhook_tokens
  WHERE token_sha256 IN (
      '<token digest 1 printed by the probe>',
      '<token digest 2 printed by the probe>'
  );

  -- B. Find inbound vault row by normalized message_id_sha256:
  SELECT id, profile_id, address_id, message_id_sha256, sender_domain, subject_redacted, received_at
  FROM managed_inbound_messages
  WHERE message_id_sha256 = '<Message-ID digest printed by the probe>';

  -- C. Find normalized email row:
  SELECT id, profile_id, message_id, subject, from_address, classification, application_id
  FROM emails
  WHERE message_id = '<Message-ID printed by the probe>';

  -- D. Find associated link row using email_id from step C:
  SELECT id, profile_id, email_id, application_id, status, match_strength, inferred_category
  FROM email_application_links
  WHERE email_id = '<email_id_from_step_C>';

  -- E. Find notification row using dedup_key:
  SELECT id, profile_id, dedup_key, title, created_at
  FROM notifications
  WHERE dedup_key = '<profile_id_from_step_C>:track-email:<email_id_from_step_C>';

  -- F. Find outbox event:
  SELECT id, aggregate_type, aggregate_id, event_type, created_at
  FROM outbox_events
  WHERE payload->>'dedup_key' = 'track-email:<email_id_from_step_C>';
  ```

  **Step 2: Delete by exact primary key (Never broad DELETE):**
  ```sql
  -- 1. Delete notification by exact PK:
  DELETE FROM notifications WHERE id = '<notification_id_from_step_E>';

  -- 2. Delete outbox event by exact PK:
  DELETE FROM outbox_events WHERE id = '<outbox_id_from_step_F>';

  -- 3. Delete email by exact PK (cascades to email_application_links):
  DELETE FROM emails WHERE id = '<email_id_from_step_C>';

  -- 4. Delete inbound vault message by exact PK:
  DELETE FROM managed_inbound_messages WHERE id = '<inbound_id_from_step_B>';

  -- 5. Delete webhook tokens by exact PK:
  DELETE FROM managed_mail_webhook_tokens
  WHERE token_sha256 IN (
      '<token digest 1 printed by the probe>',
      '<token digest 2 printed by the probe>'
  );
  ```

### 6.2 Correct Application Association in Live Production Workflow
- **Objective**: Verify that an inbound message quoting an active candidate application's requisition ID or job URL is linked to that application in the live database.
- **Exact Test Procedure**:
  1. As the **dedicated test candidate** (§6.0), create or pick one test application with a known job URL and requisition number. Never use the real candidate's profile: a linked message writes append-only `application_events` rows (§4.4).
  2. Send an email from the operator's personal email to the test candidate's managed alias quoting that exact job URL and requisition ID.
  3. Inspect Robin Applications dashboard and query `email_application_links`.
- **Expected Result**:
  - Message appears in Robin under the specific application.
  - `email_application_links` row created with `status = LINKED` and `match_strength = STRONG`.
  - Timeline reflects `EMPLOYER_MESSAGE_RECEIVED`.
- **Production Side Effects**: One message linked to the candidate's existing test application.
- **Cleanup Procedure**: Delete test message from `emails` (cascades to timeline event and link).
- **Evidence to Capture**: Screenshot or JSON from `/api/v1/track/applications/{id}` showing linked message and timeline event.

### 6.3 Real Supported Employer OTP Extraction and Handling
- **Objective**: Verify Robin extracts a real OTP from an employer portal email and serves it to the automated session.
- **Exact Test Procedure**:
  1. Trigger an authentic candidate sign-in / verification code request on a supported ATS (e.g. Greenhouse or Workday test portal) using the candidate's Robin managed email address.
  2. Monitor receipt in `managed_inbound_messages` and `verification_extractions`.
  3. Validate code extraction via `POST /api/v1/managed-mail/resolve-verification`.
- **Expected Result**:
  - Email received with `classification = VERIFICATION_CODE`.
  - Verification code extracted with high confidence ($\ge 0.90$).
  - Full body purged after code consumption according to privacy policy.
- **Production Side Effects**: Creates a real portal login session.
- **Cleanup Procedure**: Log out or allow session token to expire naturally.
- **Evidence to Capture**: Actuator / audit log showing `OTP_RESOLVED` and timestamp of content purge.

### 6.4 Real Recruiter Communication Lifecycle
- **Objective**: Verify that a multi-message thread from a recruiter is displayed in Robin's UI, tracks communication counts, and allows manual candidate confirmation of stage transitions.
- **Exact Test Procedure**:
  1. Send an email formatted as an interview invitation from an external mailbox to the **test candidate's** managed alias, quoting the details of the test application from §6.2.
  2. Follow up with a second reply message.
  3. Confirm the suggested interview stage in Robin's Track UI.
- **Expected Result**:
  - Both messages appear in chronological order on the application timeline.
  - Robin UI prompts the candidate to confirm the inferred interview invitation.
  - Upon candidate confirmation, application stage updates to `INTERVIEW`.
- **Production Side Effects**: Updates stage of candidate's test application.
- **Cleanup Procedure**: Candidate can revert application status in UI.
- **Evidence to Capture**: UI screenshot or API payload showing `INTERVIEW` stage and `communications.linkedMessages = 2`.

### 6.5 Cloudflare Email Worker Retry & Error Handling Verification
- **Distinction**: Verifies the Cloudflare Worker runtime behavior in response to backend errors.
- **Objective**: Confirm that Cloudflare Worker retries on backend 5xx errors and terminally halts on 406 rejections.
- **Procedure (Simulated / Staging)**:
  - Worker automated suite (`npm test` in `cloudflare-email-worker`) executes 6 mock scenarios verifying that `throw new Error()` is executed on non-200/non-406 responses and clean `return` is executed on 406 rejections.
  - Live production verification of Worker retry requires temporarily withholding backend 200 responses, which is **not recommended in production** due to potential delivery delays for real emails. Measured 2026-11-04 (`node --test --experimental-test-coverage`): every line of the response-handling block (200 → return, 406 → `setReject` + return, anything else → throw) executes under the worker tests; `src/index.js` overall was 91.3% lines / 77.3% branches with the post-read size check (lines 37–42) uncovered; a test added for an under-reported `rawSize` raises it to 96.0% / 81.8%, leaving only the random-token fallback (lines 122–126, unused wherever `crypto.randomUUID` exists) uncovered.
