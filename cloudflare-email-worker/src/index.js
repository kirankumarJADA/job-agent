/**
 * Cloudflare Email Routing Worker for Robin AI Job Agent (Phase 20).
 *
 * <p>Cloudflare Email Routing dispatches inbound emails matching
 * {@code robin@mail.aplivenosi.com} (including all plus-subaddressed messages
 * like {@code robin+<token>@mail.aplivenosi.com}) to this worker.
 *
 * <p>The worker safely parses the MIME payload, bounds its size, computes an
 * HMAC-SHA256 signature using the shared secret, and securely delivers it
 * to Robin's backend at {@code /api/v1/managed-mail/inbound/cloudflare}.
 */

import { parseMime } from './mime_parser.js';

export default {
  async email(message, env, ctx) {
    const maxSize = Number(env.MAX_EMAIL_BYTES || 10 * 1024 * 1024); // 10MB default
    if (message.rawSize && message.rawSize > maxSize) {
      console.warn(`[Robin-Email-Worker] Inbound email rejected: rawSize ${message.rawSize} > ${maxSize}`);
      if (typeof message.setReject === 'function') {
        message.setReject(`Message size ${message.rawSize} exceeds maximum allowed size of ${maxSize} bytes`);
      }
      return;
    }

    const backendUrl = env.ROBIN_BACKEND_URL;
    const inboundPath = env.ROBIN_INBOUND_PATH || '/api/v1/managed-mail/inbound/cloudflare';
    const secret = env.CLOUDFLARE_EMAIL_WORKER_SECRET;

    if (!backendUrl || !secret) {
      console.error('[Robin-Email-Worker] Backend URL or secret is missing. Retrying delivery later.');
      throw new Error('Email worker configuration error');
    }

    const rawBuffer = await new Response(message.raw).arrayBuffer();
    if (rawBuffer.byteLength > maxSize) {
      console.warn(`[Robin-Email-Worker] Inbound email rejected: byteLength ${rawBuffer.byteLength} > ${maxSize}`);
      if (typeof message.setReject === 'function') {
        message.setReject(`Message size ${rawBuffer.byteLength} exceeds maximum allowed size of ${maxSize} bytes`);
      }
      return;
    }

    const rawText = new TextDecoder('utf-8').decode(rawBuffer);
    const parsed = parseMime(rawText);

    const payload = {
      envelopeRecipient: message.to,
      envelopeSender: message.from || '',
      from: parsed.from,
      subject: parsed.subject,
      text: parsed.text,
      html: parsed.html,
      messageId: parsed.messageId,
      date: parsed.date,
      spf: parsed.spf,
      dkim: parsed.dkim,
      spam: parsed.spam
    };

    const payloadJson = JSON.stringify(payload);
    const payloadBytes = new TextEncoder().encode(payloadJson);
    const payloadHashBuffer = await crypto.subtle.digest('SHA-256', payloadBytes);
    const payloadSha256 = bufferToHex(payloadHashBuffer);

    const timestamp = Math.floor(Date.now() / 1000).toString();
    const token = crypto.randomUUID ? crypto.randomUUID() : generateRandomToken();
    const signatureData = `${timestamp}.${token}.${payloadSha256}`;

    const key = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign']
    );
    const signatureBuffer = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(signatureData));
    const signature = bufferToHex(signatureBuffer);

    const endpoint = `${backendUrl.replace(/\/+$/, '')}${inboundPath}`;
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Robin-Timestamp': timestamp,
        'X-Robin-Token': token,
        'X-Robin-Signature': signature
      },
      body: payloadJson
    });

    if (response.status === 200) {
      // 200: Successfully stored or idempotent duplicate
      return;
    }

    if (response.status === 406) {
      // 406: Terminal rejection (unknown recipient, malformed, stale)
      const errBody = await response.json().catch(() => ({ status: 'rejected' }));
      console.info(`[Robin-Email-Worker] Inbound delivery rejected by Robin: status=${errBody.status || 'rejected'}`);
      if (typeof message.setReject === 'function') {
        message.setReject(`Robin rejected delivery: ${errBody.status || 'rejected'}`);
      }
      return;
    }

    // 401, 503, or transient network failure: throw so Cloudflare retries
    console.error(`[Robin-Email-Worker] Delivery to backend failed: HTTP ${response.status}. Cloudflare will retry.`);
    throw new Error(`Robin backend delivery returned HTTP ${response.status}`);
  }
};

function bufferToHex(buffer) {
  const bytes = new Uint8Array(buffer);
  let hex = '';
  for (let i = 0; i < bytes.length; i++) {
    hex += bytes[i].toString(16).padStart(2, '0');
  }
  return hex;
}

function generateRandomToken() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return bufferToHex(bytes);
}
