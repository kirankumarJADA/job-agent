import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { canonicalHost, evaluateHostName, EGRESS_BLOCKED_HEADER } from './egress_proxy.js';

class UniversalHardStopError extends Error {
  constructor(reason, details = {}) {
    super(reason);
    this.name = 'HardStopError';
    this.reason = reason;
    this.details = details;
  }
}

const MAX_LABEL_LEN = 300;
const CONTROL_CHARS_RE = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g;

export function sanitizeDomText(raw) {
  if (raw === null || raw === undefined) return '';
  const cleaned = String(raw).replace(CONTROL_CHARS_RE, ' ').replace(/\s+/g, ' ').trim();
  return cleaned.length > MAX_LABEL_LEN ? cleaned.slice(0, MAX_LABEL_LEN) : cleaned;
}

export function normalizeQuestionLabel(raw) {
  return sanitizeDomText(raw)
    .replace(/\s*\*\s*$/, '')
    .replace(/\s*\(required\)\s*$/i, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}

function isIpv4PrivateOrSpecial(host) {
  const parts = host.split('.');
  if (parts.length !== 4 || parts.some((p) => !/^\d{1,3}$/.test(p) || Number(p) > 255)) return false;
  const [a, b] = parts.map(Number);
  if (a === 0 || a === 10 || a === 127) return true;
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT
  if (a === 169 && b === 254) return true; // Link-local / cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 192 && b === 0 && Number(parts[2]) === 0) return true;
  if (a === 198 && (b === 18 || b === 19)) return true;
  if (a >= 224) return true;
  return false;
}

export function isExplicitLoopbackHost(hostname) {
  // Stage 3F: canonicalise first, so `localhost.` / `127.0.0.1.` / `[::1]` are recognised.
  const h = canonicalHost(hostname);
  return h === 'localhost' || h === '127.0.0.1' || h === '::1';
}

/**
 * Name-level policy (no DNS). Stage 3F: delegates to the same canonicalising
 * evaluator the connection-level egress proxy uses, which closes the
 * trailing-dot bypass (`localhost.`, `127.0.0.1.`) and treats every IP literal
 * outside explicit test loopback as blocked. DNS-resolved addresses are
 * enforced at connection time by egress_proxy.js, not here.
 */
export function isBlockedHost(hostname, allowLocalTestHosts = false) {
  return !evaluateHostName(hostname, { allowLocalTestHosts }).allowed;
}

/** Stage 3F: subresource/WebSocket port policy — public destinations only on the default TLS port. */
export function isAllowedResourcePort(parsed, { allowLocalTestHosts = false } = {}) {
  if (allowLocalTestHosts && isExplicitLoopbackHost(parsed.hostname)) return true;
  const protocol = parsed.protocol.toLowerCase();
  if (protocol !== 'https:' && protocol !== 'wss:') return false;
  return parsed.port === '' || parsed.port === '443';
}

/** Request headers that must never follow a request across origins. */
export const CROSS_ORIGIN_STRIPPED_HEADERS = Object.freeze([
  'authorization', 'proxy-authorization', 'cookie', 'x-csrf-token', 'x-xsrf-token', 'x-api-key',
  'x-auth-token', 'x-amz-security-token',
]);

export function stripCrossOriginHeaders(headers) {
  const out = {};
  for (const [k, v] of Object.entries(headers ?? {})) {
    if (!CROSS_ORIGIN_STRIPPED_HEADERS.includes(String(k).toLowerCase())) out[k] = v;
  }
  return out;
}

/**
 * Verifies same-origin identity and SSRF bounds for an ordinary employer website.
 * Multi-step forms can transition paths within the SAME validated origin (e.g.
 * `/apply` -> `/apply/step-2` -> `/confirmation`), while any scheme downgrade,
 * userinfo credential, blocked IP, or cross-origin redirect returns false.
 */
export function universalPageIdentityMatches(expectedUrl, actualUrl, { allowLocalTestHosts = false } = {}) {
  try {
    const expected = new URL(expectedUrl);
    const actual = new URL(actualUrl);
    if (expected.username || expected.password || actual.username || actual.password) return false;
    const expectedAllowedScheme = expected.protocol === 'https:'
      || (allowLocalTestHosts && expected.protocol === 'http:' && isExplicitLoopbackHost(expected.hostname));
    const actualAllowedScheme = actual.protocol === 'https:'
      || (allowLocalTestHosts && actual.protocol === 'http:' && isExplicitLoopbackHost(actual.hostname));
    if (!expectedAllowedScheme || !actualAllowedScheme) return false;
    if (isBlockedHost(expected.hostname, allowLocalTestHosts)) return false;
    if (isBlockedHost(actual.hostname, allowLocalTestHosts)) return false;
    return expected.origin === actual.origin;
  } catch {
    return false;
  }
}

/**
 * Attaches a fail-closed network request guard against SSRF / cross-origin redirects
 * for real Chromium browser navigation on UNIVERSAL plans.
 */
export function sanitizeUrlForDiagnostic(rawUrl) {
  if (!rawUrl) return '';
  const text = String(rawUrl).trim();
  if (text.startsWith('data:') || text.startsWith('blob:')) {
    return text.slice(0, 32) + '...';
  }
  try {
    const parsed = new URL(text);
    return `${parsed.origin}${parsed.pathname}`;
  } catch {
    return text.split('?')[0].split('#')[0].slice(0, 200);
  }
}

/**
 * Evaluates network navigation and subresource requests for UNIVERSAL execution.
 * - Top-level documents remain restricted to same-origin (fail-closed) HTTPS (or explicit
 *   loopback HTTP when allowLocalTestHosts is enabled).
 * - Public HTTPS cross-origin subresources (CDNs, fonts, stylesheets, scripts, JSON APIs)
 *   are permitted so modern career sites load reliably.
 * - Private/loopback/metadata IPs, unsafe schemes, userinfo URLs, and redirect chains into
 *   disallowed hosts always fail closed.
 */
export function evaluateUniversalResourceRequest({
  reqUrl,
  expectedUrl,
  resourceType = 'other',
  isNavigation = false,
  allowLocalTestHosts = false,
  redirectChain = [],
} = {}) {
  const isDocument = resourceType === 'document' && Boolean(isNavigation);
  const urlStr = String(reqUrl ?? '');
  if (!urlStr) return { allowed: false, reason: 'EMPTY_URL' };

  if (urlStr.startsWith('data:') || urlStr.startsWith('blob:')) {
    if (isDocument) return { allowed: false, reason: 'BLOCKED_DOCUMENT_DATA_URI' };
    return { allowed: true, reason: 'DATA_OR_BLOB_SUBRESOURCE' };
  }

  const checkSingleUrl = (candidateUrl, asDocument) => {
    let parsed;
    try {
      parsed = new URL(candidateUrl);
    } catch {
      return { allowed: false, reason: 'INVALID_URL' };
    }
    if (parsed.username || parsed.password) {
      return { allowed: false, reason: 'BLOCKED_USERINFO_URL' };
    }
    if (isBlockedHost(parsed.hostname, allowLocalTestHosts)) {
      return { allowed: false, reason: 'BLOCKED_PRIVATE_OR_LOCAL_HOST' };
    }
    if (asDocument) {
      if (!universalPageIdentityMatches(expectedUrl, parsed.href, { allowLocalTestHosts })) {
        return { allowed: false, reason: 'BLOCKED_CROSS_ORIGIN_DOCUMENT' };
      }
      return { allowed: true, reason: 'SAME_ORIGIN_DOCUMENT' };
    }
    const protocol = parsed.protocol.toLowerCase();
    const isSecure = protocol === 'https:' || protocol === 'wss:';
    const isAllowedLocalTest = allowLocalTestHosts && (protocol === 'http:' || protocol === 'ws:')
      && isExplicitLoopbackHost(parsed.hostname);
    if (!isSecure && !isAllowedLocalTest) {
      return { allowed: false, reason: 'BLOCKED_INSECURE_SCHEME' };
    }
    if (!isAllowedResourcePort(parsed, { allowLocalTestHosts })) {
      return { allowed: false, reason: 'BLOCKED_PORT' };
    }
    return { allowed: true, reason: 'ALLOWED_SUBRESOURCE' };
  };

  for (const hopUrl of redirectChain ?? []) {
    const hopDecision = checkSingleUrl(hopUrl, isDocument);
    if (!hopDecision.allowed) {
      return { allowed: false, reason: `REDIRECT_HOP_${hopDecision.reason}` };
    }
  }
  return checkSingleUrl(urlStr, isDocument);
}

/**
 * Attaches a fail-closed network request guard against SSRF / cross-origin document redirects
 * for real Chromium browser navigation on UNIVERSAL plans, while allowing valid HTTPS CDN assets.
 *
 * Stage 3F: `target` should be the BrowserContext (a Page is still accepted for
 * focused tests). On a context the HTTP guard covers every page and popup the
 * context ever creates, from their first request, and `routeWebSocket` applies
 * the same destination policy to WebSocket handshakes (which `route()` never
 * sees). This is the policy layer; connection-level IP enforcement (DNS
 * rebinding) is egress_proxy.js, which Chromium is launched behind.
 */
export async function installUniversalNavigationGuard(target, expectedUrl, { allowLocalTestHosts = false } = {}) {
  const page = target;
  try {
    new URL(expectedUrl);
  } catch {
    throw new UniversalHardStopError('UNSAFE_APPLICATION_URL');
  }
  const blockedResources = [];
  const recordBlocked = (reqUrl, resourceType, reason) => {
    if (blockedResources.length < 50) {
      blockedResources.push({
        url: sanitizeUrlForDiagnostic(reqUrl),
        resourceType: String(resourceType || 'other'),
        reason,
      });
    }
  };

  await page.route('**/*', async (route) => {
    const request = route.request();
    const reqUrl = request.url();
    const resourceType = request.resourceType();
    const isNavigation = request.isNavigationRequest();
    const isDocument = resourceType === 'document' && isNavigation;
    const redirectChain = [];
    let prev = request.redirectedFrom();
    while (prev) {
      redirectChain.push(prev.url());
      prev = prev.redirectedFrom();
    }
    const decision = evaluateUniversalResourceRequest({
      reqUrl,
      expectedUrl,
      resourceType,
      isNavigation,
      allowLocalTestHosts,
      redirectChain,
    });
    if (!decision.allowed) {
      recordBlocked(reqUrl, resourceType, decision.reason);
      await route.abort('blockedbyclient');
      return;
    }

    if (reqUrl.startsWith('data:') || reqUrl.startsWith('blob:')) {
      await route.fallback();
      return;
    }

    // Intercept HTTP 3xx redirects explicitly via maxRedirects: 0 so a hostile
    // 302 Location -> 169.254.169.254 or 10.0.0.1 is aborted before Chromium ever
    // opens a socket to the redirect target.
    try {
      let currentUrl = reqUrl;
      let currentMethod = request.method();
      const originalOrigin = (() => { try { return new URL(reqUrl).origin; } catch { return null; } })();
      let requestHeaders = request.headers();
      let currentPostData = request.postDataBuffer();
      const maxHops = 5;

      for (let hop = 0; hop <= maxHops; hop += 1) {
        const fetchOpts = {
          url: currentUrl,
          method: currentMethod,
          headers: requestHeaders,
          maxRedirects: 0,
        };
        if (currentPostData && currentMethod !== 'GET' && currentMethod !== 'HEAD') {
          fetchOpts.postData = currentPostData;
        }
        const resp = await route.fetch(fetchOpts);
        const status = resp.status();
        // Stage 3F: a refusal generated by the egress proxy (DNS answer, port or name
        // rejected at the connection boundary) becomes an explicit abort, never a
        // synthetic empty response handed to the page.
        const egressMarker = String(resp.headers()[EGRESS_BLOCKED_HEADER] ?? '');
        if (egressMarker.startsWith('blocked')) {
          const m = /reason=([A-Z_]+)/.exec(egressMarker);
          recordBlocked(currentUrl, resourceType, `EGRESS_${m ? m[1] : 'BLOCKED'}`);
          await route.abort('blockedbyclient');
          return;
        }
        const locationHeader = resp.headers()['location'];
        if (status >= 300 && status < 400 && locationHeader) {
          let nextUrl;
          try {
            nextUrl = new URL(locationHeader, currentUrl).href;
          } catch {
            recordBlocked(locationHeader, resourceType, 'INVALID_REDIRECT_LOCATION');
            await route.abort('blockedbyclient');
            return;
          }
          const hopDecision = evaluateUniversalResourceRequest({
            reqUrl: nextUrl,
            expectedUrl,
            resourceType,
            isNavigation,
            allowLocalTestHosts,
            redirectChain: [currentUrl],
          });
          if (!hopDecision.allowed) {
            recordBlocked(nextUrl, resourceType, `REDIRECT_${hopDecision.reason}`);
            await route.abort('blockedbyclient');
            return;
          }
          if (isDocument) {
            // Same-origin document redirect validated safe; preserve browser URL semantics.
            await route.fulfill({ response: resp });
            return;
          }
          if (hop === maxHops) {
            recordBlocked(nextUrl, resourceType, 'TOO_MANY_REDIRECTS');
            await route.abort('blockedbyclient');
            return;
          }
          if (status === 301 || status === 302 || status === 303) {
            currentMethod = 'GET';
            currentPostData = null;
          }
          // Stage 3F: never carry credentials or CSRF tokens to another origin.
          if (new URL(nextUrl).origin !== originalOrigin) {
            requestHeaders = stripCrossOriginHeaders(requestHeaders);
          }
          currentUrl = nextUrl;
          continue;
        }
        await route.fulfill({ response: resp });
        return;
      }
    } catch {
      recordBlocked(reqUrl, resourceType, 'FETCH_ERROR');
      await route.abort('failed');
    }
  });

  // Stage 3F (SEC-1): WebSocket handshakes are invisible to route(); apply the
  // same subresource destination policy here. Allowed sockets still connect
  // from Chromium, i.e. through the egress proxy.
  if (typeof page.routeWebSocket === 'function') {
    await page.routeWebSocket(() => true, (ws) => {
      const wsUrl = ws.url();
      const decision = evaluateUniversalResourceRequest({
        reqUrl: wsUrl, expectedUrl, resourceType: 'websocket', isNavigation: false, allowLocalTestHosts,
      });
      if (!decision.allowed) {
        recordBlocked(wsUrl, 'websocket', decision.reason);
        ws.close({ code: 1008, reason: 'blocked by policy' }).catch(() => {});
        return;
      }
      ws.connectToServer();
    });
  }

  return {
    getDiagnostics: () => ({ blockedResources: blockedResources.slice() }),
    recordBlocked,
  };
}

const NAVIGATION_RACE_RE = /Execution context was destroyed|Cannot find context with specified id|Frame was detached/i;

/** True when an evaluate failed only because the page navigated underneath it. */
export function isNavigationRaceError(error) {
  return NAVIGATION_RACE_RE.test(String(error?.message ?? error ?? ''));
}

/**
 * Passively inspects the live DOM (including accessible same-origin iframes and open Shadow DOM),
 * assigning ephemeral numeric `data-robin-control-id` attributes so page strings
 * are NEVER interpolated into Playwright selectors.
 *
 * Stage 3I Phase 6: `domcontentloaded` can resolve while the previous document is still live, so a
 * navigation (next page, redirect, client-side route) may commit mid-evaluate and destroy the
 * execution context. The inspection is read-only, so it is retried on the settled page (at most
 * `attempts` times); every caller's checks then run on that page exactly as if the navigation had
 * finished first. Any other error, or a page that never settles, still throws.
 */
export async function inspectLiveFormDom(page, { attempts = 3 } = {}) {
  for (let attempt = 1; ; attempt += 1) {
    try {
      return await inspectLiveFormDomOnce(page);
    } catch (error) {
      if (attempt >= attempts || !isNavigationRaceError(error)) throw error;
      await page.waitForLoadState('domcontentloaded').catch(() => {});
    }
  }
}

async function inspectLiveFormDomOnce(page) {
  return page.evaluate(() => {
    const clean = (s) => String(s ?? '').replace(/[\u0000-\u001F\u007F]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 300);
    const docList = [{ doc: document, frameIndex: 0 }];
    let crossOriginFrames = 0;
    for (const iframe of Array.from(document.querySelectorAll('iframe'))) {
      try {
        if (iframe.contentDocument) {
          docList.push({ doc: iframe.contentDocument, frameIndex: docList.length });
        } else {
          crossOriginFrames += 1;
        }
      } catch {
        crossOriginFrames += 1;
      }
    }

    const closedShadowHosts = new Set();
    let openShadowRootCount = 0;

    const collectElements = (rootDoc) => {
      const results = [];
      const visited = new Set();
      const walk = (nodeRoot, inShadowDom) => {
        if (!nodeRoot || typeof nodeRoot.querySelectorAll !== 'function') return;
        for (const el of Array.from(nodeRoot.querySelectorAll('*'))) {
          if (!visited.has(el)) {
            visited.add(el);
            results.push({ el, rootNode: nodeRoot, inShadowDom });
          }
          if (el.shadowRoot) {
            openShadowRootCount += 1;
            walk(el.shadowRoot, true);
          } else if (el.tagName && el.tagName.includes('-')) {
            closedShadowHosts.add(el.tagName.toLowerCase());
          }
        }
      };
      walk(rootDoc, false);
      return results;
    };

    const isElementVisible = (el) => {
      if (!el || el.disabled) return false;
      if (el.getAttribute('aria-hidden') === 'true' || el.closest('[aria-hidden="true"]')) return false;
      const style = window.getComputedStyle(el);
      if (style && (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0')) return false;
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) return false;
      if (rect.left < -999 || rect.top < -999) return false;
      const tab = el.getAttribute('tabindex');
      if (tab === '-1' && (rect.width <= 2 || rect.height <= 2)) return false;
      return true;
    };

    const hasTrapMarkers = (el) => {
      if (!el) return true;
      if (el.getAttribute('aria-hidden') === 'true' || el.closest('[aria-hidden="true"]')) return true;
      if (el.getAttribute('data-honeypot') === 'true') return true;
      const signature = `${el.name || ''} ${el.id || ''} ${el.className || ''}`.toLowerCase();
      return /honeypot|beecatcher|decoy|url[-_]?trap/.test(signature);
    };

    const hasVisibleFileInteractionPath = (rootNode, el) => {
      if (!el || el.disabled || hasTrapMarkers(el)) return false;
      if (isElementVisible(el)) return true;
      if (el.id) {
        const directLabel = Array.from(rootNode.querySelectorAll('label')).find((l) => l.htmlFor === el.id);
        if (directLabel && isElementVisible(directLabel) && clean(directLabel.textContent)) return true;
      }
      const ancestorLabel = el.closest('label');
      if (ancestorLabel && isElementVisible(ancestorLabel)) return true;
      const dropzone = el.closest(
        '[data-dropzone], [data-upload], [class*="dropzone" i], [class*="upload" i], [class*="file-input" i], .field, .form-group'
      );
      if (dropzone && isElementVisible(dropzone)) {
        const isGenericWrapper = !dropzone.matches('[data-dropzone], [data-upload], [class*="dropzone" i], [class*="upload" i], [class*="file-input" i]');
        const triggers = Array.from(dropzone.querySelectorAll('button, [role="button"], label, a, [data-upload-trigger]'));
        if (triggers.some((t) => {
          if (!isElementVisible(t) || hasTrapMarkers(t)) return false;
          if (!isGenericWrapper) return true;
          const labelSig = `${clean(t.textContent)} ${clean(t.getAttribute('aria-label'))}`.toLowerCase();
          return /upload|attach|choose|browse|select\s*file|resume|cv|cover/.test(labelSig);
        })) {
          return true;
        }
      }
      return false;
    };

    const isHiddenHoneypot = (el, rootNode) => {
      if (!el || el.disabled) return true;
      const isFile = el.tagName === 'INPUT' && (el.type || '').toLowerCase() === 'file';
      if (isFile) {
        return !hasVisibleFileInteractionPath(rootNode, el);
      }
      if (el.tagName === 'INPUT' && (el.type || '').toLowerCase() === 'hidden') return true;
      if (el.getAttribute('aria-hidden') === 'true' || el.closest('[aria-hidden="true"]')) return true;
      if (hasTrapMarkers(el)) return true;
      return !isElementVisible(el);
    };

    const resolveLabel = (rootNode, el) => {
      if (el.id) {
        const direct = Array.from(rootNode.querySelectorAll('label')).find((l) => l.htmlFor === el.id);
        if (direct && clean(direct.textContent)) return clean(direct.textContent);
      }
      const ancestorLabel = el.closest('label');
      if (ancestorLabel) {
        const clone = ancestorLabel.cloneNode(true);
        clone.querySelectorAll('input, select, textarea').forEach((n) => n.remove());
        if (clean(clone.textContent)) return clean(clone.textContent);
      }
      const labelledBy = el.getAttribute('aria-labelledby');
      if (labelledBy) {
        const text = labelledBy.split(/\s+/).map((id) => rootNode.getElementById?.(id)?.textContent || document.getElementById(id)?.textContent || '').join(' ');
        if (clean(text)) return clean(text);
      }
      const ariaLabel = clean(el.getAttribute('aria-label'));
      if (ariaLabel) return ariaLabel;
      const fieldset = el.closest('fieldset');
      if (fieldset) {
        const legend = fieldset.querySelector('legend');
        if (legend && clean(legend.textContent)) return clean(legend.textContent);
      }
      if (el.tagName === 'INPUT' && (el.type || '').toLowerCase() === 'file') {
        const dropzone = el.closest('[data-dropzone], [data-upload], [class*="dropzone" i], [class*="upload" i], .field, .form-group');
        if (dropzone) {
          const trigger = dropzone.querySelector('label, button, [role="button"], .upload-label, h3, h4, p');
          if (trigger && !trigger.contains(el) && clean(trigger.textContent)) return clean(trigger.textContent);
        }
      }
      const fieldWrapper = el.closest('[class*="field"], [class*="question"], .form-group, .form-row, div');
      if (fieldWrapper) {
        const labelEl = fieldWrapper.querySelector('label, .question-label, h3, h4, p');
        if (labelEl && !labelEl.contains(el) && clean(labelEl.textContent)) return clean(labelEl.textContent);
      }
      return clean(el.getAttribute('placeholder')) || clean(el.getAttribute('name')) || clean(el.id);
    };

    const controls = [];
    const seenRadioNames = new Set();
    let seq = 1;
    let nextButton = null;
    let submitButton = null;
    let applyRevealButton = null;

    const CONTROL_TAGS = new Set(['INPUT', 'SELECT', 'TEXTAREA']);
    const BUTTON_TAGS = new Set(['BUTTON']);
    const allErrors = [];

    for (const { doc, frameIndex } of docList) {
      const collected = collectElements(doc);
      for (const { el, rootNode, inShadowDom } of collected) {
        const tag = el.tagName.toUpperCase();
        const roleAttr = (el.getAttribute('role') || '').toLowerCase();

        if ((roleAttr === 'alert' || el.classList?.contains('error') || el.classList?.contains('field-error')
            || el.classList?.contains('invalid-feedback') || el.getAttribute('aria-invalid') === 'true')
            && isElementVisible(el)) {
          const errText = clean(el.getAttribute('data-error-message') || el.textContent);
          if (errText) allErrors.push(errText);
        }

        const isControl = CONTROL_TAGS.has(tag) || roleAttr === 'combobox' || roleAttr === 'radiogroup';
        if (isControl) {
          if (isHiddenHoneypot(el, rootNode)) continue;
          const lowerTag = tag.toLowerCase();
          const type = lowerTag === 'input' ? (el.type || 'text').toLowerCase()
            : lowerTag === 'select' ? 'select'
            : lowerTag === 'textarea' ? 'textarea'
            : (roleAttr || 'custom');
          if (['submit', 'button', 'reset', 'image'].includes(type)) continue;

          if (type === 'radio') {
            const name = el.getAttribute('name') || '';
            const groupKey = `${frameIndex}:${inShadowDom ? 'sh' : 'doc'}:${name || el.id || seq}`;
            if (seenRadioNames.has(groupKey)) continue;
            seenRadioNames.add(groupKey);
            const peers = name
              ? Array.from(rootNode.querySelectorAll('input[type="radio"]')).filter((r) => r.name === name && !isHiddenHoneypot(r, rootNode))
              : [el];
            const optionControls = peers.map((r) => {
              const cid = String(seq++);
              r.setAttribute('data-robin-control-id', cid);
              return {
                controlId: cid,
                value: clean(r.value),
                label: clean(resolveLabel(rootNode, r) || r.value),
                checked: Boolean(r.checked),
              };
            });
            const fieldset = el.closest('fieldset, [role="radiogroup"], .field, .form-group, div');
            const groupLabel = clean(fieldset?.querySelector('legend, label, h3, h4, p, .question-label')?.textContent)
              || clean(name);
            const req = peers.some((r) => r.required || r.getAttribute('aria-required') === 'true')
              || /\*\s*$/.test(groupLabel);
            controls.push({
              controlId: optionControls[0]?.controlId,
              frameIndex,
              inShadowDom,
              tagName: 'input',
              htmlType: 'radio',
              role: 'radio',
              label: groupLabel,
              nameAttr: clean(name),
              idAttr: clean(el.id),
              placeholder: '',
              ariaLabel: clean(el.getAttribute('aria-label')),
              required: req,
              options: optionControls.map((o) => o.label),
              radioOptions: optionControls,
              currentValue: optionControls.find((o) => o.checked)?.label || '',
              filesAttached: 0,
            });
            continue;
          }

          const cid = String(seq++);
          el.setAttribute('data-robin-control-id', cid);
          const label = resolveLabel(rootNode, el);
          const req = Boolean(el.required)
            || el.getAttribute('aria-required') === 'true'
            || /\*\s*$/.test(label)
            || /\(required\)/i.test(label)
            || Boolean(el.closest('.field, .form-group')?.querySelector('.requiredInput[required], [data-required="true"]'));

          let options = [];
          if (lowerTag === 'select') {
            options = Array.from(el.options)
              .map((o) => ({ value: clean(o.value), label: clean(o.textContent) }))
              .filter((o) => o.label && !/^select\b/i.test(o.label));
          } else if (roleAttr === 'combobox') {
            const listId = el.getAttribute('aria-controls') || el.getAttribute('aria-owns');
            const listbox = (listId && (rootNode.getElementById?.(listId) || document.getElementById(listId)))
              || el.parentElement?.querySelector('[role="listbox"]');
            if (listbox) {
              options = Array.from(listbox.querySelectorAll('[role="option"], option, li')).map((o) => ({
                value: clean(o.getAttribute('data-value') || o.textContent),
                label: clean(o.textContent),
              }));
            }
          }

          controls.push({
            controlId: cid,
            frameIndex,
            inShadowDom,
            tagName: lowerTag,
            htmlType: type,
            role: roleAttr,
            label,
            nameAttr: clean(el.getAttribute('name')),
            idAttr: clean(el.id),
            placeholder: clean(el.getAttribute('placeholder')),
            ariaLabel: clean(el.getAttribute('aria-label')),
            ariaControls: clean(el.getAttribute('aria-controls') || el.getAttribute('aria-owns')),
            accept: clean(el.getAttribute('accept')),
            customTrigger: type === 'file' && !isElementVisible(el),
            required: req,
            options: options.map((o) => o.label),
            rawOptions: options,
            currentValue: type === 'checkbox'
              ? String(Boolean(el.checked))
              : clean(el.value !== undefined ? el.value : (roleAttr === 'combobox' ? (el.getAttribute('data-value') || el.textContent) : '')),
            filesAttached: type === 'file' ? (el.files ? el.files.length : 0) : 0,
          });
          continue;
        }
      }

      const buttons = collected
        .filter(({ el }) => BUTTON_TAGS.has(el.tagName.toUpperCase())
          || (el.tagName.toUpperCase() === 'INPUT' && ['submit', 'button'].includes((el.getAttribute('type') || '').toLowerCase()))
          || el.getAttribute('role') === 'button'
          || (el.tagName.toUpperCase() === 'A' && el.hasAttribute('data-apply-trigger')))
        .map(({ el, rootNode }) => ({ btn: el, rootNode }));

      for (const { btn, rootNode } of buttons) {
        if (isHiddenHoneypot(btn, rootNode) || btn.disabled) continue;
        const text = clean(btn.tagName === 'INPUT' ? btn.value : btn.textContent);
        const typeAttr = (btn.getAttribute('type') || '').toLowerCase();
        const isApplyRevealMatch = /^(apply(\s+now)?|apply\s+for\s+(this\s+)?(job|position|role)|start\s+application|begin\s+application)$/i.test(text);
        const isExplicitSubmitMatch = !isApplyRevealMatch
          && /\b(submit(\s+(my|your|this))?(\s+application)?|complete\s+(my\s+|your\s+)?application|send\s+application|finish\s*(&|and)\s*submit)\b/i.test(text);
        const isNextMatch = !isApplyRevealMatch && !isExplicitSubmitMatch
          && /^(continue|next|save\s*(&|and)?\s*continue|proceed|step\s*\d)/i.test(text);

        if (isApplyRevealMatch && !applyRevealButton) {
          const aid = String(seq++);
          btn.setAttribute('data-robin-apply-reveal', aid);
          applyRevealButton = { selector: `[data-robin-apply-reveal="${aid}"]`, label: text, frameIndex };
        } else if (isNextMatch && !nextButton) {
          const nid = String(seq++);
          btn.setAttribute('data-robin-next', nid);
          nextButton = { selector: `[data-robin-next="${nid}"]`, label: text, frameIndex };
        } else if ((isExplicitSubmitMatch || (typeAttr === 'submit' && !isApplyRevealMatch && !isNextMatch && !/sign\s*in|log\s*in/i.test(text))) && !submitButton) {
          const sid = String(seq++);
          btn.setAttribute('data-robin-submit', sid);
          submitButton = { selector: `[data-robin-submit="${sid}"]`, label: text || 'Submit', frameIndex };
        }
      }
    }

    const bodyText = String(document.body?.innerText || '').toLowerCase();
    const captchaPresent = document.querySelectorAll(
      'iframe[src*="captcha" i], iframe[src*="hcaptcha" i], iframe[src*="turnstile" i], [data-sitekey], .g-recaptcha, .h-captcha, .cf-turnstile'
    ).length > 0 || /verify you are human|complete the captcha|cloudflare turnstile/.test(bodyText);

    const passwordInputs = Array.from(document.querySelectorAll('input[type="password"]')).filter((el) => isElementVisible(el));
    const authenticatorMfaPresent = /two-factor|2fa|authenticator\s*(?:app|code)|google\s*authenticator|authy|totp|security\s*key|sms\s*(?:code|otp|verification)|verification\s*code\s*(?:on|from)\s*(?:your\s*)?(?:phone|authenticator)/i.test(bodyText);
    const emailContextHint = /e-?mail|inbox|sent\s*(?:a|you\s*a|to)\s*(?:.*?)(?:code|link|otp)|verify\s*(?:your\s*)?e-?mail|confirm\s*(?:your\s*)?e-?mail|check\s*(?:your\s*)?e-?mail/i.test(bodyText);

    let emailVerification = { present: false, verified: false, ambiguous: false, hasOtpInput: false, waitingForLinkOnly: false };
    let emailOtpVerified = false;
    if (passwordInputs.length === 0 && !authenticatorMfaPresent) {
      const otpInputCandidates = Array.from(document.querySelectorAll('input'))
        .filter((el) => {
          const type = (el.type || 'text').toLowerCase();
          if (!['text', 'number', 'tel', ''].includes(type)) return false;
          if (!isElementVisible(el) || hasTrapMarkers(el)) return false;
          const ac = (el.getAttribute('autocomplete') || '').toLowerCase();
          const sig = `${ac} ${el.name || ''} ${el.id || ''} ${el.placeholder || ''} ${el.getAttribute('aria-label') || ''} ${resolveLabel(document, el)}`.toLowerCase();
          return ac === 'one-time-code' || /\b(?:otp|one[- ]time[-_\s]?code|verification[-_\s]?code|verify[-_\s]?code|email[-_\s]?code|passcode|pin)\b/.test(sig);
        });
      if (otpInputCandidates.length === 1) {
        const otpInput = otpInputCandidates[0];
        const otpForm = otpInput.closest('form') || otpInput.parentElement?.closest('div, section, main') || document.body;
        const otpSubmits = Array.from(otpForm.querySelectorAll('button[type="submit"], input[type="submit"], button'))
          .filter((btn) => isElementVisible(btn) && !btn.disabled)
          .filter((btn) => {
            const txt = clean(btn.tagName === 'INPUT' ? btn.value : btn.textContent);
            if (/resend|send\s*again|back|cancel|change\s*email/i.test(txt)) return false;
            return btn.getAttribute('type') === 'submit' || /^(verify|confirm|continue|submit|activate|next)(\s|$)/i.test(txt);
          });
        if (otpSubmits.length === 1 && (emailContextHint || /otp|verification|one-time-code|email/i.test(`${otpInput.name || ''} ${otpInput.id || ''} ${resolveLabel(document, otpInput)}`))) {
          otpInput.setAttribute('data-robin-email-otp-role', 'input');
          otpSubmits[0].setAttribute('data-robin-email-otp-role', 'submit');
          emailOtpVerified = true;
          emailVerification = { present: true, verified: true, ambiguous: false, hasOtpInput: true, waitingForLinkOnly: false };
        } else {
          emailVerification = { present: true, verified: false, ambiguous: true, hasOtpInput: true, waitingForLinkOnly: false };
        }
      } else if (otpInputCandidates.length > 1) {
        emailVerification = { present: true, verified: false, ambiguous: true, hasOtpInput: true, waitingForLinkOnly: false };
      } else if (emailContextHint && /verify|confirm|activate/i.test(bodyText)) {
        emailVerification = { present: true, verified: true, ambiguous: false, hasOtpInput: false, waitingForLinkOnly: true };
      }
    }

    const genericMfaPrompt = /one-time\s*(code|passcode)|enter\s*(the\s*)?(otp|verification\s*code)/i.test(bodyText);
    const mfaPresent = authenticatorMfaPresent || (genericMfaPrompt && !emailContextHint && !emailOtpVerified);

    let loginControls = null;
    let signupControls = null;
    let loginWallPresent = false;
    let signupWallPresent = false;

    if (passwordInputs.length >= 1 && passwordInputs.length <= 2) {
      const pw0 = passwordInputs[0];
      const authForm = pw0.closest('form') || pw0.parentElement?.closest('div, section, main') || document.body;
      const allPwInSameForm = passwordInputs.every((pw) => authForm.contains(pw));
      const candidateUsers = allPwInSameForm
        ? Array.from(authForm.querySelectorAll('input[type="email"], input[type="text"], input:not([type])'))
            .filter((el) => !passwordInputs.includes(el) && isElementVisible(el) && !hasTrapMarkers(el))
            .filter((el) => {
              const sig = `${el.type || ''} ${el.autocomplete || ''} ${el.name || ''} ${el.id || ''} ${el.placeholder || ''} ${resolveLabel(document, el)}`.toLowerCase();
              return /email|user|login|account|username/.test(sig);
            })
        : [];
      const allButtonsInForm = allPwInSameForm
        ? Array.from(authForm.querySelectorAll('button[type="submit"], input[type="submit"], button'))
            .filter((btn) => isElementVisible(btn) && !btn.disabled)
        : [];
      const signupButtons = allButtonsInForm.filter((btn) => {
        const text = clean(btn.tagName === 'INPUT' ? btn.value : btn.textContent);
        return /\b(?:sign\s*up|create\s*(?:an?\s*)?account|register|get\s*started|join)\b/i.test(text)
          && !/\b(?:sign\s*in|log\s*in)\b/i.test(text);
      });
      const loginSubmits = allButtonsInForm.filter((btn) => {
        const text = clean(btn.tagName === 'INPUT' ? btn.value : btn.textContent);
        return btn.getAttribute('type') === 'submit' || /^(sign\s*in|log\s*in|continue|next)$/i.test(text);
      });
      const headingText = clean(Array.from(authForm.querySelectorAll('h1, h2, h3, h4, legend')).map((h) => h.textContent).join(' ')).toLowerCase();
      const formHeadingIsSignup = /\b(?:create\s*(?:an?\s*)?account|sign\s*up|register|create\s*profile)\b/i.test(headingText)
        && !/\b(?:sign\s*in|log\s*in)\b/i.test(headingText);
      const formHasConfirmPw = passwordInputs.length === 2 && passwordInputs.some((pw) => {
        const sig = `${pw.name || ''} ${pw.id || ''} ${pw.placeholder || ''} ${pw.getAttribute('aria-label') || ''} ${resolveLabel(document, pw)}`.toLowerCase();
        return /confirm|re-?enter|retype|repeat|verify/.test(sig);
      });
      const signupSubmits = signupButtons.length === 1
        ? signupButtons
        : ((formHeadingIsSignup || formHasConfirmPw) ? loginSubmits : []);

      const isSignupForm = allPwInSameForm && candidateUsers.length === 1 && signupSubmits.length === 1
        && (signupButtons.length === 1 || formHeadingIsSignup || formHasConfirmPw);

      if (isSignupForm) {
        const emailInput = candidateUsers[0];
        let primaryPw = pw0;
        let confirmPw = null;
        if (passwordInputs.length === 2) {
          const idxConfirm = passwordInputs.findIndex((pw) => /confirm|re-?enter|retype|repeat|verify/.test(
            `${pw.name || ''} ${pw.id || ''} ${pw.placeholder || ''} ${pw.getAttribute('aria-label') || ''} ${resolveLabel(document, pw)}`.toLowerCase()
          ));
          if (idxConfirm === 0) {
            confirmPw = passwordInputs[0];
            primaryPw = passwordInputs[1];
          } else {
            primaryPw = passwordInputs[0];
            confirmPw = passwordInputs[1];
          }
          confirmPw.setAttribute('data-robin-signup-role', 'confirm-password');
        }
        emailInput.setAttribute('data-robin-signup-role', 'email');
        primaryPw.setAttribute('data-robin-signup-role', 'password');
        signupSubmits[0].setAttribute('data-robin-signup-role', 'submit');

        const extraNameInputs = Array.from(authForm.querySelectorAll('input[type="text"], input:not([type])'))
          .filter((el) => el !== emailInput && isElementVisible(el) && !hasTrapMarkers(el));
        let hasFullName = false;
        let hasFirstName = false;
        let hasLastName = false;
        for (const el of extraNameInputs) {
          const sig = `${el.name || ''} ${el.id || ''} ${el.placeholder || ''} ${resolveLabel(document, el)}`.toLowerCase();
          if (/first\s*name|given\s*name/.test(sig) && !hasFirstName) {
            el.setAttribute('data-robin-signup-role', 'first-name');
            hasFirstName = true;
          } else if (/last\s*name|family\s*name|surname/.test(sig) && !hasLastName) {
            el.setAttribute('data-robin-signup-role', 'last-name');
            hasLastName = true;
          } else if (/full\s*name|your\s*name|\bname\b/.test(sig) && !/company|user/.test(sig) && !hasFullName) {
            el.setAttribute('data-robin-signup-role', 'full-name');
            hasFullName = true;
          }
        }
        signupControls = {
          verified: true,
          ambiguous: false,
          hasConfirmPassword: Boolean(confirmPw),
          hasFullName,
          hasFirstName,
          hasLastName,
        };
        signupWallPresent = true;
      } else if (passwordInputs.length === 1) {
        loginWallPresent = true;
        if (candidateUsers.length === 1 && loginSubmits.length === 1) {
          candidateUsers[0].setAttribute('data-robin-login-role', 'username');
          pw0.setAttribute('data-robin-login-role', 'password');
          loginSubmits[0].setAttribute('data-robin-login-role', 'submit');
          loginControls = { verified: true, ambiguous: false };
        } else {
          loginControls = { verified: false, ambiguous: true };
        }
      } else {
        loginWallPresent = true;
        loginControls = { verified: false, ambiguous: true };
      }
    } else if (passwordInputs.length > 2) {
      loginWallPresent = true;
      loginControls = { verified: false, ambiguous: true };
    } else if (/sign in to continue|log in to apply|please log in before applying/i.test(bodyText)) {
      loginWallPresent = true;
      loginControls = { verified: false, ambiguous: true };
    }

    let authSwitchSignupSelector = null;
    let authSwitchLoginSelector = null;
    for (const el of Array.from(document.querySelectorAll('a, button, [role="button"]'))) {
      if (!isElementVisible(el) || hasTrapMarkers(el)) continue;
      if (el.getAttribute('data-robin-login-role') || el.getAttribute('data-robin-signup-role')) continue;
      const txt = clean(el.textContent);
      if (!authSwitchSignupSelector && /^(create\s*(an?\s*)?account|sign\s*up|register|don'?t\s*have\s*an\s*account\??\s*sign\s*up)$/i.test(txt)) {
        el.setAttribute('data-robin-auth-switch', 'signup');
        authSwitchSignupSelector = '[data-robin-auth-switch="signup"]';
      } else if (!authSwitchLoginSelector && /^(sign\s*in|log\s*in|already\s*have\s*an\s*account\??\s*(?:sign|log)\s*in)$/i.test(txt)) {
        el.setAttribute('data-robin-auth-switch', 'login');
        authSwitchLoginSelector = '[data-robin-auth-switch="login"]';
      }
    }

    const activeStepEl = document.querySelector('[data-step], [aria-current="step"], .step-indicator');
    const stepText = clean(activeStepEl?.textContent || '');

    return {
      url: location.href,
      formCount: document.querySelectorAll('form').length,
      crossOriginFrames,
      openShadowRootCount,
      closedShadowHosts: Array.from(closedShadowHosts),
      controls,
      applyRevealButton,
      nextButton,
      submitButton,
      loginControls,
      signupControls,
      emailVerification,
      authSwitchSignupSelector,
      authSwitchLoginSelector,
      validationErrors: Array.from(new Set(allErrors)),
      stepHint: stepText,
      securitySignals: {
        captchaPresent,
        mfaPresent,
        loginWallPresent,
        signupWallPresent,
        emailVerificationPresent: emailVerification.present,
      },
    };
  });
}

export function isArtifactCompatibleWithAccept(acceptAttr, contentType = '', filePathOrName = '') {
  const raw = String(acceptAttr ?? '').trim();
  if (!raw || raw === '*/*') return true;
  const tokens = raw.split(',').map((t) => t.trim().toLowerCase()).filter(Boolean);
  if (tokens.length === 0) return true;
  const mime = String(contentType || '').trim().toLowerCase();
  const ext = path.extname(String(filePathOrName || '')).toLowerCase();
  const inferredExt = ext || (mime === 'application/pdf' ? '.pdf' : mime.includes('wordprocessingml') ? '.docx' : '');
  const inferredMime = mime || (ext === '.pdf' ? 'application/pdf' : ext === '.docx' ? 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' : ext === '.txt' ? 'text/plain' : '');
  for (const token of tokens) {
    if (token === '*/*') return true;
    if (token.startsWith('.')) {
      if (inferredExt && inferredExt === token) return true;
    } else if (token.endsWith('/*')) {
      const prefix = token.slice(0, -1);
      if (inferredMime && inferredMime.startsWith(prefix)) return true;
    } else if (inferredMime && inferredMime === token) {
      return true;
    }
  }
  return false;
}

/**
 * Discovers portal-mounted options for dynamic `[role="combobox"]` widgets whose
 * listbox is only mounted in the DOM upon interaction, closing the popup afterward.
 */
export async function hydrateDynamicComboboxOptions(page, inspected) {
  if (!inspected || !Array.isArray(inspected.controls)) return inspected;
  const candidates = inspected.controls.filter(
    (c) => (c.role === 'combobox' || c.htmlType === 'combobox') && (!Array.isArray(c.rawOptions) || c.rawOptions.length === 0)
  ).slice(0, 15);
  for (const control of candidates) {
    const loc = page.locator(`[data-robin-control-id="${Number(control.controlId)}"]`);
    if (await loc.count() === 0) continue;
    try {
      await loc.click({ timeout: 2000 });
      await page.locator('[role="option"]').first().waitFor({ state: 'visible', timeout: 800 }).catch(() => {});
      const discovered = await page.evaluate(({ ariaControls, controlId }) => {
        const clean = (s) => String(s ?? '').replace(/[\u0000-\u001F\u007F]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 300);
        let scope = ariaControls ? document.getElementById(ariaControls) : null;
        if (!scope) {
          const trigger = document.querySelector(`[data-robin-control-id="${Number(controlId)}"]`);
          scope = trigger?.parentElement?.querySelector('[role="listbox"]') || null;
        }
        const roots = scope
          ? [scope]
          : Array.from(document.querySelectorAll('[role="listbox"]')).filter((el) => {
              const st = window.getComputedStyle(el);
              return st.display !== 'none' && st.visibility !== 'hidden';
            });
        const items = [];
        for (const r of roots) {
          for (const opt of Array.from(r.querySelectorAll('[role="option"], option, li'))) {
            const label = clean(opt.textContent);
            const value = clean(opt.getAttribute('data-value') || label);
            if (label) items.push({ value, label });
          }
        }
        return items;
      }, { ariaControls: control.ariaControls || '', controlId: control.controlId });
      if (Array.isArray(discovered) && discovered.length > 0) {
        control.rawOptions = discovered;
        control.options = discovered.map((o) => o.label);
      }
    } catch {
      // keep empty options if widget does not open interactively
    } finally {
      await page.keyboard.press('Escape').catch(() => {});
    }
  }
  return inspected;
}

function isProtectedDemographic(normalized) {
  for (const marker of [
    'gender', 'transgender', 'sexual orientation', 'disability', 'neurodivergent',
    'veteran', 'race', 'ethnic', 'hispanic', 'latino', 'pronoun', 'marital',
  ]) {
    if (normalized.includes(marker)) return true;
  }
  return false;
}

function isSensitiveLegal(normalized) {
  return /authorized to work|authorised to work|legally authorized|legally authorised|work authorization|work authorisation|right to work|sponsorship|visa/.test(normalized);
}

function splitNameDeterministic(displayName) {
  if (!displayName || typeof displayName !== 'string') return { deterministic: false };
  const tokens = displayName.trim().split(/\s+/).filter(Boolean);
  if (tokens.length !== 2) return { deterministic: false };
  return { deterministic: true, firstName: tokens[0], lastName: tokens[1] };
}

function resolveOptionValue(control, answerText) {
  const target = String(answerText ?? '').trim().toLowerCase();
  for (const opt of control.rawOptions ?? []) {
    if ((opt.label || '').toLowerCase() === target || (opt.value || '').toLowerCase() === target) {
      return opt.value || opt.label;
    }
  }
  for (const rOpt of control.radioOptions ?? []) {
    if ((rOpt.label || '').toLowerCase() === target || (rOpt.value || '').toLowerCase() === target) {
      return rOpt.controlId;
    }
  }
  return null;
}

/**
 * Pure deterministic planner over inspected DOM controls + verified candidate package.
 * Never fabricates data, never falls back on guessed EEO/sponsorship answers,
 * never trusts draft answers, and preserves explicit required gaps for candidate input.
 */
export function planUniversalControls(inspected, pkg = {}) {
  const facts = pkg.profileFacts ?? {};
  const confirmedAnswers = Array.isArray(pkg.confirmedAnswers) ? pkg.confirmedAnswers : [];
  const answerMap = new Map();
  for (const a of confirmedAnswers) {
    const key = normalizeQuestionLabel(a.questionText);
    if (key && typeof a.answerText === 'string' && a.answerText.trim()) {
      answerMap.set(key, a);
    }
  }

  const actions = [];
  const uploads = [];
  const filled = [];
  const requiredGaps = [];
  const skippedOptional = [];
  const unsupported = [];

  for (const control of inspected.controls ?? []) {
    const htmlType = (control.htmlType || 'text').toLowerCase();
    const label = sanitizeDomText(control.label);
    const normalizedLabel = normalizeQuestionLabel(label);
    const combined = normalizeQuestionLabel(
      `${label} ${control.ariaLabel || ''} ${control.placeholder || ''} ${control.nameAttr || ''} ${control.idAttr || ''}`
    );

    if (htmlType === 'password' || /password|one-time|verification code|\botp\b|captcha/.test(combined)) {
      throw new UniversalHardStopError('UNIVERSAL_AUTH_OR_SECURITY_CONTROL', { label });
    }

    if (isProtectedDemographic(normalizedLabel) || isProtectedDemographic(combined)) {
      unsupported.push({ key: normalizedLabel || control.controlId, label, reason: 'Protected/EEO field is never auto-filled' });
      continue;
    }

    if (htmlType === 'file') {
      const isCover = /cover|letter/.test(combined);
      const isResume = !isCover && (/resume|cv|curriculum/.test(combined) || !combined);
      if (isCover) {
        if (pkg.coverLetter?.path) {
          if (!isArtifactCompatibleWithAccept(control.accept, pkg.coverLetter.contentType, pkg.coverLetter.filename || pkg.coverLetter.path)) {
            if (control.required) {
              requiredGaps.push({
                questionKey: control.nameAttr || control.idAttr || `file_${control.controlId}`,
                questionText: label || 'Cover letter upload',
                htmlType: 'file',
                requiredState: 'REQUIRED',
                options: [],
                reason: `Prepared cover letter artifact does not match accepted format (${control.accept})`,
              });
            }
            uploads.push({ controlId: control.controlId, artifactKind: 'coverLetter', accept: control.accept || '', filesAttached: control.filesAttached || 0, label, rejectedByAccept: true });
          } else {
            uploads.push({ controlId: control.controlId, artifactKind: 'coverLetter', accept: control.accept || '', filesAttached: control.filesAttached || 0, label, customTrigger: Boolean(control.customTrigger) });
          }
        } else if (control.required) {
          requiredGaps.push({
            questionKey: control.nameAttr || control.idAttr || `file_${control.controlId}`,
            questionText: label || 'Cover letter upload',
            htmlType: 'file',
            requiredState: 'REQUIRED',
            options: [],
            reason: 'Required cover letter artifact is missing',
          });
        }
        continue;
      }
      if (isResume) {
        if (pkg.cv?.path) {
          if (!isArtifactCompatibleWithAccept(control.accept, pkg.cv.contentType, pkg.cv.filename || pkg.cv.path)) {
            if (control.required) {
              requiredGaps.push({
                questionKey: control.nameAttr || control.idAttr || `file_${control.controlId}`,
                questionText: label || 'Resume / CV upload',
                htmlType: 'file',
                requiredState: 'REQUIRED',
                options: [],
                reason: `Prepared CV artifact does not match accepted format (${control.accept})`,
              });
            }
            uploads.push({ controlId: control.controlId, artifactKind: 'cv', accept: control.accept || '', filesAttached: control.filesAttached || 0, label, rejectedByAccept: true });
          } else {
            uploads.push({ controlId: control.controlId, artifactKind: 'cv', accept: control.accept || '', filesAttached: control.filesAttached || 0, label, customTrigger: Boolean(control.customTrigger) });
          }
        } else if (control.required) {
          requiredGaps.push({
            questionKey: control.nameAttr || control.idAttr || `file_${control.controlId}`,
            questionText: label || 'Resume / CV upload',
            htmlType: 'file',
            requiredState: 'REQUIRED',
            options: [],
            reason: 'Reviewed CV artifact is missing',
          });
        }
        continue;
      }
      if (control.required) {
        requiredGaps.push({
          questionKey: control.nameAttr || control.idAttr || `file_${control.controlId}`,
          questionText: label || 'Document upload',
          htmlType: 'file',
          requiredState: 'REQUIRED',
          options: [],
          reason: 'Unsupported required file upload requires candidate action',
        });
      }
      continue;
    }

    // Confirmed Q&A exact match takes highest priority (including authorized legal/work-auth questions)
    const confirmed = normalizedLabel ? answerMap.get(normalizedLabel) : null;
    if (confirmed) {
      if (htmlType === 'select' || htmlType === 'combobox') {
        const matchedOption = resolveOptionValue(control, confirmed.answerText);
        if (!matchedOption) {
          if (control.required) {
            requiredGaps.push({
              questionKey: control.nameAttr || control.idAttr || `q_${control.controlId}`,
              questionText: label,
              htmlType,
              requiredState: 'REQUIRED',
              options: control.options ?? [],
              reason: 'Confirmed answer does not match any form option',
            });
          }
          continue;
        }
        actions.push({ controlId: control.controlId, kind: 'select', value: matchedOption, label, source: confirmed.source || 'application_answers' });
        filled.push(label);
        continue;
      }
      if (htmlType === 'radio') {
        const matchedControlId = resolveOptionValue(control, confirmed.answerText);
        if (!matchedControlId) {
          if (control.required) {
            requiredGaps.push({
              questionKey: control.nameAttr || control.idAttr || `q_${control.controlId}`,
              questionText: label,
              htmlType: 'radio',
              requiredState: 'REQUIRED',
              options: control.options ?? [],
              reason: 'Confirmed answer does not match radio options',
            });
          }
          continue;
        }
        actions.push({ controlId: matchedControlId, kind: 'radio', value: confirmed.answerText, label, source: confirmed.source || 'application_answers' });
        filled.push(label);
        continue;
      }
      if (htmlType === 'checkbox') {
        const truthy = /^(yes|true|1|agree|confirm|checked)$/i.test(confirmed.answerText.trim());
        actions.push({ controlId: control.controlId, kind: 'checkbox', checked: truthy, label, source: confirmed.source || 'application_answers' });
        filled.push(label);
        continue;
      }
      actions.push({ controlId: control.controlId, kind: 'fill', value: confirmed.answerText, label, source: confirmed.source || 'application_answers' });
      filled.push(label);
      continue;
    }

    if (isSensitiveLegal(normalizedLabel)) {
      if (control.required) {
        requiredGaps.push({
          questionKey: control.nameAttr || control.idAttr || `legal_${control.controlId}`,
          questionText: label,
          htmlType,
          requiredState: 'REQUIRED',
          options: control.options ?? [],
          reason: 'Work authorization / sponsorship requires candidate confirmation',
        });
      }
      continue;
    }

    // Verified profile facts (only when not a custom question/unrecognized)
    let matchedFact = null;
    let source = null;
    if (/first\s*name|given\s*name|forename/.test(combined)) {
      const split = splitNameDeterministic(facts.fullName);
      if (facts.firstName) { matchedFact = facts.firstName; source = 'profileFacts.firstName'; }
      else if (split.deterministic) { matchedFact = split.firstName; source = 'profileFacts.fullName'; }
    } else if (/last\s*name|family\s*name|surname/.test(combined)) {
      const split = splitNameDeterministic(facts.fullName);
      if (facts.lastName) { matchedFact = facts.lastName; source = 'profileFacts.lastName'; }
      else if (split.deterministic) { matchedFact = split.lastName; source = 'profileFacts.fullName'; }
    } else if (!/company|employer|school|university|manager|referral/.test(combined)
        && (/full\s*name|your\s*name|\bname\b/.test(combined))) {
      matchedFact = facts.fullName || null;
      source = 'profileFacts.fullName';
    } else if (htmlType === 'email' || /\be-?mail\b/.test(combined)) {
      matchedFact = facts.email || null;
      source = 'profileFacts.email';
    } else if (htmlType === 'tel' || /phone|mobile|telephone/.test(combined)) {
      matchedFact = facts.phone || null;
      source = 'profileFacts.phone';
    } else if (/linkedin/.test(combined)) {
      matchedFact = facts.linkedin || null;
      source = 'profileFacts.linkedin';
    } else if (/github/.test(combined)) {
      matchedFact = facts.github || null;
      source = 'profileFacts.github';
    } else if (/portfolio|website|personal\s*site/.test(combined)) {
      matchedFact = facts.portfolio || null;
      source = 'profileFacts.portfolio';
    } else if (/\bcity\b|\blocation\b|current\s*location|where\s*are\s*you\s*based/.test(combined)) {
      matchedFact = facts.location || null;
      source = 'profileFacts.location';
    } else if (control.tagName === 'textarea' && /cover\s*letter/.test(combined)) {
      matchedFact = facts.coverLetterText || null;
      source = 'profileFacts.coverLetterText';
    }

    if (matchedFact && String(matchedFact).trim()) {
      if (htmlType === 'select' || htmlType === 'radio' || htmlType === 'combobox') {
        const resolved = resolveOptionValue(control, matchedFact);
        if (resolved) {
          actions.push({
            controlId: htmlType === 'radio' ? resolved : control.controlId,
            kind: htmlType === 'radio' ? 'radio' : 'select',
            value: htmlType === 'radio' ? matchedFact : resolved,
            label,
            source,
          });
          filled.push(label);
          continue;
        }
      } else if (htmlType !== 'checkbox') {
        actions.push({ controlId: control.controlId, kind: 'fill', value: String(matchedFact), label, source });
        filled.push(label);
        continue;
      }
    }

    if (control.required) {
      requiredGaps.push({
        questionKey: control.nameAttr || control.idAttr || `q_${normalizedLabel.replace(/[^a-z0-9]+/g, '_').slice(0, 48) || control.controlId}`,
        questionText: label || control.nameAttr || control.idAttr || `Required field ${control.controlId}`,
        htmlType,
        requiredState: 'REQUIRED',
        options: control.options ?? [],
        reason: 'Unknown required field without confirmed answer or verified fact',
      });
    } else {
      skippedOptional.push(label || control.controlId);
    }
  }

  return { actions, uploads, filled, requiredGaps, skippedOptional, unsupported };
}

/** Executes planned DOM actions & SHA-256 verified file uploads strictly via data-robin-control-id. */
export async function applyUniversalControlActions(page, planned, pkg, { artifactDir } = {}) {
  for (const action of planned.actions ?? []) {
    const selector = `[data-robin-control-id="${Number(action.controlId)}"]`;
    const loc = page.locator(selector);
    if (await loc.count() === 0) continue;
    if (action.kind === 'fill') {
      await loc.fill(String(action.value));
    } else if (action.kind === 'select') {
      const tag = await loc.evaluate((el) => el.tagName.toLowerCase()).catch(() => 'select');
      if (tag === 'select') {
        await loc.selectOption(String(action.value));
      } else {
        await loc.click();
        await page.locator('[role="option"]').first().waitFor({ state: 'visible', timeout: 1000 }).catch(() => {});
        const targetLower = String(action.value).trim().toLowerCase();
        const clickedOption = await loc.evaluate((el, targetText) => {
          const clean = (s) => String(s ?? '').replace(/[\u0000-\u001F\u007F]/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase();
          const ariaId = el.getAttribute('aria-controls') || el.getAttribute('aria-owns');
          let scope = ariaId ? document.getElementById(ariaId) : null;
          if (!scope) {
            scope = el.parentElement?.querySelector('[role="listbox"]') || null;
          }
          const containers = scope ? [scope] : Array.from(document.querySelectorAll('[role="listbox"]'));
          for (const container of containers) {
            for (const opt of Array.from(container.querySelectorAll('[role="option"], option, li'))) {
              const label = clean(opt.textContent);
              const val = clean(opt.getAttribute('data-value'));
              if (label === targetText || (val && val === targetText)) {
                opt.click();
                return true;
              }
            }
          }
          return false;
        }, targetLower);
        if (!clickedOption) {
          await page.keyboard.press('Escape').catch(() => {});
        }
      }
    } else if (action.kind === 'radio') {
      await loc.check({ force: false });
    } else if (action.kind === 'checkbox') {
      if (action.checked) await loc.check();
      else await loc.uncheck();
    }
  }

  const uploadStates = { cv: 'NO_FIELD', coverLetter: 'NO_FIELD' };
  for (const up of planned.uploads ?? []) {
    const artifact = up.artifactKind === 'coverLetter' ? pkg.coverLetter : pkg.cv;
    if (!artifact?.path) {
      uploadStates[up.artifactKind] = 'NO_ARTIFACT';
      continue;
    }
    if (up.rejectedByAccept || !isArtifactCompatibleWithAccept(up.accept, artifact.contentType, artifact.filename || artifact.path)) {
      uploadStates[up.artifactKind] = 'REJECTED';
      continue;
    }
    const resolved = path.resolve(artifact.path);
    if (artifactDir) {
      const root = path.resolve(artifactDir);
      if (!resolved.startsWith(root + path.sep) && resolved !== root) {
        throw new UniversalHardStopError('ARTIFACT_PATH_OUTSIDE_SANDBOX_DIR', { kind: up.artifactKind });
      }
    }
    if (!fs.existsSync(resolved)) {
      throw new UniversalHardStopError('UNIVERSAL_ARTIFACT_MISSING', { kind: up.artifactKind });
    }
    if (artifact.sha256) {
      const actualSha = crypto.createHash('sha256').update(fs.readFileSync(resolved)).digest('hex');
      if (actualSha !== artifact.sha256) {
        throw new UniversalHardStopError('PACKAGE_CHECKSUM_MISMATCH', { kind: up.artifactKind });
      }
    }
    const selector = `[data-robin-control-id="${Number(up.controlId)}"]`;
    const loc = page.locator(selector);
    if (await loc.count() > 0) {
      await loc.setInputFiles(resolved);
      const expectedName = path.basename(resolved);
      const domVerified = await loc.evaluate(
        (el, baseName) => Boolean(el.files && el.files.length > 0 && Array.from(el.files).some((f) => f.name === baseName)),
        expectedName
      ).catch(() => false);
      uploadStates[up.artifactKind] = domVerified ? 'ATTACHED' : 'REJECTED';
    }
  }
  return uploadStates;
}
