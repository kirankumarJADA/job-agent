/**
 * Stage 3G — the only destinations the external harness may address: HTTPS on
 * 443, the exact authorised host, under the authorised path prefix. Everything
 * else (other hosts, sub-domains, IP literals, userinfo, other ports, plain
 * HTTP, encoded or trailing-dot hosts, redirects leaving the prefix) is refused.
 *
 * This is in addition to — never instead of — the backend sandbox allow-list,
 * AddressGuard and the worker egress proxy, which stay fully enabled.
 */
import net from 'node:net';
import { canonicalHost } from '../../worker/src/egress_proxy.js';

export class TargetRefusedError extends Error {
  constructor(reason) {
    super(reason);
    this.name = 'TargetRefusedError';
    this.reason = reason;
  }
}

export function checkAuthorisedTarget(rawUrl, record) {
  if (!record) return { allowed: false, reason: 'NO_AUTHORISATION' };
  let url;
  try {
    url = new URL(String(rawUrl));
  } catch {
    return { allowed: false, reason: 'MALFORMED_URL' };
  }
  if (url.protocol !== 'https:') return { allowed: false, reason: 'NOT_HTTPS' };
  if (url.username || url.password) return { allowed: false, reason: 'USERINFO' };
  if (url.port !== '' && url.port !== '443') return { allowed: false, reason: 'PORT_NOT_443' };
  const rawHost = url.hostname;
  if (net.isIP(rawHost.replace(/^\[|\]$/g, ''))) return { allowed: false, reason: 'IP_LITERAL' };
  if (rawHost.endsWith('.')) return { allowed: false, reason: 'TRAILING_DOT_HOST' };
  const host = canonicalHost(rawHost);
  if (!host || host !== rawHost) return { allowed: false, reason: 'NON_CANONICAL_HOST' };
  if (host !== record.hostname) return { allowed: false, reason: 'HOST_NOT_AUTHORISED' };
  if (!url.pathname.startsWith(record.pathPrefix)) return { allowed: false, reason: 'PATH_OUTSIDE_PREFIX' };
  if (/%2e|%2f|\.\./i.test(url.pathname)) return { allowed: false, reason: 'PATH_TRAVERSAL' };
  return { allowed: true, reason: 'AUTHORISED' };
}

export function assertAuthorisedTarget(rawUrl, record) {
  const decision = checkAuthorisedTarget(rawUrl, record);
  if (!decision.allowed) throw new TargetRefusedError(decision.reason);
  return new URL(rawUrl);
}

/** Every hop of an observed redirect chain must itself be an authorised target. */
export function checkRedirectChain(urls, record) {
  for (const [i, u] of (urls ?? []).entries()) {
    const d = checkAuthorisedTarget(u, record);
    if (!d.allowed) return { allowed: false, reason: `REDIRECT_HOP_${i}_${d.reason}` };
  }
  return { allowed: true, reason: 'AUTHORISED' };
}
