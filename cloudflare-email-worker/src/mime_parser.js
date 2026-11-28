/**
 * Lightweight, zero-dependency RFC 5322 MIME parser for Cloudflare Workers.
 */

const DOMAIN_RE = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$/;
const AUTHSERV_RE = /^[a-z0-9._-]+$/;

export function parseMime(rawText, options = {}) {
  const empty = {
    from: '',
    subject: '',
    date: '',
    messageId: '',
    inReplyTo: '',
    references: '',
    text: '',
    html: '',
    spf: '',
    dkim: '',
    dkimDomain: '',
    dmarc: '',
    authServId: '',
    authTrusted: false,
    authConflict: false,
    spam: 'no'
  };

  if (!rawText || typeof rawText !== 'string') {
    return empty;
  }

  // Split headers and body at the first blank line
  const headerEnd = rawText.search(/\r?\n\r?\n/);
  const headerSection = headerEnd !== -1 ? rawText.substring(0, headerEnd) : rawText;
  const bodySection = headerEnd !== -1 ? rawText.substring(headerEnd).replace(/^\r?\n\r?\n/, '') : '';

  const headers = parseHeaders(headerSection);

  const from = getHeader(headers, 'from');
  const subject = getHeader(headers, 'subject');
  const date = getHeader(headers, 'date');
  const messageId = getHeader(headers, 'message-id');
  // Reply headers let the backend attach a follow-up to its thread; it treats them as a hint only.
  const inReplyTo = getHeader(headers, 'in-reply-to');
  const references = getHeader(headers, 'references');
  const contentType = getHeader(headers, 'content-type') || 'text/plain';

  const auth = options?.ignoreAuthHeaders
    ? { spf: '', dkim: '', dkimDomain: '', dmarc: '', authServId: '', authTrusted: false, authConflict: false }
    : extractAuthenticationVerdicts(headers, options?.trustedAuthServIds);

  let spam = 'no';
  const spamHeader = getHeader(headers, 'x-spam-flag') || getHeader(headers, 'x-spam-status');
  if (spamHeader && /yes|true/i.test(spamHeader)) spam = 'yes';

  // Body extraction
  const { text, html } = extractBody(bodySection, contentType);

  return {
    from,
    subject,
    date,
    messageId,
    inReplyTo,
    references,
    text,
    html,
    spf: auth.spf,
    dkim: auth.dkim,
    dkimDomain: auth.dkimDomain,
    dmarc: auth.dmarc,
    authServId: auth.authServId,
    authTrusted: auth.authTrusted,
    authConflict: auth.authConflict,
    spam
  };
}

/**
 * Extracts authentication verdicts strictly from Cloudflare's documented
 * ForwardableEmailMessage.headers (`Headers`) interface, ignoring raw MIME
 * headers which cannot be relied upon to preserve Cloudflare's prepended order.
 *
 * Fail-closed rules:
 * - If message.headers is missing or exposes no Authentication-Results, fail closed.
 * - Never call Headers.getAll on non-Set-Cookie headers (unsupported in Workers runtime);
 *   split comma-joined Fetch Headers values outside RFC 5322 comments and quotes.
 * - Ignore sender-injected Received-SPF unless a trusted Authentication-Results is present;
 *   do not derive SPF/DKIM/DMARC from untrusted headers or DKIM-Signature presence.
 */
export function extractAuthenticationFromHeaders(messageHeaders, trustedAuthServIdsRaw) {
  const unverified = {
    spf: '',
    dkim: '',
    dkimDomain: '',
    dmarc: '',
    authServId: '',
    authTrusted: false,
    authConflict: false
  };

  if (!messageHeaders || typeof messageHeaders.get !== 'function') {
    return unverified;
  }

  const rawAuthResults = messageHeaders.get('authentication-results');
  const authHeaders = splitCombinedHeaderValues(rawAuthResults);

  // Only consume Authentication-Results from the documented provider Headers interface.
  // Never fall back to Received-SPF or raw MIME authentication headers.
  const headerMap = new Map();
  if (authHeaders.length > 0) {
    headerMap.set('authentication-results', authHeaders);
  }
  return extractAuthenticationVerdicts(headerMap, trustedAuthServIdsRaw);
}

function splitCombinedHeaderValues(rawValue) {
  if (Array.isArray(rawValue)) {
    return rawValue
      .map((item) => String(item || '').trim())
      .filter(Boolean);
  }
  if (!rawValue || typeof rawValue !== 'string') {
    return [];
  }
  const withoutComments = stripCommentsOutsideQuotes(rawValue);
  return splitOutsideQuotes(withoutComments, ',')
    .map((part) => part.trim())
    .filter(Boolean);
}

function normalizeTrustedAuthServIds(raw) {
  if (!raw) return new Set();
  const items = typeof raw === 'string'
    ? raw.split(/[\s,]+/)
    : Array.isArray(raw) || raw instanceof Set
      ? Array.from(raw)
      : [];
  const out = new Set();
  for (const item of items) {
    const cleaned = String(item || '').trim().toLowerCase().replace(/\.$/, '');
    if (cleaned && AUTHSERV_RE.test(cleaned)) {
      out.add(cleaned);
    }
  }
  return out;
}

/**
 * Fail-closed authentication extraction:
 * - Never infer dkim=pass from the presence of a DKIM-Signature header.
 * - Never trust Authentication-Results unless an explicit trusted authserv-id
 *   allowlist is configured AND the topmost header's authserv-id matches it.
 * - Reject conflicting verdicts across headers from the same trusted receiver
 *   or multiple Received-SPF headers.
 */
function extractAuthenticationVerdicts(headers, trustedAuthServIdsRaw) {
  const trustedSet = normalizeTrustedAuthServIds(trustedAuthServIdsRaw);
  const authHeaders = headers.get('authentication-results') || [];
  const receivedSpfHeaders = headers.get('received-spf') || [];

  const receivedSpfVerdicts = receivedSpfHeaders
    .map(parseReceivedSpfVerdict)
    .filter(Boolean);
  const distinctReceivedSpf = [...new Set(receivedSpfVerdicts)];

  let authConflict = false;
  let spf = '';
  if (distinctReceivedSpf.length > 1) {
    authConflict = true;
    spf = 'fail';
  } else if (distinctReceivedSpf.length === 1) {
    spf = distinctReceivedSpf[0];
  }

  let dkim = '';
  let dkimDomain = '';
  let dmarc = '';
  let authServId = '';
  let authTrusted = false;

  if (trustedSet.size === 0 || authHeaders.length === 0) {
    return { spf, dkim, dkimDomain, dmarc, authServId, authTrusted, authConflict };
  }

  const parsedTop = parseAuthResultsHeader(authHeaders[0]);
  authServId = parsedTop.authServId;
  if (!authServId || !trustedSet.has(authServId)) {
    return { spf, dkim, dkimDomain, dmarc, authServId, authTrusted: false, authConflict };
  }

  authTrusted = true;

  // Fail closed if another Authentication-Results header claiming a trusted
  // authserv-id disagrees with the topmost header.
  for (let i = 1; i < authHeaders.length; i++) {
    const other = parseAuthResultsHeader(authHeaders[i]);
    if (other.authServId && trustedSet.has(other.authServId)) {
      if (other.dkim !== parsedTop.dkim
          || other.dkimDomain !== parsedTop.dkimDomain
          || other.dmarc !== parsedTop.dmarc
          || other.spf !== parsedTop.spf) {
        return {
          spf: 'fail',
          dkim: 'fail',
          dkimDomain: '',
          dmarc: 'fail',
          authServId,
          authTrusted: false,
          authConflict: true
        };
      }
    }
  }

  if (parsedTop.conflict) {
    return {
      spf: 'fail',
      dkim: 'fail',
      dkimDomain: '',
      dmarc: 'fail',
      authServId,
      authTrusted: false,
      authConflict: true
    };
  }

  dkim = parsedTop.dkim;
  dkimDomain = dkim === 'pass' ? parsedTop.dkimDomain : '';
  dmarc = parsedTop.dmarc;

  if (parsedTop.spf) {
    if (spf && spf !== parsedTop.spf) {
      authConflict = true;
      spf = 'fail';
    } else {
      spf = parsedTop.spf;
    }
  }

  return { spf, dkim, dkimDomain, dmarc, authServId, authTrusted, authConflict };
}

function parseReceivedSpfVerdict(headerVal) {
  const cleaned = stripCommentsOutsideQuotes(String(headerVal || '')).trim().toLowerCase();
  const m = cleaned.match(/^(pass|fail|softfail|neutral|none|temperror|permerror)\b/);
  return m ? m[1] : '';
}

function parseAuthResultsHeader(rawValue) {
  const cleaned = stripCommentsOutsideQuotes(String(rawValue || ''));
  const semi = splitOutsideQuotes(cleaned, ';');
  if (semi.length < 2) {
    return { authServId: '', dkim: '', dkimDomain: '', spf: '', dmarc: '', conflict: false };
  }
  const authServToken = semi[0].trim().split(/\s+/)[0].toLowerCase().replace(/\.$/, '');
  const authServId = AUTHSERV_RE.test(authServToken) ? authServToken : '';

  const dkimEntries = [];
  const spfVerdicts = [];
  const dmarcVerdicts = [];

  for (let i = 1; i < semi.length; i++) {
    const clause = semi[i].trim();
    if (!clause) continue;

    const dkimMatch = clause.match(/^dkim\s*=\s*([a-z0-9_-]+)/i);
    if (dkimMatch) {
      const verdict = dkimMatch[1].toLowerCase();
      const domain = extractDkimDomainFromClause(clause);
      dkimEntries.push({ verdict, domain });
      continue;
    }

    const spfMatch = clause.match(/^spf\s*=\s*([a-z0-9_-]+)/i);
    if (spfMatch) {
      spfVerdicts.push(spfMatch[1].toLowerCase());
      continue;
    }

    const dmarcMatch = clause.match(/^dmarc\s*=\s*([a-z0-9_-]+)/i);
    if (dmarcMatch) {
      dmarcVerdicts.push(dmarcMatch[1].toLowerCase());
    }
  }

  let conflict = false;
  let dkim = '';
  let dkimDomain = '';

  const distinctSpf = [...new Set(spfVerdicts)];
  const spf = distinctSpf.length > 1 ? 'fail' : (distinctSpf[0] || '');
  if (distinctSpf.length > 1) conflict = true;

  const distinctDmarc = [...new Set(dmarcVerdicts)];
  const dmarc = distinctDmarc.length > 1 ? 'fail' : (distinctDmarc[0] || '');
  if (distinctDmarc.length > 1) conflict = true;

  if (dkimEntries.length > 0) {
    const anyBad = dkimEntries.some((e) => e.verdict === 'fail' || e.verdict === 'permerror' || e.verdict === 'temperror' || e.verdict === 'policy');
    const passes = dkimEntries.filter((e) => e.verdict === 'pass');
    if (anyBad && passes.length > 0) {
      conflict = true;
      dkim = 'fail';
    } else if (anyBad) {
      const bad = dkimEntries.find((e) => e.verdict === 'fail' || e.verdict === 'permerror' || e.verdict === 'temperror' || e.verdict === 'policy');
      dkim = bad.verdict;
    } else if (passes.length > 0) {
      const passDomains = [...new Set(passes.map((e) => e.domain).filter(Boolean))];
      if (passDomains.length > 1) {
        conflict = true;
        dkim = 'fail';
      } else if (passDomains.length === 1) {
        dkim = 'pass';
        dkimDomain = passDomains[0];
      }
      // If dkim=pass lacks a valid header.d / header.i domain, leave dkim='' and dkimDomain='' (fail closed).
    } else {
      dkim = dkimEntries[0].verdict;
    }
  }

  return { authServId, dkim, dkimDomain, spf, dmarc, conflict };
}

function extractDkimDomainFromClause(clause) {
  const headerD = clause.match(/(?:^|\s)header\.d\s*=\s*(?:"([^"]+)"|([^\s;()]+))/i);
  if (headerD) {
    const candidate = normalizeDomain(headerD[1] || headerD[2] || '');
    if (candidate) return candidate;
  }
  const headerI = clause.match(/(?:^|\s)header\.i\s*=\s*(?:"[^"@]*@([^"]+)"|[^\s;@]*@([^\s;()]+))/i);
  if (headerI) {
    const candidate = normalizeDomain(headerI[1] || headerI[2] || '');
    if (candidate) return candidate;
  }
  return '';
}

function normalizeDomain(raw) {
  const cleaned = String(raw || '').trim().toLowerCase().replace(/^@+/, '').replace(/\.+$/, '');
  return DOMAIN_RE.test(cleaned) ? cleaned : '';
}

function stripCommentsOutsideQuotes(text) {
  let out = '';
  let inQuotes = false;
  let escaped = false;
  let commentDepth = 0;

  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (escaped) {
      if (commentDepth === 0) out += ch;
      escaped = false;
      continue;
    }
    if (ch === '\\') {
      if (inQuotes || commentDepth > 0) {
        if (commentDepth === 0) out += ch;
        escaped = true;
        continue;
      }
    }
    if (!commentDepth && ch === '"') {
      inQuotes = !inQuotes;
      out += ch;
      continue;
    }
    if (!inQuotes) {
      if (ch === '(') {
        commentDepth++;
        out += ' ';
        continue;
      }
      if (ch === ')') {
        if (commentDepth > 0) commentDepth--;
        out += ' ';
        continue;
      }
    }
    if (commentDepth === 0) {
      out += ch;
    }
  }
  return out;
}

function splitOutsideQuotes(text, delimiter) {
  const parts = [];
  let current = '';
  let inQuotes = false;
  let escaped = false;

  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (escaped) {
      current += ch;
      escaped = false;
      continue;
    }
    if (inQuotes && ch === '\\') {
      current += ch;
      escaped = true;
      continue;
    }
    if (ch === '"') {
      inQuotes = !inQuotes;
      current += ch;
      continue;
    }
    if (!inQuotes && ch === delimiter) {
      parts.push(current);
      current = '';
      continue;
    }
    current += ch;
  }
  parts.push(current);
  return parts;
}

function parseHeaders(headerText) {
  const lines = headerText.split(/\r?\n/);
  const unfolded = [];
  for (const line of lines) {
    if ((line.startsWith(' ') || line.startsWith('\t')) && unfolded.length > 0) {
      unfolded[unfolded.length - 1] += ' ' + line.trim();
    } else if (line.trim().length > 0) {
      unfolded.push(line);
    }
  }

  const map = new Map();
  for (const entry of unfolded) {
    const colon = entry.indexOf(':');
    if (colon > 0) {
      const key = entry.substring(0, colon).trim().toLowerCase();
      const val = entry.substring(colon + 1).trim();
      if (!map.has(key)) {
        map.set(key, []);
      }
      map.get(key).push(val);
    }
  }
  return map;
}

function getHeader(headers, name) {
  const list = headers.get(name.toLowerCase());
  return list && list.length > 0 ? list[0] : '';
}

function extractBody(bodyText, contentType) {
  const ctLower = contentType.toLowerCase();
  if (ctLower.includes('multipart/')) {
    const boundaryMatch = contentType.match(/boundary=["']?([^"';\s]+)["']?/i);
    if (boundaryMatch) {
      const boundary = boundaryMatch[1];
      return parseMultipart(bodyText, boundary);
    }
  }

  // Single part
  const decoded = decodeBody(bodyText, contentType);
  if (ctLower.includes('text/html')) {
    return { text: '', html: decoded };
  }
  return { text: decoded, html: '' };
}

function parseMultipart(bodyText, boundary) {
  const parts = bodyText.split('--' + boundary);
  let text = '';
  let html = '';

  for (const part of parts) {
    if (part.trim() === '--' || part.trim() === '') continue;
    const headerEnd = part.search(/\r?\n\r?\n/);
    if (headerEnd === -1) continue;

    const partHeaders = parseHeaders(part.substring(0, headerEnd));
    const partBody = part.substring(headerEnd).replace(/^\r?\n\r?\n/, '');
    const partCt = getHeader(partHeaders, 'content-type') || 'text/plain';
    const cte = getHeader(partHeaders, 'content-transfer-encoding');

    const decoded = decodeContentTransfer(partBody, cte);

    if (partCt.toLowerCase().includes('text/plain') && !text) {
      text = decoded.trim();
    } else if (partCt.toLowerCase().includes('text/html') && !html) {
      html = decoded.trim();
    } else if (partCt.toLowerCase().includes('multipart/')) {
      const subBoundaryMatch = partCt.match(/boundary=["']?([^"';\s]+)["']?/i);
      if (subBoundaryMatch) {
        const sub = parseMultipart(partBody, subBoundaryMatch[1]);
        if (!text && sub.text) text = sub.text;
        if (!html && sub.html) html = sub.html;
      }
    }
  }

  return { text, html };
}

function decodeBody(bodyText, contentType) {
  // Check for transfer encoding in headers or content
  return bodyText.trim();
}

function decodeContentTransfer(bodyText, encoding) {
  if (!encoding) return bodyText;
  const enc = encoding.toLowerCase().trim();
  if (enc === 'quoted-printable') {
    return decodeQuotedPrintable(bodyText);
  }
  if (enc === 'base64') {
    try {
      const clean = bodyText.replace(/\s+/g, '');
      return atob(clean);
    } catch {
      return bodyText;
    }
  }
  return bodyText;
}

function decodeQuotedPrintable(str) {
  return str
    .replace(/=\r?\n/g, '') // soft line breaks
    .replace(/=([A-Fa-f0-9]{2})/g, (_, hex) => {
      try {
        return String.fromCharCode(parseInt(hex, 16));
      } catch {
        return '=' + hex;
      }
    });
}
