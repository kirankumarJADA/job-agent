# External integration dependencies

Local/mock execution is complete and fail-closed. The following integrations require credentials or provider infrastructure and were not contacted during local verification:

- **Gmail:** OAuth client credentials and a dedicated application mailbox with read-only scope. Configure `GMAIL_CREDENTIAL_REF`; the provider refuses to poll when absent.
- **Outlook:** Microsoft OAuth application/client configuration and a dedicated mailbox. Configure `OUTLOOK_CREDENTIAL_REF`; the provider refuses to poll when absent.
- **IMAP:** TLS IMAP endpoint, dedicated mailbox, and an opaque credential reference. Configure `IMAP_CREDENTIAL_REF`; the provider refuses to poll when absent.
- **NVIDIA NIM:** reachable OpenAI-compatible NIM endpoint and `NIM_API_KEY` plus `NIM_BASE_URL`.
- **Gemini:** `GEMINI_API_KEY`; the implementation uses Google's `generateContent` endpoint.
- **Groq:** `GROQ_API_KEY` and optional `GROQ_BASE_URL`; local routing falls back to the simulated provider when unavailable.
- **Real employer ATS execution:** requires explicit user authorization, approved site policy, and an employer-specific test environment. CAPTCHA, anti-bot, access-control challenges, and real submissions remain hard stops.

No password, OAuth token, OTP, cookie, or mailbox content is stored in source control or emitted in logs. Local E2E uses only the mock employer and mock mailbox.

## Frontend dependencies (audited 2026-09-18)

- `react-router-dom` 6.30.6 → 2 moderate advisories (GHSA-wrjc-x8rr-h8h6 open redirect via backslash in `Link`/`useNavigate`; GHSA-337j-9hxr-rhxg SSR `deserializeErrors` constructor injection). The only patched line is 7.18.x, a breaking major upgrade. The SSR `deserializeErrors` advisory does not apply: this app is a client-side Vite SPA with no React Router SSR hydration. The open-redirect advisory requires rendering attacker-controlled hrefs through `Link`/`useNavigate`; the app navigates only internal routes. **Decision: no breaking upgrade was forced.** Required remediation: planned React Router 7 migration, plus a grep of the codebase for any external URL ever passed to `Link`/`useNavigate` before shipping.
