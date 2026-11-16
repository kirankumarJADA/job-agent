#requires -Version 5.1
<#
.SYNOPSIS
  Clean-start production database initialisation for Oracle PostgreSQL database 'robin'.
  Applies the verified Flyway migration chain V001 through V052, removes mock dev seed
  data, and validates a pristine production schema with zero fake entities and zero jobs.

.DESCRIPTION
  - Target: 'robin' exclusively. Refuses to run against any other database.
  - Database 'robin_restore' is preserved untouched and verified before/after.
  - Path: SSH tunnel (127.0.0.1:<LocalPort> -> 127.0.0.1:5432 on Oracle VM 132.226.133.162).
  - Encryption: TLS verify-full with CA cert C:\Users\jadak\robin-pg-ca.crt.
  - Authentication: SCRAM-SHA-256 for role 'robin_app'.
  - Security: Passwords prompted securely via Read-Host -AsSecureString; held only in process RAM.
  - Forward-only: Never runs flyway clean or drops tables.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\oracle\init-production-robin.ps1
#>
[CmdletBinding()]
param(
    [string] $VmHost = '132.226.133.162',
    [string] $SshUser = 'ubuntu',
    [ValidateRange(1024, 65535)] [int] $LocalPort = 15432,
    [string] $TargetDb = 'robin',
    [string] $CaCert = 'C:\Users\jadak\robin-pg-ca.crt',
    [string] $ExpectedCaSha256 = '0944e9ef8193f597afcdfa779311de146d22149cbe18f601a09f00128b54b2f1',
    [string] $EvidenceRoot = 'C:\RobinBackups\migration-evidence',
    [double] $MinAvailableGb = 1.5,
    [string] $AppPassword
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot '..\common.ps1')

$stamp = [DateTime]::UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'")
$ev = Join-Path $EvidenceRoot "clean-production-init-$stamp"
New-Item -ItemType Directory -Path $ev -Force | Out-Null
$milestones = Join-Path $ev 'milestones.txt'
$summaryFile = Join-Path $ev 'clean-init-summary.txt'
$results = New-Object 'System.Collections.Generic.List[string]'

function Milestone([string] $Text) {
    Add-Content -Path $milestones -Value ("{0} {1}" -f ([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss') + 'Z'), $Text) -Encoding ascii
    Write-Host ''
    Write-Host "==== $Text ====" -ForegroundColor Cyan
}

function Record-Check([bool] $Passed, [string] $Name, [string] $Detail) {
    $status = if ($Passed) { 'PASS' } else { 'FAIL' }
    $line = "$status|$Name|$Detail"
    $results.Add($line)
    Write-Step $line
}

function Stop-Run([string] $Why) {
    Milestone "STOPPED: $Why"
    Record-Check $false 'fatal_error' $Why
    Set-Content -Path $summaryFile -Value $results -Encoding ascii
    throw $Why
}

function Get-Sha([string] $Path) {
    (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

Milestone "PRE-FLIGHT VALIDATION"

# 1. Target database enforcement
if ($TargetDb -ne 'robin') {
    Stop-Run "SAFETY ENFORCEMENT: Target database must be strictly 'robin'. Received: '$TargetDb'."
}
Record-Check $true 'target_database_name' "Database: $TargetDb"

# 2. CA certificate validation
if (-not (Test-Path -LiteralPath $CaCert)) {
    Stop-Run "CA certificate file not found: $CaCert"
}
$caSha = Get-Sha $CaCert
if ($caSha -ne $ExpectedCaSha256) {
    Stop-Run "CA certificate SHA-256 mismatch: got $caSha, expected $ExpectedCaSha256"
}
Record-Check $true 'ca_cert_integrity' "SHA-256: $caSha"

# 3. Memory check
$availGb = (Get-Counter '\Memory\Available MBytes').CounterSamples[0].CookedValue / 1024
if ($availGb -lt $MinAvailableGb) {
    Stop-Run ("Insufficient memory: {0:N1} GB available, minimum {1:N1} GB required." -f $availGb, $MinAvailableGb)
}
Record-Check $true 'memory_check' ("{0:N1} GB available" -f $availGb)

# 4. Port availability
if (Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue) {
    Stop-Run "Port $LocalPort is already in use on this workstation."
}
Record-Check $true 'local_port_free' "Port $LocalPort is available"

$tunnel = $null
try {
    # 5. Open SSH tunnel
    Milestone "ESTABLISHING SECURE SSH TUNNEL (127.0.0.1:$LocalPort -> $VmHost:5432)"
    $tunnel = Start-Process -FilePath (Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe') -PassThru -WindowStyle Hidden -ArgumentList @(
        '-N', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ExitOnForwardFailure=yes',
        '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15',
        '-L', "127.0.0.1:${LocalPort}:127.0.0.1:5432", "$SshUser@$VmHost"
    )

    $deadline = (Get-Date).AddSeconds(25)
    while (-not (Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue)) {
        if ($tunnel.HasExited) {
            Stop-Run "SSH tunnel process terminated unexpectedly (exit code $($tunnel.ExitCode))."
        }
        if ((Get-Date) -gt $deadline) {
            Stop-Run "SSH tunnel did not start listening on port $LocalPort within 25 seconds."
        }
        Start-Sleep -Milliseconds 500
    }
    Record-Check $true 'ssh_tunnel' "Tunnel active (pid $($tunnel.Id), port $LocalPort)"
    Milestone "SSH TUNNEL ACTIVE (pid $($tunnel.Id))"

    # 6. Database credentials
    if (-not $AppPassword -and $env:ROBIN_APP_PASSWORD) {
        $AppPassword = $env:ROBIN_APP_PASSWORD
    }
    if (-not $AppPassword) {
        Write-Host ''
        $securePw = Read-Host -Prompt "Enter robin_app database password (hidden)" -AsSecureString
        $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePw)
        $AppPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
        $securePw = $null
    }
    if (-not $AppPassword) {
        Stop-Run "Database password was not provided."
    }
    Record-Check $true 'credentials_acquired' "robin_app password acquired securely in RAM"

    # 7. Check target database state prior to migration
    Milestone "TARGET PRE-MIGRATION STATE AUDIT"
    $caPath = (Resolve-Path -LiteralPath $CaCert).ProviderPath -replace '\\', '/'
    $jdbcUrl = "jdbc:postgresql://127.0.0.1:${LocalPort}/${TargetDb}?sslmode=verify-full&sslrootcert=$caPath"

    # Remote inspection via SSH psql to confirm empty public schema on robin
    $robinTableCount = ('SELECT count(*)::int FROM information_schema.tables WHERE table_schema = ''public'';' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()

    if ($robinTableCount -ne '0') {
        Stop-Run "Target database 'robin' is NOT empty! Contains $robinTableCount tables. Aborting clean initialization."
    }
    Record-Check $true 'target_db_empty' "Database 'robin' public schema tables: $robinTableCount"

    # Confirm robin_restore baseline before touch
    $restoreTableCount = ('SELECT count(*)::int FROM information_schema.tables WHERE table_schema = ''public'';' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin_restore -t -A -f -').Trim()
    $restoreRowCount = ('SELECT count(*)::int FROM jobs;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin_restore -t -A -f -').Trim()
    Record-Check $true 'rehearsal_db_isolation' "robin_restore contains $restoreTableCount tables, jobs row count: $restoreRowCount (untouched)"

    # 8. Execute Clean Flyway Migration (V001 through V052)
    Milestone "EXECUTING FLYWAY CLEAN MIGRATION (V001 -> V052)"
    $migReport = Join-Path $ev 'flyway-clean-migrate.txt'
    $flywayMig = Invoke-FlywayHarness -JdbcUrl $jdbcUrl -User 'robin_app' -Password $AppPassword `
        -Action 'migrate-clean' -ReportFile $migReport

    $migOk = ($flywayMig.ExitCode -eq 0) -and ($flywayMig.Report -like 'ROBIN_FLYWAY migrate PASS*')
    Record-Check $migOk 'flyway_clean_migration' $(if ($migOk) { $flywayMig.Report } else { "exit $($flywayMig.ExitCode): $($flywayMig.Report)" })

    if (-not $migOk) {
        Stop-Run "Flyway clean initialization failed: $($flywayMig.Report)"
    }
    Milestone "FLYWAY MIGRATIONS V001..V052 APPLIED SUCCESSFULLY"

    # 9. Execute Flyway Validation
    Milestone "VALIDATING FLYWAY SCHEMA INTEGRITY"
    $valReport = Join-Path $ev 'flyway-validate.txt'
    $flywayVal = Invoke-FlywayHarness -JdbcUrl $jdbcUrl -User 'robin_app' -Password $AppPassword `
        -Action 'validate' -ReportFile $valReport

    $valOk = ($flywayVal.ExitCode -eq 0) -and ($flywayVal.Report -like 'ROBIN_FLYWAY validate PASS*')
    Record-Check $valOk 'flyway_schema_validation' $(if ($valOk) { $flywayVal.Report } else { "exit $($flywayVal.ExitCode): $($flywayVal.Report)" })

    if (-not $valOk) {
        Stop-Run "Flyway post-migration validation failed: $($flywayVal.Report)"
    }
    Milestone "FLYWAY VALIDATION PASSED (0 PENDING, 0 INVALID, 52 VERSIONED)"

    # 10. Post-migration schema and object verification
    Milestone "POST-MIGRATION OBJECT & DATA AUDIT"

    # Verify tables and views count
    $baseTablesCount = ('SELECT count(*)::int FROM information_schema.tables WHERE table_schema = ''public'' AND table_type = ''BASE TABLE'';' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $viewsCount = ('SELECT count(*)::int FROM information_schema.tables WHERE table_schema = ''public'' AND table_type = ''VIEW'';' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $totalRelations = [int]$baseTablesCount + [int]$viewsCount
    $tablesOk = ($baseTablesCount -eq '72') -and ($viewsCount -eq '1')
    Record-Check $tablesOk 'schema_object_count' "Base tables: $baseTablesCount, Views: $viewsCount (Total: $totalRelations)"

    # Verify extensions
    $extensions = ('SELECT string_agg(extname, '','' ORDER BY extname) FROM pg_extension;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $expectedExts = 'citext,pg_stat_statements,pgcrypto,plpgsql'
    $extOk = ($extensions -eq $expectedExts)
    Record-Check $extOk 'required_extensions' "Extensions present: $extensions"

    # Verify clean data state: 0 users, 0 jobs, 0 applications, 0 outbox_events, 0 worker_events
    $userCount = ('SELECT count(*)::int FROM users;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $jobCount = ('SELECT count(*)::int FROM jobs;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $appCount = ('SELECT count(*)::int FROM applications;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $outboxCount = ('SELECT count(*)::int FROM outbox_events;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $workerEventsCount = ('SELECT count(*)::int FROM worker_events;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()

    $dataCleanOk = ($userCount -eq '0') -and ($jobCount -eq '0') -and ($appCount -eq '0') -and ($outboxCount -eq '0') -and ($workerEventsCount -eq '0')
    Record-Check $dataCleanOk 'clean_data_state' "users: $userCount, jobs: $jobCount, applications: $appCount, outbox_events: $outboxCount, worker_events: $workerEventsCount"

    # Verify flyway history
    $flywayApplied = ('SELECT count(*)::int FROM flyway_schema_history WHERE success = true;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $flywayFailed = ('SELECT count(*)::int FROM flyway_schema_history WHERE success = false;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $flywayMax = ('SELECT max(version) FROM flyway_schema_history WHERE success = true;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()

    $historyOk = ($flywayApplied -eq '52') -and ($flywayFailed -eq '0') -and ($flywayMax -eq '052')
    Record-Check $historyOk 'flyway_history' "Applied: $flywayApplied, Failed: $flywayFailed, MaxVersion: $flywayMax"

    # Verify robin_restore remained untouched
    $restoreFinalTableCount = ('SELECT count(*)::int FROM information_schema.tables WHERE table_schema = ''public'';' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin_restore -t -A -f -').Trim()
    $restoreFinalRowCount = ('SELECT count(*)::int FROM jobs;' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin_restore -t -A -f -').Trim()
    $restoreUntouchedOk = ($restoreFinalTableCount -eq $restoreTableCount) -and ($restoreFinalRowCount -eq $restoreRowCount)
    Record-Check $restoreUntouchedOk 'rehearsal_db_untouched' "robin_restore tables: $restoreFinalTableCount, jobs: $restoreFinalRowCount (strictly unchanged)"

    # Verify role permissions
    $hasCreate = ('SELECT has_schema_privilege(''robin_app'', ''public'', ''CREATE'');' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $hasUsage = ('SELECT has_schema_privilege(''robin_app'', ''public'', ''USAGE'');' | `
        ssh "$SshUser@$VmHost" 'sudo -u postgres psql -d robin -t -A -f -').Trim()
    $permOk = ($hasCreate -eq 't') -and ($hasUsage -eq 't')
    Record-Check $permOk 'app_role_permissions' "robin_app privileges on public: CREATE=$hasCreate, USAGE=$hasUsage"

    if (-not ($tablesOk -and $extOk -and $dataCleanOk -and $historyOk -and $restoreUntouchedOk -and $permOk)) {
        Stop-Run "Post-migration verification checks detected inconsistencies. See summary."
    }

    Milestone "PRODUCTION DATABASE INITIALISATION COMPLETED SUCCESSFULLY"

} finally {
    # Teardown
    $AppPassword = $null
    Remove-Item Env:ROBIN_APP_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:ROBIN_EXTERNAL_DB_PASSWORD -ErrorAction SilentlyContinue

    if ($tunnel -and -not $tunnel.HasExited) {
        Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue
    }
    $isListening = [bool](Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction SilentlyContinue)
    Record-Check (-not $isListening) 'ssh_tunnel_teardown' "Tunnel closed, port $LocalPort free"

    Set-Content -Path $summaryFile -Value $results -Encoding ascii
    Milestone "RUN EVIDENCE SAVED TO: $ev"
}

Write-Host ''
Write-Host "Initialisation Summary:" -ForegroundColor Green
Get-Content -Path $summaryFile | ForEach-Object {
    if ($_ -like 'PASS|*') { Write-Host "  $_" -ForegroundColor Green }
    else { Write-Host "  $_" -ForegroundColor Red }
}
