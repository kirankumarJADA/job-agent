#requires -Version 5.1
<#
.SYNOPSIS
  Exact, verified, AES-256 encrypted export of public.outbox_events, the one
  table whose rows the R2 partial backup left out. See README.md, "Outbox
  export".

.DESCRIPTION
  One read-only session (REPEATABLE READ, READ ONLY) reads the requested
  parts in chunks. The source server encodes each chunk compactly, hashes and
  fingerprints it and compresses it (pgcrypto, zlib level 9) before sending,
  so the export costs a fraction of the plain row size in egress: about
  47 bytes per pending row and 52 per published row, measured on production
  on 8 Nov.

    -Parts pending    job dictionary (D), pending events of other types (A1),
                      per-job summary of pending job.discovered (S), then the
                      pending job.discovered rows (B1)
    -Parts published  job dictionary (D), then every published row (B2)
    -Parts all        both

  Before every chunk the run checks that the chunk fits in -EgressCapMB and
  stops cleanly if it does not; a watchdog enforces the cap as a hard limit.
  Every chunk that arrived is decrypted, decoded and checked against the
  source's row count, SHA-256 and fingerprint on a throwaway server inside
  the container (RAM only, no swap, Unix socket only). The decoded rows are
  written with pg_dump, restored into a fresh database, fingerprinted again
  and only then encrypted with the R2 passphrase, which is first proven by
  decrypting the R2 archive (-R2RunDir, mounted read-only) to its recorded
  hash. Only the encrypted archive and metadata reach -BackupRoot.

  Live mode asks INSIDE the container for the database password and the
  passphrase; neither is passed on a command line, stored or shown.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\export-outbox.ps1 -Parts pending -EgressCapMB 48
#>
[CmdletBinding()]
param(
    [ValidateSet('live', 'dryrun')] [string] $Mode = 'live',
    [ValidateSet('pending', 'published', 'all')] [string] $Parts = 'pending',
    [string] $PgHost,
    [int] $PgPort = 5432,
    [string] $PgUser,
    [string] $PgDatabase = 'postgres',
    [ValidateSet('require', 'verify-full', 'disable')] [string] $SslMode = 'require',
    [string] $SslRootCert,
    [string] $BackupRoot = 'C:\RobinBackups',
    [string] $R2RunDir = 'C:\RobinBackupsCopy\20261108T155443Z-live-partial',
    [string] $Image = 'postgres:17.11-bookworm',
    [string] $ExpectedImageId = 'sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994',
    [int] $EgressCapMB = 0,
    [ValidateRange(100, 200000)] [int] $ChunkRows = 40000,
    [ValidateRange(1, 8)] [int] $DataTmpfsGb = 2,
    [double] $MinFreeRamGb = 2.0,
    [string] $Network,
    [string] $DryRunPgPassword,
    [string] $DryRunPassphrase,
    [string] $TestCorruptChunk,
    [switch] $TestTamperRow,
    # Also fingerprint every table in the snapshot and compare with R2's.
    [switch] $FingerprintAllTables
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

# Same input rules as backup-supabase.ps1: a bare host name and user name only.
function Test-PoolerHost([string] $Value) {
    if (-not $Value) { return 'The host is empty.' }
    if ($Value -match '[@/:\s]') {
        return 'Enter only the host name: the part of the connection string after "@" and before ":5432" (no "@", "postgresql://", ":5432" or "/postgres").'
    }
    if ($Value -notmatch '^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$') { return 'That is not a valid host name.' }
    if ($Value -notmatch '\.pooler\.supabase\.com$') { return 'That is not a session pooler host: it should end in .pooler.supabase.com.' }
    $null
}
function Test-PoolerUser([string] $Value) {
    if (-not $Value) { return 'The user is empty.' }
    if ($Value -match '[@/:\s]') {
        return 'Enter only the user name: the part of the connection string between "postgresql://" and the next ":" (never the password).'
    }
    if ($Value -notmatch '^[a-z_][a-z0-9_]*\.[a-z0-9]+$') { return 'The session pooler user has the form <role>.<project ref>, as shown in Connect > Session pooler.' }
    $null
}
function Read-Checked([string] $Prompt, [string] $Check) {
    while ($true) {
        $value = ([string](Read-Host $Prompt)).Trim()
        $problem = & $Check $value
        if (-not $problem) { return $value }
        Write-Host "  $problem Try again, or press Ctrl+C to stop." -ForegroundColor Yellow
    }
}

if ($EgressCapMB -lt 0) { throw '-EgressCapMB cannot be negative.' }
if ($Mode -eq 'live') {
    if ($SslMode -eq 'disable') { throw 'Live mode requires TLS: use -SslMode require or verify-full.' }
    if ($DryRunPgPassword -or $DryRunPassphrase -or $TestCorruptChunk -or $TestTamperRow) { throw 'Dry-run secrets and test hooks must not be used in live mode.' }
    if ($EgressCapMB -le 0) { throw 'Live mode needs -EgressCapMB: the remaining egress on the Supabase usage page, in MB, minus a safety margin.' }
    if (-not (Test-Path -LiteralPath (Join-Path $R2RunDir 'robin.dump.gpg'))) { throw "-R2RunDir: no robin.dump.gpg in $R2RunDir (needed to prove the passphrase)." }
    if ($PgHost) { $problem = Test-PoolerHost $PgHost.Trim().ToLowerInvariant(); if ($problem) { throw "-PgHost: $problem" } }
    else { $PgHost = Read-Checked 'Session pooler host (Supabase dashboard > Connect > Session pooler)' 'Test-PoolerHost' }
    $PgHost = $PgHost.Trim().ToLowerInvariant()
    if ($PgUser) { $problem = Test-PoolerUser $PgUser.Trim(); if ($problem) { throw "-PgUser: $problem" } }
    else { $PgUser = Read-Checked 'Session pooler user' 'Test-PoolerUser' }
    $PgUser = $PgUser.Trim()
} else {
    if (-not ($Network -and $DryRunPgPassword -and $DryRunPassphrase)) {
        throw 'Dry-run mode needs -Network, -DryRunPgPassword and -DryRunPassphrase (the outbox export test supplies them).'
    }
}
if (-not $PgHost -or -not $PgUser) { throw 'Host and user are required.' }

# The R2 snapshot's record of the table: the export is compared with it.
$r2Outbox = ''; $r2Pending = ''
if ($R2RunDir -and (Test-Path -LiteralPath $R2RunDir)) {
    $fpLine = @(Get-Content (Join-Path $R2RunDir 'source-fingerprints.txt') -ErrorAction SilentlyContinue |
        Where-Object { $_.StartsWith('public.outbox_events|') }) | Select-Object -First 1
    if ($fpLine) { $f = $fpLine -split '\|'; $r2Outbox = "$($f[1])|$($f[2])" }
    $pendLine = @(Get-Content (Join-Path $R2RunDir 'source-facts.txt') -ErrorAction SilentlyContinue |
        Where-Object { $_.StartsWith('outbox_pending|') }) | Select-Object -First 1
    if ($pendLine) { $r2Pending = ($pendLine -split '\|')[1] }
}

$freeGb = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
if ($freeGb -lt $MinFreeRamGb) {
    throw ("Only {0:N2} GB of RAM is available; the export needs at least {1:N1} GB (its data stays in RAM). Close applications and try again." -f $freeGb, $MinFreeRamGb)
}

Assert-DockerReady
Assert-NoLeftoverContainer 'robin-outbox-'
$imageId = Assert-Image -Image $Image -ExpectedId $ExpectedImageId
Assert-BackupRoot -Path $BackupRoot

$stamp = Get-UtcStamp
$runDir = Join-Path $BackupRoot "$stamp-$Mode-outbox-$Parts"
New-Item -ItemType Directory -Path $runDir | Out-Null
$name = "robin-outbox-$($stamp.ToLowerInvariant())"
$memArg = "$($DataTmpfsGb + 2)g"
Write-Step "run directory: $runDir"
Write-Step "container: $name (received chunks and decoded rows only in RAM, no swap)"
Write-Step ("parts: {0}; {1} rows per chunk; egress cap {2} MB; {3:N2} GB RAM available" -f $Parts, $ChunkRows, $EgressCapMB, $freeGb)

# The container's own server decodes and verifies the chunks. It listens on
# its Unix socket only (listen_addresses empty) and keeps its data directory
# on tmpfs; --memory equal to --memory-swap means nothing can be swapped out.
$runArgs = @('run', '-d', '--name', $name,
    '--tmpfs', '/dump:rw,size=1g,mode=0700',
    '--tmpfs', "/var/lib/postgresql/data:rw,size=${DataTmpfsGb}g",
    '--memory', $memArg, '--memory-swap', $memArg,
    '-e', 'POSTGRES_HOST_AUTH_METHOD=trust',
    '-e', "ROBIN_SRC_HOST=$PgHost", '-e', "ROBIN_SRC_PORT=$PgPort", '-e', "ROBIN_SRC_USER=$PgUser",
    '-e', "ROBIN_SRC_DB=$PgDatabase", '-e', "ROBIN_SRC_SSLMODE=$SslMode",
    '-e', "ROBIN_MODE=$Mode", '-e', "ROBIN_PARTS=$Parts", '-e', "ROBIN_CHUNK_ROWS=$ChunkRows",
    '-e', ('ROBIN_EGRESS_CAP_BYTES=' + ([int64]$EgressCapMB * 1000000)),
    '-e', "ROBIN_IMAGE=$Image", '-e', "ROBIN_IMAGE_DIGEST=$imageId",
    '-e', "ROBIN_R2_OUTBOX=$r2Outbox", '-e', "ROBIN_R2_PENDING=$r2Pending",
    '-e', ('ROBIN_FINGERPRINT_ALL=' + $(if ($FingerprintAllTables) { '1' } else { '0' })))
if ($R2RunDir -and (Test-Path -LiteralPath (Join-Path $R2RunDir 'robin.dump.gpg'))) {
    $runArgs += @('-v', ((Resolve-Path -LiteralPath $R2RunDir).ProviderPath + ':/in/r2:ro'))
}
if ($Network) { $runArgs += @('--network', $Network) }
if ($SslRootCert) { $runArgs += @('-e', 'ROBIN_SRC_SSLROOTCERT=/work/root.crt') }
$runArgs += @('--rm', $Image,
    '-c', 'listen_addresses=', '-c', 'wal_level=minimal', '-c', 'max_wal_senders=0',
    '-c', 'fsync=off', '-c', 'synchronous_commit=off', '-c', 'full_page_writes=off')

$exportExit = 1
try {
    Invoke-Docker $runArgs | Out-Null
    Wait-PostgresReady -Container $name
    Copy-WorkFiles -Container $name -Files @(
        'container\export-outbox.sh', 'container\outbox-plan.sh', 'container\outbox-budget.sh',
        'sql\outbox-export-session.sql', 'sql\outbox-encode-rows.sql', 'sql\outbox-encode-other.sql',
        'sql\outbox-decode.sql', 'sql\table-fingerprints.sql')
    if ($SslRootCert) { Invoke-Docker @('cp', $SslRootCert, "${name}:/work/root.crt") | Out-Null }

    # The container script reports progress on stderr. In Windows PowerShell
    # 5.1 a redirected native stderr becomes error records, which 'Stop'
    # makes terminating, so the export itself runs with 'Continue'. Live mode
    # writes straight to this console (no capture, no pipe): the prompts
    # come from the container's terminal.
    $prevPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Mode -eq 'live') {
            Write-Step 'starting the export; you will be asked for the database password, then for the encryption passphrase'
            & docker exec -it $name bash /work/export-outbox.sh
        } else {
            $execEnv = @('-e', "ROBIN_DRYRUN_PGPASSWORD=$DryRunPgPassword", '-e', "ROBIN_DRYRUN_PASSPHRASE=$DryRunPassphrase")
            if ($TestCorruptChunk) { $execEnv += @('-e', "ROBIN_TEST_CORRUPT_CHUNK=$TestCorruptChunk") }
            if ($TestTamperRow) { $execEnv += @('-e', 'ROBIN_TEST_TAMPER_ROW=1') }
            & docker exec @execEnv $name bash /work/export-outbox.sh 2>&1 | ForEach-Object { "$_" } | Out-Host
        }
        $exportExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevPreference
    }
    Invoke-Docker @('cp', "${name}:/out/.", $runDir) | Out-Null
} finally {
    Remove-Container $name
    Write-Step "container $name removed (its tmpfs, holding the received chunks and decoded rows, is gone)"
}

$hostChecks = Join-Path $runDir 'host-checks.txt'
$gpgFile = Join-Path $runDir 'outbox-export.dump.gpg'
$hashFile = Join-Path $runDir 'outbox-export.dump.gpg.sha256'
$hostOk = $false
if ((Test-Path $gpgFile) -and (Test-Path $hashFile)) {
    $expected = ((Get-Content $hashFile -TotalCount 1) -split '\s+')[0]
    $actual = (Get-FileHash -Algorithm SHA256 -Path $gpgFile).Hash.ToLowerInvariant()
    $hostOk = ($expected -eq $actual)
    $line = if ($hostOk) { "PASS|host_copy_sha256|$actual" } else { "FAIL|host_copy_sha256|host $actual, container $expected" }
    Set-Content -Path $hostChecks -Value $line -Encoding ascii
}
$plaintextLeft = @(Get-ChildItem -Path $runDir -File | Where-Object { $_.Name -eq 'outbox-export.dump' -or $_.Name -like '*.bin' })
if ($plaintextLeft.Count -gt 0) { throw "Unexpected unencrypted file in ${runDir}: $($plaintextLeft.Name -join ', ')" }

$status = switch ($exportExit) { 0 { 'complete' } 4 { 'partial' } default { 'failed' } }
$passed = ($status -ne 'failed') -and $hostOk
$what = if ($status -eq 'partial') { 'OUTBOX EXPORT (PARTIAL)' } else { 'OUTBOX EXPORT' }
Write-Summary -RunDir $runDir -ChecksFile (Join-Path $runDir 'checks.txt') -Passed $passed -What $what
[pscustomobject]@{ RunDir = $runDir; Passed = $passed; Status = $status; ExitCode = $exportExit }
if (-not $passed) { exit 1 }
