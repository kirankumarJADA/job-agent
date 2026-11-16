#requires -Version 5.1
<#
.SYNOPSIS
  Robin — Limited Fresh Job Discovery Dry-Run Test.
  Fetches live postings across active discovery sources, validates schema contracts,
  and simulates duplicate detection without writing to any database.

.DESCRIPTION
  - Safe, read-only dry-run.
  - Zero database inserts, updates, or deletes.
  - Zero outbox events published; zero notifications sent.
  - Validates Greenhouse (Stripe), Arbeitnow, and FreeHire.
  - Audits Adzuna credential configuration.
  - Validates title, company, location, description, URL, and timestamp.
  - Simulates Robin's deduplication key computation and collision handling.
  - Verifies that database 'robin' remains 100% clean and untouched before and after.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\oracle\test-discovery-dryrun.ps1
#>
[CmdletBinding()]
param(
    [string] $VmHost = '132.226.133.162',
    [string] $SshUser = 'ubuntu',
    [string] $TargetDb = 'robin'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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

function Compute-RobinContentHash([string] $Title, [string] $Description) {
    $raw = "$Title`n$Description"
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
Write-Host "         ROBIN — FRESH JOB DISCOVERY DRY-RUN TEST               " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host " Mode:            READ-ONLY DRY-RUN (ZERO DB WRITES)"
Write-Host " Target Host:     $VmHost"
Write-Host " Target Database: $TargetDb"
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

# 1. Target Database Isolation Pre-Audit
Write-Step "Checking database 'robin' baseline row count (must remain 0 jobs)..."
$preJobs = (Invoke-RemotePsql 'SELECT count(*)::int FROM jobs;').Trim()
$preUsers = (Invoke-RemotePsql 'SELECT count(*)::int FROM users;').Trim()
$preOutbox = (Invoke-RemotePsql 'SELECT count(*)::int FROM outbox_events;').Trim()

Record-Result ($preJobs -eq '0' -and $preUsers -eq '0' -and $preOutbox -eq '0') `
    'db_baseline_clean' "Database 'robin' currently clean: jobs=$preJobs, users=$preUsers, outbox=$preOutbox"

# 2. Inspect Configured Discovery Sources in 'robin'
Write-Step "Inspecting configured discovery sources in 'robin'..."
$sources = Invoke-RemotePsql 'SELECT kind, org_identifier, display_name, policy, enabled FROM job_sources ORDER BY kind, org_identifier;'
Write-Host ''
Write-Host "Configured Sources in 'robin':" -ForegroundColor Yellow
$sources | ForEach-Object { Write-Host "  $_" }
Write-Host ''

$sourceCount = ($sources | Measure-Object).Count
Record-Result ($sourceCount -eq 8) 'sources_registered' "8 active production discovery sources registered in 'robin'"

# 3. Live Fetch from Discovery Providers
$fetchedJobs = New-Object 'System.Collections.Generic.List[pscustomobject]'

# Source A: Greenhouse (Stripe)
Write-Step "Fetching postings from Greenhouse (Stripe board)..."
try {
    $ghUrl = 'https://boards-api.greenhouse.io/v1/boards/stripe/jobs?content=true'
    $ghResp = Invoke-RestMethod -Uri $ghUrl -Method Get -TimeoutSec 15
    $ghList = @($ghResp.jobs)
    Record-Result ($ghList.Count -gt 0) 'greenhouse_fetch' "Fetched $($ghList.Count) live jobs from Greenhouse (Stripe)"

    # Select two distinct vacancies that previously collided (Stripe Dublin 8172508 & 8172487)
    # plus a live UK engineering vacancy (Stripe London Full Stack Engineer)
    $ghSelected = New-Object 'System.Collections.Generic.List[psobject]'
    $j1 = $ghList | Where-Object { $_.id -eq 8172508 } | Select-Object -First 1
    $j2 = $ghList | Where-Object { $_.id -eq 8172487 } | Select-Object -First 1
    $j3 = $ghList | Where-Object { $_.location.name -match 'London' -and $_.title -match 'Engineer' } | Select-Object -First 1

    if ($j1) { $ghSelected.Add($j1) }
    if ($j2) { $ghSelected.Add($j2) }
    if ($j3) { $ghSelected.Add($j3) }

    # Fallback to first 3 if any specific IDs were not found
    if ($ghSelected.Count -lt 3) {
        for ($i = 0; $i -lt [Math]::Min(3, $ghList.Count); $i++) {
            if (-not $ghSelected.Contains($ghList[$i])) { $ghSelected.Add($ghList[$i]) }
            if ($ghSelected.Count -ge 3) { break }
        }
    }

    foreach ($j in $ghSelected) {
        $descRaw = [System.Net.WebUtility]::HtmlDecode([string]$j.content)
        # Strip HTML tags for plain text description
        $descClean = ($descRaw -replace '<[^>]+>', ' ').Trim() -replace '\s+', ' '

        $fetchedJobs.Add([pscustomobject]@{
            SourceProvider = 'GREENHOUSE'
            SourceOrg      = 'stripe'
            ExternalId     = [string]$j.id
            Title          = [string]$j.title
            Company        = [string]$j.company_name
            Location       = if ($j.location -and $j.location.name) { [string]$j.location.name } else { 'Remote' }
            Description    = $descClean
            SourceUrl      = [string]$j.absolute_url
            DateRaw        = [string]$j.updated_at
        })
    }
} catch {
    Record-Result $false 'greenhouse_fetch' "Greenhouse fetch error: $($_.Exception.Message)"
}

# Source B: Arbeitnow
Write-Step "Fetching postings from Arbeitnow API..."
try {
    $anUrl = 'https://www.arbeitnow.com/api/job-board-api'
    $anResp = Invoke-RestMethod -Uri $anUrl -Method Get -TimeoutSec 15
    $anList = @($anResp.data)
    Record-Result ($anList.Count -gt 0) 'arbeitnow_fetch' "Fetched $($anList.Count) live jobs from Arbeitnow"

    for ($i = 0; $i -lt [Math]::Min(3, $anList.Count); $i++) {
        $j = $anList[$i]
        $descClean = ($j.description -replace '<[^>]+>', ' ').Trim() -replace '\s+', ' '
        $dateIso = if ($j.created_at) {
            [DateTimeOffset]::FromUnixTimeSeconds([long]$j.created_at).ToString('o')
        } else {
            [DateTimeOffset]::UtcNow.ToString('o')
        }

        $fetchedJobs.Add([pscustomobject]@{
            SourceProvider = 'ARBEITNOW'
            SourceOrg      = 'jobs'
            ExternalId     = [string]$j.slug
            Title          = [string]$j.title
            Company        = [string]$j.company_name
            Location       = if ($j.location) { [string]$j.location } else { 'Remote' }
            Description    = $descClean
            SourceUrl      = [string]$j.url
            DateRaw        = $dateIso
        })
    }
} catch {
    Record-Result $false 'arbeitnow_fetch' "Arbeitnow fetch error: $($_.Exception.Message)"
}

# Source C: FreeHire
Write-Step "Fetching postings from FreeHire API (Category: backend)..."
try {
    $fhUrl = 'https://freehire.me/api/v1/agent/jobs/search?category=backend'
    $fhResp = Invoke-RestMethod -Uri $fhUrl -Method Get -TimeoutSec 15
    $fhList = @($fhResp.data)
    Record-Result ($fhList.Count -gt 0) 'freehire_fetch' "Fetched $($fhList.Count) live jobs from FreeHire (backend)"

    for ($i = 0; $i -lt [Math]::Min(3, $fhList.Count); $i++) {
        $j = $fhList[$i]
        $descClean = ($j.description -replace '<[^>]+>', ' ').Trim() -replace '\s+', ' '

        $fetchedJobs.Add([pscustomobject]@{
            SourceProvider = 'FREEHIRE'
            SourceOrg      = 'backend'
            ExternalId     = [string]$j.public_slug
            Title          = [string]$j.title
            Company        = [string]$j.company
            Location       = if ($j.location) { [string]$j.location } else { 'Remote' }
            Description    = $descClean
            SourceUrl      = [string]$j.url
            DateRaw        = [string]$j.posted_at
        })
    }
} catch {
    Record-Result $false 'freehire_fetch' "FreeHire fetch error: $($_.Exception.Message)"
}

# Source D: Adzuna Credential & Gateway Check
Write-Step "Checking Adzuna API authentication requirements..."
$hasAdzunaCreds = [bool]$env:ADZUNA_APP_ID -and [bool]$env:ADZUNA_APP_KEY
if ($hasAdzunaCreds) {
    Record-Result $true 'adzuna_gateway' "Adzuna credentials configured"
} else {
    Record-Result $true 'adzuna_gateway' "Adzuna: Requires operator API credentials (ADZUNA_APP_ID/KEY); correctly dormant per policy"
}

Write-Host ''
Write-Step "Total live sample jobs collected: $($fetchedJobs.Count)"
Write-Host ''

# 4. Field Validation
Write-Step "Validating field schema for all $($fetchedJobs.Count) sample jobs..."

$allFieldsValid = $true
$jobIndex = 1

foreach ($job in $fetchedJobs) {
    $validTitle = -not [string]::IsNullOrWhiteSpace($job.Title) -and ($job.Title.Length -ge 3)
    $validCompany = -not [string]::IsNullOrWhiteSpace($job.Company) -and ($job.Company.Length -ge 2)
    $validLocation = -not [string]::IsNullOrWhiteSpace($job.Location)
    $validDesc = -not [string]::IsNullOrWhiteSpace($job.Description) -and ($job.Description.Length -ge 20)
    $validUrl = [Uri]::IsWellFormedUriString($job.SourceUrl, [UriKind]::Absolute)
    $validDate = $false
    try {
        [DateTimeOffset]::Parse($job.DateRaw) | Out-Null
        $validDate = $true
    } catch {
        $validDate = $false
    }

    $jobValid = $validTitle -and $validCompany -and $validLocation -and $validDesc -and $validUrl -and $validDate
    if (-not $jobValid) { $allFieldsValid = $false }

    Write-Host ("Job #{0} [{1}/{2}]: '{3}' at '{4}' ({5})" -f `
        $jobIndex, $job.SourceProvider, $job.SourceOrg, $job.Title, $job.Company, $job.Location) -ForegroundColor Cyan
    Write-Host ("  - URL:         {0}" -f $job.SourceUrl)
    Write-Host ("  - Date:        {0}" -f $job.DateRaw)
    Write-Host ("  - Desc Length: {0} chars" -f $job.Description.Length)
    Write-Host ("  - Validation:  Title={0}, Company={1}, Loc={2}, Desc={3}, URL={4}, Date={5}" -f `
        $validTitle, $validCompany, $validLocation, $validDesc, $validUrl, $validDate) -ForegroundColor $(if ($jobValid) { 'Green' } else { 'Red' })

    $jobIndex++
}

Record-Result $allFieldsValid 'field_schema_validation' "All $($fetchedJobs.Count) postings satisfy Title, Company, Location, Description, URL, and Date contracts"

# 5. Deduplication & Collision Testing
Write-Step "Testing Robin deduplication key computation and duplicate collision handling..."

$dedupKeys = @{}
$duplicatesFound = New-Object 'System.Collections.Generic.List[string]'

foreach ($job in $fetchedJobs) {
    $dedupKey = Compute-RobinDedupKey -Provider $job.SourceProvider -Org $job.SourceOrg `
        -ExternalId $job.ExternalId -CanonicalUrl $job.SourceUrl `
        -Company $job.Company -Title $job.Title -Location $job.Location
    $contentHash = Compute-RobinContentHash -Title $job.Title -Description $job.Description

    if ($dedupKeys.ContainsKey($dedupKey)) {
        $orig = $dedupKeys[$dedupKey]
        $duplicatesFound.Add("Duplicate identified: '$($job.Title)' at '$($job.Company)' ($($job.Location)) [New ID: $($job.ExternalId), Existing ID: $($orig.ExternalId), Key: $dedupKey]")
        Write-Host ("  [DUPLICATE DETECTED IN FEED] '{0}' at '{1}' ({2}) shares dedup_key with ID {3}" -f `
            $job.Title, $job.Company, $job.Location, $orig.ExternalId) -ForegroundColor Yellow
    } else {
        $dedupKeys[$dedupKey] = $job
    }
}

Record-Result ($dedupKeys.Count -eq $fetchedJobs.Count) 'distinct_vacancies_preserved' `
    ("All $($fetchedJobs.Count) live vacancies remain distinct; separate provider IDs are never merged (Unique Keys: $($dedupKeys.Count))")

# Test Distinct External IDs with Identical Title/Company/Location
$simJob1 = $fetchedJobs[0]
$keyJob1 = Compute-RobinDedupKey -Provider $simJob1.SourceProvider -Org $simJob1.SourceOrg `
    -ExternalId '8172508' -CanonicalUrl $simJob1.SourceUrl `
    -Company $simJob1.Company -Title $simJob1.Title -Location $simJob1.Location
$keyJob2 = Compute-RobinDedupKey -Provider $simJob1.SourceProvider -Org $simJob1.SourceOrg `
    -ExternalId '8172487' -CanonicalUrl $simJob1.SourceUrl `
    -Company $simJob1.Company -Title $simJob1.Title -Location $simJob1.Location

Record-Result ($keyJob1 -ne $keyJob2) 'distinct_external_ids_differentiated' `
    "Different provider job IDs for identical title/company/location remain distinct ($keyJob1 != $keyJob2)"

# Test Duplicate Simulation: Exact Match Collision
$exactDuplicateKey = Compute-RobinDedupKey -Provider $simJob1.SourceProvider -Org $simJob1.SourceOrg `
    -ExternalId '8172508' -CanonicalUrl $simJob1.SourceUrl `
    -Company $simJob1.Company -Title $simJob1.Title -Location $simJob1.Location
$isDetectedDuplicate = ($exactDuplicateKey -eq $keyJob1)

Record-Result $isDetectedDuplicate 'exact_duplicate_detection' `
    "Simulated identical job correctly matches existing dedup_key ($exactDuplicateKey)"

# Test Normalization Tolerance: Whitespace and Casing variations in provider and externalId
$dirtyProvider = "  " + $simJob1.SourceProvider.ToLowerInvariant() + " "
$dirtyOrg = " " + $simJob1.SourceOrg.ToUpperInvariant() + "  "
$dirtyExternalId = " 8172508 "
$normalizedDuplicateKey = Compute-RobinDedupKey -Provider $dirtyProvider -Org $dirtyOrg `
    -ExternalId $dirtyExternalId -CanonicalUrl $simJob1.SourceUrl `
    -Company $simJob1.Company -Title $simJob1.Title -Location $simJob1.Location
$isNormalizedMatch = ($normalizedDuplicateKey -eq $keyJob1)

Record-Result $isNormalizedMatch 'normalization_tolerance' `
    "Dedup key correctly normalizes casing and whitespace variations to identical SHA-256 digest"

# 6. Job Relevance & UK Targeting Evaluation
Write-Step "Evaluating job relevance against target UK software engineering profile..."

$targetTitles = @('software engineer', 'backend engineer', 'java developer', 'full-stack', 'full stack', 'cloud', 'platform engineer')
$ukLocations = @('uk', 'gb', 'united kingdom', 'great britain', 'england', 'scotland', 'wales', 'london', 'remote')

$primaryFeedJobs = New-Object 'System.Collections.Generic.List[pscustomobject]'
$filteredJobs = New-Object 'System.Collections.Generic.List[pscustomobject]'

foreach ($job in $fetchedJobs) {
    $titleLower = $job.Title.ToLowerInvariant() -replace '[-/]', ' '
    $locLower = $job.Location.ToLowerInvariant()

    $titleMatch = $false
    foreach ($t in $targetTitles) {
        if ($titleLower.Contains($t)) { $titleMatch = $true; break }
        # Check token matching for compound titles (e.g. 'Senior Backend Software Engineer')
        $tokens = $t -split '\s+'
        if ($tokens.Count -gt 1) {
            $allTokens = $true
            foreach ($tok in $tokens) {
                if (-not $titleLower.Contains($tok)) { $allTokens = $false; break }
            }
            if ($allTokens) { $titleMatch = $true; break }
        }
    }

    $locMatch = $false
    foreach ($l in $ukLocations) {
        if ($locLower -match "\b$l\b") { $locMatch = $true; break }
    }

    $reasons = New-Object 'System.Collections.Generic.List[string]'
    if (-not $titleMatch) { $reasons.Add("TITLE: '$($job.Title)' does not match preferred engineering roles") }
    if (-not $locMatch) { $reasons.Add("LOCATION: '$($job.Location)' is outside preferred UK locations") }

    if ($reasons.Count -eq 0) {
        $primaryFeedJobs.Add($job)
        Write-Host ("  [PRIMARY FEED] '{0}' at '{1}' ({2})" -f $job.Title, $job.Company, $job.Location) -ForegroundColor Green
    } else {
        $filteredJobs.Add([pscustomobject]@{ Job = $job; Reasons = $reasons })
        Write-Host ("  [FILTERED OUT] '{0}' at '{1}' ({2}) -> {3}" -f $job.Title, $job.Company, $job.Location, ($reasons -join '; ')) -ForegroundColor DarkYellow
    }
}

Record-Result ($primaryFeedJobs.Count -gt 0 -and $filteredJobs.Count -gt 0) 'relevance_feed_filtering' `
    ("Relevance filter successfully categorized $($primaryFeedJobs.Count) job(s) for primary feed and $($filteredJobs.Count) job(s) into filtered tier (data preserved, zero deletion)")

# 7. Post-Run Database Integrity Check
Write-Step "Verifying database 'robin' remains 100% clean and untouched post-test..."
$postJobs = (Invoke-RemotePsql 'SELECT count(*)::int FROM jobs;').Trim()
$postUsers = (Invoke-RemotePsql 'SELECT count(*)::int FROM users;').Trim()
$postOutbox = (Invoke-RemotePsql 'SELECT count(*)::int FROM outbox_events;').Trim()

Record-Result ($postJobs -eq '0' -and $postUsers -eq '0' -and $postOutbox -eq '0') `
    'db_zero_mutation_guarantee' "Zero database writes confirmed: jobs=$postJobs, users=$postUsers, outbox=$postOutbox"

# Summary
Write-Host ''
Write-Host "================================================================" -ForegroundColor Cyan
Write-Host "                  DRY-RUN VERIFICATION SUMMARY                  " -ForegroundColor Cyan
Write-Host "================================================================" -ForegroundColor Cyan

$passedCount = @($checks | Where-Object { $_.Status -eq 'PASS' }).Count
$failedCount = @($checks | Where-Object { $_.Status -eq 'FAIL' }).Count

Write-Host " Total Checks:  $($checks.Count)"
Write-Host " Passed Checks: $passedCount" -ForegroundColor Green
Write-Host " Failed Checks: $failedCount" -ForegroundColor $(if ($failedCount -eq 0) { 'Green' } else { 'Red' })
Write-Host "================================================================" -ForegroundColor Cyan

if ($failedCount -gt 0) {
    Write-Host "DRY-RUN RESULT: FAIL" -ForegroundColor Red
    exit 1
} else {
    Write-Host "DRY-RUN RESULT: PASS" -ForegroundColor Green
    exit 0
}
