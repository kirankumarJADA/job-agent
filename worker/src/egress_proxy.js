/**
 * Stage 3F — connection-level egress enforcement for the Playwright worker.
 *
 * Why this exists: hostname-string checks inside `page.route()` cannot stop
 * DNS rebinding (a public name that resolves to 127.0.0.1 / 10.x / metadata),
 * and `page.route()` never sees WebSocket upgrades, Service Worker fetches or
 * popups created outside the guarded page. This module is a loopback-only
 * HTTP/HTTPS forward proxy that Chromium is launched behind for every plan.
 * For EVERY outbound connection — documents, subresources, redirects, popups,
 * workers, WebSockets, and Playwright's own `route.fetch()` — it:
 *
 *   1. canonicalises the destination host (lower-case, trailing dots removed,
 *      IPv4 numeric forms normalised by the WHATWG URL parser);
 *   2. enforces the scheme/port policy;
 *   3. resolves the name ITSELF (all A and AAAA records) and rejects the
 *      connection if ANY resolved address is loopback, private, link-local,
 *      CGNAT, multicast, unspecified, reserved, documentation, or a cloud
 *      metadata address — including IPv4 embedded in IPv4-mapped, NAT64 and
 *      6to4 IPv6 addresses;
 *   4. opens the TCP socket to the exact IP it validated (DNS pinning), so a
 *      second, different DNS answer can never be what Chromium connects to.
 *
 * TLS stays end-to-end between Chromium and the employer (CONNECT tunnel), so
 * certificate validation is unchanged. The proxy never sees plaintext HTTPS,
 * cookies or credentials of HTTPS traffic, and never logs URLs or headers.
 *
 * Limits (documented, not hidden): this governs connections made by the
 * Chromium process and by Playwright's request API. It does not govern other
 * processes on the host; a host/network firewall remains the defence for
 * those. WebRTC UDP is not proxyable and is disabled separately (launch flags
 * + an init script in browser_worker.js).
 */
import http from 'node:http';
import net from 'node:net';
import dns from 'node:dns';
import { domainToASCII } from 'node:url';

/**
 * Header the proxy puts on every refusal it generates. Chromium treats a refused
 * CONNECT as a tunnel error, but Playwright's route.fetch() surfaces the proxy's
 * 403 as an ordinary response; the guard uses this marker to turn it into an
 * explicit abort. (A real server sending it could only abort its own resource.)
 */
export const EGRESS_BLOCKED_HEADER = 'x-robin-egress';

export const EgressDecision = Object.freeze({
  ALLOWED: 'ALLOWED',
  BLOCKED_HOST: 'BLOCKED_HOST',
  BLOCKED_PORT: 'BLOCKED_PORT',
  BLOCKED_SCHEME: 'BLOCKED_SCHEME',
  BLOCKED_ADDRESS: 'BLOCKED_ADDRESS',
  DNS_FAILURE: 'DNS_FAILURE',
  MALFORMED: 'MALFORMED',
});

/** Public destinations are only ever reached on the standard web ports. */
export const PRODUCTION_ALLOWED_PORTS = Object.freeze([443]);

const BLOCKED_SUFFIXES = ['.localhost', '.local', '.internal', '.lan', '.home.arpa', '.intranet', '.corp',
  '.private', '.localdomain'];
const BLOCKED_NAMES = new Set(['localhost', 'localhost.localdomain', 'ip6-localhost', 'ip6-loopback',
  'metadata', 'metadata.google.internal', 'instance-data', 'instance-data.ec2.internal', 'kubernetes',
  'kubernetes.default', 'kubernetes.default.svc']);

const blockList = (() => {
  const b = new net.BlockList();
  const v4 = [
    ['0.0.0.0', 8], ['10.0.0.0', 8], ['100.64.0.0', 10], ['127.0.0.0', 8], ['169.254.0.0', 16],
    ['172.16.0.0', 12], ['192.0.0.0', 24], ['192.0.2.0', 24], ['192.88.99.0', 24], ['192.168.0.0', 16],
    ['198.18.0.0', 15], ['198.51.100.0', 24], ['203.0.113.0', 24], ['224.0.0.0', 4], ['240.0.0.0', 4],
  ];
  for (const [addr, prefix] of v4) b.addSubnet(addr, prefix, 'ipv4');
  const v6 = [
    ['::', 128], ['::1', 128], ['::', 96] /* deprecated IPv4-compatible */, ['100::', 64],
    ['2001::', 23] /* IETF special incl. Teredo, benchmarking, ORCHID */, ['2001:db8::', 32],
    ['fc00::', 7] /* ULA incl. fd00:ec2::254 metadata */, ['fe80::', 10], ['fec0::', 10], ['ff00::', 8],
  ];
  for (const [addr, prefix] of v6) b.addSubnet(addr, prefix, 'ipv6');
  return b;
})();

function expandIpv6(address) {
  // Returns 8 hextets as numbers, handling '::' and an embedded dotted-quad tail.
  let a = address.toLowerCase();
  const zone = a.indexOf('%');
  if (zone >= 0) a = a.slice(0, zone);
  let tail = [];
  const lastColon = a.lastIndexOf(':');
  const maybeV4 = a.slice(lastColon + 1);
  if (net.isIPv4(maybeV4)) {
    const o = maybeV4.split('.').map(Number);
    tail = [(o[0] << 8) | o[1], (o[2] << 8) | o[3]];
    a = a.slice(0, lastColon + 1) + '0:0';
  }
  const [head, rest] = a.split('::');
  const h = head ? head.split(':') : [];
  const r = rest !== undefined ? (rest ? rest.split(':') : []) : null;
  let parts;
  if (r === null) parts = h;
  else parts = [...h, ...Array(8 - h.length - r.length).fill('0'), ...r];
  const nums = parts.map((p) => parseInt(p || '0', 16));
  if (tail.length) { nums[6] = tail[0]; nums[7] = tail[1]; }
  return nums;
}

function embeddedIpv4(address) {
  const h = expandIpv6(address);
  if (h.length !== 8 || h.some((x) => Number.isNaN(x))) return null;
  const v4 = (hi, lo) => `${hi >> 8}.${hi & 0xff}.${lo >> 8}.${lo & 0xff}`;
  // ::ffff:a.b.c.d (IPv4-mapped)
  if (h.slice(0, 5).every((x) => x === 0) && h[5] === 0xffff) return v4(h[6], h[7]);
  // 64:ff9b::/96 (NAT64 well-known) and 64:ff9b:1::/48 (local-use NAT64)
  if (h[0] === 0x64 && h[1] === 0xff9b) return v4(h[6], h[7]);
  // 2002:AABB:CCDD::/48 (6to4)
  if (h[0] === 0x2002) return v4(h[1], h[2]);
  return null;
}

/** True when the literal IP address must never be connected to. */
export function isBlockedAddress(address) {
  const ip = String(address ?? '').replace(/^\[|\]$/g, '');
  if (net.isIPv4(ip)) return blockList.check(ip, 'ipv4');
  if (net.isIPv6(ip)) {
    const v4 = embeddedIpv4(ip);
    if (v4 && blockList.check(v4, 'ipv4')) return true;
    return blockList.check(ip.replace(/%.*$/, ''), 'ipv6');
  }
  return true; // not an IP at all: refuse rather than guess
}

/**
 * Canonical host for policy decisions: punycode, lower-case, trailing dots
 * stripped, brackets removed, IPv4 numeric shorthands normalised by the URL
 * parser. Returns null for anything that does not survive canonicalisation.
 */
export function canonicalHost(rawHost) {
  if (rawHost === null || rawHost === undefined) return null;
  let h = String(rawHost).trim();
  if (!h || /[\s/\\@?#%]/.test(h.replace(/^\[.*\]$/, ''))) return null;
  if (h.startsWith('[') && h.endsWith(']')) {
    const inner = h.slice(1, -1);
    return net.isIPv6(inner) ? inner.toLowerCase() : null;
  }
  if (net.isIPv6(h)) return h.toLowerCase();
  let parsed;
  try {
    parsed = new URL(`http://${h}/`);
  } catch {
    return null;
  }
  h = parsed.hostname.replace(/\.+$/, '');
  if (!h) return null;
  if (!net.isIP(h)) {
    const ascii = domainToASCII(h);
    if (!ascii) return null;
    h = ascii.toLowerCase();
  }
  return h;
}

/** Name-only policy (before DNS): blocks internal names and any IP literal unless test-loopback. */
export function evaluateHostName(host, { allowLocalTestHosts = false } = {}) {
  const h = canonicalHost(host);
  if (!h) return { allowed: false, reason: EgressDecision.MALFORMED };
  if (net.isIP(h)) {
    if (allowLocalTestHosts && isTestLoopback(h)) return { allowed: true, reason: EgressDecision.ALLOWED, host: h };
    // Public IP literals are refused: employer sites are reached by name.
    return { allowed: false, reason: isBlockedAddress(h) ? EgressDecision.BLOCKED_ADDRESS : EgressDecision.BLOCKED_HOST, host: h };
  }
  if (allowLocalTestHosts && h === 'localhost') return { allowed: true, reason: EgressDecision.ALLOWED, host: h };
  if (BLOCKED_NAMES.has(h) || BLOCKED_SUFFIXES.some((s) => h.endsWith(s)) || !h.includes('.')) {
    return { allowed: false, reason: EgressDecision.BLOCKED_HOST, host: h };
  }
  return { allowed: true, reason: EgressDecision.ALLOWED, host: h };
}

function isTestLoopback(ip) {
  return ip === '127.0.0.1' || ip === '::1';
}

/**
 * Port policy: production destinations only on 443. Local test loopback is
 * limited to `localTestPorts` when given (null = any loopback port).
 */
export function evaluatePort(port, { host, allowLocalTestHosts = false, allowedPorts = PRODUCTION_ALLOWED_PORTS,
  localTestPorts = null } = {}) {
  const p = Number(port);
  if (!Number.isInteger(p) || p < 1 || p > 65535) return { allowed: false, reason: EgressDecision.BLOCKED_PORT };
  if (allowLocalTestHosts && (host === 'localhost' || isTestLoopback(host))) {
    if (Array.isArray(localTestPorts) && !localTestPorts.includes(p)) {
      return { allowed: false, reason: EgressDecision.BLOCKED_PORT };
    }
    return { allowed: true, reason: EgressDecision.ALLOWED };
  }
  return allowedPorts.includes(p) ? { allowed: true, reason: EgressDecision.ALLOWED } : { allowed: false, reason: EgressDecision.BLOCKED_PORT };
}

async function defaultLookup(host) {
  return dns.promises.lookup(host, { all: true, verbatim: true });
}

/**
 * Resolves and validates a destination, returning the single address to
 * connect to. Rejects when the name is refused, any resolved address is
 * blocked, or resolution fails. `lookup` is injectable for deterministic tests.
 */
export async function resolveDestination(host, port, { allowLocalTestHosts = false, lookup = defaultLookup,
  allowedPorts = PRODUCTION_ALLOWED_PORTS, localTestPorts = null } = {}) {
  const name = evaluateHostName(host, { allowLocalTestHosts });
  if (!name.allowed) return { allowed: false, reason: name.reason };
  const portDecision = evaluatePort(port, { host: name.host, allowLocalTestHosts, allowedPorts, localTestPorts });
  if (!portDecision.allowed) return { allowed: false, reason: portDecision.reason };
  if (net.isIP(name.host)) {
    return { allowed: true, reason: EgressDecision.ALLOWED, address: name.host, family: net.isIP(name.host) };
  }
  let records;
  try {
    records = await lookup(name.host);
  } catch {
    return { allowed: false, reason: EgressDecision.DNS_FAILURE };
  }
  const list = Array.isArray(records) ? records : [records];
  if (list.length === 0) return { allowed: false, reason: EgressDecision.DNS_FAILURE };
  const localTestName = allowLocalTestHosts && name.host === 'localhost';
  for (const r of list) {
    const addr = r?.address ?? r;
    if (localTestName && isTestLoopback(addr)) continue;
    if (isBlockedAddress(addr)) return { allowed: false, reason: EgressDecision.BLOCKED_ADDRESS };
  }
  const chosen = list[0];
  return { allowed: true, reason: EgressDecision.ALLOWED, address: chosen.address ?? chosen,
    family: chosen.family ?? net.isIP(chosen.address ?? chosen) };
}

function parseAuthority(authority) {
  // "host:port" or "[v6]:port"
  const m = /^\[([^\]]+)\]:(\d+)$/.exec(authority) || /^([^:]+):(\d+)$/.exec(authority);
  if (!m) return null;
  return { host: m[1], port: Number(m[2]) };
}

/**
 * Starts the loopback egress proxy. Returns { url, port, close, getDiagnostics }.
 * Diagnostics contain decision counts and canonical host + reason only — never
 * paths, queries, headers or bodies.
 */
export async function startEgressProxy({ allowLocalTestHosts = false, lookup = defaultLookup,
  allowedPorts = PRODUCTION_ALLOWED_PORTS, localTestPorts = null, connectTimeoutMs = 15000 } = {}) {
  const blocked = [];
  const allowed = [];
  let allowedCount = 0;
  const recordAllowed = (host, port, address) => {
    allowedCount += 1;
    if (allowed.length < 100) allowed.push({ host: canonicalHost(host) ?? '[malformed]', port: Number(port), address });
  };
  const sockets = new Set();
  const record = (host, reason) => {
    if (blocked.length < 100) blocked.push({ host: canonicalHost(host) ?? '[malformed]', reason });
  };
  const opts = { allowLocalTestHosts, lookup, allowedPorts, localTestPorts };

  const server = http.createServer(async (req, res) => {
    // Plain-HTTP forward proxy (absolute-form request target).
    let target;
    try {
      target = new URL(req.url);
    } catch {
      record('', EgressDecision.MALFORMED);
      res.writeHead(400, { [EGRESS_BLOCKED_HEADER]: `blocked; reason=${EgressDecision.MALFORMED}` }).end();
      return;
    }
    if (target.protocol !== 'http:' || target.username || target.password) {
      record(target.hostname, EgressDecision.BLOCKED_SCHEME);
      res.writeHead(403, { [EGRESS_BLOCKED_HEADER]: `blocked; reason=${EgressDecision.BLOCKED_SCHEME}` }).end();
      return;
    }
    const port = target.port ? Number(target.port) : 80;
    // Plain HTTP is only ever permitted to local test loopback.
    const dest = await resolveDestination(target.hostname, port, { ...opts, allowedPorts: allowLocalTestHosts ? allowedPorts : [] });
    if (!dest.allowed) {
      record(target.hostname, dest.reason);
      res.writeHead(403, { 'content-type': 'text/plain', [EGRESS_BLOCKED_HEADER]: `blocked; reason=${dest.reason}` })
        .end('blocked by egress policy');
      return;
    }
    recordAllowed(target.hostname, port, dest.address);
    const headers = { ...req.headers };
    delete headers['proxy-authorization'];
    delete headers['proxy-connection'];
    const upstream = http.request({
      host: dest.address, family: dest.family, port, method: req.method,
      path: `${target.pathname}${target.search}`, headers, timeout: connectTimeoutMs,
      // Pin the connection: never let the agent re-resolve the name.
      lookup: (_h, _o, cb) => cb(null, dest.address, dest.family),
    }, (up) => {
      res.writeHead(up.statusCode ?? 502, up.headers);
      up.pipe(res);
    });
    upstream.on('timeout', () => upstream.destroy(new Error('timeout')));
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502).end(); else res.destroy(); });
    req.pipe(upstream);
  });

  server.on('connect', async (req, clientSocket, head) => {
    sockets.add(clientSocket);
    clientSocket.on('close', () => sockets.delete(clientSocket));
    clientSocket.on('error', () => {});
    const authority = parseAuthority(String(req.url ?? ''));
    if (!authority) {
      record('', EgressDecision.MALFORMED);
      clientSocket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
      return;
    }
    const dest = await resolveDestination(authority.host, authority.port, opts);
    if (!dest.allowed) {
      record(authority.host, dest.reason);
      clientSocket.end(`HTTP/1.1 403 Forbidden\r\n${EGRESS_BLOCKED_HEADER}: blocked; reason=${dest.reason}\r\nContent-Length: 0\r\n\r\n`);
      return;
    }
    recordAllowed(authority.host, authority.port, dest.address);
    const upstream = net.connect({ host: dest.address, port: authority.port, family: dest.family, timeout: connectTimeoutMs });
    sockets.add(upstream);
    upstream.on('close', () => sockets.delete(upstream));
    upstream.once('connect', () => {
      upstream.setTimeout(0);
      // Defence in depth: the kernel-reported peer must be the validated address.
      if (upstream.remoteAddress && isBlockedAddress(upstream.remoteAddress)
          && !(allowLocalTestHosts && isTestLoopback(upstream.remoteAddress))) {
        record(authority.host, EgressDecision.BLOCKED_ADDRESS);
        upstream.destroy();
        clientSocket.end(`HTTP/1.1 403 Forbidden\r\n${EGRESS_BLOCKED_HEADER}: blocked; reason=${EgressDecision.BLOCKED_ADDRESS}\r\nContent-Length: 0\r\n\r\n`);
        return;
      }
      clientSocket.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (head && head.length) upstream.write(head);
      upstream.pipe(clientSocket);
      clientSocket.pipe(upstream);
    });
    upstream.on('timeout', () => upstream.destroy());
    upstream.on('error', () => {
      if (clientSocket.writable) clientSocket.end('HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n');
    });
  });

  // WebSocket over plain HTTP proxies arrives as an absolute-form Upgrade request.
  server.on('upgrade', (req, socket) => {
    let host = '';
    try { host = new URL(req.url).hostname; } catch { /* malformed */ }
    record(host, EgressDecision.BLOCKED_SCHEME);
    socket.end(`HTTP/1.1 403 Forbidden\r\n${EGRESS_BLOCKED_HEADER}: blocked; reason=${EgressDecision.BLOCKED_SCHEME}\r\nContent-Length: 0\r\n\r\n`);
  });

  server.on('connection', (s) => { sockets.add(s); s.on('close', () => sockets.delete(s)); });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const { port } = server.address();
  return {
    url: `http://127.0.0.1:${port}`,
    port,
    getDiagnostics: () => ({ allowedConnections: allowedCount, allowedDestinations: allowed.slice(),
      blockedConnections: blocked.slice() }),
    close: () => new Promise((resolve) => {
      for (const s of sockets) s.destroy();
      server.close(() => resolve());
    }),
  };
}
