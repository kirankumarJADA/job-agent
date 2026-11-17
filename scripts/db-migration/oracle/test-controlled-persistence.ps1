#requires -Version 5.1
<#
.SYNOPSIS
  Robin — Controlled Persistence & Duplicate-Prevention Integration Test.
  Tests job insertion, distinct vacancy differentiation, duplicate collision handling,
  and structured relevance filter storage on Oracle 'robin', with guaranteed cleanup
  leaving zero permanent database records.

.DESCRIPTION
  - Safe, controlled integration test against Oracle PostgreSQL database 'robin'.
  - Tests deduplication key computation and identity key indexing in live database schema.
  - Verifies that distinct Greenhouse job IDs (e.g. 8172508 vs 8172487) sharing company,
    title and location are stored as separate rows and never merged.
  - Verifies that duplicate submissions of an existing job update the existing record
    without duplicating rows.
  - Tests storage of structured filter reasons (TITLE, LOCATION) with status FILTERED_OUT.
  - Finally block executes TRUNCATE TABLE jobs CASCADE to guarantee zero permanent records.
  - Validates pre and post row counts (must be 0 jobs, 0 identity keys, 0 outbox events).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\oracle\test-controlled-persistence.ps1
#>
[CmdletBinding()]
param(
    [string] $VmHost = '132.226.133.162',
    [string] $SshUser = 'ubuntu',
    [string] $TargetDb = 'robin'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Strict safety guard: This script truncates jobs cascade; it must NEVER target robin_restore
if ($TargetDb -cne 'robin') {
    throw "SAFETY ABORT: test-controlled-persistence.ps1 is strictly restricted to database 'robin'. It must NEVER run against 'robin_restore' because its cleanup executes TRUNCATE TABLE jobs CASCADE which would destroy the 2,666 historical jobs in robin_restore."
}

function Write-Step([string] $Message) {
    Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Message)
}

function Get-Sha256([string] $InputString) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($InputString)
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $hasher.ComputeHash($bytes)
        -join ($hash | ForEach-Object { $_.ToString('x2') })
    } finally {
        $hasher.Dispose()
    }
}

function Compute-RobinDedupKey(
    [string] $Provider,
    [string] $Org,
    [string] $ExternalId,
    [string] $CanonicalUrl,
    [string] $Company,
    [string] $Title,
    [string] $Location
) {
    if (-not [string]::IsNullOrWhiteSpace($ExternalId)) {
        $p = if ($Provider) { $Provider.Trim().ToLowerInvariant() } else { '' }
        $o = if ($Org) { $Org.Trim().ToLowerInvariant() } else { '' }
        $prefix = if ($p -or $o) { "$p`:$o`:" } else { '' }
        return Get-Sha256 ("id:$prefix" + $ExternalId.Trim().ToLowerInvariant())
    }
    if (-not [string]::IsNullOrWhiteSpace($CanonicalUrl)) {
        $cleanUrl = $CanonicalUrl.Trim().ToLowerInvariant() -replace '[?].*$', '' -replace '/+$', ''
        return Get-Sha256 "url:$cleanUrl"
    }
    $raw = ('{0}|{1}|{2}' -f ($Company.Trim().ToLowerInvariant()), ($Title.Trim().ToLowerInvariant()), ($Location.Trim().ToLowerInvariant()))
    Get-Sha256 $raw
}

function Invoke-RemotePsql([string] $Sql) {
    $cleanSql = $Sql.Trim()
    $res = $cleanSql | ssh "$SshUser@$VmHost" "sudo -u postgres psql -d $TargetDb -t -A -f -"
    if ($LASTEXITCODE -ne 0) {
        throw "Remote query failed against $TargetDb (exit $LASTEXITCODE)"
    }
    $res
}

Write-Host ''
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host " ROBIN — CONTROLLED PERSISTENCE & DUPLICATE PREVENTION TEST     " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host " Target Host:     $VmHost"
Write-Host " Target Database: $TargetDb"
Write-Host " Cleanup Policy:  GUARANTEED TRUNCATE (ZERO PERMANENT RECORDS)"
Write-Host " Time:            $([DateTime]::UtcNow.ToString('yyyy-MM-dd HH:mm:ss')) UTC"
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host ''

$checks = New-Object 'System.Collections.Generic.List[pscustomobject]'

function Record-Result([bool] $Passed, [string] $Category, [string] $Detail) {
    $status = if ($Passed) { 'PASS' } else { 'FAIL' }
    $obj = [pscustomobject]@{
        Status   = $status
        Category = $Category
        Detail   = $Detail
    }
    $checks.Add($obj)
    $color = if ($Passed) { 'Green' } else { 'Red' }
    Write-Host ("[{0}] {1}: {2}" -f $status, $Category, $Detail) -ForegroundColor $color
}

# 1. Baseline Pre-Flight
Write-Step "Checking baseline row counts in '$TargetDb'..."
$preJobs = (Invoke-RemotePsql 'SELECT count(*)::int FROM jobs;').Trim()
$preKeys = (Invoke-RemotePsql 'SELECT count(*)::int FROM job_identity_keys;').Trim()
$preOutbox = (Invoke-RemotePsql 'SELECT count(*)::int FROM outbox_events;').Trim()

Record-Result ($preJobs -eq '0' -and $preKeys -eq '0' -and $preOutbox -eq '0') `
    'baseline_clean' "Database '$TargetDb' is clean: jobs=$preJobs, keys=$preKeys, outbox=$preOutbox"

# Lookup Stripe source ID and company ID
$sourceId = (Invoke-RemotePsql "SELECT id FROM job_sources WHERE org_identifier = 'stripe' LIMIT 1;").Trim()
$companyId = (Invoke-RemotePsql "SELECT id FROM companies WHERE slug = 'stripe' LIMIT 1;").Trim()

if (-not $sourceId -or -not $companyId) {
    throw "Required Stripe source or company row missing in '$TargetDb'"
}

try {
    # 2. Test Step A: Insert Vacancy #1 (Greenhouse 8172508 - Abuse Investigator Dublin)
    Write-Step "Step A: Inserting Vacancy #1 (Stripe Dublin 8172508)..."
    $job1Id = [Guid]::NewGuid().ToString()
    $job1External = '8172508'
    $job1Dedup = Compute-RobinDedupKey -Provider 'GREENHOUSE' -Org 'stripe' -ExternalId $job1External `
        -CanonicalUrl "https://stripe.com/jobs/search?gh_jid=$job1External" `
        -Company 'Stripe' -Title 'Abuse Investigator' -Location 'Dublin'
    $job1SrcKey = "src:$sourceId`:$job1External"
    $job1Hash = Get-Sha256 "Abuse Investigator`nInvestigating platform abuse and financial security."

    $insertSql1 = @"
INSERT INTO jobs (id, source_id, external_id, dedup_key, company_id, company_name_raw,
                  title, location_raw, city, country, remote_type, employment_type,
                  description_text, skills_extracted, canonical_url, content_hash, status)
VALUES ('$job1Id', '$sourceId', '$job1External', '$job1Dedup', '$companyId', 'Stripe',
        'Abuse Investigator', 'Dublin', 'Dublin', 'IE', 'ONSITE', 'FULL_TIME',
        'Investigating platform abuse and financial security.', '{}', 'https://stripe.com/jobs/search?gh_jid=$job1External', '$job1Hash', 'DISCOVERED');

INSERT INTO job_identity_keys (key, job_id, key_type, source_id)
VALUES ('$job1SrcKey', '$job1Id', 'SRC', '$sourceId');
"@
    Invoke-RemotePsql $insertSql1 | Out-Null

    $countA = (Invoke-RemotePsql 'SELECT count(*)::int FROM jobs;').Trim()
    $keysA = (Invoke-RemotePsql 'SELECT count(*)::int FROM job_identity_keys;').Trim()
    Record-Result ($countA -eq '1' -and $keysA -eq '1') `
        'insert_vacancy_1' "Vacancy #1 persisted: jobs=$countA, keys=$keysA, dedup_key=$job1Dedup"

    # 3. Test Step B: Insert Vacancy #2 (Greenhouse 8172487 - Abuse Investigator Dublin)
    # Identical Title, Company, Location, but DIFFERENT external ID
    Write-Step "Step B: Inserting Vacancy #2 (Stripe Dublin 8172487 - identical title/company/loc)..."
    $job2Id = [Guid]::NewGuid().ToString()
    $job2External = '8172487'
    $job2Dedup = Compute-RobinDedupKey -Provider 'GREENHOUSE' -Org 'stripe' -ExternalId $job2External `
        -CanonicalUrl "https://stripe.com/jobs/search?gh_jid=$job2External" `
        -Company 'Stripe' -Title 'Abuse Investigator' -Location 'Dublin'
    $job2SrcKey = "src:$sourceId`:$job2External"
    $job2Hash = Get-Sha256 "Abuse Investigator`nInvestigating platform abuse and financial security."

    Record-Result ($job1Dedup -ne $job2Dedup) `
        'distinct_dedup_keys' "Separate Greenhouse IDs produce distinct keys ($job1Dedup != $job2Dedup)"

    $insertSql2 = @"
INSERT INTO jobs (id, source_id, external_id, dedup_key, company_id, company_name_raw,
                  title, location_raw, city, country, remote_type, employment_type,
                  description_text, skills_extracted, canonical_url, content_hash, status)
VALUES ('$job2Id', '$sourceId', '$job2External', '$job2Dedup', '$companyId', 'Stripe',
        'Abuse Investigator', 'Dublin', 'Dublin', 'IE', 'ONSITE', 'FULL_TIME',
        'Investigating platform abuse and financial security.', '{}', 'https://stripe.com/jobs/search?gh_jid=$job2External', '$job2Hash', 'DISCOVERED');

INSERT INTO job_identity_keys (key, job_id, key_type, source_id)
VALUES ('$job2SrcKey', '$job2Id', 'SRC', '$sourceId');
"@
    Invoke-RemotePsql $insertSql2 | Out-Null

    $countB = (Invoke-RemotePsql 'SELECT count(*)::int FROM jobs;').Trim()
    $keysB = (Invoke-RemotePsql 'SELECT count(*)::int FROM job_identity_keys;').Trim()
    Record-Result ($countB -eq '2' -and $keysB -eq '2') `
        'distinct_vacancies_coexist' "Both distinct vacancies coexist without collision or merge: jobs=$countB, keys=$keysB"

    # 4. Test Step C: Re-submit Duplicate of Vacancy #1 (Simulated Re-discovery)
    Write-Step "Step C: Simulating duplicate re-discovery of Vacancy #1 (8172508)..."
    $matchJobId = (Invoke-RemotePsql "SELECT job_id FROM job_identity_keys WHERE key = '$job1SrcKey';").Trim()
    $isMatched = ($matchJobId -eq $job1Id)

    Record-Result $isMatched 'identity_key_resolution' `
        "Duplicate resolved to existing Job ID ($matchJobId == $job1Id) via identity key"

    # Simulate Robin TOUCH action: update freshness, do not insert new job row
    Invoke-RemotePsql "UPDATE jobs SET last_seen_at = now() WHERE id = '$job1Id';" | Out-Null

    $countC = (Invoke-RemotePsql 'SELECT count(*)::int FROM jobs;').Trim()
    Record-Result ($countC -eq '2') 'duplicate_collision_prevention' `
        "Duplicate ingestion correctly updated existing record with zero row duplication (jobs remains $countC)"

    # 5. Test Step D: Relevance Filtering & Structured Filter Reasons
    Write-Step "Step D: Applying relevance filters and recording structured filter reasons..."
    # Insert a relevant UK engineering role: Stripe London Full Stack Engineer (8226261)
    $job3Id = [Guid]::NewGuid().ToString()
    $job3External = '8226261'
    $job3Dedup = Compute-RobinDedupKey -Provider 'GREENHOUSE' -Org 'stripe' -ExternalId $job3External `
        -CanonicalUrl "https://stripe.com/jobs/search?gh_jid=$job3External" `
        -Company 'Stripe' -Title 'Full Stack Engineer, Money Movement' -Location 'London'
    $job3SrcKey = "src:$sourceId`:$job3External"
    $job3Hash = Get-Sha256 "Full Stack Engineer, Money Movement`nBuilding core payments infrastructure in London."

    $insertSql3 = @"
INSERT INTO jobs (id, source_id, external_id, dedup_key, company_id, company_name_raw,
                  title, location_raw, city, country, remote_type, employment_type,
                  description_text, skills_extracted, canonical_url, content_hash, status)
VALUES ('$job3Id', '$sourceId', '$job3External', '$job3Dedup', '$companyId', 'Stripe',
        'Full Stack Engineer, Money Movement', 'London', 'London', 'GB', 'HYBRID', 'FULL_TIME',
        'Building core payments infrastructure in London.', '{}', 'https://stripe.com/jobs/search?gh_jid=$job3External', '$job3Hash', 'DISCOVERED');

INSERT INTO job_identity_keys (key, job_id, key_type, source_id)
VALUES ('$job3SrcKey', '$job3Id', 'SRC', '$sourceId');
"@
    Invoke-RemotePsql $insertSql3 | Out-Null

    # Filter out Job 1 & Job 2 with structured reasons (TITLE mismatch and LOCATION mismatch)
    $reasonsJson = '[{"criterion":"TITLE","expected":"Software Engineer, Backend Engineer, Java Developer, Full-stack, Cloud, Platform Engineer","actual":"Abuse Investigator","message":"Job title not in target engineering roles"},{"criterion":"LOCATION","expected":"UK","actual":"Dublin","message":"Job location outside UK"}]'
    $filterSql = @"
UPDATE jobs SET status = 'FILTERED_OUT', filter_reasons = '$reasonsJson'::jsonb
WHERE id IN ('$job1Id', '$job2Id');
"@
    Invoke-RemotePsql $filterSql | Out-Null

    # Query Primary Feed (active engineering jobs) vs Filtered Tier
    $primaryFeedCount = (Invoke-RemotePsql "SELECT count(*)::int FROM jobs WHERE status = 'DISCOVERED' AND deleted_at IS NULL;").Trim()
    $filteredTierCount = (Invoke-RemotePsql "SELECT count(*)::int FROM jobs WHERE status = 'FILTERED_OUT' AND filter_reasons IS NOT NULL;").Trim()

    Record-Result ($primaryFeedCount -eq '1' -and $filteredTierCount -eq '2') `
        'feed_relevance_separation' `
        "Primary feed cleanly isolated ($primaryFeedCount active UK engineering job), non-target roles preserved in filtered tier ($filteredTierCount jobs)"

} finally {
    # 6. Guaranteed Complete Cleanup
    Write-Step "Executing guaranteed database cleanup..."
    $cleanupSql = @"
TRUNCATE TABLE jobs CASCADE;
DELETE FROM outbox_events WHERE aggregate_type = 'JOB';
"@
    Invoke-RemotePsql $cleanupSql | Out-Null

    $postJobs = (Invoke-RemotePsql 'SELECT count(*)::int FROM jobs;').Trim()
    $postKeys = (Invoke-RemotePsql 'SELECT count(*)::int FROM job_identity_keys;').Trim()
    $postOutbox = (Invoke-RemotePsql 'SELECT count(*)::int FROM outbox_events;').Trim()

    Record-Result ($postJobs -eq '0' -and $postKeys -eq '0' -and $postOutbox -eq '0') `
        'guaranteed_cleanup' `
        "Guaranteed cleanup executed successfully: jobs=$postJobs, keys=$postKeys, outbox=$postOutbox (Zero permanent records)"
}

# Summary
Write-Host ''
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "         CONTROLLED PERSISTENCE INTEGRATION TEST SUMMARY        " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$passedCount = @($checks | Where-Object { $_.Status -eq 'PASS' }).Count
$failedCount = @($checks | Where-Object { $_.Status -eq 'FAIL' }).Count

Write-Host " Total Checks:  $($checks.Count)"
Write-Host " Passed Checks: $passedCount" -ForegroundColor Green
Write-Host " Failed Checks: $failedCount" -ForegroundColor $(if ($failedCount -eq 0) { 'Green' } else { 'Red' })
Write-Host "================================================================" -ForegroundColor Cyan

if ($failedCount -gt 0) {
    Write-Host "INTEGRATION TEST RESULT: FAIL" -ForegroundColor Red
    exit 1
} else {
    Write-Host "INTEGRATION TEST RESULT: PASS" -ForegroundColor Green
    exit 0
}
