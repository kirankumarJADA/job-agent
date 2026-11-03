# ROBIN — Phase 20 Verification Report

**Prepared:** 2026-11-03, by read-only repository/source inspection (no database, Render, Cloudflare, or Vercel dashboard access available in this session).
**Scope:** Managed-mail (Cloudflare) production readiness, encryption-key safety, Worker/backend reconciliation.
**Verdict:** IMPLEMENTATION COMPLETE, PRODUCTION GATE BLOCKED. Phase 20 is not closed.

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

### 1.2 Why the backend can report `APP_SECRETS_KEY` as missing

`backend/src/main/java/com/personal/jobagent/security/SecretCipher.java:31-38`:

```java
public SecretCipher(@Value("${app.secrets.encryption-key:}") String configuredKey) {
    this.key = configuredKey == null || configuredKey.isBlank()
            ? null : deriveKey(configuredKey.trim());
}
public boolean configured() { return key != null; }
```

`deriveKey()` (lines 85-99) is deliberately forgiving: a non-Base64 value falls back to raw UTF-8 bytes, and any decoded length other than exactly 32 bytes is SHA-256-hashed into a 32-byte key. **There is no format-validation failure path.** The only way `configured()` is `false` is if the environment variable resolves to null/empty on the running JVM. That means the "APP_SECRETS_KEY missing" state can only come from one of:

1. The variable was never added to the Render service that is actually serving traffic.
2. It was added, but the service has not restarted since (Spring reads env once at JVM boot).
3. A name mismatch (case, extra/missing character, stray space) in Render's dashboard.
4. It is set on a different Render service/environment than the one the custom domain currently routes to.

**This cannot be distinguished further from source.** It requires looking at the Render dashboard itself, or the live JSON from `GET /api/v1/managed-mail/address` (see §4).

### 1.3 Provider default risk

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

If `APP_MANAGED_MAIL_PROVIDER` was never explicitly set to `cloudflare` on Render, the backend silently defaults to `mailgun` and checks for `MAILGUN_WEBHOOK_SIGNING_KEY` instead — which is also never configured for this deployment. This alone would produce a `NOT_CONFIGURED` state even if `CLOUDFLARE_EMAIL_WORKER_SECRET` and `APP_SECRETS_KEY` were both correctly set. **This must be ruled in or out using §4 before touching anything else.**

---

## 2. Encryption-key safety (APP_SECRETS_KEY)

### 2.1 Complete inventory of ciphertext columns

Only two classes in the entire backend call `SecretCipher.encrypt`/`decrypt` — confirmed exhaustively by grep across `backend/src/main/java`:

| Table | Column(s) | Migration | Writer |
|---|---|---|---|
| `employer_credentials` | `username_encrypted`, `password_encrypted` | `V040__employer_credentials_and_mailbox_verification.sql:16-17` | `EmployerCredentialService.java` |
| `managed_inbound_messages` | `content_encrypted` | `V042__managed_mail_receiving.sql:77` | `ManagedMessageContent.java` |

No other table, service, or column depends on `APP_SECRETS_KEY`. (Gmail OAuth tokens use a **separate** key, `APP_GMAIL_TOKEN_KEY` via a different cipher class — confirmed not a third consumer.)

### 2.2 Do existing rows depend on a previous key?

**Not determinable from source — requires a database read, which this session does not have.** The logical constraint from the code:

- `managed_inbound_messages.content_encrypted` is written inside `ManagedInboundService.accept()`, which is `@Transactional`. If `SecretCipher.encrypt()` throws because no key is configured, that throw happens *before* the `insert`, so the whole transaction rolls back and `CloudflareInboundController` returns `503` (the provider retries). **While the key is missing, zero rows can be written to this table** — there is no partial/corrupted state possible here.
- `employer_credentials` rows can only exist if a key was configured *at the time they were written*. If rows exist today while the key is reported missing now, that is direct evidence the key **changed or was removed** after those rows were created — and `SecretCipher.decryptInternal()` already anticipates this exact failure with the message *"Credential decryption failed — was APP_SECRETS_KEY rotated?"* (`SecretCipher.java:81`).

**Required next step before any key change:** a read-only row count, e.g. `select count(*) from employer_credentials;` — zero rows means a first-time key is completely safe; any non-zero count means the prior key (if different from whatever is configured now) must be recovered or those specific rows are permanently unreadable and the affected candidates would need to re-enter their employer credentials. I cannot run this query myself — I have no database credentials in this session.

### 2.3 Explicit instruction compliance

Per your directive, **no key has been generated, rotated, or changed, and no environment variable has been modified.** This report only establishes what is true in source.

---

## 3. Worker / backend reconciliation

### 3.1 Confirmed Git state (re-verified this session)

```
git rev-parse HEAD        → 5d893245937e0956fc90b69790ec7b62e12eb79f
git rev-parse origin/master → 5d893245937e0956fc90b69790ec7b62e12eb79f   (identical)
git status --short:
 M GEMINI.md
 M cloudflare-email-worker/wrangler.toml
```

Local `master` exactly matches `origin/master` (both at the PR #9 merge commit). GitHub Actions run #60 on that commit shows "completed successfully." PR #9's own description (by the repo owner) states: *"Live Cloudflare email delivery remains pending and Phase 20 must stay open until it is proven end-to-end"* — independently confirmed on the live GitHub PR page.

### 3.2 The wrangler.toml discrepancy — resolved

The committed `cloudflare-email-worker/wrangler.toml` on `master` still has:

```toml
ROBIN_BACKEND_URL = "https://api.aplivenosi.com"
```

The **local, uncommitted** working copy already has the correct fix:

```diff
+workers_dev = false
+addresses = ["robin@mail.aplivenosi.com"]
-ROBIN_BACKEND_URL = "https://api.aplivenosi.com"
+ROBIN_BACKEND_URL = "https://job-agent-mwhu.onrender.com"
```

This matches what Wrangler actually deployed (per your reported deploy output: backend binding `https://job-agent-mwhu.onrender.com`, version `e3098b1d-00f1-4064-8e5c-61b7553807a1`). **The fix is correct and already live in Cloudflare — it simply has never been committed to Git.** No `wrangler.jsonc`/`wrangler.json` exists anywhere in the repo (confirmed by directory listing and `find`), so `wrangler.toml` is the sole config file and the sole source of truth to reconcile.

There is also an unrelated, pre-existing local modification to `GEMINI.md` (a personal resume-automation prompt file, unrelated to Robin/Phase 20) — preserved, untouched, not part of this commit.

### 3.3 Action taken this session

I committed **only** `cloudflare-email-worker/wrangler.toml` in its own commit, leaving `GEMINI.md`'s modification untouched in the working tree. **I did not push this commit** — pushing to `origin/master` is a production-adjacent, visible-to-others action and your own rule 5 requires explicit approval for "production deployments, production environment changes" — a push to `master` is what Render/Cloudflare treat as a deploy trigger candidate, so I'm treating it the same way. See §6 for the exact pending action.

---

## 4. Live configuration endpoint

`GET /api/v1/managed-mail/address` (`ManagedMailboxController.java:50-55,99-111`) is a candidate-owned, session-authenticated, read-only endpoint. Its JSON response (`view()`, lines 99-111) includes:

```json
{
  "readiness": "NOT_CONFIGURED | PENDING_RECEIVING_VERIFICATION | ACTIVE",
  "provider": "<live resolved APP_MANAGED_MAIL_PROVIDER value>",
  "domain": "...",
  "missing": ["<exact list of env var names the live backend currently lacks>"]
}
```

**I have no way to call this myself** — it requires your authenticated Firebase session cookie, which exists only in your browser, not in this tool session. You previously reported the Applications page shows managed email `NOT_CONFIGURED` and names `APP_SECRETS_KEY` specifically, but I don't have the literal `missing` array or `provider` value yet — those two fields would immediately disambiguate between the four causes in §1.2 and confirm/rule out §1.3's provider-default risk.

**This remains the single highest-value, zero-risk next action** (see §6).

---

## 5. Phase 20 exit criteria — current status

| Criterion | Status |
|---|---|
| Live backend provider + missing-config understood | ❌ Blocked — needs §4 |
| Encryption-key continuity safe | ❌ Blocked — needs a DB row count (§2.2) |
| Worker ↔ backend HMAC config matched | ✅ Names confirmed correct in code; live secret values unverifiable from here |
| Repo config matches intended deployed Worker config | ⚠️ Fix exists locally, committed this session, **not yet pushed** |
| Genuine inbound test email succeeds | ❌ Not attempted — blocked on §1–§4 first |
| Encrypted record persisted + visible in Robin | ❌ Not proven |
| Candidate ownership / application association correct | ❌ Not proven (code path verified, not exercised live) |
| Idempotency / error handling tested | ✅ Unit/integration-tested in `ManagedInboundServiceTest`/`CloudflareManagedMailIT` per prior CI evidence; not tested against a live Cloudflare delivery |
| Tests/build/regression pass | ✅ Confirmed via GitHub Actions run #60 on the current HEAD |
| Deployment state and GitHub state reconciled | ⚠️ Partially — commit made, push pending your approval |

**Phase 20 is not closed.** The blocking items are all read-only lookups you (not me) can perform in under five minutes total, with no risk to anything.

---

## 6. Exact pending actions, smallest first

1. **(Zero risk, ~1 min)** In the browser where you're already logged in, open `https://job-agent-mwhu.onrender.com/api/v1/managed-mail/address` and paste back `missing` and `provider`. This resolves §1.2/§1.3 definitively.
2. **(Zero risk, ~2 min, needs Supabase SQL editor access)** Run `select count(*) from employer_credentials;` and `select count(*) from managed_inbound_messages;` (read-only). This resolves §2.2.
3. **(Your approval needed)** Push the already-made `wrangler.toml` commit to `origin/master`. This does not itself redeploy anything (Cloudflare already has the corrected values live from your manual `wrangler deploy`) — it only makes Git match reality. Confirm when ready.
4. Only after 1–2 come back clean: the controlled real-inbound-email test (steps already detailed in the handover's Priority 4), which is the actual Phase 20 closing action.
