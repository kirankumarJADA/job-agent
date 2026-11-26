import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

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
  const h = String(hostname ?? '').toLowerCase();
  return h === 'localhost' || h === '127.0.0.1' || h === '::1' || h === '[::1]';
}

export function isBlockedHost(hostname, allowLocalTestHosts = false) {
  const h = String(hostname ?? '').toLowerCase().replace(/^\[|\]$/g, '');
  if (!h) return true;
  if (allowLocalTestHosts && isExplicitLoopbackHost(h)) return false;
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')
      || h.endsWith('.lan') || h.endsWith('.home.arpa')) {
    return true;
  }
  if (/^\d+\.\d+\.\d+\.\d+$/.test(h)) {
    return isIpv4PrivateOrSpecial(h) || true; // IP literals refused in production
  }
  if (h.includes(':')) {
    if (h === '::1' || h === '::' || /^fe[89ab]/i.test(h) || /^f[cd]/i.test(h)
        || h.includes('127.0.0.1') || h.includes('169.254.')) {
      return true;
    }
    return true; // IPv6 literals refused in production
  }
  return !h.includes('.');
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
export async function installUniversalNavigationGuard(page, expectedUrl, { allowLocalTestHosts = false } = {}) {
  let expectedOrigin;
  try {
    expectedOrigin = new URL(expectedUrl).origin;
  } catch {
    throw new UniversalHardStopError('UNSAFE_APPLICATION_URL');
  }
  await page.route('**/*', async (route) => {
    const reqUrl = route.request().url();
    if (reqUrl.startsWith('data:') || reqUrl.startsWith('blob:')) {
      await route.fallback();
      return;
    }
    let parsed;
    try {
      parsed = new URL(reqUrl);
    } catch {
      await route.abort('blockedbyclient');
      return;
    }
    if (isBlockedHost(parsed.hostname, allowLocalTestHosts)) {
      await route.abort('blockedbyclient');
      return;
    }
    const isDocument = route.request().resourceType() === 'document' && route.request().isNavigationRequest();
    if (isDocument && !universalPageIdentityMatches(expectedUrl, reqUrl, { allowLocalTestHosts })) {
      await route.abort('blockedbyclient');
      return;
    }
    if (!isDocument && parsed.origin !== expectedOrigin && !allowLocalTestHosts) {
      await route.abort('blockedbyclient');
      return;
    }
    await route.fallback();
  });
}

/**
 * Passively inspects the live DOM (including accessible same-origin iframes),
 * assigning ephemeral numeric `data-robin-control-id` attributes so page strings
 * are NEVER interpolated into Playwright selectors.
 */
export async function inspectLiveFormDom(page) {
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

    const isHiddenHoneypot = (el) => {
      if (!el || el.disabled) return true;
      if (el.tagName === 'INPUT' && (el.type || '').toLowerCase() === 'hidden') return true;
      if (el.getAttribute('aria-hidden') === 'true' || el.closest('[aria-hidden="true"]')) return true;
      const style = window.getComputedStyle(el);
      if (style && (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0')) return true;
      const rect = el.getBoundingClientRect();
      if (rect.width === 0 && rect.height === 0) return true;
      if (rect.left < -999 || rect.top < -999) return true;
      const tab = el.getAttribute('tabindex');
      if (tab === '-1' && (el.getAttribute('aria-hidden') === 'true' || rect.width <= 2 || rect.height <= 2)) return true;
      return false;
    };

    const resolveLabel = (doc, el) => {
      if (el.id) {
        const direct = Array.from(doc.querySelectorAll('label')).find((l) => l.htmlFor === el.id);
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
        const text = labelledBy.split(/\s+/).map((id) => doc.getElementById(id)?.textContent || '').join(' ');
        if (clean(text)) return clean(text);
      }
      const ariaLabel = clean(el.getAttribute('aria-label'));
      if (ariaLabel) return ariaLabel;
      const fieldset = el.closest('fieldset');
      if (fieldset) {
        const legend = fieldset.querySelector('legend');
        if (legend && clean(legend.textContent)) return clean(legend.textContent);
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

    for (const { doc, frameIndex } of docList) {
      const elements = Array.from(doc.querySelectorAll('input, select, textarea, [role="combobox"], [role="radiogroup"]'));
      for (const el of elements) {
        if (isHiddenHoneypot(el)) continue;
        const tag = el.tagName.toLowerCase();
        const type = tag === 'input' ? (el.type || 'text').toLowerCase()
          : tag === 'select' ? 'select'
          : tag === 'textarea' ? 'textarea'
          : (el.getAttribute('role') || 'custom').toLowerCase();
        if (['submit', 'button', 'reset', 'image'].includes(type)) continue;

        if (type === 'radio') {
          const name = el.getAttribute('name') || '';
          const groupKey = `${frameIndex}:${name || el.id || seq}`;
          if (seenRadioNames.has(groupKey)) continue;
          seenRadioNames.add(groupKey);
          const peers = name
            ? Array.from(doc.querySelectorAll('input[type="radio"]')).filter((r) => r.name === name && !isHiddenHoneypot(r))
            : [el];
          const optionControls = peers.map((r) => {
            const cid = String(seq++);
            r.setAttribute('data-robin-control-id', cid);
            return {
              controlId: cid,
              value: clean(r.value),
              label: clean(resolveLabel(doc, r) || r.value),
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
        const label = resolveLabel(doc, el);
        const req = Boolean(el.required)
          || el.getAttribute('aria-required') === 'true'
          || /\*\s*$/.test(label)
          || /\(required\)/i.test(label)
          || Boolean(el.closest('.field, .form-group')?.querySelector('.requiredInput[required], [data-required="true"]'));

        let options = [];
        if (tag === 'select') {
          options = Array.from(el.options)
            .map((o) => ({ value: clean(o.value), label: clean(o.textContent) }))
            .filter((o) => o.label && !/^select\b/i.test(o.label));
        } else if (el.getAttribute('role') === 'combobox') {
          const listId = el.getAttribute('aria-controls') || el.getAttribute('aria-owns');
          const listbox = listId ? doc.getElementById(listId) : el.parentElement?.querySelector('[role="listbox"]');
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
          tagName: tag,
          htmlType: type,
          role: clean(el.getAttribute('role')),
          label,
          nameAttr: clean(el.getAttribute('name')),
          idAttr: clean(el.id),
          placeholder: clean(el.getAttribute('placeholder')),
          ariaLabel: clean(el.getAttribute('aria-label')),
          accept: clean(el.getAttribute('accept')),
          required: req,
          options: options.map((o) => o.label),
          rawOptions: options,
          currentValue: type === 'checkbox' ? String(Boolean(el.checked)) : clean(el.value),
          filesAttached: type === 'file' ? (el.files ? el.files.length : 0) : 0,
        });
      }

      const buttons = Array.from(doc.querySelectorAll('button, input[type="submit"], input[type="button"], [role="button"]'));
      for (const btn of buttons) {
        if (isHiddenHoneypot(btn) || btn.disabled) continue;
        const text = clean(btn.tagName === 'INPUT' ? btn.value : btn.textContent);
        const typeAttr = (btn.getAttribute('type') || '').toLowerCase();
        const isSubmitMatch = /submit(\s+application)?|apply(\s+now)?|complete\s+application|send\s+application/i.test(text);
        const isNextMatch = !isSubmitMatch && /^(continue|next|save\s*(&|and)?\s*continue|proceed|step\s*\d)/i.test(text);
        if (isNextMatch && !nextButton) {
          const nid = String(seq++);
          btn.setAttribute('data-robin-next', nid);
          nextButton = { selector: `[data-robin-next="${nid}"]`, label: text, frameIndex };
        } else if ((isSubmitMatch || typeAttr === 'submit') && !submitButton) {
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
    const mfaPresent = /two-factor|one-time\s*(code|passcode)|authenticator code|enter\s*(the\s*)?(otp|verification code)/i.test(bodyText);
    const loginWallPresent = document.querySelectorAll('input[type="password"]').length > 0
      || /sign in to continue|log in to apply|please log in before applying/i.test(bodyText);

    const validationErrors = Array.from(document.querySelectorAll(
      '[role="alert"], .error, .field-error, .invalid-feedback, [aria-invalid="true"]'
    ))
      .map((el) => clean(el.textContent))
      .filter((t) => t.length > 0);

    const activeStepEl = document.querySelector('[data-step], [aria-current="step"], .step-indicator');
    const stepText = clean(activeStepEl?.textContent || '');

    return {
      url: location.href,
      formCount: document.querySelectorAll('form').length,
      crossOriginFrames,
      controls,
      nextButton,
      submitButton,
      validationErrors,
      stepHint: stepText,
      securitySignals: { captchaPresent, mfaPresent, loginWallPresent },
    };
  });
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
          uploads.push({ controlId: control.controlId, artifactKind: 'coverLetter', accept: control.accept || '', filesAttached: control.filesAttached || 0, label });
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
          uploads.push({ controlId: control.controlId, artifactKind: 'cv', accept: control.accept || '', filesAttached: control.filesAttached || 0, label });
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
        const optionLoc = page.locator('[role="option"], li').filter({ hasText: String(action.value) }).first();
        if (await optionLoc.count() > 0) await optionLoc.click();
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
      uploadStates[up.artifactKind] = 'ATTACHED';
    }
  }
  return uploadStates;
}
