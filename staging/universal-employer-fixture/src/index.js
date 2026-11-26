/**
 * Robin synthetic employer portal — Cloudflare Worker + Durable Object entry.
 *
 * NOT DEPLOYED. Deploying requires the operator authorisation described in
 * docs/STAGE_3G_CONTROLLED_EXTERNAL_VALIDATION.md and ../RUNBOOK.md.
 *
 * Safety properties enforced here (and unit-tested in worker/test/staging_fixture_package.test.js):
 *  - Serves ONLY the configured FIXTURE_HOSTNAME over HTTPS; any other Host
 *    (including *.workers.dev) is refused with 421, plain HTTP is redirected.
 *  - Only paths under FIXTURE_PATH_PREFIX + a known scenario slug are served.
 *  - Never fetches anything: no proxying, no outbound calls (not a relay).
 *  - All state lives in one Durable Object per scenario, so the idempotency
 *    counter is strongly consistent and survives isolate restarts.
 *  - Diagnostics and teardown require `Authorization: Bearer <FIXTURE_ADMIN_TOKEN>`
 *    (a Wrangler secret); with no token configured they are refused.
 *  - Every response carries HSTS, noindex and a restrictive CSP.
 *  - Data expires: an alarm wipes the scenario's storage DATA_TTL_HOURS after
 *    its first write (default 24 h).
 */
import { createUniversalEmployerApp } from './app.js';

/** Scenario slug -> fixture options. Slugs map to the Stage 3E §5 matrix rows. */
export const SCENARIO_PROFILES = Object.freeze({
  's01-multistep': {},
  's02-cdn': {},
  's03-uploads': { includeCoverLetterField: true, customDropzone: true, coverLetterAccept: '.pdf' },
  's03-uploads-docx': { includeCoverLetterField: true, customDropzone: true, coverLetterAccept: '.docx' },
  's04-conditional': { conditionalField: true },
  's05-combobox': { portalCombobox: true },
  's06-shadow': { shadowDom: true },
  's07-login': { loginWallMode: true },
  's08-mfa': { mfaMode: true },
  's09-unknown-question': {},
  's10-answer-reuse': {},
  's11-captcha': { captchaMode: 'turnstile-test', loadOfficialCaptchaScript: true },
  's12-redirect': { redirectTarget: 'http://169.254.169.254/latest/meta-data/' },
  's13-confirmation': { idempotent: true, uniqueReceipts: true },
  's13-validation-error': { validationErrorOnSubmit: true },
  's13-ok-false': { okFalseOnSubmit: true },
  's13-fake-banner': { fakeBannerWithoutPost: true },
  's14-duplicate': { idempotent: true, uniqueReceipts: true },
  's15-evidence': { idempotent: true, uniqueReceipts: true },
});

export const SECURITY_HEADERS = Object.freeze({
  'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
  'X-Robots-Tag': 'noindex, nofollow, noarchive',
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'Cache-Control': 'no-store',
  'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline' https://challenges.cloudflare.com; "
    + "frame-src https://challenges.cloudflare.com; connect-src 'self'; img-src 'self' data:; form-action 'self'; "
    + "base-uri 'none'; frame-ancestors 'none'",
});

function withSecurityHeaders(response) {
  const headers = new Headers(response.headers);
  for (const [k, v] of Object.entries(SECURITY_HEADERS)) headers.set(k, v);
  return new Response(response.body, { status: response.status, headers });
}

function plain(status, text, extra = {}) {
  return withSecurityHeaders(new Response(text, { status, headers: { 'Content-Type': 'text/plain', ...extra } }));
}

function constantTimeEquals(a, b) {
  const x = new TextEncoder().encode(String(a));
  const y = new TextEncoder().encode(String(b));
  if (x.length !== y.length) return false;
  let diff = 0;
  for (let i = 0; i < x.length; i += 1) diff |= x[i] ^ y[i];
  return diff === 0;
}

function isAdmin(request, env) {
  const token = env.FIXTURE_ADMIN_TOKEN;
  if (!token || String(token).length < 32) return false; // unset or weak: admin endpoints are closed
  const header = request.headers.get('authorization') || '';
  return header.startsWith('Bearer ') && constantTimeEquals(header.slice(7).trim(), token);
}

/** Parses `/<prefix>/<slug>/<rest>`; returns null for anything outside the fixture. */
export function routeFor(url, prefix) {
  const p = String(prefix || '').replace(/\/+$/, '');
  if (!p.startsWith('/') || !url.pathname.startsWith(`${p}/`)) return null;
  const rest = url.pathname.slice(p.length + 1);
  const slash = rest.indexOf('/');
  const slug = slash === -1 ? rest : rest.slice(0, slash);
  if (slug === '__robin') return { admin: rest.slice(slash + 1) };
  if (!Object.prototype.hasOwnProperty.call(SCENARIO_PROFILES, slug)) return null;
  return { slug, basePath: `${p}/${slug}/careers/jobs/stage3-role` };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const expectedHost = String(env.FIXTURE_HOSTNAME || '').toLowerCase();
    if (!expectedHost || url.hostname.toLowerCase() !== expectedHost) {
      return plain(421, 'misdirected request');
    }
    if (url.protocol !== 'https:') {
      return withSecurityHeaders(new Response(null, { status: 308, headers: { Location: url.href.replace(/^http:/, 'https:') } }));
    }
    if (url.pathname === '/robots.txt') return plain(200, 'User-agent: *\nDisallow: /\n');
    const route = routeFor(url, env.FIXTURE_PATH_PREFIX);
    if (!route) return plain(404, 'Not found');
    if (route.admin !== undefined) {
      if (!isAdmin(request, env)) return plain(401, 'unauthorised');
      const slug = new URL(request.url).searchParams.get('scenario');
      if (!slug || !Object.prototype.hasOwnProperty.call(SCENARIO_PROFILES, slug)) return plain(400, 'unknown scenario');
      const stub = env.FIXTURE_STATE.get(env.FIXTURE_STATE.idFromName(slug));
      return withSecurityHeaders(await stub.fetch(new Request(`https://do.internal/__robin/${route.admin}?scenario=${slug}`,
        { method: request.method })));
    }
    const stub = env.FIXTURE_STATE.get(env.FIXTURE_STATE.idFromName(route.slug));
    const forwarded = new Request(request, { headers: new Headers(request.headers) });
    forwarded.headers.set('x-robin-scenario', route.slug);
    return withSecurityHeaders(await stub.fetch(forwarded));
  },
};

/** One instance per scenario slug: the fixture app with Durable Object storage. */
export class FixtureState {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.apps = new Map();
  }

  appFor(slug) {
    if (!this.apps.has(slug)) {
      const prefix = String(this.env.FIXTURE_PATH_PREFIX || '').replace(/\/+$/, '');
      this.apps.set(slug, createUniversalEmployerApp({
        ...SCENARIO_PROFILES[slug],
        basePath: `${prefix}/${slug}/careers/jobs/stage3-role`,
        receiptPrefix: `ROBIN-${slug.toUpperCase()}`,
        store: this.state.storage,
      }));
    }
    return this.apps.get(slug);
  }

  async scheduleExpiry() {
    if (await this.state.storage.getAlarm?.()) return;
    const hours = Math.min(Math.max(Number(this.env.DATA_TTL_HOURS || 24), 1), 72);
    await this.state.storage.setAlarm?.(Date.now() + hours * 3600_000);
  }

  async alarm() {
    await this.state.storage.deleteAll();
    this.apps.clear();
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname.startsWith('/__robin/')) {
      const slug = url.searchParams.get('scenario');
      const action = url.pathname.slice('/__robin/'.length);
      if (action === 'diagnostics' && request.method === 'GET') {
        return new Response(JSON.stringify(await this.appFor(slug).diagnostics()), { headers: { 'Content-Type': 'application/json' } });
      }
      if (action === 'teardown' && request.method === 'POST') {
        await this.appFor(slug).reset();
        this.apps.delete(slug);
        return new Response(JSON.stringify({ tornDown: slug }), { headers: { 'Content-Type': 'application/json' } });
      }
      return new Response('Not found', { status: 404 });
    }
    const slug = request.headers.get('x-robin-scenario');
    if (!slug || !Object.prototype.hasOwnProperty.call(SCENARIO_PROFILES, slug)) return new Response('Not found', { status: 404 });
    await this.scheduleExpiry();
    return this.appFor(slug).handle(request);
  }
}
