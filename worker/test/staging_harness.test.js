// Stage 3G — authorisation gate, exact-target policy, staging config contract and evidence ledger.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validateAuthorisation, scenarioPermitted } from '../../staging/harness/authorisation.js';
import { checkAuthorisedTarget, assertAuthorisedTarget, checkRedirectChain, TargetRefusedError } from '../../staging/harness/target_policy.js';
import { validateStagingConfig, secretFingerprint } from '../../staging/harness/staging_config.js';
import { EvidenceLedger, EvidenceCategory, Status, redact } from '../../staging/harness/evidence.js';
import { runHarness } from '../../staging/harness/run.mjs';
import { SCENARIOS } from '../../staging/harness/scenarios.js';

const HARNESS = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'staging', 'harness');
const SHA = '9a783915fedb75cd2c8691da7e950265312a2443';
const NOW = Date.parse('2026-12-02T12:00:00Z');

function validRecord(overrides = {}) {
  return {
    version: 1, hostname: 'robin-fixture.ops-test-zone.net', pathPrefix: '/robin-fixture/',
    nonProduction: true, syntheticDataOnly: true,
    window: { start: '2026-12-01T09:00:00Z', end: '2026-12-05T18:00:00Z' },
    operator: { name: 'Test Operator', contact: 'ops@ops-test-zone.net' },
    approval: { approvedBy: 'Programme Owner', approvedAt: '2026-11-30T10:00:00Z' },
    syntheticCandidate: { label: 'Synthetic Candidate 01', realPersonalData: false },
    syntheticDocuments: { cv: 'synthetic-cv-01.pdf', coverLetter: 'synthetic-cl-01.pdf', generatedFromSyntheticProfile: true },
    testAccount: { host: 'robin-fixture.ops-test-zone.net', storedVia: 'employer-credentials-api' },
    teardown: { mechanism: 'fixture teardown endpoint + wrangler delete', retentionDeadline: '2026-12-08T18:00:00Z' },
    scenariosAuthorised: [1, 2, 3, 13, 14, 15],
    ...overrides,
  };
}

// ── authorisation gate ──────────────────────────────────────────────────

test('a complete, approved record inside its window is valid; scenario gating follows the record', () => {
  const v = validateAuthorisation(validRecord(), { now: NOW });
  assert.deepEqual(v.reasons, []);
  assert.equal(v.valid, true);
  assert.equal(scenarioPermitted(v, 14), true);
  assert.equal(scenarioPermitted(v, 7), false, 'scenarios not listed are not authorised');
});

test('absence, incompleteness, the wrong time and unsafe content all fail closed with a specific reason', () => {
  const cases = [
    [null, 'AUTHORISATION_RECORD_ABSENT'],
    [validRecord({ hostname: '*.ops-test-zone.net' }), 'HOSTNAME_NOT_AN_EXACT_OPERATOR_FQDN'],
    [validRecord({ hostname: '203.0.113.7' }), 'HOSTNAME_NOT_AN_EXACT_OPERATOR_FQDN'],
    [validRecord({ hostname: 'robin.workers.dev' }), 'HOSTNAME_NOT_AN_EXACT_OPERATOR_FQDN'],
    [validRecord({ hostname: 'fixture.example.com' }), 'HOSTNAME_NOT_AN_EXACT_OPERATOR_FQDN'],
    [validRecord({ hostname: 'Robin-Fixture.ops-test-zone.net' }), 'HOSTNAME_NOT_AN_EXACT_OPERATOR_FQDN'],
    [validRecord({ pathPrefix: '/' }), 'PATH_PREFIX_INVALID'],
    [validRecord({ nonProduction: 'yes' }), 'NON_PRODUCTION_NOT_CONFIRMED'],
    [validRecord({ syntheticDataOnly: false }), 'SYNTHETIC_DATA_ONLY_NOT_CONFIRMED'],
    [validRecord({ window: { start: '2026-12-03T00:00:00Z', end: '2026-12-04T00:00:00Z' } }), 'TEST_WINDOW_NOT_STARTED'],
    [validRecord({ window: { start: '2026-11-20T00:00:00Z', end: '2026-11-21T00:00:00Z' }, approval: { approvedBy: 'x', approvedAt: '2026-11-19T00:00:00Z' } }), 'TEST_WINDOW_EXPIRED'],
    [validRecord({ window: { start: '2026-12-01T00:00:00Z', end: '2026-12-30T00:00:00Z' } }), 'TEST_WINDOW_TOO_LONG'],
    [validRecord({ operator: { name: 'x' } }), 'TEST_OPERATOR_MISSING'],
    [validRecord({ approval: {} }), 'WRITTEN_APPROVAL_MISSING'],
    [validRecord({ approval: { approvedBy: 'x', approvedAt: '2026-12-02T00:00:00Z' } }), 'APPROVAL_AFTER_WINDOW_START'],
    [validRecord({ syntheticCandidate: { label: 'x', realPersonalData: true } }), 'SYNTHETIC_CANDIDATE_NOT_DECLARED'],
    [validRecord({ syntheticDocuments: { cv: 'x.pdf' } }), 'SYNTHETIC_DOCUMENTS_NOT_DECLARED'],
    [validRecord({ testAccount: { host: 'other.ops-test-zone.net', storedVia: 'employer-credentials-api' } }), 'TEST_ACCOUNT_NOT_SCOPED_TO_HOST'],
    [validRecord({ testAccount: { host: 'robin-fixture.ops-test-zone.net', storedVia: 'employer-credentials-api', password: 'p' } }), 'SECRET_IN_AUTHORISATION_RECORD'],
    [validRecord({ teardown: { mechanism: 'x', retentionDeadline: '2027-02-01T00:00:00Z' } }), 'RETENTION_TOO_LONG'],
    [validRecord({ teardown: {} }), 'TEARDOWN_NOT_DEFINED'],
    [validRecord({ scenariosAuthorised: [] }), 'SCENARIOS_NOT_AUTHORISED'],
    [validRecord({ scenariosAuthorised: [1, 16] }), 'SCENARIOS_NOT_AUTHORISED'],
  ];
  for (const [record, reason] of cases) {
    const v = validateAuthorisation(record, { now: NOW });
    assert.equal(v.valid, false, reason);
    assert.ok(v.reasons.includes(reason), `${reason} not in ${v.reasons.join(',')}`);
    assert.equal(v.record, null);
  }
});

test('the committed example record is a template that fails validation as-is', () => {
  const example = JSON.parse(fs.readFileSync(path.join(HARNESS, 'authorisation.example.json'), 'utf8'));
  const v = validateAuthorisation(example, { now: NOW });
  assert.equal(v.valid, false);
  assert.ok(v.reasons.length >= 8);
});

// ── exact target policy ──────────────────────────────────────────────────

test('only HTTPS on 443 to the exact authorised host under the prefix is addressable', () => {
  const r = validRecord();
  assert.equal(checkAuthorisedTarget('https://robin-fixture.ops-test-zone.net/robin-fixture/s01-multistep/careers/jobs/stage3-role/apply', r).allowed, true);
  assert.equal(checkAuthorisedTarget('https://robin-fixture.ops-test-zone.net:443/robin-fixture/x', r).allowed, true);
  const refused = {
    'http://robin-fixture.ops-test-zone.net/robin-fixture/x': 'NOT_HTTPS',
    'https://robin-fixture.ops-test-zone.net:8443/robin-fixture/x': 'PORT_NOT_443',
    'https://evil.ops-test-zone.net/robin-fixture/x': 'HOST_NOT_AUTHORISED',
    'https://a.robin-fixture.ops-test-zone.net/robin-fixture/x': 'HOST_NOT_AUTHORISED',
    'https://robin-fixture.ops-test-zone.net.evil.net/robin-fixture/x': 'HOST_NOT_AUTHORISED',
    'https://203.0.113.7/robin-fixture/x': 'IP_LITERAL',
    'https://[2001:db8::1]/robin-fixture/x': 'IP_LITERAL',
    'https://robin-fixture.ops-test-zone.net./robin-fixture/x': 'TRAILING_DOT_HOST',
    'https://user:pw@robin-fixture.ops-test-zone.net/robin-fixture/x': 'USERINFO',
    'https://robin-fixture.ops-test-zone.net/other/x': 'PATH_OUTSIDE_PREFIX',
    // The WHATWG parser decodes and resolves %2e%2e before the check: it lands outside the prefix.
    'https://robin-fixture.ops-test-zone.net/robin-fixture/%2e%2e/admin': 'PATH_OUTSIDE_PREFIX',
    // Encoded slashes survive parsing and are refused explicitly.
    'https://robin-fixture.ops-test-zone.net/robin-fixture/a%2f..%2f..%2fadmin': 'PATH_TRAVERSAL',
    'javascript:alert(1)': 'NOT_HTTPS',
    'not a url': 'MALFORMED_URL',
  };
  for (const [u, reason] of Object.entries(refused)) {
    assert.deepEqual(checkAuthorisedTarget(u, r), { allowed: false, reason }, u);
  }
  assert.throws(() => assertAuthorisedTarget('https://evil.example/robin-fixture/x', r), TargetRefusedError);
  assert.equal(checkAuthorisedTarget('https://robin-fixture.ops-test-zone.net/robin-fixture/x', null).reason, 'NO_AUTHORISATION');
});

test('a redirect chain is refused at the first hop that leaves the authorised target', () => {
  const r = validRecord();
  const ok = checkRedirectChain(['https://robin-fixture.ops-test-zone.net/robin-fixture/a', 'https://robin-fixture.ops-test-zone.net/robin-fixture/b'], r);
  assert.equal(ok.allowed, true);
  const bad = checkRedirectChain(['https://robin-fixture.ops-test-zone.net/robin-fixture/a', 'http://169.254.169.254/latest/meta-data/'], r);
  assert.deepEqual(bad, { allowed: false, reason: 'REDIRECT_HOP_1_NOT_HTTPS' });
});

// ── staging configuration contract ──────────────────────────────────────

function stagingEnv(overrides = {}) {
  return {
    ROBIN_ENVIRONMENT: 'staging',
    APP_SANDBOX_ALLOWED_DOMAINS: 'robin-fixture.ops-test-zone.net',
    APP_SANDBOX_ALLOWED_PATH_PREFIXES: '/robin-fixture/',
    APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS: 'false',
    APP_SANDBOX_REQUIRE_HTTPS: 'true',
    SPRING_DATASOURCE_URL: 'jdbc:postgresql://staging-db.ops-test-zone.net:5432/robin_staging',
    APP_SECRETS_KEY: 'staging-only-secrets-key-0123456789abcdef',
    WORKER_EVENT_TOKEN: 'staging-only-worker-token-0123456789abcdef',
    ...overrides,
  };
}

test('a correct staging environment passes; an absent session key is a fail-safe warning, not a silent key', () => {
  const r = validRecord({ stagingDatabaseFingerprint: secretFingerprint(stagingEnv().SPRING_DATASOURCE_URL) });
  const result = validateStagingConfig(stagingEnv(), r);
  assert.deepEqual(result.violations, []);
  assert.equal(result.ok, true);
  assert.ok(result.warnings.includes('ROBIN_WORKER_SESSION_KEY_ABSENT_SESSIONS_IN_MEMORY_ONLY'));
  assert.ok(result.warnings.includes('IN_PROCESS_EGRESS_PROXY_COVERS_CHROMIUM_ONLY_NOT_OTHER_HOST_PROCESSES'));
});

test('every unsafe staging setting is a named violation, and no secret value ever appears in the result', () => {
  const r = validRecord({ stagingDatabaseFingerprint: secretFingerprint(stagingEnv().SPRING_DATASOURCE_URL) });
  const prodKey = 'production-secrets-key-value-do-not-reuse-0001';
  const cases = [
    [{ ROBIN_ENVIRONMENT: 'production' }, 'ROBIN_ENVIRONMENT_NOT_STAGING'],
    [{ APP_SANDBOX_ALLOWED_DOMAINS: 'robin-fixture.ops-test-zone.net,boards.greenhouse.io' }, 'APP_SANDBOX_ALLOWED_DOMAINS_NOT_EXACT'],
    [{ APP_SANDBOX_ALLOWED_DOMAINS: '' }, 'APP_SANDBOX_ALLOWED_DOMAINS_NOT_EXACT'],
    [{ APP_SANDBOX_ALLOWED_PATH_PREFIXES: '/' }, 'APP_SANDBOX_ALLOWED_PATH_PREFIXES_NOT_EXACT'],
    [{ APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS: 'true' }, 'APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS_NOT_FALSE'],
    [{ APP_SANDBOX_REQUIRE_HTTPS: 'false' }, 'APP_SANDBOX_REQUIRE_HTTPS_NOT_TRUE'],
    [{ SPRING_DATASOURCE_URL: 'jdbc:postgresql://prod-db.example:5432/robin' }, 'DATABASE_IS_NOT_THE_AUTHORISED_STAGING_DATABASE'],
    [{ APP_SECRETS_KEY: '' }, 'APP_SECRETS_KEY_MISSING'],
    [{ APP_SECRETS_KEY: 'short' }, 'APP_SECRETS_KEY_TOO_SHORT'],
    [{ APP_SECRETS_KEY: prodKey }, 'APP_SECRETS_KEY_REUSES_A_PRODUCTION_SECRET'],
    [{ ROBIN_WORKER_SESSION_KEY: 'not-32-bytes' }, 'ROBIN_WORKER_SESSION_KEY_NOT_32_BYTES_BASE64'],
    [{ HTTPS_PROXY: 'http://10.0.0.1:3128' }, 'HTTPS_PROXY_SET_ON_WORKER'],
    [{ PLAYWRIGHT_DISABLE_FORCED_CHROMIUM_PROXIED_LOOPBACK: '1' }, 'PLAYWRIGHT_DISABLE_FORCED_CHROMIUM_PROXIED_LOOPBACK_SET_ON_WORKER'],
  ];
  for (const [env, violation] of cases) {
    const merged = stagingEnv(env);
    const result = validateStagingConfig(merged, r, { productionSecretFingerprints: [secretFingerprint(prodKey)] });
    assert.equal(result.ok, false, violation);
    assert.ok(result.violations.includes(violation), `${violation} not in ${result.violations}`);
    const serialised = JSON.stringify(result);
    for (const secret of [merged.APP_SECRETS_KEY, merged.WORKER_EVENT_TOKEN, merged.SPRING_DATASOURCE_URL, merged.ROBIN_WORKER_SESSION_KEY]) {
      if (secret && secret.length > 4) assert.ok(!serialised.includes(secret), 'secret value leaked into the result');
    }
  }
  assert.deepEqual(validateStagingConfig(stagingEnv(), null).violations, ['NO_VALID_AUTHORISATION']);
});

// ── evidence ledger ──────────────────────────────────────────────────────

test('evidence categories stay separate; real-employer evidence and uncleaned external passes are refused', () => {
  const ledger = new EvidenceLedger({ commitSha: SHA, environmentHost: 'robin-fixture.ops-test-zone.net' });
  assert.match(ledger.runId, /^3g-\d{8}-[0-9a-f]{8}$/);
  ledger.record({ scenario: 1, category: EvidenceCategory.LOCAL_FIXTURE, status: Status.PASS, cleanup: 'VERIFIED' });
  ledger.record({ scenario: 1, category: EvidenceCategory.EXTERNAL_SYNTHETIC_STAGING, status: Status.BLOCKED });
  assert.throws(() => ledger.record({ scenario: 2, category: EvidenceCategory.REAL_EMPLOYER, status: Status.PASS }), /REAL_EMPLOYER/);
  assert.throws(() => ledger.record({ scenario: 2, category: EvidenceCategory.EXTERNAL_SYNTHETIC_STAGING, status: Status.PASS }), /CLEANUP/);
  assert.throws(() => ledger.record({ scenario: 16, category: EvidenceCategory.LOCAL_FIXTURE, status: Status.PASS }), /SCENARIO/);
  const s = ledger.summary();
  assert.equal(s.LOCAL_FIXTURE.PASS, 1);
  assert.equal(s.EXTERNAL_SYNTHETIC_STAGING.PASS, 0);
  assert.equal(s.EXTERNAL_SYNTHETIC_STAGING.BLOCKED, 1);
  assert.equal(ledger.records[0].environmentHost, null, 'local records never carry the external host');
  assert.throws(() => new EvidenceLedger({ commitSha: 'main' }), /EXACT_COMMIT_SHA/);
});

test('redaction keeps origin+path only and drops credentials, cookies, OTPs and document content', () => {
  const out = redact({ url: 'https://u:p@robin-fixture.ops-test-zone.net/robin-fixture/x?token=abc&email=a@b.c#f',
    password: 'p', otpCode: '123456', cookie: 'robin_sess=1', cvContent: '%PDF', nested: { authorization: 'Bearer x', ok: 'fine' } });
  assert.equal(out.url, 'https://robin-fixture.ops-test-zone.net/robin-fixture/x');
  for (const k of ['password', 'otpCode', 'cookie', 'cvContent']) assert.equal(out[k], '[redacted]');
  assert.deepEqual(out.nested, { authorization: '[redacted]', ok: 'fine' });
});

// ── harness entry point ─────────────────────────────────────────────────

test('the harness refuses to run without a record: all 15 external scenarios BLOCKED, nothing contacted', (t) => {
  t.mock.method(globalThis, 'fetch', async () => { throw new Error('the harness must not make any request when blocked'); });
  const result = runHarness({ authorisationPath: path.join(os.tmpdir(), 'no-such-record.json'), commitSha: SHA, now: NOW });
  assert.equal(result.exitCode, 2);
  assert.equal(result.gate, 'AUTHORISATION');
  assert.deepEqual(result.reasons, ['AUTHORISATION_RECORD_ABSENT']);
  const s = result.ledger.summary();
  assert.equal(s.EXTERNAL_SYNTHETIC_STAGING.BLOCKED, 15);
  assert.equal(s.EXTERNAL_SYNTHETIC_STAGING.PASS + s.LOCAL_FIXTURE.PASS + s.POSTGRES_TESTCONTAINERS.PASS, 0);
  assert.equal(SCENARIOS.length, 15);
});

test('valid authorisation but unsafe staging config: blocked at the config gate; safe config without a driver: exit 3', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'stage3g-'));
  const file = path.join(dir, 'record.json');
  const env = stagingEnv();
  fs.writeFileSync(file, JSON.stringify(validRecord({ stagingDatabaseFingerprint: secretFingerprint(env.SPRING_DATASOURCE_URL) })));
  const unsafe = runHarness({ authorisationPath: file, commitSha: SHA, now: NOW, env: { ...env, APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS: 'true' } });
  assert.equal(unsafe.exitCode, 2);
  assert.equal(unsafe.gate, 'STAGING_CONFIG');
  const noDriver = runHarness({ authorisationPath: file, commitSha: SHA, now: NOW, env });
  assert.equal(noDriver.exitCode, 3);
  assert.equal(noDriver.gate, 'DRIVER');
  assert.equal(noDriver.ledger.summary().EXTERNAL_SYNTHETIC_STAGING.BLOCKED, 15);
  const unparseable = path.join(dir, 'bad.json');
  fs.writeFileSync(unparseable, '{not json');
  assert.deepEqual(runHarness({ authorisationPath: unparseable, commitSha: SHA, now: NOW }).reasons, ['AUTHORISATION_RECORD_UNPARSEABLE']);
});
