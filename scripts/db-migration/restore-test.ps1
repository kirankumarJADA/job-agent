#requires -Version 5.1
<#
.SYNOPSIS
  Stage C of the database migration: restore an encrypted backup into an
  isolated PostgreSQL 17 instance and verify it. See scripts/db-migration/README.md.

.DESCRIPTION
  Starts a disposable postgres:17.11 container. Its data directory and the
  decrypted archive live only on RAM-backed tmpfs mounts, and its port is
  bound to 127.0.0.1. Inside it, container/restore.sh:

  - decrypts the archive;
  - restores the application scope as robin_app (NOSUPERUSER NOBYPASSRLS, the
    owner of every restored object, as planned for Oracle);
  - verifies the inventory, constraints, per-table row counts and content
    fingerprints, RLS behaviour and the pending outbox.

  This script then runs Flyway validate, using the application's Flyway
  configuration, and removes the container.

  Live mode asks for the encryption passphrase inside the container.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\restore-test.ps1 `
    -RunDir C:\RobinBackups\20261107T090000Z-live
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $RunDir,
    [ValidateSet('live', 'dryrun')] [string] $Mode = 'live',
    [int] $HostPort = 55439,
    [string] $Image = 'postgres:17.11-bookworm',
    [string] $ExpectedImageId = 'sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994',
    [string] $DryRunPassphrase,
    [int] $DataTmpfsGb = 6,
    [switch] $SkipFlywayValidate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

if ($Mode -eq 'live' -and $DryRunPassphrase) { throw 'Dry-run secrets must not be passed in live mode.' }
if ($Mode -eq 'dryrun' -and -not $DryRunPassphrase) { throw 'Dry-run mode needs -DryRunPassphrase (dry-run.ps1 supplies it).' }
$inputs = @('robin.dump.gpg', 'robin.dump.sha256', 'source-fingerprints.txt', 'source-inventory.txt',
            'source-facts.txt', 'archive-counts.txt', 'manifest.txt')
foreach ($f in $inputs) {
    if (-not (Test-Path -LiteralPath (Join-Path $RunDir $f))) { throw "Missing $f in $RunDir" }
}
Assert-DockerReady
$imageId = Assert-Image -Image $Image -ExpectedId $ExpectedImageId
if (Get-NetTCPConnection -LocalPort $HostPort -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $HostPort is already in use on this machine; pass -HostPort."
}

$stamp = Get-UtcStamp
$reportDir = Join-Path $RunDir "restore-test-$stamp"
New-Item -ItemType Directory -Path $reportDir | Out-Null
$name = "robin-restore-$($stamp.ToLowerInvariant())"
$superPw = New-HexSecret
$appPw = New-HexSecret
Write-Step "report directory: $reportDir"
Write-Step "container: $name (data and decrypted archive on tmpfs; port 127.0.0.1:$HostPort only)"

$runArgs = @('run', '-d', '--name', $name,
    '-p', "127.0.0.1:${HostPort}:5432",
    '--tmpfs', "/var/lib/postgresql/data:rw,size=${DataTmpfsGb}g",
    '--tmpfs', '/restore:rw,size=4g,mode=0700',
    '-e', "POSTGRES_PASSWORD=$superPw", '-e', "ROBIN_APP_PASSWORD=$appPw", '-e', "ROBIN_MODE=$Mode",
    $Image,
    '-c', 'fsync=off', '-c', 'full_page_writes=off', '-c', 'synchronous_commit=off',
    '-c', 'max_wal_size=2GB', '-c', 'maintenance_work_mem=512MB')

$restoreExit = 1
$flywayOk = [bool] $SkipFlywayValidate
$flywayLine = 'NOTE|flyway_validate|skipped (-SkipFlywayValidate)'
try {
    Invoke-Docker $runArgs | Out-Null
    Wait-PostgresReady -Container $name
    Copy-WorkFiles -Container $name -Files @(
        'container\restore.sh', 'container\copy-counts.awk',
        'sql\table-fingerprints.sql', 'sql\inventory.sql', 'sql\outbox-analysis.sql')
    foreach ($f in $inputs) { Invoke-Docker @('cp', (Join-Path $RunDir $f), "${name}:/in/") | Out-Null }

    if ($Mode -eq 'live') {
        Write-Step 'starting the restore test; you will be asked for the encryption passphrase'
        & docker exec -it $name bash /work/restore.sh
    } else {
        & docker exec -e "ROBIN_DRYRUN_PASSPHRASE=$DryRunPassphrase" $name bash /work/restore.sh | Out-Host
    }
    $restoreExit = $LASTEXITCODE

    if ($restoreExit -eq 0 -and -not $SkipFlywayValidate) {
        Write-Step 'Flyway validate against the restored copy (application Flyway configuration)'
        $flyway = Invoke-FlywayHarness -JdbcUrl "jdbc:postgresql://127.0.0.1:$HostPort/robin_restore" `
            -User 'robin_app' -Password $appPw -Action validate -ReportFile (Join-Path $reportDir 'flyway-validate.txt')
        $flywayOk = ($flyway.ExitCode -eq 0) -and ($flyway.Report -like 'ROBIN_FLYWAY validate PASS*')
        $flywayLine = if ($flywayOk) { "PASS|flyway_validate|$($flyway.Report)" } else { "FAIL|flyway_validate|exit $($flyway.ExitCode): $($flyway.Report)" }
    } elseif ($restoreExit -ne 0) {
        $flywayLine = 'NOTE|flyway_validate|not run because the restore checks failed'
    }

    Invoke-Docker @('cp', "${name}:/out/.", $reportDir) | Out-Null
} finally {
    Remove-Container $name
    Write-Step "container $name removed (its tmpfs data directory and decrypted archive are gone)"
}

$checks = Join-Path $reportDir 'restore-checks.txt'
Add-Content -Path $checks -Value $flywayLine -Encoding ascii
$passed = ($restoreExit -eq 0) -and $flywayOk
Write-Summary -RunDir $reportDir -ChecksFile $checks -Passed $passed -What 'RESTORE TEST'
[pscustomobject]@{ ReportDir = $reportDir; Passed = $passed }
if (-not $passed) { exit 1 }
