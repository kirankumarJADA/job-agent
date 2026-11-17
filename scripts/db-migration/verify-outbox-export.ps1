#requires -Version 5.1
<#
.SYNOPSIS
  Proves that the outbox export runs together hold every row R2 left out:
  the pending run's and the published run's row counts and fingerprints must
  add up to the whole-table figures recorded in the R2 snapshot.

.DESCRIPTION
  Reads only metadata: each run's manifest.txt, checks.txt and the hash of
  its encrypted archive. Nothing is decrypted and nothing is contacted.

    - every run must be OK or PARTIAL with 0 failed checks, and its
      outbox-export.dump.gpg must match its recorded SHA-256;
    - exactly one run must hold the complete pending parts (A1 and B1), and
      exactly one the complete published part (B2);
    - pending rows + published rows = R2's row count for public.outbox_events,
      and pending fingerprint + published fingerprint = R2's fingerprint.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\verify-outbox-export.ps1 `
    -RunDir C:\RobinBackups\<pending run>,C:\RobinBackups\<published run>
  (Through -File, list the folders separated by commas without spaces.)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string[]] $RunDir,
    [string] $R2RunDir = 'C:\RobinBackupsCopy\20261108T155443Z-live-partial'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
# powershell -File passes "a,b" as one string: accept comma-separated lists.
$RunDir = @($RunDir | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$fails = 0
function Check([bool] $ok, [string] $name, [string] $detail) {
    if ($ok) { Write-Step "PASS $name $detail" } else { Write-Step "FAIL $name $detail"; $script:fails++ }
}
function Read-Manifest([string] $dir) {
    $m = @{}
    foreach ($l in Get-Content -LiteralPath (Join-Path $dir 'manifest.txt')) {
        $i = $l.IndexOf('|'); if ($i -gt 0 -and -not $m.ContainsKey($l.Substring(0, $i))) { $m[$l.Substring(0, $i)] = $l.Substring($i + 1) }
    }
    $m
}

$fpLine = @(Get-Content -LiteralPath (Join-Path $R2RunDir 'source-fingerprints.txt') | Where-Object { $_.StartsWith('public.outbox_events|') }) | Select-Object -First 1
if (-not $fpLine) { throw "No public.outbox_events line in $R2RunDir\source-fingerprints.txt" }
$r2 = $fpLine -split '\|'
$r2Rows = [long]$r2[1]; $r2Fp = [decimal]$r2[2]

$pending = @(); $published = @()
foreach ($d in $RunDir) {
    $dir = (Resolve-Path -LiteralPath $d).ProviderPath
    $m = Read-Manifest $dir
    $failed = @(Get-Content -LiteralPath (Join-Path $dir 'checks.txt') | Where-Object { $_ -like 'FAIL|*' })
    Check (($m['status'] -in 'complete', 'partial') -and $failed.Count -eq 0) "run_ok" ("{0}: status {1}, {2} failed check(s)" -f (Split-Path $dir -Leaf), $m['status'], $failed.Count)
    $gpg = Join-Path $dir 'outbox-export.dump.gpg'
    $want = ((Get-Content -LiteralPath "$gpg.sha256" -TotalCount 1) -split '\s+')[0].ToLowerInvariant()
    $have = (Get-FileHash -Algorithm SHA256 -LiteralPath $gpg).Hash.ToLowerInvariant()
    Check (($have -eq $want) -and ($have -eq $m['encrypted_sha256'])) "archive_sha256" ("{0}: {1}" -f (Split-Path $dir -Leaf), $have)
    $parts = @($m['parts_complete'] -split '\s+' | Where-Object { $_ })
    if (($parts -contains 'A1') -and ($parts -contains 'B1')) { $pending += [pscustomobject]@{ Dir = $dir; Rows = [long]$m['pending_rows']; Fp = [decimal]$m['pending_fingerprint'] } }
    if ($parts -contains 'B2') { $published += [pscustomobject]@{ Dir = $dir; Rows = [long]$m['published_rows']; Fp = [decimal]$m['published_fingerprint'] } }
}
Check ($pending.Count -eq 1) 'pending_run' ("{0} run(s) hold the complete pending parts" -f $pending.Count)
Check ($published.Count -eq 1) 'published_run' ("{0} run(s) hold the complete published part" -f $published.Count)
if ($pending.Count -eq 1 -and $published.Count -eq 1) {
    $rows = $pending[0].Rows + $published[0].Rows
    $fp = $pending[0].Fp + $published[0].Fp
    Check (($rows -eq $r2Rows) -and ($fp -eq $r2Fp)) 'equals_r2_snapshot' ("pending {0} + published {1} = {2} rows, fingerprint {3}; R2 recorded {4} rows, {5}" -f $pending[0].Rows, $published[0].Rows, $rows, $fp, $r2Rows, $r2Fp)
}
if ($fails -gt 0) { Write-Step "OUTBOX EXPORT VERIFICATION FAILED ($fails)"; exit 1 }
Write-Step 'OUTBOX EXPORT VERIFIED: R2 plus these runs hold every row of the database'
