#!/usr/bin/env node
/**
 * Stage 3G external validation harness — entry point.
 *
 *   node staging/harness/run.mjs --authorisation <path-to-record.json> --commit <sha> [--out <ledger.json>]
 *
 * Order of gates (each one fails closed and exits non-zero):
 *   1. authorisation record present, complete, approved, inside its test window  (exit 2)
 *   2. staging environment matches the record and reuses no production secret   (exit 2)
 *   3. a staging scenario driver is available                                     (exit 3)
 *
 * The harness never contacts any host unless gates 1 and 2 pass, and then only
 * hosts accepted by target_policy.js. With no authorisation it records every
 * external scenario as BLOCKED and exits 2 — which is the expected outcome today.
 */
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { validateAuthorisation } from './authorisation.js';
import { validateStagingConfig } from './staging_config.js';
import { EvidenceLedger, EvidenceCategory, Status, newRunId } from './evidence.js';
import { SCENARIOS } from './scenarios.js';

function arg(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

export function runHarness({ authorisationPath, commitSha, env = process.env, now = Date.now(),
  productionSecretFingerprints = [], driver = null } = {}) {
  const runId = newRunId(new Date(now));
  let record = null;
  if (authorisationPath && fs.existsSync(authorisationPath)) {
    try { record = JSON.parse(fs.readFileSync(authorisationPath, 'utf8')); } catch { record = 'UNPARSEABLE'; }
  }
  const auth = record === 'UNPARSEABLE'
    ? { valid: false, reasons: ['AUTHORISATION_RECORD_UNPARSEABLE'], record: null }
    : validateAuthorisation(record, { now });
  const ledger = new EvidenceLedger({ runId, commitSha, environmentHost: auth.valid ? auth.record.hostname : null });
  const blockAll = (reason) => {
    for (const s of SCENARIOS) {
      ledger.record({ scenario: s.id, category: EvidenceCategory.EXTERNAL_SYNTHETIC_STAGING, status: Status.BLOCKED,
        preconditions: s.preconditions, execution: 'not executed', expected: s.expected, actual: reason,
        cleanup: 'NOT_APPLICABLE' });
    }
  };
  if (!auth.valid) {
    blockAll(`AUTHORISATION_GATE: ${auth.reasons.join(',')}`);
    return { exitCode: 2, gate: 'AUTHORISATION', reasons: auth.reasons, ledger };
  }
  const config = validateStagingConfig(env, auth.record, { productionSecretFingerprints });
  if (!config.ok) {
    blockAll(`STAGING_CONFIG_GATE: ${config.violations.join(',')}`);
    return { exitCode: 2, gate: 'STAGING_CONFIG', reasons: config.violations, warnings: config.warnings, ledger };
  }
  if (!driver) {
    blockAll('DRIVER_GATE: no staging scenario driver is configured (requires the staging seeding contract)');
    return { exitCode: 3, gate: 'DRIVER', reasons: ['STAGING_DRIVER_NOT_AVAILABLE'], warnings: config.warnings, ledger };
  }
  return { exitCode: 0, gate: 'READY', reasons: [], warnings: config.warnings, ledger, record: auth.record };
}

const isMain = process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url;
if (isMain) {
  const result = runHarness({ authorisationPath: arg('--authorisation') ?? process.env.ROBIN_STAGE3G_AUTHORISATION,
    commitSha: arg('--commit') ?? process.env.GITHUB_SHA });
  const out = arg('--out');
  const report = { runId: result.ledger.runId, gate: result.gate, reasons: result.reasons, warnings: result.warnings ?? [],
    summary: result.ledger.summary(), records: result.ledger.records };
  if (out) fs.writeFileSync(out, JSON.stringify(report, null, 2), { mode: 0o600 });
  process.stdout.write(`${JSON.stringify({ runId: report.runId, gate: report.gate, reasons: report.reasons, summary: report.summary })}\n`);
  process.exitCode = result.exitCode;
}
