import http from 'node:http';
import { createUniversalEmployerApp } from '../../../staging/universal-employer-fixture/src/app.js';

/**
 * Controlled local multi-step employer website fixture (strictly 127.0.0.1,
 * in-memory — zero external network calls).
 *
 * Stage 3G: the page/flow logic now lives in ONE runtime-agnostic app,
 * `staging/universal-employer-fixture/src/app.js`, which is also what the
 * (not yet authorised or deployed) Cloudflare staging package serves. This file
 * is only the Node http adapter, so local tests and external staging exercise
 * the same fixture code rather than two diverging copies.
 *
 * Options are passed straight through (see createUniversalEmployerApp); the
 * defaults reproduce the original Stage 3B fixture.
 */
export async function startUniversalEmployerFixture(options = {}) {
  const app = createUniversalEmployerApp(options);

  const server = http.createServer(async (req, res) => {
    try {
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = chunks.length ? Buffer.concat(chunks) : null;
      const request = new Request(new URL(req.url, `http://${req.headers.host || '127.0.0.1'}`), {
        method: req.method,
        headers: Object.entries(req.headers).flatMap(([k, v]) => (Array.isArray(v) ? v.map((x) => [k, x]) : [[k, v]])),
        body: body && req.method !== 'GET' && req.method !== 'HEAD' ? body : undefined,
      });
      const response = await app.handle(request);
      const headers = {};
      response.headers.forEach((value, key) => { headers[key] = value; });
      res.writeHead(response.status, headers);
      res.end(Buffer.from(await response.arrayBuffer()));
    } catch {
      res.writeHead(500, { 'Content-Type': 'text/plain' });
      res.end('fixture error');
    }
  });

  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  return {
    baseUrl,
    targetUrl: `${baseUrl}${app.paths.applyPath}`,
    get page1GetCount() { return app.state.page1GetCount; },
    step1Posts: app.step1Posts,
    submissions: app.submissions,
    diagnostics: () => app.diagnostics(),
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}
