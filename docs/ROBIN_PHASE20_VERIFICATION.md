# ROBIN — Phase 20 Verification Report

**Prepared:** 2026-11-03, by read-only repository/source inspection, updated same day with operator-obtained production evidence.
**Scope:** Managed-mail (Cloudflare) production readiness, encryption-key safety, Worker/backend reconciliation.
**Verdict:** IMPLEMENTATION COMPLETE. First genuine inbound-mail production test PASSED (one message, encrypted, persisted). Phase 20 is **not yet fully closed** — duplicate-delivery idempotency, email-to-application association, automatic OTP extraction, and the full recruiter-communication lifecycle remain production-unverified (see §4.3 and §5).

---

## 1. Configuration investigation

### 1.1 Exact variable names (confirmed against source, not documentation)

| Variable | Spring property | Consumer | Confirmed at |
|---|---|---|---|
| `APP_MANAGED_MAIL_DOMAIN` | `app.managed-mail.domain` | `ManagedMailSettings` | `application.yml:83` |
| `APP_MANAGED_MAIL_PROVIDER` | `app.managed-mail.provider` | `ManagedMailSettings` | `application.yml:84` — **default is `mailgun` if unset** |
| `APP_MANAGED_MAIL_ADDRESS_PREFIX` | `app.managed-mail.address-prefix` | `ManagedMailSettings` | `application.yml:85` |
| `CLOUDFLARE_EMAIL_WORKER_SECRET` | `app.managed-mail.cloudflare.webhook-secret` | `ManagedMailSettings.cloudflareWebhookSecret()` | `application.yml:98` |
| `APP_SECRETS_KEY` | `app.secrets.encryption-key` | `SecretCipher` | `application.yml:75` |

`CLOUDFLARE_EMAIL_WORKER_SECRET` (HMAC webhook signature) and `APP_SECRETS_KEY` (AES-256-GCM encryption at rest) are two **entirely separate** Spring beans/properties (`ManagedMailSettings` vs. `SecretCipher`). No code path in the repository conflates them.

### 1.2 Why the backend previously reported `APP_SECRETS_KEY` as missing — RESOLVED

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

**Resolved 2026-11-03:** the operator independently generated a new `APP_SECRETS_KEY` and added it to the Render service, then restarted the backend. Render health checks reported the backend `UP` after restart, and the Applications page subsequently recognized the managed-mail configuration (see §4.1). This is consistent with cause (1) or (2) above having been the actual root cause. No source-level or production evidence distinguishes which of (1)/(2) applied before the fix — it no longer matters, since the variable is now confirmed present and the service has restarted with it loaded.

### 1.3 Provider default risk — RESOLVED by observed behavior

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

Had `APP_MANAGED_MAIL_PROVIDER` never been explicitly set to `cloudflare` on Render, the backend would silently default to `mailgun` and the mailbox would never reach `ACTIVE` via a Cloudflare-routed message. Because the mailbox **did** reach `ACTIVE` and **did** receive and persist a real Cloudflare-routed message (§4.1–§4.2), this is now behaviorally confirmed resolved — the live provider value must be `cloudflare` with `CLOUDFLARE_EMAIL_WORKER_SECRET` present, or the observed chain could not have completed. The literal `missing`/`provider` JSON from `GET /api/v1/managed-mail/address` was not separately captured, but the end-to-end outcome makes that capture unnecessary now.

---

## 2. Encryption-key safety (APP_SECRETS_KEY)

### 2.1 Complete inventory of ciphertext columns

Only two classes in the entire backend call `SecretCipher.encrypt`/`decrypt` — confirmed exhaustively by grep across `backend/src/main/java`:

| Table | Column(s) | Migration | Writer |
|---|---|---|---|
| `employer_credentials` | `username_encrypted`, `password_encrypted` | `V040__employer_credentials_and_mailbox_verification.sql:16-17` | `EmployerCredentialService.java` |
| `managed_inbound_messages` | `content_encrypted` | `V042__managed_mail_receiving.sql:77` | `ManagedMessageContent.java` |

No other table, service, or column depends on `APP_SECRETS_KEY`. (Gmail OAuth tokens use a **separate** key, `APP_GMAIL_TOKEN_KEY` via a different cipher class — confirmed not a third consumer.)

### 2.2 Do existing rows depend on a previous key? — RESOLVED by a pre-provisioning baseline read

**Timeline, as reported by the operator:** a read-only Supabase count query was run against **both** `employer_credentials` and `managed_inbound_messages` *before* the new `APP_SECRETS_KEY` was generated and added to Render. **Both tables returned zero rows at that time.** The candidate then generated and deployed the new, independent `APP_SECRETS_KEY`; a real inbound message was subsequently received and stored encrypted (§4.2).

This closes the continuity question completely, for a different reason than originally analyzed: there is no "previous key" to be orphaned, because **nothing existed to encrypt under one**. Every row either table holds today was necessarily written after the current key was already in place — there is no scenario in which a row is unreadable due to a key change, because there was no prior ciphertext to lose.

- `employer_credentials`: pre-provisioning count was **0**. Any rows present now (not re-counted after the key was provisioned, since the system has been used since — see below) were written under the current key, by construction. **No legacy-ciphertext risk exists for this table.**
- `managed_inbound_messages`: pre-provisioning count was **0**, consistent with the post-provisioning count of **1** captured in §4.2 (one real message, received and encrypted after the key was live). `content_encrypted` is written inside `ManagedInboundService.accept()`, which is `@Transactional` — if `SecretCipher.encrypt()` throws because no key is configured, the insert never commits, so no partial/corrupted state was ever possible here either way.

**Important precision, per the operator's own correction:** this establishes the *pre-provisioning* count was zero for both tables — it does **not** establish that the *current* count of `employer_credentials` is zero, since the system has been used since that query ran. No claim is made here about how many `employer_credentials` rows exist right now, only that none of them (whatever the current count is) can be depending on a lost prior key.

### 2.3 Explicit instruction compliance

No key has been generated, rotated, or changed by any Claude session. The `APP_SECRETS_KEY` generation and Render configuration described in §1.2/§4.1 was performed independently by the operator, not by this tool. This report only establishes what is true in source and what the operator has reported as directly observed.

---

## 3. Worker / backend reconciliation

### 3.1 Confirmed Git state (re-verified 2026-11-03, immediately before this file's own finalizing commit — see §3.3 for the resulting 4-commit state)

```
git rev-parse HEAD           → 9e66c7a1b2fe8956c433ecffbd9bc06732d900c8
git rev-parse origin/master  → 5d893245937e0956fc90b69790ec7b62e12eb79f
git log --oneline origin/master..HEAD:
  9e66c7a docs: add Phase 20 verification, current-state, gap, scorecard and roadmap reports
  610d574 fix(automation): correct Workday capability row to match actual service behavior
  e56c2a9 fix(cloudflare-email-worker): reconcile wrangler.toml with deployed config
git status --short:
 M GEMINI.md
git merge-base HEAD origin/master → 5d893245937e0956fc90b69790ec7b62e12eb79f  (= origin/master; clean linear history, no conflicts)
```

Local `master` is exactly 3 commits ahead of `origin/master`, cleanly based on it. `GEMINI.md` (a personal, unrelated file) is the only working-tree modification, and is not touched by any of the three commits — confirmed by `git log --oneline origin/master..HEAD -- GEMINI.md` returning nothing.

### 3.2 The wrangler.toml discrepancy — resolved and committed

`cloudflare-email-worker/wrangler.toml` now reads (commit `e56c2a9`):

```toml
workers_dev = false
addresses = ["robin@mail.aplivenosi.com"]
...
[vars]
ROBIN_BACKEND_URL = "https://job-agent-mwhu.onrender.com"
```

The `ROBIN_BACKEND_URL` correction matches the host used consistently elsewhere in this repository's own docs (`ROBIN_BUILD_STATUS.md`, `ROBIN_REPOSITORY_AUDIT_2026-10-13.md`) for the live Render backend. `workers_dev = false` is a standard, recognized Wrangler top-level key and is a reasonable setting for a worker that is only ever invoked by Email Routing, not direct HTTP traffic.

**`addresses = [...]` — confirmed valid, verified against Cloudflare's own documentation.** [developers.cloudflare.com/email-service/configuration/email-routing-addresses](https://developers.cloudflare.com/email-service/configuration/email-routing-addresses/) confirms a top-level `addresses` field in `wrangler.toml`/`wrangler.jsonc` is the documented way to declare Email Routing rules directly in Worker config, requiring **Wrangler 4.113.0 or later**; the deployed CLI for this project is **4.157.0**, comfortably above the minimum. The docs explicitly cover this project's exact pattern: *"To route mail sent to an Email Routing subdomain, list each literal recipient address"* — i.e. a literal address on a subdomain (`robin@mail.aplivenosi.com`) is supported, as opposed to a `*@domain` catch-all, which the docs restrict to apex domains only (consistent with the subdomain catch-all limitation `cloudflare-email-worker/README.md` already documents). `cloudflare-email-worker/README.md` §2.5 still describes the dashboard click-path as an alternative way to create the same rule, but the `wrangler.toml` declaration is a genuine, current, functioning configuration mechanism, not an inert or speculative one. Combined with the real inbound delivery in §4.2, both the declared configuration and the live production route are now verified working.

### 3.3 Action taken

Four commits exist locally, none pushed: the `wrangler.toml` fix (`e56c2a9`), a correction to the Workday ATS capability registry (`610d574`, unrelated to Phase 20 — see §4.4), the initial set of five documentation reports (`9e66c7a`), and a fourth, documentation-only commit finalizing this evidence (this file plus the other four docs, incorporating both corrections in §2.2 and §3.2). Pushing to `origin/master` is a production-adjacent, visible-to-others action that can trigger CI and connected auto-deploys on Render/Vercel/Cloudflare — **not done, pending explicit operator approval**.

---

## 4. Production verification evidence obtained 2026-11-03

This section is evidence **the operator directly observed in production** (Render, the live Robin frontend, the candidate's real Gmail account, and a read-only Supabase query) — not source-code analysis, not a unit/integration test, and not something this tool executed or can independently confirm. It is reported here exactly as given, distinguished explicitly from the source-level analysis in §1–§3 above.

### 4.1 Platform and application health

1. Render backend health check returned `UP`.
2. Health sub-components — PostgreSQL, Redis, disk space, liveness, and readiness — all reported `UP`.
3. The Render deployment started successfully.
4. Flyway reported 52 migration entries, 51 applied, 0 failed, schema version `051`. (The 52-vs-51 discrepancy was not further investigated in this session — not confirmed whether it reflects a pending/future migration, a repeatable migration, or something else.)
5. `APP_SECRETS_KEY` was independently generated by the operator and added to the Render service's environment. *(A read-only Supabase count query run immediately before this step found zero rows in both `employer_credentials` and `managed_inbound_messages` — see §2.2 for why this fully resolves the encryption-key-continuity question.)*
6. The backend restarted successfully after that change.
7. The Robin Applications page (frontend) recognized the managed-mail configuration as present/active rather than `NOT_CONFIGURED`.

### 4.2 End-to-end inbound email test

8. The candidate reserved a unique Robin email address (a `robin+<token>@mail.aplivenosi.com`-pattern alias).
9. A real email was sent from the candidate's own personal Gmail account to that alias.
10. Robin changed the mailbox's readiness status to `ACTIVE`.
11. The message appeared in Robin's recent-messages view.
12. A read-only Supabase count query against `managed_inbound_messages` returned:
    - rows received: **1**
    - rows with `content_encrypted` populated: **1**
    - rows with `content_purged_at` set: **0**

**This is genuine, directly-observed production evidence that the full Cloudflare → Worker → Render backend → Postgres chain completed at least once, with content encrypted at rest and not purged within the observation window.** It is the first time this chain has been confirmed outside of source-code/test analysis.

### 4.3 Explicitly NOT yet production-verified

The evidence above proves one successful single-message delivery. It does **not** prove, and this report makes no claim of:

- **Duplicate webhook delivery idempotency in production** — the replay/duplicate-detection logic is unit/integration-tested (`ManagedInboundServiceTest`, `CloudflareManagedMailIT`), but no duplicate/replayed delivery has been observed or tested against the live Cloudflare→Render path.
- **Email-to-application association** — whether this specific message was correctly attributed to the right candidate's specific job application (vs. just landing in a general inbound-messages table) was not confirmed by the evidence obtained.
- **Automatic OTP extraction and use in a real, supported employer verification flow** — not exercised or observed.
- **The full recruiter-communication lifecycle** — (replies, multi-message threads, recruiter-initiated contact, timeline-event updates beyond the single inbound message) — not exercised or observed.

These remain open items, not assumptions of failure — simply not yet tested in production.

### 4.4 Unrelated finding, now resolved in the same commit range

A separate, pre-existing truthfulness bug in `AtsCapabilityRegistry`'s `WORKDAY` row (unrelated to managed mail) was found and fixed in commit `610d574` during this review cycle — see the companion review notes. It is mentioned here only because earlier drafts of this Phase 20 documentation set incorrectly cross-referenced it as still-open; it is not a Phase 20 blocker and was never part of the managed-mail chain.

---

## 5. Phase 20 exit criteria — current status

| Criterion | Status |
|---|---|
| Live backend provider + missing-config understood | ✅ Resolved behaviorally — mailbox reached `ACTIVE` via Cloudflare, so provider/secret must be correctly configured (§1.3) |
| Encryption-key continuity safe for `managed_inbound_messages` | ✅ Confirmed — pre-provisioning count was 0, post-provisioning is 1/1 encrypted, 0 purged (§2.2, §4.2) |
| Encryption-key continuity safe for `employer_credentials` | ✅ Resolved — pre-provisioning count was 0, so no row can depend on a lost prior key (§2.2). Current row count was not re-captured and is not claimed. |
| Worker ↔ backend HMAC config matched | ✅ Names confirmed correct in code; behaviorally confirmed working since a real message was accepted and persisted |
| Repo config matches intended deployed Worker config | ⚠️ Fix committed (`e56c2a9`), **not yet pushed**; config itself (including `addresses`) confirmed valid against current Wrangler/Cloudflare docs (§3.2) |
| Genuine inbound test email succeeds | ✅ **Done 2026-11-03** — real Gmail → alias → `ACTIVE` mailbox → message visible in Robin (§4.2) |
| Encrypted record persisted + visible in Robin | ✅ Confirmed — 1 row, `content_encrypted` populated, visible in recent messages (§4.2) |
| Candidate ownership / application association correct | ❌ Not proven — code path exists, not confirmed against this specific message (§4.3) |
| Idempotency / error handling tested | ✅ Unit/integration-tested; ❌ not tested against a live duplicate Cloudflare delivery (§4.3) |
| Tests/build/regression pass | ✅ Confirmed via GitHub Actions run #60 on `origin/master`; this session's own test runs recorded separately in the accompanying review |
| Deployment state and GitHub state reconciled | ⚠️ 4 commits made, none pushed — awaiting operator approval |

**Phase 20 is implementation-complete and has its first real production proof point, but is not fully closed.** Duplicate-delivery idempotency in production, email-to-application association, automatic OTP extraction, and the full recruiter-communication lifecycle all remain production-unverified and should not be claimed otherwise.

---

## 6. Remaining actions

1. **(Your approval needed)** Push the local commits to `origin/master`. This does not itself change the live Cloudflare/Render configuration (Cloudflare already has the corrected `wrangler.toml` values live from the prior manual `wrangler deploy`) — it makes Git match what is already running, plus the unrelated Workday registry fix and this documentation. See the accompanying review's Task E for the exact command.
2. Before claiming Phase 20 fully closed: exercise or explicitly scope-defer duplicate-delivery idempotency, email-to-application association, OTP extraction, and recruiter-lifecycle verification in production (§4.3). These four are the only remaining open items — encryption-key continuity (§2.2) and the `wrangler.toml` configuration (§3.2) are both now resolved.
