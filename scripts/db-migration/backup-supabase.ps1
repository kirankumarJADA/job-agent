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

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\backup-supabase.ps1 `
    -PgHost <session pooler host> -PgUser <session pooler user>
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
    [string] $DryRunPassphrase
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

if ($Mode -eq 'live') {
    if ($SslMode -eq 'disable') { throw 'Live mode requires TLS: use -SslMode require or verify-full.' }
    if ($DryRunPgPassword -or $DryRunPassphrase) { throw 'Dry-run secrets must not be passed in live mode.' }
    if (-not $PgHost) { $PgHost = Read-Host 'Session pooler host (Supabase dashboard > Connect > Session pooler)' }
    if (-not $PgUser) { $PgUser = Read-Host 'Session pooler user' }
} else {
    if (-not ($Network -and $DryRunPgPassword -and $DryRunPassphrase)) {
        throw 'Dry-run mode needs -Network, -DryRunPgPassword and -DryRunPassphrase (dry-run.ps1 supplies them).'
    }
}
if (-not $PgHost -or -not $PgUser) { throw 'Host and user are required.' }

Assert-DockerReady
$imageId = Assert-Image -Image $Image -ExpectedId $ExpectedImageId
Assert-BackupRoot -Path $BackupRoot

$stamp = Get-UtcStamp
$runDir = Join-Path $BackupRoot "$stamp-$Mode"
New-Item -ItemType Directory -Path $runDir | Out-Null
$name = "robin-backup-$($stamp.ToLowerInvariant())"
Write-Step "run directory: $runDir"
Write-Step "container: $name (plaintext only in its RAM-backed /dump)"

$runArgs = @('run', '-d', '--name', $name,
    '--tmpfs', '/dump:rw,size=4g,mode=0700',
    '-e', "PGHOST=$PgHost", '-e', "PGPORT=$PgPort", '-e', "PGUSER=$PgUser",
    '-e', "PGDATABASE=$PgDatabase", '-e', "PGSSLMODE=$SslMode",
    '-e', "ROBIN_MODE=$Mode", '-e', "ROBIN_IMAGE=$Image", '-e', "ROBIN_IMAGE_DIGEST=$imageId")
if ($Network) { $runArgs += @('--network', $Network) }
if ($SslRootCert) { $runArgs += @('-e', 'PGSSLROOTCERT=/work/root.crt') }
$runArgs += @($Image, 'sleep', 'infinity')

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
Write-Summary -RunDir $runDir -ChecksFile (Join-Path $runDir 'checks.txt') -Passed $passed -What 'BACKUP'
[pscustomobject]@{ RunDir = $runDir; Passed = $passed }
if (-not $passed) { exit 1 }
