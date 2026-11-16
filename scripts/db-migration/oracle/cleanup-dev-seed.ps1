#requires -Version 5.1
<#
.SYNOPSIS
  Purges development seed data from Oracle PostgreSQL database 'robin' while
  preserving all Flyway V052 schema objects, production discovery sources, and
  rehearsal database 'robin_restore'.

.DESCRIPTION
  - Target: 'robin' exclusively.
  - Foreign key order:
      1. TRUNCATE TABLE jobs CASCADE (clears jobs and cascades to job_identity_keys)
      2. DELETE FROM job_sources WHERE org_identifier IN ('examplefintech', 'examplecloud')
      3. DELETE FROM companies WHERE slug IN ('example-fintech', 'example-cloud')
      4. DELETE FROM users WHERE email = 'dev@example.local' (idempotent)
  - Protected production entities preserved:
      - Company: 'stripe'
      - Job sources: 'ADZUNA (gb)', 'ARBEITNOW (jobs)', 'FREEHIRE (x5)', 'GREENHOUSE (stripe)'
  - Preview Mode (Default): Runs in a BEGIN ... ROLLBACK transaction and prints diff.
  - Execute Mode (-Execute): Runs in a BEGIN ... COMMIT transaction with explicit confirmation.

.EXAMPLE
  # Preview mode (default, rolls back)
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\oracle\cleanup-dev-seed.ps1

  # Execution mode (commits changes)
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\oracle\cleanup-dev-seed.ps1 -Execute
#>
[CmdletBinding()]
param(
    [string] $VmHost = '132.226.133.162',
    [string] $SshUser = 'ubuntu',
    [string] $TargetDb = 'robin',
    [switch] $Execute,
    [switch] $Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-Step([string] $Message) {
    Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Message)
}

function Invoke-RemoteSql([string] $Database, [string] $Sql) {
    $cleanSql = $Sql.Trim()
    $res = $cleanSql | ssh "$SshUser@$VmHost" "sudo -u postgres psql -d $Database -t -A -f -"
    if ($LASTEXITCODE -ne 0) {
        throw "Remote psql execution failed against $Database (exit $LASTEXITCODE)"
    }
    $res
}

Write-Host ''
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "   ROBIN — ORACLE 'robin' DEVELOPMENT SEED DATA CLEANUP        " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host " Target Host:     $VmHost"
Write-Host " Target Database: $TargetDb"
Write-Host " Operation Mode:  $(if ($Execute) { 'EXECUTE (MUTATING)' } else { 'PREVIEW ONLY (ROLLBACK)' })"
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ''

# 1. Target database enforcement
if ($TargetDb -ne 'robin') {
    throw "SAFETY ENFORCEMENT: Target database must strictly be 'robin'. Received: '$TargetDb'."
}

# 2. Pre-cleanup state audit
Write-Step "Auditing database 'robin' Flyway and schema state..."
$flywayCount = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM flyway_schema_history WHERE success = true;').Trim()
$flywayMax = (Invoke-RemoteSql $TargetDb 'SELECT max(version) FROM flyway_schema_history WHERE success = true;').Trim()
$tableCount = (Invoke-RemoteSql $TargetDb "SELECT count(*)::int FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';").Trim()
$viewCount = (Invoke-RemoteSql $TargetDb "SELECT count(*)::int FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'VIEW';").Trim()

Write-Step "Database 'robin': $tableCount base tables, $viewCount views, Flyway $flywayCount applied migrations (MaxVersion: $flywayMax)"

if ($tableCount -ne '72' -or $viewCount -ne '1' -or $flywayMax -ne '052') {
    throw "SCHEMA MISMATCH: Expected 72 tables, 1 view, and Flyway V052. Found $tableCount tables, $viewCount views, version $flywayMax."
}

# 3. Check robin_restore isolation baseline
$restoreJobCount = (Invoke-RemoteSql 'robin_restore' 'SELECT count(*)::int FROM jobs;').Trim()
Write-Step "Rehearsal database 'robin_restore' baseline: $restoreJobCount jobs (must remain unchanged)"

# 4. Inventory seed objects in 'robin'
Write-Step "Inventorying current seed objects in 'robin'..."
$jobsSeed = (Invoke-RemoteSql $TargetDb "SELECT string_agg(external_id, ', ') FROM jobs WHERE external_id IN ('demo-001', 'demo-002');").Trim()
$idKeysSeed = (Invoke-RemoteSql $TargetDb "SELECT count(*)::int FROM job_identity_keys WHERE job_id IN (SELECT id FROM jobs WHERE external_id IN ('demo-001', 'demo-002'));").Trim()
$sourcesSeed = (Invoke-RemoteSql $TargetDb "SELECT string_agg(org_identifier, ', ') FROM job_sources WHERE org_identifier IN ('examplefintech', 'examplecloud');").Trim()
$companiesSeed = (Invoke-RemoteSql $TargetDb "SELECT string_agg(slug, ', ') FROM companies WHERE slug IN ('example-fintech', 'example-cloud');").Trim()
$usersSeed = (Invoke-RemoteSql $TargetDb "SELECT count(*)::int FROM users WHERE email = 'dev@example.local';").Trim()

Write-Host ''
Write-Host "Objects to be purged:" -ForegroundColor Yellow
Write-Host "  - Demo Jobs:            $jobsSeed"
Write-Host "  - Job Identity Keys:    $idKeysSeed rows"
Write-Host "  - Mock Job Sources:     $sourcesSeed"
Write-Host "  - Mock Companies:       $companiesSeed"
Write-Host "  - Mock Dev User:        $usersSeed rows (dev@example.local)"
Write-Host ''

# 5. Inventory production entities to preserve
$prodSources = (Invoke-RemoteSql $TargetDb "SELECT count(*)::int FROM job_sources WHERE org_identifier NOT IN ('examplefintech', 'examplecloud');").Trim()
$prodCompanies = (Invoke-RemoteSql $TargetDb "SELECT count(*)::int FROM companies WHERE slug NOT IN ('example-fintech', 'example-cloud');").Trim()

Write-Host "Protected production entities to preserve:" -ForegroundColor Green
Write-Host "  - Production Job Sources: $prodSources (Adzuna, Arbeitnow, FreeHire x5, Stripe)"
Write-Host "  - Production Companies:   $prodCompanies (Stripe)"
Write-Host ''

if (-not $Execute) {
    # PREVIEW MODE (ROLLBACK)
    Write-Step "Executing PREVIEW run in a transactional ROLLBACK block..."

    $previewScript = @"
BEGIN;
TRUNCATE TABLE jobs CASCADE;
DELETE FROM job_sources WHERE org_identifier IN ('examplefintech', 'examplecloud');
DELETE FROM companies WHERE slug IN ('example-fintech', 'example-cloud');
DELETE FROM users WHERE email = 'dev@example.local';
SELECT 'jobs', count(*) FROM jobs
UNION ALL SELECT 'job_identity_keys', count(*) FROM job_identity_keys
UNION ALL SELECT 'job_sources', count(*) FROM job_sources
UNION ALL SELECT 'companies', count(*) FROM companies
UNION ALL SELECT 'users', count(*) FROM users;
ROLLBACK;
"@
    $previewOut = Invoke-RemoteSql $TargetDb $previewScript
    Write-Host ''
    Write-Host "Preview Dry-Run Result (Rolled Back):" -ForegroundColor Cyan
    $previewOut | ForEach-Object { Write-Host "  $_" }
    Write-Host ''
    Write-Step "PREVIEW COMPLETED. Changes were rolled back. Database 'robin' was NOT modified."
    Write-Host ''
    Write-Host "To execute this cleanup and commit changes, run with -Execute:" -ForegroundColor Yellow
    Write-Host "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\oracle\cleanup-dev-seed.ps1 -Execute" -ForegroundColor White
    exit 0
}

# EXECUTION GATE
if (-not $Force) {
    $confirm = Read-Host "Are you sure you want to PURGE the development seed rows from 'robin'? Type 'CONFIRM' to proceed"
    if ($confirm -ne 'CONFIRM') {
        Write-Host "Aborted by user." -ForegroundColor Red
        exit 1
    }
}

Write-Step "Executing MUTATING cleanup in a transactional COMMIT block..."

$execScript = @"
BEGIN;
TRUNCATE TABLE jobs CASCADE;
DELETE FROM job_sources WHERE org_identifier IN ('examplefintech', 'examplecloud');
DELETE FROM companies WHERE slug IN ('example-fintech', 'example-cloud');
DELETE FROM users WHERE email = 'dev@example.local';
COMMIT;
"@
$execOut = Invoke-RemoteSql $TargetDb $execScript
Write-Step "Transaction committed successfully."

# 6. Post-cleanup verification
Write-Step "Running post-cleanup validation checks..."

$postJobs = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM jobs;').Trim()
$postKeys = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM job_identity_keys;').Trim()
$postSources = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM job_sources;').Trim()
$postCompanies = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM companies;').Trim()
$postUsers = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM users;').Trim()
$postApps = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM applications;').Trim()
$postOutbox = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM outbox_events;').Trim()
$postWorkers = (Invoke-RemoteSql $TargetDb 'SELECT count(*)::int FROM worker_events;').Trim()

# Verify robin_restore untouched
$restoreFinalJobs = (Invoke-RemoteSql 'robin_restore' 'SELECT count(*)::int FROM jobs;').Trim()

Write-Host ''
Write-Host "Post-Cleanup Audit Summary:" -ForegroundColor Cyan
Write-Host "  - jobs:                     $postJobs (expected: 0)" -ForegroundColor $(if ($postJobs -eq '0') { 'Green' } else { 'Red' })
Write-Host "  - job_identity_keys:        $postKeys (expected: 0)" -ForegroundColor $(if ($postKeys -eq '0') { 'Green' } else { 'Red' })
Write-Host "  - users:                    $postUsers (expected: 0)" -ForegroundColor $(if ($postUsers -eq '0') { 'Green' } else { 'Red' })
Write-Host "  - applications:             $postApps (expected: 0)" -ForegroundColor $(if ($postApps -eq '0') { 'Green' } else { 'Red' })
Write-Host "  - outbox_events:            $postOutbox (expected: 0)" -ForegroundColor $(if ($postOutbox -eq '0') { 'Green' } else { 'Red' })
Write-Host "  - worker_events:            $postWorkers (expected: 0)" -ForegroundColor $(if ($postWorkers -eq '0') { 'Green' } else { 'Red' })
Write-Host "  - job_sources:              $postSources (expected: 8 production sources)" -ForegroundColor $(if ($postSources -eq '8') { 'Green' } else { 'Red' })
Write-Host "  - companies:                $postCompanies (expected: 1 Stripe)" -ForegroundColor $(if ($postCompanies -eq '1') { 'Green' } else { 'Red' })
Write-Host "  - robin_restore jobs:       $restoreFinalJobs (strictly untouched)" -ForegroundColor $(if ($restoreFinalJobs -eq $restoreJobCount) { 'Green' } else { 'Red' })

$allOk = ($postJobs -eq '0') -and ($postKeys -eq '0') -and ($postUsers -eq '0') -and ($postApps -eq '0') -and `
         ($postOutbox -eq '0') -and ($postWorkers -eq '0') -and ($postSources -eq '8') -and ($postCompanies -eq '1') -and `
         ($restoreFinalJobs -eq $restoreJobCount)

if (-not $allOk) {
    throw "POST-CLEANUP VALIDATION FAILED: Inconsistencies detected!"
}

Write-Host ''
Write-Host "================================================================" -ForegroundColor Green
Write-Host "   CLEANUP COMPLETE: DATABASE 'robin' IS IN A PRISTINE STATE    " -ForegroundColor Green
Write-Host "================================================================" -ForegroundColor Green
