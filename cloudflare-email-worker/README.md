# Robin — Cloudflare Email Routing Worker

Dedicated Email Routing Worker for Robin AI Job Agent (`mail.aplivenosi.com`).

---

## 1. Architecture & Subdomain Limitation

### Cloudflare Subdomain Limitation
In Cloudflare Email Routing, subdomains (e.g., `mail.aplivenosi.com`) **do not support catch-all (`*@subdomain`) rules**.
Only root apex domains support wildcard catch-all rules.

### Solution: Single Literal Rule + Plus-Subaddressing
Cloudflare Email Routing natively supports subaddressing (plus-addressing) on custom addresses.
By creating **one literal routing rule** on Cloudflare for:
```text
robin@mail.aplivenosi.com  ───> Action: Send to Worker "robin-email-router"
```
Cloudflare automatically routes all candidate-specific plus-addresses:
```text
robin+<candidate-unique-token>@mail.aplivenosi.com
```
directly to this worker!

### Worker Lifecycle:
1. Candidate applies to a job; Robin generates and provides their unique contact alias: `robin+<unique-token>@mail.aplivenosi.com`.
2. Employer sends recruiter emails, acknowledgements, or interview invitations to `robin+<unique-token>@mail.aplivenosi.com`.
3. Cloudflare MX servers receive the email and trigger this Email Worker via the `email(message, env, ctx)` handler.
4. The worker validates message size, parses MIME headers and body, computes an HMAC-SHA256 signature using `CLOUDFLARE_EMAIL_WORKER_SECRET`, and forwards the payload to Robin's backend:
   ```text
   POST https://<robin-backend-host>/api/v1/managed-mail/inbound/cloudflare
   ```
5. Robin backend authenticates the HMAC signature, verifies replay window/token uniqueness, decrypts and stores the message, attributes it to the candidate's application in TRACK, and updates timeline events.
6. If the recipient is unknown or malformed, the backend returns HTTP 406, and the worker rejects the message via `message.setReject()`.
7. If the backend is temporarily unreachable, the worker throws an error, causing Cloudflare Email Routing to retry delivery according to standard SMTP retry protocols.

---

## 2. Cloudflare Dashboard Setup & Deployment Instructions

> [!IMPORTANT]
> **Operator Action Required**: These configuration steps must be performed in the Cloudflare Dashboard for domain `aplivenosi.com`.
> **DNS Status Note**: When Cloudflare Email Routing is enabled for `mail.aplivenosi.com`, Cloudflare **automatically provisions and locks** the required MX records (`route1.mx.cloudflare.net`, `route2.mx.cloudflare.net`, `route3.mx.cloudflare.net`) and SPF TXT record in the DNS table.
> **DO NOT add duplicate manual MX records** if these records already appear as locked/managed in your DNS dashboard.

### Step 2.1: Verify DNS Records for `mail.aplivenosi.com`
In Cloudflare Dashboard → **aplivenosi.com** → **DNS** → **Records**, verify the locked Email Routing records:
- **MX**: `mail.aplivenosi.com` pointing to `route1.mx.cloudflare.net`, `route2.mx.cloudflare.net`, `route3.mx.cloudflare.net`.
- **TXT**: `mail.aplivenosi.com` with `v=spf1 include:_spf.mx.cloudflare.net ~all`.
*(If already enabled and locked by Cloudflare, leave them untouched).*

### Step 2.2: Ensure Subaddressing Is Enabled
In Cloudflare Dashboard → **aplivenosi.com** → **Email Routing** → **Settings**:
- Verify that Email Routing status is **Active** for `mail.aplivenosi.com`.
- Confirm that **Subaddressing** (plus-addressing) is enabled.

### Step 2.3: Configure Shared HMAC Webhook Secret
Generate a strong 32-byte cryptographically random shared secret:
```bash
openssl rand -base64 32
```
Save this exact value on **both sides**:
1. On **Robin Backend** (Environment variables):
   ```text
   APP_MANAGED_MAIL_PROVIDER=cloudflare
   APP_MANAGED_MAIL_DOMAIN=mail.aplivenosi.com
   APP_MANAGED_MAIL_ADDRESS_PREFIX=robin+
   APP_MANAGED_MAIL_CLOUDFLARE_WEBHOOK_SECRET=<your-generated-secret>
   APP_SECRETS_KEY=<your-32-byte-base64-encryption-key>
   ```
2. On **Cloudflare Worker**:
   ```bash
   cd cloudflare-email-worker
   npx wrangler secret put CLOUDFLARE_EMAIL_WORKER_SECRET
   # Paste the secret when prompted
   ```

### Step 2.4: Deploy the Worker
Update `ROBIN_BACKEND_URL` in `wrangler.toml` to your live Robin backend host (e.g. `https://job-agent-backend.onrender.com` or your custom API domain), then deploy:
```bash
npx wrangler deploy
```

### Step 2.5: Configure the Literal Base Routing Rule
In Cloudflare Dashboard → **aplivenosi.com** → **Email Routing** → **Routes**:
1. Click **Create rule**:
   - **Type**: Custom address
   - **Custom address**: `robin` @ `mail.aplivenosi.com`
   - **Action**: Send to a Worker
   - **Destination worker**: `robin-email-router`
2. Save and enable the rule.

With this literal rule in place and subaddressing enabled, Cloudflare Email Routing delivers all `robin+<token>@mail.aplivenosi.com` inbound emails to `robin-email-router`.

---

## 3. Local Verification

To run the worker unit tests locally:
```bash
npm test --prefix cloudflare-email-worker
```
Test suite verifies:
- Delivery to backend with valid HMAC-SHA256 signature and timestamp.
- Size limit rejection (`MAX_EMAIL_BYTES`).
- Rejection handling when backend returns HTTP 406 (`message.setReject`).
- Retry trigger when backend returns HTTP 503 or transient network failure.
- MIME parsing of multiline unfolded headers and multipart plain/html bodies.
