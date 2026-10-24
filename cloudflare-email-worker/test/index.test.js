import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../src/index.js';
import { parseMime } from '../src/mime_parser.js';

describe('Cloudflare Email Worker', () => {

  const SECRET = 'test-cloudflare-secret-key-12345';
  const BACKEND = 'https://api.test.example';

  function mockMessage(rawContent, to = 'robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com', from = 'hr@employer.example') {
    let rejectReason = null;
    const encoder = new TextEncoder();
    const rawBytes = encoder.encode(rawContent);

    return {
      to,
      from,
      rawSize: rawBytes.byteLength,
      raw: new ReadableStream({
        start(controller) {
          controller.enqueue(rawBytes);
          controller.close();
        }
      }),
      setReject(reason) {
        rejectReason = reason;
      },
      getRejectReason() {
        return rejectReason;
      }
    };
  }

  it('successfully delivers parsed message with valid HMAC signature to backend', async () => {
    const rawMime = 'From: Employer HR <hr@employer.example>\r\n' +
      'To: robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com\r\n' +
      'Subject: Interview Invitation\r\n' +
      'Message-ID: <msg-123@employer.example>\r\n' +
      'Date: Sat, 24 Oct 2026 00:00:00 +0000\r\n' +
      'Content-Type: text/plain; charset=utf-8\r\n\r\n' +
      'Please join us for an interview at https://employer.example/interview/123';

    const msg = mockMessage(rawMime);
    let capturedRequest = null;

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => {
      capturedRequest = { url, opts };
      return {
        status: 200,
        json: async () => ({ status: 'stored' })
      };
    };

    try {
      await worker.email(msg, {
        ROBIN_BACKEND_URL: BACKEND,
        CLOUDFLARE_EMAIL_WORKER_SECRET: SECRET
      }, {});

      assert.ok(capturedRequest, 'fetch should have been invoked');
      assert.equal(capturedRequest.url, `${BACKEND}/api/v1/managed-mail/inbound/cloudflare`);
      assert.equal(capturedRequest.opts.method, 'POST');

      const headers = capturedRequest.opts.headers;
      assert.ok(headers['X-Robin-Timestamp'], 'timestamp header present');
      assert.ok(headers['X-Robin-Token'], 'token header present');
      assert.ok(headers['X-Robin-Signature'], 'signature header present');

      const body = JSON.parse(capturedRequest.opts.body);
      assert.equal(body.envelopeRecipient, 'robin+u7kq2m9x4vb3tn5p@mail.aplivenosi.com');
      assert.equal(body.envelopeSender, 'hr@employer.example');
      assert.equal(body.subject, 'Interview Invitation');
      assert.ok(body.text.includes('https://employer.example/interview/123'));
      assert.equal(msg.getRejectReason(), null, 'message should not be rejected');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('rejects message via setReject when backend returns 406 Not Acceptable', async () => {
    const rawMime = 'From: Unknown <unknown@test.example>\r\n' +
      'To: robin+notfound@mail.aplivenosi.com\r\n' +
      'Subject: Hello\r\n\r\n' +
      'Body text';

    const msg = mockMessage(rawMime, 'robin+notfound@mail.aplivenosi.com');

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      status: 406,
      json: async () => ({ status: 'rejected' })
    });

    try {
      await worker.email(msg, {
        ROBIN_BACKEND_URL: BACKEND,
        CLOUDFLARE_EMAIL_WORKER_SECRET: SECRET
      }, {});

      assert.ok(msg.getRejectReason()?.includes('Robin rejected delivery'), 'rejection reason set');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('throws error to let Cloudflare retry when backend returns 503', async () => {
    const rawMime = 'From: hr@employer.example\r\n' +
      'To: robin+token@mail.aplivenosi.com\r\n' +
      'Subject: System Down\r\n\r\n' +
      'Body';

    const msg = mockMessage(rawMime);

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      status: 503,
      json: async () => ({ status: 'retry' })
    });

    try {
      await assert.rejects(
        async () => {
          await worker.email(msg, {
            ROBIN_BACKEND_URL: BACKEND,
            CLOUDFLARE_EMAIL_WORKER_SECRET: SECRET
          }, {});
        },
        /Robin backend delivery returned HTTP 503/
      );
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('throws error when secret or backend URL is not configured', async () => {
    const msg = mockMessage('Subject: Test\r\n\r\nBody');
    await assert.rejects(
      async () => {
        await worker.email(msg, { ROBIN_BACKEND_URL: '' }, {});
      },
      /Email worker configuration error/
    );
  });

  it('rejects message exceeding size limit', async () => {
    const msg = mockMessage('A'.repeat(500));
    await worker.email(msg, {
      ROBIN_BACKEND_URL: BACKEND,
      CLOUDFLARE_EMAIL_WORKER_SECRET: SECRET,
      MAX_EMAIL_BYTES: 100 // 100 bytes max
    }, {});

    assert.ok(msg.getRejectReason()?.includes('exceeds maximum allowed size'), 'oversize rejected');
  });

  describe('MIME Parser', () => {
    it('unfolds multiline headers and parses multipart MIME', () => {
      const multipartMime = 'From: "Hiring Manager" <hiring@company.example>\r\n' +
        'Subject: Re: Application for Senior\r\n' +
        ' Engineer\r\n' +
        'Date: Sat, 24 Oct 2026 01:00:00 +0000\r\n' +
        'Message-ID: <part-1234@company.example>\r\n' +
        'Received-SPF: Pass (mailfrom=hiring@company.example)\r\n' +
        'Content-Type: multipart/alternative; boundary="boundary-xyz"\r\n\r\n' +
        '--boundary-xyz\r\n' +
        'Content-Type: text/plain; charset=utf-8\r\n' +
        'Content-Transfer-Encoding: quoted-printable\r\n\r\n' +
        'Thank you for applying=20=\r\n' +
        'to our position.\r\n' +
        '--boundary-xyz\r\n' +
        'Content-Type: text/html; charset=utf-8\r\n\r\n' +
        '<p>Thank you for applying to our position.</p>\r\n' +
        '--boundary-xyz--';

      const parsed = parseMime(multipartMime);
      assert.equal(parsed.from, '"Hiring Manager" <hiring@company.example>');
      assert.equal(parsed.subject, 'Re: Application for Senior Engineer');
      assert.equal(parsed.messageId, '<part-1234@company.example>');
      assert.equal(parsed.spf, 'pass');
      assert.ok(parsed.text.includes('Thank you for applying to our position'));
      assert.ok(parsed.html.includes('<p>Thank you for applying to our position.</p>'));
    });
  });
});
