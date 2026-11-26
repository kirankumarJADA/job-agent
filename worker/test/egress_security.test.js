// Stage 3F — adversarial egress / browser-isolation regression suite.
//
// Every test is deterministic and local: targets are loopback listeners on
// 127.0.0.1 (the "attacker" listener counts every TCP connection it receives),
// and DNS answers are injected through the proxy's `lookup` seam, so no public
// service or third-party host is ever contacted.
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import { chromium } from '@playwright/test';
import {
  startEgressProxy, resolveDestination, isBlockedAddress, canonicalHost, evaluateHostName, evaluatePort, EgressDecision,
} from '../src/egress_proxy.js';
import {
  installUniversalNavigationGuard, evaluateUniversalResourceRequest, isBlockedHost, stripCrossOriginHeaders,
  sanitizeUrlForDiagnostic,
} from '../src/universal_form.js';
import { openHardenedBrowser, closeUnexpectedPages } from '../src/browser_worker.js';

// ── helpers ────────────────────────────────────────────────────────────

async function listener(handler = (req, res) => res.end('attacker')) {
  const server = http.createServer(handler);
  const state = { connections: 0, requests: [], upgrades: 0, headers: [] };
  server.on('connection', () => { state.connections += 1; });
  server.on('upgrade', (req, socket) => { state.upgrades += 1; socket.destroy(); });
  server.on('request', (req) => { state.requests.push(req.url); state.headers.push(req.headers); });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return { server, port, origin: `http://127.0.0.1:${port}`, state, close: () => new Promise((r) => { server.closeAllConnections?.(); server.close(() => r()); }) };
}

/** Sends a raw CONNECT through the proxy and returns the status line's code. */
function rawConnect(proxyPort, authority) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(proxyPort, '127.0.0.1', () => {
      socket.write(`CONNECT ${authority} HTTP/1.1\r\nHost: ${authority}\r\n\r\n`);
    });
    let buf = '';
    socket.on('data', (d) => {
      buf += d.toString('latin1');
      const m = /^HTTP\/1\.1 (\d{3})/.exec(buf);
      if (m) { resolve(Number(m[1])); socket.destroy(); }
    });
    socket.on('error', reject);
    setTimeout(() => { socket.destroy(); reject(new Error('timeout')); }, 5000);
  });
}

const settle = (ms = 400) => new Promise((r) => setTimeout(r, ms));

async function hardenedFixture({ appHandler, localTestPorts = null, attacker } = {}) {
  const app = await listener(appHandler);
  const ports = localTestPorts ?? [app.port];
  const opened = await openHardenedBrowser({
    chromiumImpl: chromium, headless: true, egressPolicy: { allowLocalTestHosts: true, localTestPorts: ports },
  });
  const guard = await installUniversalNavigationGuard(opened.context, `${app.origin}/apply`, { allowLocalTestHosts: true });
  const page = await opened.context.newPage();
  const closedPopups = [];
  closeUnexpectedPages(opened.context, page, (url) => closedPopups.push(url));
  return {
    app, attacker, page, guard, closedPopups, ...opened,
    async close() {
      await opened.context.close().catch(() => {});
      await opened.browser.close().catch(() => {});
      await opened.egress.close();
      await app.close();
      await attacker?.close();
    },
  };
}

// ── 1. IP classification (DNS answers, IPv4-mapped IPv6, metadata) ──────

test('blocked address classes: loopback, RFC1918, link-local, CGNAT, metadata, multicast, reserved, IPv6 specials', () => {
  for (const ip of ['127.0.0.1', '127.255.255.254', '10.0.0.1', '172.16.0.1', '172.31.255.255', '192.168.1.1',
    '169.254.169.254', '100.64.0.1', '100.100.100.200', '0.0.0.0', '224.0.0.1', '240.0.0.1', '255.255.255.255',
    '198.18.0.1', '192.0.2.1', '::1', '::', 'fe80::1', 'fe80::1%eth0', 'fc00::1', 'fd00:ec2::254', 'ff02::1',
    '2001:db8::1']) {
    assert.equal(isBlockedAddress(ip), true, `${ip} must be blocked`);
  }
  for (const ip of ['93.184.216.34', '8.8.8.8', '1.1.1.1', '2606:4700:4700::1111', '2a00:1450:4001::200e']) {
    assert.equal(isBlockedAddress(ip), false, `${ip} is public and must be allowed`);
  }
});

test('IPv4 embedded in IPv6 is unwrapped: mapped, NAT64 and 6to4 forms of internal addresses are blocked', () => {
  for (const ip of ['::ffff:127.0.0.1', '::ffff:7f00:1', '::ffff:10.0.0.1', '::ffff:169.254.169.254',
    '64:ff9b::7f00:1', '64:ff9b::a00:1', '2002:7f00:1::1', '2002:a9fe:a9fe::1']) {
    assert.equal(isBlockedAddress(ip), true, `${ip} must be blocked`);
  }
  assert.equal(isBlockedAddress('::ffff:8.8.8.8'), false);
});

test('host canonicalisation closes trailing-dot, numeric-IPv4 and encoding bypasses', () => {
  assert.equal(canonicalHost('localhost.'), 'localhost');
  assert.equal(canonicalHost('LOCALHOST..'), 'localhost');
  assert.equal(canonicalHost('127.0.0.1.'), '127.0.0.1');
  assert.equal(canonicalHost('0177.0.0.1'), '127.0.0.1');
  assert.equal(canonicalHost('2130706433'), '127.0.0.1');
  assert.equal(canonicalHost('0x7f.1'), '127.0.0.1');
  assert.equal(canonicalHost('[::1]'), '::1');
  assert.equal(canonicalHost('user@evil.com'), null);
  assert.equal(canonicalHost('%31%32%37.0.0.1'), null);
  for (const h of ['localhost.', '127.0.0.1.', '0177.0.0.1', '2130706433', '[::ffff:127.0.0.1]', 'metadata.google.internal',
    'svc.cluster.local', 'printer.lan', 'intranet', 'a.localhost']) {
    assert.equal(evaluateHostName(h).allowed, false, `${h} must be refused`);
    assert.equal(isBlockedHost(h, false), true, `${h} must be refused by the page guard`);
  }
  assert.equal(evaluateHostName('careers.example.com').allowed, true);
});

test('port policy: public destinations only on 443; test loopback limited to the allow-listed ports', () => {
  assert.equal(evaluatePort(443, { host: 'example.com' }).allowed, true);
  for (const p of [22, 25, 80, 6379, 8080, 3306, 0, 70000]) {
    assert.equal(evaluatePort(p, { host: 'example.com' }).allowed, false, `port ${p}`);
  }
  assert.equal(evaluatePort(5000, { host: '127.0.0.1', allowLocalTestHosts: true, localTestPorts: [5000] }).allowed, true);
  assert.equal(evaluatePort(5001, { host: '127.0.0.1', allowLocalTestHosts: true, localTestPorts: [5000] }).allowed, false);
  assert.deepEqual(evaluateUniversalResourceRequest({ reqUrl: 'https://evil.com:22/x', expectedUrl: 'https://e.example.com/apply' }),
    { allowed: false, reason: 'BLOCKED_PORT' });
  assert.equal(evaluateUniversalResourceRequest({ reqUrl: 'wss://cdn.example.com:8443/s', expectedUrl: 'https://e.example.com/apply' }).allowed, false);
  assert.equal(evaluateUniversalResourceRequest({ reqUrl: 'https://cdn.example.com/s.js', expectedUrl: 'https://e.example.com/apply' }).allowed, true);
});

// ── 2. DNS resolution and rebinding at the connection boundary ──────────

test('DNS answers resolving to loopback, RFC1918, link-local/metadata, or mixed public+private are refused', async () => {
  const cases = { 'loop.example.com': ['127.0.0.1'], 'rfc1918.example.com': ['10.20.30.40'],
    'meta.example.com': ['169.254.169.254'], 'mixed.example.com': ['93.184.216.34', '192.168.0.10'],
    'mapped.example.com': ['::ffff:127.0.0.1'], 'ula.example.com': ['fd00::1'] };
  const lookup = async (host) => (cases[host] ?? []).map((address) => ({ address, family: net.isIP(address) }));
  for (const host of Object.keys(cases)) {
    const decision = await resolveDestination(host, 443, { lookup });
    assert.equal(decision.allowed, false, host);
    assert.equal(decision.reason, EgressDecision.BLOCKED_ADDRESS, host);
  }
  const ok = await resolveDestination('public.example.com', 443,
    { lookup: async () => [{ address: '93.184.216.34', family: 4 }] });
  assert.deepEqual([ok.allowed, ok.address], [true, '93.184.216.34']);
  const nx = await resolveDestination('nx.example.com', 443, { lookup: async () => { throw new Error('ENOTFOUND'); } });
  assert.equal(nx.reason, EgressDecision.DNS_FAILURE);
});

test('a public name rebound to loopback never reaches the listener (proxy CONNECT)', async () => {
  const attacker = await listener();
  const proxy = await startEgressProxy({ allowedPorts: [attacker.port],
    lookup: async () => [{ address: '127.0.0.1', family: 4 }] });
  try {
    assert.equal(await rawConnect(proxy.port, `rebind.example.com:${attacker.port}`), 403);
    assert.equal(await rawConnect(proxy.port, `127-0-0-1.sslip.io:${attacker.port}`), 403);
    await settle(100);
    assert.equal(attacker.state.connections, 0);
    assert.ok(proxy.getDiagnostics().blockedConnections.every((b) => b.reason === EgressDecision.BLOCKED_ADDRESS));
  } finally {
    await proxy.close();
    await attacker.close();
  }
});

test('DNS rebinding between validation and connection: each connection resolves once and connects to exactly that IP', async () => {
  const target = await listener();
  const answers = [[{ address: '127.0.0.1', family: 4 }], [{ address: '10.0.0.1', family: 4 }]];
  let calls = 0;
  const proxy = await startEgressProxy({ allowLocalTestHosts: true, localTestPorts: [target.port],
    lookup: async () => answers[Math.min(calls++, answers.length - 1)] });
  try {
    assert.equal(await rawConnect(proxy.port, `localhost:${target.port}`), 200);
    assert.equal(calls, 1, 'exactly one resolution per connection — the connect uses that answer, never a second lookup');
    assert.deepEqual(proxy.getDiagnostics().allowedDestinations.map((d) => d.address), ['127.0.0.1']);
    // The "rebind": the next resolution of the same name answers with a private address.
    assert.equal(await rawConnect(proxy.port, `localhost:${target.port}`), 403);
    assert.equal(calls, 2);
    await settle(100);
    assert.equal(target.state.connections, 1, 'only the validated, pinned connection ever reached a socket');
  } finally {
    await proxy.close();
    await target.close();
  }
});

test('proxy refuses IP literals, trailing-dot names, prohibited ports and internal names before any DNS lookup', async () => {
  let lookups = 0;
  const proxy = await startEgressProxy({ lookup: async () => { lookups += 1; return [{ address: '93.184.216.34', family: 4 }]; } });
  try {
    for (const authority of ['127.0.0.1:443', '[::1]:443', '[::ffff:7f00:1]:443', '169.254.169.254:443', 'localhost.:443',
      '127.0.0.1.:443', 'metadata.google.internal:443', 'example.com:22', 'example.com:80', 'example.com:6379']) {
      assert.equal(await rawConnect(proxy.port, authority), 403, authority);
    }
    assert.equal(lookups, 0, 'refusals happen on the canonical name/port, before resolving anything');
    const diag = proxy.getDiagnostics();
    assert.ok(diag.blockedConnections.every((b) => !/[/?#]/.test(b.host)), 'diagnostics carry hosts only, never paths or queries');
  } finally {
    await proxy.close();
  }
});

test('plain-HTTP forwarding is refused outside test loopback, and the request never reaches a socket', async () => {
  const attacker = await listener();
  const proxy = await startEgressProxy({ lookup: async () => [{ address: '127.0.0.1', family: 4 }] });
  try {
    const status = await new Promise((resolve) => {
      const req = http.request({ host: '127.0.0.1', port: proxy.port, method: 'GET',
        path: `http://plain.example.com:${attacker.port}/secret?token=abc` }, (res) => { res.resume(); resolve(res.statusCode); });
      req.on('error', () => resolve(-1));
      req.end();
    });
    assert.equal(status, 403);
    await settle(100);
    assert.equal(attacker.state.connections, 0);
  } finally {
    await proxy.close();
    await attacker.close();
  }
});

// ── 3. Real Chromium: WebSocket, Service Worker, popups, redirects ──────

const APP_PAGE = '<!doctype html><title>app</title><body><div id="s">ready</div></body>';

test('WebSocket to a non-allow-listed loopback port, a private IP, or the metadata IP never connects', async () => {
  const attacker = await listener();
  const fx = await hardenedFixture({ attacker, appHandler: (req, res) => res.end(APP_PAGE) });
  try {
    await fx.page.goto(`${fx.app.origin}/apply`);
    const results = await fx.page.evaluate(async (port) => {
      const attempt = (url) => new Promise((resolve) => {
        let ws;
        try { ws = new WebSocket(url); } catch (e) { resolve('THROWN'); return; }
        ws.onopen = () => resolve('OPENED');
        ws.onerror = () => resolve('ERROR');
        ws.onclose = () => resolve('CLOSED');
        setTimeout(() => resolve('TIMEOUT'), 4000);
      });
      return {
        loopback: await attempt(`ws://127.0.0.1:${port}/ws-probe`),
        private: await attempt('ws://10.255.255.1/ws-probe'),
        metadata: await attempt('ws://169.254.169.254/latest/meta-data'),
      };
    }, attacker.port);
    await settle();
    assert.notEqual(results.loopback, 'OPENED');
    assert.notEqual(results.private, 'OPENED');
    assert.notEqual(results.metadata, 'OPENED');
    assert.equal(attacker.state.connections, 0, 'no TCP connection reached the loopback listener');
    assert.equal(attacker.state.upgrades, 0);
    const blockedWs = fx.guard.getDiagnostics().blockedResources.filter((b) => b.resourceType === 'websocket');
    assert.ok(blockedWs.length >= 2, 'private and metadata WebSockets are refused by the context guard itself');
    assert.ok(fx.egress.getDiagnostics().blockedConnections.some((b) => b.reason === EgressDecision.BLOCKED_PORT),
      'the loopback WebSocket the page guard allowed in test mode was refused at the connection boundary');
  } finally {
    await fx.close();
  }
});

test('negative control: without the hardened launch the same loopback WebSocket probe DOES connect (Stage 3E SEC-1)', async () => {
  // Proves the WebSocket test above is sensitive: an ordinary Chromium launch with only the
  // page-level guard (test mode) lets the socket through, so the egress proxy is what stops it.
  const attacker = await listener();
  const app = await listener((req, res) => res.end(APP_PAGE));
  const browser = await chromium.launch({ headless: true });
  try {
    const context = await browser.newContext();
    await installUniversalNavigationGuard(context, `${app.origin}/apply`, { allowLocalTestHosts: true });
    const page = await context.newPage();
    await page.goto(`${app.origin}/apply`);
    await page.evaluate((port) => new Promise((resolve) => {
      const ws = new WebSocket(`ws://127.0.0.1:${port}/ws-probe`);
      ws.onopen = ws.onerror = ws.onclose = () => resolve();
      setTimeout(resolve, 3000);
    }), attacker.port);
    await settle(300);
    assert.ok(attacker.state.connections >= 1, 'control must reach the listener, otherwise the hardened test proves nothing');
  } finally {
    await browser.close();
    await app.close();
    await attacker.close();
  }
});

test('Service Worker registration is blocked, so a worker fetch cannot bypass the network policy', async () => {
  const attacker = await listener();
  const fx = await hardenedFixture({ attacker, appHandler: (req, res) => {
    if (req.url === '/sw.js') {
      res.setHeader('content-type', 'application/javascript');
      res.end(`self.addEventListener('install', (e) => e.waitUntil(fetch('http://127.0.0.1:${attacker.port}/from-sw').catch(() => {})));`);
      return;
    }
    res.end(APP_PAGE);
  } });
  try {
    await fx.page.goto(`${fx.app.origin}/apply`);
    const outcome = await fx.page.evaluate(async () => {
      if (!('serviceWorker' in navigator)) return 'UNAVAILABLE';
      try {
        await Promise.race([navigator.serviceWorker.register('/sw.js'), new Promise((_, rej) => setTimeout(() => rej(new Error('t')), 3000))]);
        return navigator.serviceWorker.controller ? 'CONTROLLED' : 'REGISTERED_UNCONTROLLED';
      } catch {
        return 'REJECTED';
      }
    });
    await settle(1500);
    assert.notEqual(outcome, 'CONTROLLED');
    assert.equal(attacker.state.connections, 0, 'no Service Worker request reached the listener');
    assert.ok(!fx.app.state.requests.includes('/sw.js') || attacker.state.requests.length === 0);
  } finally {
    await fx.close();
  }
});

test('window.open / target=_blank popups are closed and their navigation to loopback never connects', async () => {
  const attacker = await listener();
  const fx = await hardenedFixture({ attacker, appHandler: (req, res) => res.end(
    `<!doctype html><a id="blank" target="_blank" href="http://127.0.0.1:${attacker.port}/blank-tab">x</a>`) });
  try {
    await fx.page.goto(`${fx.app.origin}/apply`);
    await fx.page.evaluate((port) => { window.open(`http://127.0.0.1:${port}/cross-origin-popup`); }, attacker.port);
    await fx.page.click('#blank');
    await settle(1500);
    assert.equal(attacker.state.connections, 0);
    assert.ok(fx.closedPopups.length >= 1, 'secondary pages are closed');
    assert.equal(fx.browser.contexts()[0].pages().length, 1, 'only the automation page remains');
  } finally {
    await fx.close();
  }
});

test('a popup that redirects to a private network is refused at the redirect hop and by the egress proxy', async () => {
  const fx = await hardenedFixture({ appHandler: (req, res) => {
    if (req.url === '/to-private') { res.writeHead(302, { location: 'http://10.0.0.1/admin' }).end(); return; }
    if (req.url === '/to-metadata') { res.writeHead(302, { location: 'http://169.254.169.254/latest/meta-data/' }).end(); return; }
    res.end(APP_PAGE);
  } });
  try {
    await fx.page.goto(`${fx.app.origin}/apply`);
    // Same-page navigation exercises the hop check deterministically (popups are also closed on sight).
    await fx.page.goto(`${fx.app.origin}/to-private`).catch(() => {});
    await fx.page.goto(`${fx.app.origin}/to-metadata`).catch(() => {});
    await fx.page.evaluate((o) => window.open(`${o}/to-private`), fx.app.origin);
    await settle(800);
    const reasons = fx.guard.getDiagnostics().blockedResources.map((b) => b.reason);
    assert.ok(reasons.some((r) => /REDIRECT/.test(r)), `redirect hops are refused: ${reasons.join(',')}`);
    const allowed = fx.egress.getDiagnostics().allowedDestinations.map((d) => d.address);
    assert.ok(allowed.every((a) => a === '127.0.0.1'), 'nothing but the allow-listed loopback fixture was ever connected to');
  } finally {
    await fx.close();
  }
});

test('cross-origin redirects drop Authorization/Cookie/CSRF headers; same-origin redirects keep them', async () => {
  // Two loopback origins, both allow-listed at the connection boundary.
  const other = await listener((req, res) => res.end('other'));
  const app = await listener((req, res) => {
    if (req.url === '/redir-cross') { res.writeHead(302, { location: `${other.origin}/echo` }).end(); return; }
    if (req.url === '/redir-same') { res.writeHead(302, { location: '/echo-same' }).end(); return; }
    res.end(APP_PAGE);
  });
  const opened = await openHardenedBrowser({ chromiumImpl: chromium, headless: true,
    egressPolicy: { allowLocalTestHosts: true, localTestPorts: [app.port, other.port] } });
  try {
    await installUniversalNavigationGuard(opened.context, `${app.origin}/apply`, { allowLocalTestHosts: true });
    const page = await opened.context.newPage();
    await page.goto(`${app.origin}/apply`);
    await page.evaluate(async () => {
      const h = { authorization: 'Bearer s3cr3t-token', 'x-csrf-token': 'csrf-123' };
      await fetch('/redir-same', { headers: h }).catch(() => {});
      await fetch('/redir-cross', { headers: h, mode: 'no-cors' }).catch(() => {});
    });
    await settle(500);
    const sameIdx = app.state.requests.indexOf('/echo-same');
    assert.ok(sameIdx >= 0, 'the same-origin redirect target was fetched');
    assert.equal(app.state.headers[sameIdx].authorization, 'Bearer s3cr3t-token');
    const crossIdx = other.state.requests.indexOf('/echo');
    assert.ok(crossIdx >= 0, 'the cross-origin redirect target was fetched');
    assert.equal(other.state.headers[crossIdx].authorization, undefined);
    assert.equal(other.state.headers[crossIdx]['x-csrf-token'], undefined);
    assert.equal(other.state.headers[crossIdx].cookie, undefined);
  } finally {
    await opened.context.close().catch(() => {});
    await opened.browser.close().catch(() => {});
    await opened.egress.close();
    await app.close();
    await other.close();
  }
});

// ── 4. No path around the proxy: public names are resolved BY THE PROXY ─────────────
//
// If Chromium or Playwright's route.fetch() resolved a public hostname and connected
// themselves, the proxy would never see the name and DNS pinning would be bypassed.
// The proxy below answers every lookup with 127.0.0.1 (a rebinding attack). Seeing the
// name arrive at the proxy — and be refused there as BLOCKED_ADDRESS — proves the
// request took the proxied path; the name does not exist in real DNS.

async function rebindingBrowser({ guard }) {
  const app = await listener((req, res) => res.end(APP_PAGE));
  const lookups = [];
  const opened = await openHardenedBrowser({ chromiumImpl: chromium, headless: true, egressPolicy: {
    allowLocalTestHosts: true, localTestPorts: [app.port],
    lookup: async (host) => { lookups.push(host); return [{ address: '127.0.0.1', family: 4 }]; },
  } });
  const guardHandle = guard
    ? await installUniversalNavigationGuard(opened.context, `${app.origin}/apply`, { allowLocalTestHosts: true }) : null;
  const page = await opened.context.newPage();
  await page.goto(`${app.origin}/apply`);
  return { app, lookups, page, guard: guardHandle, ...opened, async close() {
    await opened.context.close().catch(() => {}); await opened.browser.close().catch(() => {});
    await opened.egress.close(); await app.close();
  } };
}

test('Chromium sends public hostnames to the proxy, which resolves and refuses a rebound answer (no direct path)', async () => {
  const fx = await rebindingBrowser({ guard: false }); // dedicated-ATS plans have no context guard: proxy only
  try {
    const outcome = await fx.page.evaluate(() => fetch('https://rebind-direct.robin-test.example/secret', { mode: 'no-cors' })
      .then(() => 'FETCHED', () => 'REFUSED'));
    assert.equal(outcome, 'REFUSED');
    assert.ok(fx.lookups.includes('rebind-direct.robin-test.example'), 'the proxy, not Chromium, resolved the name');
    assert.ok(fx.egress.getDiagnostics().blockedConnections
      .some((b) => b.host === 'rebind-direct.robin-test.example' && b.reason === EgressDecision.BLOCKED_ADDRESS));
    assert.ok(fx.egress.getDiagnostics().allowedDestinations.every((d) => d.address === '127.0.0.1' && d.port === fx.app.port),
      'the only allowed connections are the allow-listed fixture');
  } finally {
    await fx.close();
  }
});

test('Playwright route.fetch() (the UNIVERSAL guard path) also goes through the proxy and is refused on a rebound answer', async () => {
  const fx = await rebindingBrowser({ guard: true });
  try {
    const outcome = await fx.page.evaluate(() => fetch('https://rebind-routed.robin-test.example/bundle.js', { mode: 'no-cors' })
      .then(() => 'FETCHED', () => 'REFUSED'));
    assert.equal(outcome, 'REFUSED');
    assert.ok(fx.lookups.includes('rebind-routed.robin-test.example'), 'route.fetch() handed the name to the proxy');
    assert.ok(fx.guard.getDiagnostics().blockedResources.some((b) => b.reason === 'EGRESS_BLOCKED_ADDRESS'),
      'the proxy refusal is surfaced as an explicit, recorded abort — never a synthetic empty response');
    assert.ok(fx.egress.getDiagnostics().blockedConnections
      .some((b) => b.host === 'rebind-routed.robin-test.example' && b.reason === EgressDecision.BLOCKED_ADDRESS));
  } finally {
    await fx.close();
  }
});

test('stripCrossOriginHeaders removes every credential-bearing header and keeps the rest', () => {
  const out = stripCrossOriginHeaders({ Authorization: 'a', cookie: 'c', 'Proxy-Authorization': 'p', 'x-csrf-token': 'x',
    'X-XSRF-TOKEN': 'y', 'x-api-key': 'k', accept: 'text/html', 'user-agent': 'ua' });
  assert.deepEqual(out, { accept: 'text/html', 'user-agent': 'ua' });
});

test('diagnostic URLs never carry query strings, fragments or userinfo', () => {
  assert.equal(sanitizeUrlForDiagnostic('https://user:pw@careers.example.com/apply?token=abc&email=a@b.c#frag'),
    'https://careers.example.com/apply');
});
