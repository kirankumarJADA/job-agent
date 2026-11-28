import http from 'node:http';

const SANDBOX_POSTING_URL_DEFAULT = 'https://boards.greenhouse.io/robin-sandbox/jobs/8200681';

/**
 * Starts a local stateful HTTP fixture server modeling Greenhouse's Remix apply flow
 * strictly in memory / 127.0.0.1 for deterministic Playwright sandbox testing.
 *
 * All browser requests to `boards.greenhouse.io/robin-sandbox/**` or
 * `job-boards.greenhouse.io/robin-sandbox/**` are routed to this fixture via Playwright
 * `page.route` while every external host is aborted — zero external network traffic.
 */
export async function startGreenhouseSandboxFixture({
  boardToken = 'robin-sandbox',
  jobPostId = '8200681',
  confirmationId = 'GH-SBX-849201',
  submissionStatus = 200,
  includeSubmissionPost = true,
  postTargetPath = null,
  includeJobApplicationKey = true,
  includeConfirmationContainer = true,
  includeConfirmationId = true,
  confirmationText = 'Your application has been submitted.',
  confirmationHeading = 'Application submitted!',
  captchaBeforeClick = false,
  loginWallAfterSubmit = false,
} = {}) {
  const applyPath = `/${boardToken}/jobs/${jobPostId}`;
  const confirmPath = `${applyPath}/confirmation`;
  const submissions = [];
  const analyticsRequests = [];
  const otherRequests = [];

  const renderApplyPage = () => {
    const captchaSnippet = captchaBeforeClick
      ? '<div class="g-recaptcha" data-sitekey="sandbox-key">Please verify you are human (CAPTCHA)</div>'
      : '';
    const postUrl = postTargetPath || applyPath;
    const submitScript = includeSubmissionPost
      ? `
        document.getElementById('application_form').addEventListener('submit', async (e) => {
          e.preventDefault();
          const bodyObj = ${
            includeJobApplicationKey
              ? `{ job_application: { first_name: document.getElementById('first_name')?.value || '', email: document.getElementById('email')?.value || '' } }`
              : `{ analytics_event: 'button_clicked' }`
          };
          const res = await fetch(${JSON.stringify(postUrl)}, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(bodyObj),
          });
          if (res.ok || ${submissionStatus >= 400 ? 'true' : 'false'}) {
            window.location.href = ${JSON.stringify(confirmPath)};
          }
        });
      `
      : `
        document.getElementById('application_form').addEventListener('submit', async (e) => {
          e.preventDefault();
          await fetch('/analytics/ping', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ event: 'clicked_without_submit' }),
          }).catch(() => {});
          window.location.href = ${JSON.stringify(confirmPath)};
        });
      `;

    return `<!doctype html>
<html>
<head><title>Sandbox Engineer at Robin Sandbox</title></head>
<body>
<div id="application">
  <h1>Sandbox Software Engineer</h1>
  ${captchaSnippet}
  <form id="application_form" action="${applyPath}" method="post">
    <div class="field">
      <label for="first_name">First name<span>*</span></label>
      <input id="first_name" type="text" required />
    </div>
    <div class="field">
      <label for="last_name">Last name<span>*</span></label>
      <input id="last_name" type="text" required />
    </div>
    <div class="field">
      <label for="email">Email address<span>*</span></label>
      <input id="email" type="email" required />
    </div>
    <div class="field">
      <label for="phone">Phone</label>
      <input id="phone" type="tel" />
    </div>
    <div class="field upload">
      <label for="resume">Resume</label>
      <input id="resume" type="file" accept=".pdf" />
    </div>
    <button type="submit" id="submit_app">Submit Application</button>
  </form>
</div>
<script>${submitScript}</script>
</body>
</html>`;
  };

  const renderConfirmationPage = () => {
    if (loginWallAfterSubmit) {
      return `<!doctype html><html><body><main>Please sign in to continue. Session expired.</main></body></html>`;
    }
    const idAttr = includeConfirmationId && confirmationId ? ` data-confirmation-id="${confirmationId}"` : '';
    const idLine = includeConfirmationId && confirmationId ? `<p>Confirmation ID: <strong>${confirmationId}</strong></p>` : '';
    const inner = `
      <h2>${confirmationHeading}</h2>
      <p>${confirmationText}</p>
      ${idLine}
      <input type="hidden" name="csrf_token" value="secret_csrf_should_not_leak" />
    `;
    if (includeConfirmationContainer) {
      return `<!doctype html><html><body><div class="confirmation"${idAttr}>${inner}</div></body></html>`;
    }
    return `<!doctype html><html><body><main>${inner}</main></body></html>`;
  };

  const server = http.createServer(async (req, res) => {
    const parsedUrl = new URL(req.url, 'http://127.0.0.1');
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const bodyText = Buffer.concat(chunks).toString('utf8');

    if (req.method === 'GET' && parsedUrl.pathname === applyPath) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(renderApplyPage());
      return;
    }

    if (req.method === 'POST' && (parsedUrl.pathname === applyPath || (postTargetPath && parsedUrl.pathname === postTargetPath))) {
      let jsonBody = null;
      try { jsonBody = JSON.parse(bodyText); } catch { /* ignore */ }
      submissions.push({
        path: parsedUrl.pathname,
        method: req.method,
        contentType: req.headers['content-type'] || '',
        body: jsonBody,
      });
      res.writeHead(submissionStatus, { 'Content-Type': 'application/json; charset=utf-8' });
      if (submissionStatus >= 200 && submissionStatus < 300) {
        const respPayload = includeConfirmationId && confirmationId
          ? { ok: true, confirmation_id: confirmationId }
          : { ok: true };
        res.end(JSON.stringify(respPayload));
      } else {
        res.end(JSON.stringify({ ok: false, error: 'Rejected by sandbox server' }));
      }
      return;
    }

    if (req.method === 'POST' && parsedUrl.pathname === '/analytics/ping') {
      analyticsRequests.push({ path: parsedUrl.pathname, bodyText });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    if (req.method === 'GET' && parsedUrl.pathname === confirmPath) {
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(renderConfirmationPage());
      return;
    }

    otherRequests.push({ method: req.method, path: parsedUrl.pathname });
    res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end('<html><body>Not Found</body></html>');
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  const targetUrl = `https://boards.greenhouse.io${applyPath}`;

  /**
   * Playwright route bridge: forwards ONLY sandbox URLs to local 127.0.0.1 fixture server
   * and aborts every other outbound network request.
   */
  const attachToPage = async (page) => {
    await page.route('**/*', async (route) => {
      const request = route.request();
      let parsed;
      try {
        parsed = new URL(request.url());
      } catch {
        return route.abort();
      }
      if (!['boards.greenhouse.io', 'job-boards.greenhouse.io'].includes(parsed.hostname.toLowerCase())) {
        return route.abort();
      }
      const upstream = `${baseUrl}${parsed.pathname}${parsed.search}`;
      const resp = await fetch(upstream, {
        method: request.method(),
        headers: request.headers(),
        body: request.method() === 'GET' || request.method() === 'HEAD' ? undefined : request.postData() ?? undefined,
      });
      const responseBody = Buffer.from(await resp.arrayBuffer());
      const contentType = resp.headers.get('content-type') || 'text/html; charset=utf-8';
      return route.fulfill({ status: resp.status, contentType, body: responseBody });
    });
  };

  return {
    baseUrl,
    targetUrl,
    confirmUrl: `https://boards.greenhouse.io${confirmPath}`,
    submissions,
    analyticsRequests,
    otherRequests,
    attachToPage,
    async close() {
      await new Promise((resolve, reject) => server.close((err) => (err ? reject(err) : resolve())));
    },
  };
}

export { SANDBOX_POSTING_URL_DEFAULT };
