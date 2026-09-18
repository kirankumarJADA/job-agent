import http from 'node:http';
import { randomUUID } from 'node:crypto';

function html(body) {
  return `<!doctype html><html><head><title>Mock Employer</title></head><body>${body}</body></html>`;
}

export async function startMockEnvironment({ jobId, applicationId, companyDomain = 'mockemployer.test', otp = '123456', mailboxOtp = otp }) {
  const events = [];
  const messages = [{
    id: `mail-${applicationId}`,
    applicationId,
    sender: `security@${companyDomain}`,
    receivedAt: new Date().toISOString(),
    body: `Verify your Mock Employer account with OTP ${mailboxOtp}`,
  }];
  let submitted = null;
  let server;
  server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const cookies = Object.fromEntries((req.headers.cookie ?? '').split(';').filter(Boolean).map((x) => x.trim().split('=')));
    const send = (status, body, headers = {}) => { res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8', ...headers }); res.end(body); };
    const redirect = (location, headers = {}) => { res.writeHead(302, { Location: location, ...headers }); res.end(); };
    const body = await new Promise((resolve) => { const chunks = []; req.on('data', (chunk) => chunks.push(chunk)); req.on('end', () => resolve(Buffer.concat(chunks))); });

    if (url.pathname === '/mailbox/messages') {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(messages));
      return;
    }
    if (url.pathname === '/signup' && req.method === 'GET') {
      send(200, html('<h1>Mock Employer Signup</h1><form method="post"><input name="full_name"><input name="email"><input name="password"><button type="submit">Create account</button></form>')); return;
    }
    if (url.pathname === '/signup' && req.method === 'POST') {
      events.push({ type: 'SIGNUP_COMPLETED', applicationId });
      redirect('/verify', { 'Set-Cookie': 'account=1; HttpOnly; SameSite=Lax' }); return;
    }
    if (url.pathname === '/verify' && req.method === 'GET') {
      send(200, html('<h1>Verify</h1><form method="post"><input name="otp"><button type="submit">Verify account</button></form>')); return;
    }
    if (url.pathname === '/verify' && req.method === 'POST') {
      if (!body.toString().includes(otp)) { send(422, html('<h1>Invalid OTP</h1><p>Invalid verification code</p>')); return; }
      events.push({ type: 'VERIFICATION_COMPLETED', applicationId });
      redirect('/login', { 'Set-Cookie': 'verified=1; HttpOnly; SameSite=Lax' }); return;
    }
    if (url.pathname === '/login' && req.method === 'GET') {
      send(200, html('<h1>Mock Employer Login</h1><form method="post"><input name="email"><input name="password"><button type="submit">Login</button></form>')); return;
    }
    if (url.pathname === '/login' && req.method === 'POST') {
      events.push({ type: 'LOGIN_COMPLETED', applicationId });
      redirect(`/apply/${jobId}`, { 'Set-Cookie': 'logged_in=1; HttpOnly; SameSite=Lax' }); return;
    }
    if (url.pathname === `/apply/${jobId}` && req.method === 'GET') {
      if (!cookies.logged_in) { redirect('/login'); return; }
      if (cookies.application_step === '2') {
        send(200, html(`<h1>Application step 2</h1><form method="post" enctype="multipart/form-data" action="/apply/${jobId}/submit"><textarea name="why_role"></textarea><input type="file" name="cv"><input type="file" name="cover_letter"><button data-next="submit" type="submit">Submit mock application</button></form>`)); return;
      }
      send(200, html(`<h1>Application step 1</h1><form method="post" action="/apply/${jobId}"><select name="country"><option value="GB">United Kingdom</option></select><label><input type="checkbox" name="work_authorized" value="true"> Authorized</label><label><input type="radio" name="sponsorship" value="none"> No sponsorship</label><button data-next="step2" type="submit">Next</button></form>`)); return;
    }
    if (url.pathname === `/apply/${jobId}` && req.method === 'POST') {
      events.push({ type: 'APPLICATION_STEP_ONE_COMPLETED', applicationId });
      send(200, html(`<h1>Application step 2</h1><form method="post" enctype="multipart/form-data" action="/apply/${jobId}/submit"><textarea name="why_role"></textarea><input type="file" name="cv"><input type="file" name="cover_letter"><button data-next="submit" type="submit">Submit mock application</button></form>`), { 'Set-Cookie': 'application_step=2; HttpOnly; SameSite=Lax' }); return;
    }
    if (url.pathname === `/apply/${jobId}/submit` && req.method === 'POST') {
      const raw = body.toString();
      const cv = raw.match(/filename="([^"]*cv[^"]*)"/i)?.[1] ?? raw.match(/filename="([^"]+)"/)?.[1];
      const coverLetter = raw.match(/filename="([^"]*cover[^"]*)"/i)?.[1];
      submitted = { applicationId, jobId, hasCv: Boolean(cv), hasCoverLetter: Boolean(coverLetter), rawLength: raw.length };
      events.push({ type: 'APPLICATION_SUBMITTED', applicationId, jobId, cv, coverLetter });
      send(200, html(`<h1 data-state="CONFIRMATION_RECEIVED">Application submitted</h1><p>Confirmation received</p>`)); return;
    }
    send(404, html('<h1>Not found</h1>'));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    mailboxUrl: `http://127.0.0.1:${port}`,
    events,
    get submission() { return submitted; },
    async close() { await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve())); },
    injectMessage(message) { messages.push({ id: randomUUID(), ...message }); },
  };
}
