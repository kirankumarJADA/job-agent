#requires -Version 5.1
<#
.SYNOPSIS
  Local rehearsal of the Oracle target: provisions PostgreSQL 17 with
  provision-postgres17.sh in a fresh ubuntu:24.04 container, checks its access
  rules, then restores a DRY-RUN backup into it over TLS (verify-full) as
  robin_app and runs the Stage C checks and Flyway validate (over JDBC with
  verify-full, the backend's own connection path).

.DESCRIPTION
  Called by dry-run.ps1 -OracleRehearsal, which supplies the dry run's
  throwaway passphrase. Nothing here touches production or Oracle. It
  downloads ubuntu:24.04 (if absent) and the PGDG packages inside the
  container. Everything is removed afterwards.

  What the local container cannot exercise: the OCI security list, Oracle's
  iptables rules (step 8 of the script is skipped with --container-test),
  systemd, the ARM64 build, the block volume and latency from Render.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $RunDir,
    [Parameter(Mandatory)] [string] $DryRunPassphrase,
    [int] $HostPort = 55441,
    [string] $UbuntuImage = 'ubuntu:24.04',
    [string] $ToolsImage = 'postgres:17.11-bookworm'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\common.ps1')

Assert-DockerReady
if (Get-NetTCPConnection -LocalPort $HostPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $HostPort is in use; pass -HostPort." }
$stamp = (Get-UtcStamp).ToLowerInvariant()
$net = "robin-oracle-$stamp"
$pg = "robin-oracle-pg-$stamp"
$appPw = New-HexSecret
$reportDir = Join-Path $RunDir "oracle-rehearsal-$stamp"
New-Item -ItemType Directory -Path $reportDir | Out-Null
$results = New-Object 'System.Collections.Generic.List[string]'
function Add-Result([bool] $Ok, [string] $Name, [string] $Detail) {
    $line = '{0}|{1}|{2}' -f $(if ($Ok) { 'PASS' } else { 'FAIL' }), $Name, $Detail
    $results.Add($line); Write-Step $line
}
function Invoke-Probe([string] $Conn) {
    # One psql attempt from a throwaway client container on the test network.
    $ErrorActionPreference = 'Continue'
    $out = & docker run --rm --network $net -e "PGPASSWORD=$appPw" -v "${reportDir}:/ca:ro" $ToolsImage `
        psql -X -At -w -c "select coalesce((select version from pg_stat_ssl where pid = pg_backend_pid() and ssl), 'no-tls')" $Conn 2>&1
    [pscustomobject]@{ Exit = $LASTEXITCODE; Text = (@($out) -join ' ').Trim() }
}

$verdict = 'FAILED'
try {
    Write-Step "Oracle-shaped rehearsal ${stamp}: $pg on $net, port 127.0.0.1:$HostPort"
    Invoke-Docker @('network', 'create', $net) | Out-Null
    $subnet = (& docker network inspect -f '{{(index .IPAM.Config 0).Subnet}}' $net).Trim()
    Invoke-Docker @('run', '-d', '--name', $pg, '--hostname', $pg, '--network', $net, '-p', "127.0.0.1:${HostPort}:5432",
        '--tmpfs', '/srv/pgdata:rw,size=2g', $UbuntuImage, 'sleep', 'infinity') | Out-Null
    Invoke-Docker @('exec', $pg, 'mkdir', '-p', '/work') | Out-Null
    Invoke-Docker @('cp', (Join-Path $PSScriptRoot 'provision-postgres17.sh'), "${pg}:/work/") | Out-Null
    Invoke-Docker @('exec', $pg, 'sed', '-i', '-e', 's/\r$//', '/work/provision-postgres17.sh') | Out-Null
    Invoke-Docker @('exec', '-e', "PW=$appPw", $pg, 'sh', '-c', 'umask 077; printf %s "$PW" > /root/app.pw') | Out-Null

    Write-Step 'running provision-postgres17.sh (container test mode; PGDG packages are downloaded inside the container)'
    & docker exec -e ROBIN_APP_PASSWORD_FILE=/root/app.pw $pg bash /work/provision-postgres17.sh --container-test `
        --data-dir /srv/pgdata --server-name $pg --server-name localhost --server-ip 127.0.0.1 `
        --allow $subnet --shared-buffers 256MB --effective-cache-size 1GB | Out-Host
    $provisionExit = $LASTEXITCODE
    $provisionLog = Join-Path $reportDir 'provision.log'
    Invoke-Docker @('cp', "${pg}:/var/log/robin-provision.log", $provisionLog) | Out-Null
    $provisionOk = ($provisionExit -eq 0) -and (Select-String -Path $provisionLog -Pattern '^PROVISION OK' -Quiet)
    Add-Result $provisionOk 'provision' $(if ($provisionOk) { 'provision-postgres17.sh completed (see provision.log)' } else { 'provision-postgres17.sh failed (see provision.log)' })
    if (-not $provisionOk) { throw 'provisioning failed' }
    Invoke-Docker @('exec', $pg, 'rm', '-f', '/root/app.pw') | Out-Null
    $ca = Join-Path $reportDir 'robin-pg-ca.crt'
    Invoke-Docker @('cp', "${pg}:/etc/robin/robin-pg-ca.crt", $ca) | Out-Null

    # Access rules: one allowed path, everything else refused by the server.
    $ok = Invoke-Probe "host=$pg dbname=robin user=robin_app sslmode=verify-full sslrootcert=/ca/robin-pg-ca.crt"
    Add-Result (($ok.Exit -eq 0) -and ($ok.Text -like 'TLSv1.*')) 'tls_verify_full_login' "robin_app to robin with verify-full: exit $($ok.Exit), $($ok.Text)"
    $p = Invoke-Probe "host=$pg dbname=robin user=robin_app sslmode=disable"
    Add-Result (($p.Exit -ne 0) -and ($p.Text -match 'no pg_hba.conf entry|rejects connection')) 'no_plaintext_login' "without TLS: refused ($($p.Text -replace '\s+', ' '))"
    $p = Invoke-Probe "host=$pg dbname=robin user=postgres sslmode=verify-full sslrootcert=/ca/robin-pg-ca.crt"
    Add-Result (($p.Exit -ne 0) -and ($p.Text -match 'pg_hba.conf rejects connection|no pg_hba.conf entry')) 'no_remote_superuser' "postgres over the network: refused"
    $p = Invoke-Probe "host=$pg dbname=postgres user=robin_app sslmode=verify-full sslrootcert=/ca/robin-pg-ca.crt"
    Add-Result (($p.Exit -ne 0) -and ($p.Text -match 'pg_hba.conf rejects connection|no pg_hba.conf entry')) 'only_database_robin' "robin_app to database postgres: refused"
    $pgIp = ([string](& docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $pg)).Trim()
    $p = Invoke-Probe "host=not-in-certificate.invalid hostaddr=$pgIp dbname=robin user=robin_app sslmode=verify-full sslrootcert=/ca/robin-pg-ca.crt"
    Add-Result (($p.Exit -ne 0) -and ($p.Text -match 'does not match host name|server certificate')) 'certificate_name_checked' "a name not in the certificate: refused by verify-full"

    $restoreOut = & (Join-Path $PSScriptRoot '..\restore-test.ps1') -Mode dryrun -Target remote -RunDir $RunDir `
        -TargetHost $pg -TargetPort 5432 -TargetDb robin -CaCert $ca -Network $net `
        -DryRunPassphrase $DryRunPassphrase -DryRunAppPassword $appPw -FlywayJdbcHost 127.0.0.1 -FlywayJdbcPort $HostPort
    $restore = @($restoreOut) | Where-Object { $_ -is [pscustomobject] -and $_.PSObject.Properties['ReportDir'] } | Select-Object -Last 1
    Add-Result ([bool]($restore -and $restore.Passed)) 'remote_restore' $(if ($restore) { "restore-test.ps1 -Target remote: $($restore.ReportDir)" } else { 'restore-test.ps1 returned no result' })
    if (@($results | Where-Object { $_ -like 'FAIL|*' }).Count -eq 0) { $verdict = 'PASSED' }
} catch {
    Add-Result $false 'error' $_.Exception.Message
} finally {
    Remove-Container $pg
    Remove-DockerNetwork $net
    Write-Step "removed $pg and $net"
    Set-Content -Path (Join-Path $reportDir 'oracle-rehearsal-checks.txt') -Value $results -Encoding ascii
}
Write-Step "ORACLE-SHAPED REHEARSAL $verdict - $reportDir"
[pscustomobject]@{ ReportDir = $reportDir; Passed = ($verdict -eq 'PASSED') }
if ($verdict -ne 'PASSED') { exit 1 }
