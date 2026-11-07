#requires -Version 5.1
<#
.SYNOPSIS
  Stage B of the database migration: one read-only, verified, AES-256 encrypted
  pg_dump of the production database. See scripts/db-migration/README.md.

.DESCRIPTION
  Runs pg_dump 17 inside a disposable postgres:17.11 container. The plaintext
  archive exists only in that container's RAM-backed tmpfs and disappears with
  the container. Only the encrypted archive and metadata files (counts, hashes,
  object names; no row data, no host, no user, no credentials) reach
  -BackupRoot.

  Live mode asks INSIDE the container for the database password and the
  encryption passphrase. Neither is passed on a command line, stored on this
  machine or shown on screen.

  Every statement against the database is read-only: one REPEATABLE READ,
  READ ONLY transaction exports a snapshot, fingerprints every table in it and
  holds it while pg_dump --snapshot copies the same state.

  Live mode requires -EgressCapMB: the run stops pg_dump and psql as soon as
  the container has received that many MB (decimal, the unit of the Supabase
  usage page), so a backup can never take more than the allowance you set.

  -DeferTableData leaves the ROWS of the named tables out of the archive (a
  PARTIAL backup). Their definitions, and their row counts and fingerprints at
  the snapshot, are still recorded, so a later complete backup can prove they
  did not change. A partial backup never replaces the complete one.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\backup-supabase.ps1 -EgressCapMB 1500

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\backup-supabase.ps1 `
    -EgressCapMB 680 -DeferTableData public.outbox_events
#>
[CmdletBinding()]
param(
    [ValidateSet('live', 'dryrun')] [string] $Mode = 'live',
    [string] $PgHost,
    [int] $PgPort = 5432,
    [string] $PgUser,
    [string] $PgDatabase = 'postgres',
    [ValidateSet('require', 'verify-full', 'disable')] [string] $SslMode = 'require',
    [string] $SslRootCert,
    [string] $BackupRoot = 'C:\RobinBackups',
    [string] $Image = 'postgres:17.11-bookworm',
    [string] $ExpectedImageId = 'sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994',
    [string] $Network,
    [string] $DryRunPgPassword,
    [string] $DryRunPassphrase,
    [int] $EgressCapMB = 0,
    [string] $DeferTableData = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

# Only a bare host name and user name are accepted. A value pasted with "@",
# "postgresql://", ":5432" or "/postgres" is rejected and asked for again,
# never silently changed (the first live attempt failed on a leading "@").
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

$deferred = @($DeferTableData -split ',' | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ })
foreach ($t in $deferred) {
    if ($t -notmatch '^[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*$') { throw "-DeferTableData: '$t' is not a schema.table name." }
}
if ($EgressCapMB -lt 0) { throw '-EgressCapMB cannot be negative.' }

if ($Mode -eq 'live') {
    if ($SslMode -eq 'disable') { throw 'Live mode requires TLS: use -SslMode require or verify-full.' }
    if ($DryRunPgPassword -or $DryRunPassphrase) { throw 'Dry-run secrets must not be passed in live mode.' }
    if ($EgressCapMB -le 0) {
        throw 'Live mode needs -EgressCapMB: the remaining egress on the Supabase usage page, in MB, minus a safety margin.'
    }
    if ($PgHost) { $problem = Test-PoolerHost $PgHost.Trim().ToLowerInvariant(); if ($problem) { throw "-PgHost: $problem" } }
    else { $PgHost = Read-Checked 'Session pooler host (Supabase dashboard > Connect > Session pooler)' 'Test-PoolerHost' }
    $PgHost = $PgHost.Trim().ToLowerInvariant()
    if ($PgUser) { $problem = Test-PoolerUser $PgUser.Trim(); if ($problem) { throw "-PgUser: $problem" } }
    else { $PgUser = Read-Checked 'Session pooler user' 'Test-PoolerUser' }
    $PgUser = $PgUser.Trim()
} else {
    if (-not ($Network -and $DryRunPgPassword -and $DryRunPassphrase)) {
        throw 'Dry-run mode needs -Network, -DryRunPgPassword and -DryRunPassphrase (dry-run.ps1 supplies them).'
    }
}
if (-not $PgHost -or -not $PgUser) { throw 'Host and user are required.' }
$scope = if ($deferred.Count -gt 0) { 'partial' } else { 'complete' }

Assert-DockerReady
Assert-NoLeftoverContainer 'robin-backup-'
$imageId = Assert-Image -Image $Image -ExpectedId $ExpectedImageId
Assert-BackupRoot -Path $BackupRoot

$stamp = Get-UtcStamp
$runDir = Join-Path $BackupRoot ("$stamp-$Mode" + $(if ($scope -eq 'partial') { '-partial' } else { '' }))
New-Item -ItemType Directory -Path $runDir | Out-Null
$name = "robin-backup-$($stamp.ToLowerInvariant())"
Write-Step "run directory: $runDir"
Write-Step "container: $name (plaintext only in its RAM-backed /dump)"
if ($scope -eq 'partial') { Write-Step ("PARTIAL backup: the rows of {0} are left out (not the complete backup)" -f ($deferred -join ', ')) }
if ($EgressCapMB -gt 0) { Write-Step "egress cap: $EgressCapMB MB received by the container, then pg_dump and psql are stopped" }

$runArgs = @('run', '-d', '--name', $name,
    '--tmpfs', '/dump:rw,size=4g,mode=0700',
    '-e', "PGHOST=$PgHost", '-e', "PGPORT=$PgPort", '-e', "PGUSER=$PgUser",
    '-e', "PGDATABASE=$PgDatabase", '-e', "PGSSLMODE=$SslMode",
    '-e', "ROBIN_MODE=$Mode", '-e', "ROBIN_IMAGE=$Image", '-e', "ROBIN_IMAGE_DIGEST=$imageId",
    '-e', ('ROBIN_EGRESS_CAP_BYTES=' + ([int64]$EgressCapMB * 1000000)),
    '-e', ('ROBIN_DEFER_TABLE_DATA=' + ($deferred -join ',')))
if ($Network) { $runArgs += @('--network', $Network) }
if ($SslRootCert) { $runArgs += @('-e', 'PGSSLROOTCERT=/work/root.crt') }
# Safety net for an interrupted run (window closed, crash): the container's
# only process ends after 4 hours, and --rm then deletes the container and
# its tmpfs, so plaintext can never outlive the run by more than that. The
# finally block below normally removes it at once.
$runArgs += @('--rm', $Image, 'sleep', '14400')

$backupExit = 1
try {
    Invoke-Docker $runArgs | Out-Null
    Copy-WorkFiles -Container $name -Files @(
        'container\backup.sh', 'container\run-pg-dump.sh', 'container\copy-counts.awk',
        'sql\snapshot-session.sql', 'sql\table-fingerprints.sql', 'sql\inventory.sql')
    if ($SslRootCert) { Invoke-Docker @('cp', $SslRootCert, "${name}:/work/root.crt") | Out-Null }

    if ($Mode -eq 'live') {
        Write-Step 'starting the backup; you will be asked for the database password, then for the encryption passphrase'
        & docker exec -it $name bash /work/backup.sh
    } else {
        & docker exec -e "ROBIN_DRYRUN_PGPASSWORD=$DryRunPgPassword" -e "ROBIN_DRYRUN_PASSPHRASE=$DryRunPassphrase" `
            $name bash /work/backup.sh | Out-Host
    }
    $backupExit = $LASTEXITCODE

    Invoke-Docker @('cp', "${name}:/out/.", $runDir) | Out-Null
} finally {
    Remove-Container $name
    Write-Step "container $name removed (its tmpfs, holding the plaintext, is gone)"
}

$hostChecks = Join-Path $runDir 'host-checks.txt'
$gpgFile = Join-Path $runDir 'robin.dump.gpg'
$hashFile = Join-Path $runDir 'robin.dump.gpg.sha256'
$hostOk = $false
if ((Test-Path $gpgFile) -and (Test-Path $hashFile)) {
    $expected = ((Get-Content $hashFile -TotalCount 1) -split '\s+')[0]
    $actual = (Get-FileHash -Algorithm SHA256 -Path $gpgFile).Hash.ToLowerInvariant()
    $hostOk = ($expected -eq $actual)
    $line = if ($hostOk) { "PASS|host_copy_sha256|$actual" } else { "FAIL|host_copy_sha256|host $actual, container $expected" }
    Set-Content -Path $hostChecks -Value $line -Encoding ascii
}
$plaintextLeft = @(Get-ChildItem -Path $runDir -File | Where-Object { $_.Name -eq 'robin.dump' })
if ($plaintextLeft.Count -gt 0) { throw "Unexpected plaintext archive in $runDir" }

$passed = ($backupExit -eq 0) -and $hostOk
$what = if ($scope -eq 'partial') { 'PARTIAL BACKUP' } else { 'BACKUP' }
Write-Summary -RunDir $runDir -ChecksFile (Join-Path $runDir 'checks.txt') -Passed $passed -What $what
[pscustomobject]@{ RunDir = $runDir; Passed = $passed; Scope = $scope }
if (-not $passed) { exit 1 }
