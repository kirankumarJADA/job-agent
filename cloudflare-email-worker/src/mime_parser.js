/**
 * Lightweight, zero-dependency RFC 5322 MIME parser for Cloudflare Workers.
 */

export function parseMime(rawText) {
  if (!rawText || typeof rawText !== 'string') {
    return {
      from: '',
      subject: '',
      date: '',
      messageId: '',
      text: '',
      html: '',
      spf: '',
      dkim: '',
      spam: 'no'
    };
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
  const contentType = getHeader(headers, 'content-type') || 'text/plain';

  // Authentication verdict extraction
  const receivedSpf = getHeader(headers, 'received-spf');
  const authResults = getHeader(headers, 'authentication-results');
  let spf = '';
  if (receivedSpf && /pass/i.test(receivedSpf)) spf = 'pass';
  else if (authResults && /spf=pass/i.test(authResults)) spf = 'pass';
  else if (receivedSpf && /fail/i.test(receivedSpf)) spf = 'fail';

  let dkim = '';
  if (authResults && /dkim=pass/i.test(authResults)) dkim = 'pass';
  else if (getHeader(headers, 'dkim-signature')) dkim = 'pass';

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
    text,
    html,
    spf,
    dkim,
    spam
  };
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
