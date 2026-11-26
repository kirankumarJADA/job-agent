/**
 * Stage 3G — the staging environment configuration contract.
 *
 * Validates the environment a staging backend + worker would run with, against
 * the authorisation record, WITHOUT ever returning or printing a secret value:
 * secrets are only checked for presence, shape and (optionally) that their
 * SHA-256 fingerprint is not on the operator's production deny-list.
 */
import crypto from 'node:crypto';

const fingerprint = (v) => crypto.createHash('sha256').update(String(v)).digest('hex').slice(0, 16);

function list(value) {
  return String(value ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}

/**
 * @param env     the staging environment (process.env shape)
 * @param record  a VALID authorisation record (validateAuthorisation(...).record)
 * @param options.productionSecretFingerprints  sha256[0..16] of production secrets (operator-supplied)
 * @returns { ok, violations: string[], warnings: string[] } — names and codes only
 */
export function validateStagingConfig(env, record, { productionSecretFingerprints = [] } = {}) {
  const violations = [];
  const warnings = [];
  if (!record) return { ok: false, violations: ['NO_VALID_AUTHORISATION'], warnings };

  if (env.ROBIN_ENVIRONMENT !== 'staging') violations.push('ROBIN_ENVIRONMENT_NOT_STAGING');

  const domains = list(env.APP_SANDBOX_ALLOWED_DOMAINS);
  if (domains.length !== 1 || domains[0] !== record.hostname) violations.push('APP_SANDBOX_ALLOWED_DOMAINS_NOT_EXACT');
  const prefixes = list(env.APP_SANDBOX_ALLOWED_PATH_PREFIXES);
  if (prefixes.length !== 1 || prefixes[0] !== record.pathPrefix) violations.push('APP_SANDBOX_ALLOWED_PATH_PREFIXES_NOT_EXACT');
  if (env.APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS !== 'false') violations.push('APP_SANDBOX_ALLOW_LOCAL_TEST_HOSTS_NOT_FALSE');
  if (env.APP_SANDBOX_REQUIRE_HTTPS !== 'true') violations.push('APP_SANDBOX_REQUIRE_HTTPS_NOT_TRUE');

  const db = String(env.SPRING_DATASOURCE_URL ?? '');
  if (!db) violations.push('STAGING_DATABASE_URL_MISSING');
  else if (record.stagingDatabaseFingerprint && fingerprint(db) !== record.stagingDatabaseFingerprint) {
    violations.push('DATABASE_IS_NOT_THE_AUTHORISED_STAGING_DATABASE');
  }

  for (const name of ['APP_SECRETS_KEY', 'WORKER_EVENT_TOKEN']) {
    const v = env[name];
    if (!v) { violations.push(`${name}_MISSING`); continue; }
    if (String(v).length < 32) violations.push(`${name}_TOO_SHORT`);
    if (productionSecretFingerprints.includes(fingerprint(v))) violations.push(`${name}_REUSES_A_PRODUCTION_SECRET`);
  }

  // The session vault is optional and fails safe when absent (sessions stay in memory).
  const sessionKey = env.ROBIN_WORKER_SESSION_KEY;
  if (!sessionKey) {
    warnings.push('ROBIN_WORKER_SESSION_KEY_ABSENT_SESSIONS_IN_MEMORY_ONLY');
  } else {
    let ok = false;
    try { ok = Buffer.from(String(sessionKey), 'base64').length === 32; } catch { ok = false; }
    if (!ok) violations.push('ROBIN_WORKER_SESSION_KEY_NOT_32_BYTES_BASE64');
    if (productionSecretFingerprints.includes(fingerprint(sessionKey))) violations.push('ROBIN_WORKER_SESSION_KEY_REUSES_A_PRODUCTION_SECRET');
  }

  // The worker egress proxy has no off switch; any of these would indicate an attempt to bypass it.
  for (const name of ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'PLAYWRIGHT_DISABLE_FORCED_CHROMIUM_PROXIED_LOOPBACK']) {
    if (env[name]) violations.push(`${name}_SET_ON_WORKER`);
  }
  warnings.push('IN_PROCESS_EGRESS_PROXY_COVERS_CHROMIUM_ONLY_NOT_OTHER_HOST_PROCESSES');
  return { ok: violations.length === 0, violations, warnings };
}

export { fingerprint as secretFingerprint };
