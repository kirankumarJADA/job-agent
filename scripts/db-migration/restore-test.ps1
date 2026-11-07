#requires -Version 5.1
<#
.SYNOPSIS
  Stage C of the database migration: restore an encrypted backup into an
  isolated PostgreSQL 17 instance and verify it. See scripts/db-migration/README.md.

.DESCRIPTION
  -Target local (the default) starts a disposable postgres:17.11 container.
  Its data directory and the decrypted archive live only on RAM-backed tmpfs
  mounts, and its port is bound to 127.0.0.1. Inside it, container/restore.sh:

  - decrypts the archive;
  - restores the application scope as robin_app (NOSUPERUSER NOBYPASSRLS, the
    owner of every restored object, as planned for Oracle);
  - verifies the inventory, constraints, per-table row counts and content
    fingerprints, RLS behaviour and the pending outbox.

  -Target remote restores into a server prepared by
  oracle/provision-postgres17.sh instead (the Oracle VM, reached through an
  SSH tunnel, or the local Oracle-shaped test container). The decrypted
  archive still exists only in this machine's container tmpfs; it travels to
  the server inside TLS, verified against the server's CA (verify-full), as
  robin_app. The target database must exist, be owned by robin_app and be
  empty; restore.sh refuses anything else.

  This script then runs Flyway validate, using the application's Flyway
  configuration, and removes the container. (For a live remote target,
  Flyway validate is left to the cutover runbook, because the harness would
  need robin_app's password on a command line.)

  Live mode asks for the encryption passphrase (and, for a remote target,
  robin_app's password) inside the container.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\restore-test.ps1 `
    -RunDir C:\RobinBackups\20261107T090000Z-live

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\restore-test.ps1 `
    -RunDir C:\RobinBackups\20261118T090000Z-live -Target remote `
    -TargetHost <name in the server certificate> -TargetHostAddr host.docker.internal -TargetPort 15432 `
    -TargetDb robin_restore -CaCert C:\RobinBackups\oracle\robin-pg-ca.crt
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $RunDir,
    [ValidateSet('live', 'dryrun')] [string] $Mode = 'live',
    [ValidateSet('local', 'remote')] [string] $Target = 'local',
    [int] $HostPort = 55439,
    [string] $Image = 'postgres:17.11-bookworm',
    [string] $ExpectedImageId = 'sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994',
    [string] $DryRunPassphrase,
    [int] $DataTmpfsGb = 6,
    [switch] $SkipFlywayValidate,
    # Remote target only:
    [string] $TargetHost,
    [string] $TargetHostAddr,
    [int] $TargetPort = 5432,
    [string] $TargetDb = 'robin',
    [string] $CaCert,
    [string] $Network,
    [string] $DryRunAppPassword,
    [string] $FlywayJdbcHost,
    [int] $FlywayJdbcPort = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

if ($Mode -eq 'live' -and ($DryRunPassphrase -or $DryRunAppPassword)) { throw 'Dry-run secrets must not be passed in live mode.' }
if ($Mode -eq 'dryrun' -and -not $DryRunPassphrase) { throw 'Dry-run mode needs -DryRunPassphrase (dry-run.ps1 supplies it).' }
if ($Target -eq 'remote') {
    if (-not $TargetHost -or -not $CaCert) { throw 'A remote target needs -TargetHost (the name in its certificate) and -CaCert.' }
    if (-not (Test-Path -LiteralPath $CaCert)) { throw "CA certificate not found: $CaCert" }
    if ($TargetDb -notmatch '^[a-z_][a-z0-9_]*$') { throw '-TargetDb must be a plain database name.' }
    if ($Mode -eq 'dryrun' -and -not $DryRunAppPassword) { throw 'A dry-run remote target needs -DryRunAppPassword.' }
}
$inputs = @('robin.dump.gpg', 'robin.dump.sha256', 'source-fingerprints.txt', 'source-inventory.txt',
            'source-facts.txt', 'archive-counts.txt', 'manifest.txt')
foreach ($f in $inputs) {
    if (-not (Test-Path -LiteralPath (Join-Path $RunDir $f))) { throw "Missing $f in $RunDir" }
}
Assert-DockerReady
$imageId = Assert-Image -Image $Image -ExpectedId $ExpectedImageId
if ($Target -eq 'local' -and (Get-NetTCPConnection -LocalPort $HostPort -State Listen -ErrorAction SilentlyContinue)) {
    throw "Port $HostPort is already in use on this machine; pass -HostPort."
}

$stamp = Get-UtcStamp
$reportDir = Join-Path $RunDir "restore-test-$stamp$(if ($Target -eq 'remote') { '-remote' } else { '' })"
New-Item -ItemType Directory -Path $reportDir | Out-Null
$name = "robin-restore-$($stamp.ToLowerInvariant())"
Write-Step "report directory: $reportDir"

if ($Target -eq 'local') {
    $superPw = New-HexSecret
    $appPw = New-HexSecret
    Write-Step "container: $name (data and decrypted archive on tmpfs; port 127.0.0.1:$HostPort only)"
    $runArgs = @('run', '-d', '--name', $name,
        '-p', "127.0.0.1:${HostPort}:5432",
        '--tmpfs', "/var/lib/postgresql/data:rw,size=${DataTmpfsGb}g",
        '--tmpfs', '/restore:rw,size=4g,mode=0700',
        '-e', "POSTGRES_PASSWORD=$superPw", '-e', "ROBIN_APP_PASSWORD=$appPw", '-e', "ROBIN_MODE=$Mode",
        $Image,
        '-c', 'fsync=off', '-c', 'full_page_writes=off', '-c', 'synchronous_commit=off',
        '-c', 'max_wal_size=2GB', '-c', 'maintenance_work_mem=512MB')
} else {
    Write-Step "container: $name (client tools only; decrypted archive on tmpfs; restores into $TargetDb on $TargetHost over TLS verify-full)"
    $runArgs = @('run', '-d', '--name', $name,
        '--tmpfs', '/restore:rw,size=4g,mode=0700',
        '-e', "ROBIN_MODE=$Mode", '-e', 'ROBIN_TARGET=remote', '-e', "ROBIN_TARGET_DB=$TargetDb",
        '-e', "PGHOST=$TargetHost", '-e', "PGPORT=$TargetPort", '-e', 'PGSSLMODE=verify-full',
        '-e', 'PGSSLROOTCERT=/in/ca.crt')
    if ($TargetHostAddr) { $runArgs += @('--add-host', 'host.docker.internal:host-gateway', '-e', "ROBIN_TARGET_HOSTADDR=$TargetHostAddr") }
    if ($Network) { $runArgs += @('--network', $Network) }
    $runArgs += @('--entrypoint', 'sleep', $Image, 'infinity')
}

$restoreExit = 1
$flywayOk = [bool] $SkipFlywayValidate
$flywayLine = 'NOTE|flyway_validate|skipped (-SkipFlywayValidate)'
try {
    Invoke-Docker $runArgs | Out-Null
    if ($Target -eq 'local') { Wait-PostgresReady -Container $name }
    Copy-WorkFiles -Container $name -Files @(
        'container\restore.sh', 'container\copy-counts.awk',
        'sql\table-fingerprints.sql', 'sql\inventory.sql', 'sql\outbox-analysis.sql')
    foreach ($f in $inputs) { Invoke-Docker @('cp', (Join-Path $RunDir $f), "${name}:/in/") | Out-Null }
    # Present only in partial backups (it lists the tables whose rows were left out).
    $deferredFile = Join-Path $RunDir 'deferred-table-data.txt'
    if (Test-Path -LiteralPath $deferredFile) { Invoke-Docker @('cp', $deferredFile, "${name}:/in/") | Out-Null }
    if ($Target -eq 'remote') {
        Invoke-Docker @('cp', $CaCert, "${name}:/in/ca.crt") | Out-Null
        if ($TargetHostAddr) {
            # libpq connects to hostaddr but verifies the certificate against PGHOST.
            $addr = (& docker exec $name getent ahostsv4 $TargetHostAddr | Select-Object -First 1) -split '\s+' | Select-Object -First 1
            if (-not $addr) { throw "Cannot resolve $TargetHostAddr inside the container." }
            $execEnv = @('-e', "PGHOSTADDR=$addr")
        } else { $execEnv = @() }
    } else { $execEnv = @() }

    if ($Mode -eq 'live') {
        Write-Step 'starting the restore test; you will be asked for the encryption passphrase'
        & docker exec -it @execEnv $name bash /work/restore.sh
    } else {
        $dryEnv = @('-e', "ROBIN_DRYRUN_PASSPHRASE=$DryRunPassphrase")
        if ($Target -eq 'remote') { $dryEnv += @('-e', "ROBIN_APP_PASSWORD=$DryRunAppPassword") }
        & docker exec @dryEnv @execEnv $name bash /work/restore.sh | Out-Host
    }
    $restoreExit = $LASTEXITCODE

    if ($restoreExit -eq 0 -and -not $SkipFlywayValidate) {
        if ($Target -eq 'local') {
            Write-Step 'Flyway validate against the restored copy (application Flyway configuration)'
            $flyway = Invoke-FlywayHarness -JdbcUrl "jdbc:postgresql://127.0.0.1:$HostPort/robin_restore" `
                -User 'robin_app' -Password $appPw -Action validate -ReportFile (Join-Path $reportDir 'flyway-validate.txt')
        } elseif ($Mode -eq 'dryrun' -and $FlywayJdbcHost -and $FlywayJdbcPort -gt 0) {
            Write-Step 'Flyway validate over JDBC with sslmode=verify-full (the backend''s connection path)'
            $ca = (Resolve-Path -LiteralPath $CaCert).ProviderPath -replace '\\', '/'
            $flyway = Invoke-FlywayHarness -JdbcUrl "jdbc:postgresql://${FlywayJdbcHost}:$FlywayJdbcPort/${TargetDb}?sslmode=verify-full&sslrootcert=$ca" `
                -User 'robin_app' -Password $DryRunAppPassword -Action validate -ReportFile (Join-Path $reportDir 'flyway-validate.txt')
        } else {
            $flyway = $null
        }
        if ($flyway) {
            $flywayOk = ($flyway.ExitCode -eq 0) -and ($flyway.Report -like 'ROBIN_FLYWAY validate PASS*')
            $flywayLine = if ($flywayOk) { "PASS|flyway_validate|$($flyway.Report)" } else { "FAIL|flyway_validate|exit $($flyway.ExitCode): $($flyway.Report)" }
        } else {
            $flywayOk = $true
            $flywayLine = 'NOTE|flyway_validate|not run here for a live remote target (cutover runbook step E3)'
        }
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
