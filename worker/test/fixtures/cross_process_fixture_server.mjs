import http from 'node:http';
import { createUniversalEmployerApp } from '../../../staging/universal-employer-fixture/src/app.js';

function createMemoryStore() {
  const map = new Map();
  return {
    async get(key) {
      return map.has(key) ? map.get(key) : null;
    },
    async put(key, val) {
      map.set(key, val);
    },
    async deleteAll() {
      map.clear();
    },
  };
}

let config = {
  basePath: '/careers',
  confirmationId: 'CONF-2026-8801',
  captchaMode: 'none',
  securityMode: 'none',
  includeProhibitedDeclaration: false,
  includeUnknownQuestion: true,
  postingClosedHttpStatus: 200,
  postingClosedBanner: false,
  loginWallOnGet: false,
  redirectTarget: null,
  offOriginRedirect: null,
};

let appInstance = null;
let server = null;

function rebuildApp(origin) {
  const baseApp = createUniversalEmployerApp({
    basePath: config.basePath,
    store: createMemoryStore(),
    confirmationId: config.confirmationId,
    captchaMode: config.captchaMode,
    securityMode: config.securityMode,
    redirectTarget: config.redirectTarget,
    includeStartDateShadow: false,
    includePortalCombobox: false,
    includeSalaryQuestion: false,
  });

  appInstance = {
    baseApp,
    origin,
    async handle(request) {
      const url = new URL(request.url);
      if (request.method === 'GET' && url.pathname === baseApp.paths.applyPath) {
        if (config.offOriginRedirect) {
          return new Response(null, {
            status: 302,
            headers: { Location: config.offOriginRedirect, 'Content-Type': 'text/html; charset=utf-8' },
          });
        }
        if (config.postingClosedHttpStatus && config.postingClosedHttpStatus !== 200) {
          return new Response('Job closed', {
            status: config.postingClosedHttpStatus,
            headers: { 'Content-Type': 'text/html; charset=utf-8' },
          });
        }
        if (config.loginWallOnGet) {
          return new Response(
            '<html><body><form id="login"><label>Password</label><input type="password" name="password"/></form></body></html>',
            { status: 200, headers: { 'Content-Type': 'text/html; charset=utf-8' } }
          );
        }
        if (config.postingClosedBanner) {
          return new Response(
            '<html><body><main><h1>This job is no longer accepting applications.</h1></main></body></html>',
            { status: 200, headers: { 'Content-Type': 'text/html; charset=utf-8' } }
          );
        }
      }

      const response = await baseApp.handle(request);
      if (request.method === 'GET' && url.pathname === baseApp.paths.step2Path && response.status === 200) {
        let html = await response.text();
        if (!config.includeUnknownQuestion) {
          html = html.replace(
            '<input id="f_notice" name="notice_period" type="text" required />',
            '<input id="f_notice" name="notice_period" type="text" value="4 weeks" required />'
          );
        }
        if (config.includeProhibitedDeclaration) {
          html = html.replace(
            '<button type="submit" id="final-submit">',
            `<div class="field"><label><input id="f_decl" name="certify_accuracy" type="checkbox" required /> ` +
              `I certify under penalty of perjury that all statements are true and accurate</label></div>` +
              `<button type="submit" id="final-submit">`
          );
        }
        const headers = new Headers(response.headers);
        headers.delete('content-length');
        return new Response(html, { status: response.status, headers });
      }
      return response;
    },
  };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

server = http.createServer(async (req, res) => {
  try {
    const addr = server.address();
    const origin = `http://127.0.0.1:${addr.port}`;
    const url = new URL(req.url, origin);

    if (req.method === 'POST' && url.pathname === '/__test/config') {
      const raw = await readBody(req);
      config = { ...config, ...JSON.parse(raw || '{}') };
      rebuildApp(origin);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, config }));
      return;
    }

    if (req.method === 'POST' && url.pathname === '/__test/reset') {
      await appInstance.baseApp.reset();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    if (req.method === 'GET' && url.pathname === '/__test/diagnostics') {
      const diag = await appInstance.baseApp.diagnostics();
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(diag));
      return;
    }

    const bodyText = (req.method !== 'GET' && req.method !== 'HEAD') ? await readBody(req) : undefined;
    const webReq = new Request(url, {
      method: req.method,
      headers: req.headers,
      body: bodyText && bodyText.length > 0 ? bodyText : undefined,
    });
    const webRes = await appInstance.handle(webReq);
    res.writeHead(webRes.status, Object.fromEntries(webRes.headers.entries()));
    res.end(Buffer.from(await webRes.arrayBuffer()));
  } catch (err) {
    res.writeHead(500, { 'Content-Type': 'text/plain' });
    res.end(String(err));
  }
});

server.listen(0, '127.0.0.1', () => {
  const { port } = server.address();
  const origin = `http://127.0.0.1:${port}`;
  rebuildApp(origin);
  process.stdout.write(`READY ${origin}\n`);
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
