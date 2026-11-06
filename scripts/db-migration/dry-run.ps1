#requires -Version 5.1
<#
.SYNOPSIS
  Stage A dry run: runs backup-supabase.ps1 and restore-test.ps1 end to end
  against a synthetic, Supabase-shaped PostgreSQL 17 source on this machine.

.DESCRIPTION
  No production system is contacted. The run creates and later removes:

  - a private Docker network;
  - a throwaway source container (supabase_admin superuser, plus a postgres
    role that is NOT a superuser and has BYPASSRLS);
  - test credentials generated for this run only.

  The application's own guarded Flyway strategy migrates the source
  (ExternalDatabaseFlywayIT, migrate mode, loopback and marker guarded), and
  synthetic rows are added. The script then performs Stages B and C exactly
  as they will run against production, except that secrets come from this
  script instead of prompts.
#>
[CmdletBinding()]
param(
    [string] $BackupRoot = 'C:\RobinBackups\dryrun',
    [int] $SourcePort = 55438,
    [int] $RestorePort = 55439,
    [int] $JobCount = 2500,
    [int] $EventCount = 60000,
    [switch] $KeepSource
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$Image = 'postgres:17.11-bookworm'
Assert-DockerReady
if (-not (Test-Path -LiteralPath $BackupRoot)) { New-Item -ItemType Directory -Path $BackupRoot | Out-Null }
if (Get-NetTCPConnection -LocalPort $SourcePort -State Listen -ErrorAction SilentlyContinue) {
    throw "Port $SourcePort is already in use; pass -SourcePort."
}

$stamp = Get-UtcStamp
$suffix = $stamp.ToLowerInvariant()
$net = "robin-dryrun-$suffix"
$src = "robin-dryrun-src-$suffix"
$adminPw = New-HexSecret
$pgPw = New-HexSecret
$passphrase = New-HexSecret 32
Write-Step "DRY RUN $stamp - synthetic source $src on network $net (test credentials exist for this run only)"

$verdict = 'FAILED'
try {
    Invoke-Docker @('network', 'create', $net) | Out-Null
    Invoke-Docker @('run', '-d', '--name', $src, '--network', $net, '-p', "127.0.0.1:${SourcePort}:5432",
        '-e', 'POSTGRES_USER=supabase_admin', '-e', "POSTGRES_PASSWORD=$adminPw", '-e', 'POSTGRES_DB=postgres',
        $Image) | Out-Null
    Wait-PostgresReady -Container $src -User 'supabase_admin'
    Copy-WorkFiles -Container $src -Files @('sql\dryrun-source-setup.sql', 'sql\dryrun-source-data.sql')
    Invoke-Docker @('exec', $src, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'supabase_admin',
        '-d', 'postgres', '-v', "pg_pw=$pgPw", '-f', '/work/dryrun-source-setup.sql') | Out-Host
    Write-Step 'Supabase-shaped platform objects created; migrating with the application Flyway strategy'

    $migrate = Invoke-FlywayHarness -JdbcUrl "jdbc:postgresql://127.0.0.1:$SourcePort/postgres" `
        -User 'postgres' -Password $pgPw -Action migrate -ReportFile (Join-Path $BackupRoot "flyway-migrate-$stamp.txt")
    if ($migrate.ExitCode -ne 0) { throw "Flyway migrate failed (exit $($migrate.ExitCode)): $($migrate.Report)" }
    Write-Step "flyway: $($migrate.Report)"

    Invoke-Docker @('exec', $src, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres',
        '-v', "job_count=$JobCount", '-v', "event_count=$EventCount", '-f', '/work/dryrun-source-data.sql') | Out-Host
    Write-Step "synthetic rows inserted ($JobCount jobs, $EventCount job.discovered events plus 23 others)"

    $backupOut = & (Join-Path $PSScriptRoot 'backup-supabase.ps1') -Mode dryrun -PgHost $src -PgPort 5432 `
        -PgUser 'postgres' -SslMode disable -Network $net -DryRunPgPassword $pgPw -DryRunPassphrase $passphrase `
        -BackupRoot $BackupRoot
    $backup = @($backupOut) | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['RunDir'] } | Select-Object -Last 1
    if (-not $backup) { throw 'backup-supabase.ps1 returned no result' }
    if (-not $backup.Passed) { throw "Backup checks failed; see $($backup.RunDir)" }

    $restoreOut = & (Join-Path $PSScriptRoot 'restore-test.ps1') -Mode dryrun -RunDir $backup.RunDir `
        -HostPort $RestorePort -DryRunPassphrase $passphrase
    $restore = @($restoreOut) | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['ReportDir'] } | Select-Object -Last 1
    if (-not $restore) { throw 'restore-test.ps1 returned no result' }
    if ($restore.Passed) { $verdict = 'PASSED' }
    Write-Step "backup:  $($backup.RunDir)"
    Write-Step "restore: $($restore.ReportDir)"
} finally {
    if ($KeepSource) {
        Write-Step "kept $src and $net (-KeepSource)"
    } else {
        Remove-Container $src
        Remove-DockerNetwork $net
        Write-Step "removed $src and $net"
    }
}
Write-Step "DRY RUN $verdict"
if ($verdict -ne 'PASSED') { exit 1 }
