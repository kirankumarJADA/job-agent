#requires -Version 5.1
<#
.SYNOPSIS
  Independent, read-only check of one backup run folder written by
  backup-supabase.ps1, against the plan's B3 gates.

.DESCRIPTION
  It never decrypts anything, never contacts the database and changes
  nothing. It re-derives what it can from the files themselves (SHA-256, the
  OpenPGP header, TOC counts, row-count comparison, outbox facts, Flyway
  history) instead of trusting the backup's own PASS lines alone.

  A PARTIAL run (written with -DeferTableData) is checked as partial: the
  deferred tables must be absent from the archive and present in the
  snapshot's counts and fingerprints. Its verdict says PARTIAL, never complete.

  -CompareWith <another run folder> compares the two snapshots' per-table row
  counts and content fingerprints. Use it to prove that a later complete
  backup saw exactly the data an earlier partial one did.

  A live source that was not read-only at the snapshot fails source_read_only,
  unless -AcceptWritableSource is given: then it is reported as a WARN. Use it
  only when you have decided to back up a writable but idle source; the
  archive still comes from one REPEATABLE READ snapshot, and a later backup's
  -CompareWith shows whether any data changed in between.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\verify-backup.ps1 `
    -RunDir C:\RobinBackups\20261118T090000Z-live -CompareWith C:\RobinBackups\20261107T180000Z-live-partial
#>
param(
    [Parameter(Mandatory = $true)] [string] $RunDir,
    [ValidateSet('live', 'dryrun')] [string] $Mode = 'live',
    [string] $CompareWith,
    [switch] $AcceptWritableSource
)
$ErrorActionPreference = 'Stop'
$script:pass = 0; $script:fail = 0; $script:warn = 0
function Pass([string] $n, [string] $d) { $script:pass++; "PASS  {0,-26} {1}" -f $n, $d }
function Fail([string] $n, [string] $d) { $script:fail++; "FAIL  {0,-26} {1}" -f $n, $d }
function Warn([string] $n, [string] $d) { $script:warn++; "WARN  {0,-26} {1}" -f $n, $d }
function Check([bool] $ok, [string] $n, [string] $d) { if ($ok) { Pass $n $d } else { Fail $n $d } }
function Read-Kv([string] $file) {
    $h = @{}
    if (Test-Path -LiteralPath $file) {
        foreach ($l in @(Get-Content -LiteralPath $file)) { $i = $l.IndexOf('|'); if ($i -gt 0) { $h[$l.Substring(0, $i)] = $l.Substring($i + 1) } }
    }
    $h
}
function Get-Head([string] $file, [int] $n) {
    $buf = New-Object byte[] $n
    $fs = [System.IO.File]::OpenRead($file)
    try { [void]$fs.Read($buf, 0, $n) } finally { $fs.Dispose() }
    , $buf
}
function Read-Fingerprints([string] $dir) {
    $h = @{}
    foreach ($l in @(Get-Content -LiteralPath (Join-Path $dir 'source-fingerprints.txt'))) {
        $p = $l -split '\|'
        if ($p.Count -ge 3) { $h[$p[0]] = "$($p[1])|$($p[2])" }
    }
    $h
}

# The plan's B3 numbers (§1.5 and §2). Dry runs use the synthetic source's.
if ($Mode -eq 'live') {
    $exp = @{ JobDiscoveredMin = 770720; Other = 23; PendingKnown = 771412; OutboxSeq = '1670005'; ReadOnly = 'on' }
} else {
    $exp = @{ JobDiscoveredMin = 30000; Other = 23; PendingKnown = 30023; OutboxSeq = '60023'; ReadOnly = 'off' }
}
$m = Read-Kv (Join-Path $RunDir 'manifest.txt')
$deferredFile = Join-Path $RunDir 'deferred-table-data.txt'
$deferred = @()
if (Test-Path -LiteralPath $deferredFile) { $deferred = @(Get-Content -LiteralPath $deferredFile | Where-Object { $_ }) }
$scope = if ($deferred.Count -gt 0) { 'partial' } else { 'complete' }
$deferredPublic = @($deferred | Where-Object { $_ -like 'public.*' }).Count
$tocExpected = [ordered]@{
    toc_table_data_public = [string](70 - $deferredPublic); toc_row_security_public = '70'; toc_triggers_public = '12'
    toc_views_public = '1'; toc_sequence_set_public = '1'; toc_indexes_public = '175'
}
"Backup run verification ($Mode, $scope): $RunDir"
if ($scope -eq 'partial') { "SCOPE PARTIAL: the rows of $($deferred -join ', ') are NOT in this archive" }
''

# 1. files
$gpg = Join-Path $RunDir 'robin.dump.gpg'
$required = @('robin.dump.gpg', 'robin.dump.gpg.sha256', 'robin.dump.sha256', 'manifest.txt', 'checks.txt', 'host-checks.txt',
    'source-facts.txt', 'source-fingerprints.txt', 'source-inventory.txt', 'toc.txt', 'archive-counts.txt',
    'count-comparison.txt', 'backup.log', 'pg_dump.log', 'pg_dump.timing')
$missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $RunDir $_)) })
Check ($missing.Count -eq 0) 'files_present' $(if ($missing.Count) { 'missing: ' + ($missing -join ', ') } else { "$($required.Count) expected files" })
if (-not (Test-Path -LiteralPath $gpg)) { "VERIFY: stopped, robin.dump.gpg is missing"; exit 1 }
$size = (Get-Item -LiteralPath $gpg).Length
Check ($size -gt 0) 'encrypted_file' ("robin.dump.gpg {0:N0} bytes ({1:N1} MB)" -f $size, ($size / 1MB))
Check ($m['scope'] -eq $scope -or (-not $m['scope'] -and $scope -eq 'complete')) 'scope_recorded' "manifest scope '$($m['scope'])', deferred list: $(if ($deferred.Count) { $deferred -join ', ' } else { 'none' })"

# 2. no plaintext archive anywhere in the run folder
$files = @(Get-ChildItem -LiteralPath $RunDir -Recurse -File -Force)
$plain = @($files | Where-Object { $_.Name -eq 'robin.dump' })
$magic = @($files | Where-Object { $_.Length -ge 5 -and [System.Text.Encoding]::ASCII.GetString((Get-Head $_.FullName 5)) -eq 'PGDMP' })
$bad = @(@($plain) + @($magic) | ForEach-Object { $_.Name } | Sort-Object -Unique)
Check ($bad.Count -eq 0) 'no_plaintext_archive' $(if ($bad.Count) { 'plaintext found: ' + ($bad -join ', ') } else { "no robin.dump and none of $($files.Count) files starts with the pg_dump signature PGDMP" })

# 3. SHA-256: recomputed vs the .sha256 file, the manifest and the host check
$actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $gpg).Hash.ToLowerInvariant()
$listed = ((Get-Content -LiteralPath (Join-Path $RunDir 'robin.dump.gpg.sha256') -TotalCount 1) -split '\s+')[0]
$hostLine = [string](Get-Content -LiteralPath (Join-Path $RunDir 'host-checks.txt') -TotalCount 1)
$shaOk = ($actual -eq $listed) -and ($actual -eq $m['encrypted_sha256']) -and ($hostLine -eq "PASS|host_copy_sha256|$actual")
Check $shaOk 'sha256' $(if ($shaOk) { "$actual (equals the .sha256 file, the manifest and the host check)" } else { "recomputed $actual; .sha256 $listed; manifest $($m['encrypted_sha256']); host check '$hostLine'" })

# 4. OpenPGP header: symmetric-key packet AES-256, iterated+salted S2K SHA-512, then integrity-protected data
$h = Get-Head $gpg 64
if (($h[0] -band 0x40) -ne 0) { $tag = $h[0] -band 0x3F; $p = 2; $len = $h[1] }
else {
    $tag = ($h[0] -band 0x3C) -shr 2; $lt = $h[0] -band 0x03
    if ($lt -eq 0) { $p = 2; $len = $h[1] } elseif ($lt -eq 1) { $p = 3; $len = ($h[1] -shl 8) + $h[2] } else { $p = 5; $len = ($h[1] -shl 24) + ($h[2] -shl 16) + ($h[3] -shl 8) + $h[4] }
}
$ver = $h[$p]; $cipher = $h[$p + 1]; $s2k = $h[$p + 2]; $hash = $h[$p + 3]; $c = $h[$p + 12]
$count = (16 + ($c -band 15)) -shl (($c -shr 4) + 6)
$next = $h[$p + $len]
$nextTag = if (($next -band 0x40) -ne 0) { $next -band 0x3F } else { ($next -band 0x3C) -shr 2 }
$encOk = ($tag -eq 3) -and ($ver -eq 4) -and ($cipher -eq 9) -and ($s2k -eq 3) -and ($hash -eq 10) -and ($count -eq 65011712) -and ($nextTag -eq 18)
Check $encOk 'encryption_header' ("packet $tag v$ver, cipher $cipher (9 = AES-256), S2K mode $s2k (3 = iterated+salted), hash $hash (10 = SHA-512), count $count, next packet $nextTag (18 = integrity-protected)")

# 5. the backup's own checks
$lines = @(Get-Content -LiteralPath (Join-Path $RunDir 'checks.txt'))
$fails = @($lines | Where-Object { $_ -like 'FAIL|*' })
$notes = @($lines | Where-Object { $_ -like 'NOTE|*' })
$passes = @($lines | Where-Object { $_ -like 'PASS|*' })
$names = @($lines | ForEach-Object { ($_ -split '\|')[1] })
$expectedNames = @('plaintext_in_ram', 'server_major', 'pg_dump_exit', 'snapshot_session', 'pg_dump_log', 'source_fingerprints', 'toc_readable',
    'toc_table_data_public', 'toc_row_security_public', 'toc_triggers_public', 'toc_views_public', 'toc_sequence_set_public',
    'toc_indexes_public', 'archive_row_counts', 'decrypt_roundtrip')
if ([long]$m['egress_cap_bytes'] -gt 0) { $expectedNames += 'egress_within_cap' }
if ($scope -eq 'partial') { $expectedNames += 'deferred_table_data' }
$absent = @($expectedNames | Where-Object { $names -notcontains $_ })
Check (($fails.Count -eq 0) -and ($absent.Count -eq 0) -and ($m['checks_fail'] -eq '0')) 'backup_checks' ("{0} PASS, {1} FAIL, {2} NOTE{3}" -f $passes.Count, $fails.Count, $notes.Count, $(if ($absent.Count) { '; missing: ' + ($absent -join ', ') } else { '' }))
foreach ($f in $fails) { Fail 'backup_check_failed' $f }
foreach ($n in $notes) { Warn 'backup_note' $n }
$dec = [string]($lines | Where-Object { $_ -like '*|decrypt_roundtrip|*' } | Select-Object -First 1)
Check ($dec -like 'PASS|*') 'decrypt_roundtrip' $(if ($dec) { ($dec -split '\|', 3)[2] } else { 'not recorded' })

# 6. TOC counts for public against the plan's absolute numbers
foreach ($k in $tocExpected.Keys) {
    $line = [string]($lines | Where-Object { $_ -like "*|$k|*" } | Select-Object -First 1)
    $val = if ($line) { ($line -split '\|')[2] } else { '' }
    Check (($line -like 'PASS|*') -and ($val -eq $tocExpected[$k])) $k "archive $val, plan expects $($tocExpected[$k])"
}

# 7. every snapshot row is in the archive, except the deliberately deferred
#    tables. Recomputed here from archive-counts.txt (rows per table in the
#    archive) and source-fingerprints.txt (rows per table in the snapshot),
#    independently of the backup's own count-comparison.txt.
$snap = @{}; $extTables = @{}
foreach ($l in @(Get-Content -LiteralPath (Join-Path $RunDir 'source-fingerprints.txt'))) {
    $p = $l -split '\|'
    if ($p.Count -ge 4) { $snap[$p[0]] = [long]$p[1]; if ($p[3] -eq 't') { $extTables[$p[0]] = $true } }
}
$arch = @{}
foreach ($l in @(Get-Content -LiteralPath (Join-Path $RunDir 'archive-counts.txt'))) {
    $p = $l -split '\|'
    if ($p.Count -ge 2) { $arch[$p[0]] = [long]$p[1] }
}
$problems = New-Object 'System.Collections.Generic.List[string]'
$okTables = 0; $leftOut = New-Object 'System.Collections.Generic.List[string]'; $leftOutRows = [long]0
foreach ($t in $snap.Keys) {
    if ($deferred -contains $t) {
        if ($arch.ContainsKey($t)) { $problems.Add("deferred $t is in the archive") } else { $leftOut.Add($t); $leftOutRows += $snap[$t] }
    } elseif ($arch.ContainsKey($t)) {
        if ($arch[$t] -eq $snap[$t]) { $okTables++ } else { $problems.Add("$t snapshot $($snap[$t]), archive $($arch[$t])") }
    } elseif (-not $extTables.ContainsKey($t)) {
        $problems.Add("$t ($($snap[$t]) rows) is missing from the archive")
    }
}
foreach ($t in $arch.Keys) { if (-not $snap.ContainsKey($t)) { $problems.Add("$t is in the archive but not in the snapshot") } }
foreach ($t in $deferred) { if (-not $snap.ContainsKey($t)) { $problems.Add("deferred $t is not a table in the snapshot") } }
Check (($problems.Count -eq 0) -and ($okTables -gt 0)) 'row_counts' $(if ($problems.Count) { "$($problems.Count) problem(s), first: $($problems[0])" } else { "$okTables tables: archive rows equal snapshot rows (recomputed); $($snap.Count) tables, $($m['rows_fingerprinted']) rows fingerprinted" })
# The backup's own comparison file must agree, and must not mark anything as
# deferred in a run that claims to be complete.
$cmpAll = @(Get-Content -LiteralPath (Join-Path $RunDir 'count-comparison.txt') | Where-Object { $_ })
$badCmp = @($cmpAll | Where-Object { ($_ -notlike 'EXTENSION_TABLE_NOT_IN_ARCHIVE|*') -and (($scope -eq 'complete') -or ($_ -notlike 'DEFERRED|*')) })
Check ($badCmp.Count -eq 0) 'count_comparison_file' $(if ($badCmp.Count) { "$($badCmp.Count) line(s) flag a difference, first: $($badCmp[0])" } else { "no differences flagged$(if ($scope -eq 'partial') { ' apart from the deferred table(s)' })" })
if ($scope -eq 'partial') {
    $same = ((@($leftOut | Sort-Object) -join ',') -eq (@($deferred | Sort-Object) -join ','))
    Check ($same -and ([string]$leftOutRows -eq $m['deferred_rows'])) 'deferred_tables' "left out of the archive: $(@($leftOut) -join ', ') ($leftOutRows rows at the snapshot, manifest $($m['deferred_rows']))"
} elseif ($leftOut.Count -gt 0) {
    Fail 'deferred_tables' "the run claims to be complete, but $($leftOut -join ', ') is not in the archive"
}

# 8. outbox (B3.4) and its sequence: snapshot facts, recorded for partial runs too
$facts = Read-Kv (Join-Path $RunDir 'source-facts.txt')
$inv = Read-Kv (Join-Path $RunDir 'source-inventory.txt')
$byType = @{}
foreach ($pair in ([string]$facts['outbox_pending_by_type'] -split ',')) { $kv = $pair -split '='; if ($kv.Count -eq 2) { $byType[$kv[0]] = [long]$kv[1] } }
$jd = [long]$byType['job.discovered']
$other = [long](@($byType.Keys | Where-Object { $_ -ne 'job.discovered' } | ForEach-Object { $byType[$_] }) | Measure-Object -Sum).Sum
$pending = [long]$facts['outbox_pending']
Check (($jd -ge $exp.JobDiscoveredMin) -and ($other -eq $exp.Other) -and (($jd + $other) -eq $pending)) 'outbox_pending' ("pending $pending = job.discovered $jd + other $other (gate: job.discovered >= $($exp.JobDiscoveredMin), other = $($exp.Other))")
if ($pending -ne $exp.PendingKnown) { Warn 'outbox_pending_known' "pending $pending differs from the $($exp.PendingKnown) recorded when writes stopped" }
$fpLine = [string](Get-Content -LiteralPath (Join-Path $RunDir 'source-fingerprints.txt') | Where-Object { $_ -like 'public.outbox_events|*' } | Select-Object -First 1)
$total = if ($fpLine) { [long](($fpLine -split '\|')[1]) } else { -1 }
Check ($total -ge $pending) 'outbox_total' ("total rows $total = published $($total - $pending) + pending $pending")
$seq = $inv['sequence_last_value:outbox_events_sequence_id_seq']
Check ($seq -eq $exp.OutboxSeq) 'outbox_sequence' "outbox_events_sequence_id_seq last value $seq, expected $($exp.OutboxSeq)"
"INFO  outbox_age                 oldest pending $($facts['outbox_oldest_pending']), newest pending $($facts['outbox_newest_pending'])"

# 9. Flyway history (B3.6)
$fwOk = ($inv['flyway_rows'] -eq '52') -and ($inv['flyway_success_rows'] -eq '52') -and ($inv['flyway_baseline_rows'] -eq '1') -and ($inv['flyway_max_version'] -eq '051')
Check $fwOk 'flyway_history' ("rows $($inv['flyway_rows']), successful $($inv['flyway_success_rows']), baseline $($inv['flyway_baseline_rows']), latest version $($inv['flyway_max_version'])")

# 10. the source was read-only when the snapshot was taken. A writable live
#     source is accepted only with -AcceptWritableSource, and then as a WARN.
$ro = [string]$facts['default_transaction_read_only']
$roText = "default_transaction_read_only=$ro at snapshot $($facts['snapshot_time']) (expected $($exp.ReadOnly))"
if ($ro -eq $exp.ReadOnly) { Pass 'source_read_only' $roText }
elseif ($AcceptWritableSource -and ($Mode -eq 'live') -and ($ro -eq 'off')) {
    Warn 'source_read_only' "$roText; accepted with -AcceptWritableSource: one REPEATABLE READ snapshot, and a later backup's -CompareWith shows whether data changed"
}
else { Fail 'source_read_only' $roText }

# 11. transfer measured by the container, against its cap
if ($m.ContainsKey('network_rx_bytes')) {
    $rx = [long]$m['network_rx_bytes']; $cap = [long]$m['egress_cap_bytes']
    $txt = "{0:N1} MB received by the backup container{1}" -f ($rx / 1e6), $(if ($cap -gt 0) { ", cap {0:N0} MB" -f ($cap / 1e6) } else { ', no cap' })
    # Over the cap can only mean bytes that arrived after pg_dump had finished
    # (backup.sh stops the run otherwise); it is reported, not hidden.
    if (($cap -eq 0) -or ($rx -le $cap)) { Pass 'transfer_within_cap' $txt } else { Warn 'transfer_over_cap' ("$txt; {0:N1} MB over the cap" -f (($rx - $cap) / 1e6)) }
} elseif ($m.ContainsKey('scope')) {
    Fail 'transfer_measured' 'the manifest of a capped-era run has no network_rx_bytes'
}

# 12. nothing left behind in Docker
$ErrorActionPreference = 'Continue'
$left = @(& docker ps -a --filter name=robin-backup- --format '{{.Names}}' 2>$null)
Check ($left.Count -eq 0) 'no_backup_container' $(if ($left.Count) { 'still present: ' + ($left -join ', ') } else { 'no robin-backup-* container' })
$runStart = (Get-Item -LiteralPath $RunDir).CreationTimeUtc
$vols = @(& docker volume ls -q -f dangling=true 2>$null)
$newVols = 0
if ($vols.Count -gt 0) { $newVols = @(& docker volume inspect --format '{{.CreatedAt}}' $vols 2>$null | Where-Object { [DateTime]::Parse($_).ToUniversalTime() -gt $runStart }).Count }
Check ($newVols -eq 0) 'no_new_docker_volumes' "$newVols dangling volume(s) created after the run folder"
$ErrorActionPreference = 'Stop'

# 13. optional: the same data as another run's snapshot
if ($CompareWith) {
    $a = Read-Fingerprints $RunDir; $b = Read-Fingerprints $CompareWith
    $diff = @(@($a.Keys) + @($b.Keys) | Sort-Object -Unique | Where-Object { $a[$_] -ne $b[$_] })
    $rows = [long](@($a.Values | ForEach-Object { [long](($_ -split '\|')[0]) }) | Measure-Object -Sum).Sum
    Check ($diff.Count -eq 0) 'same_data_as_other_run' $(if ($diff.Count) { "$($diff.Count) table(s) differ from $CompareWith, first: $($diff[0])" } else { "$($a.Count) tables, $rows rows: counts and fingerprints equal $CompareWith" })
}

''
"INFO  archive                    plaintext archive {0:N0} bytes (inside the container only), encrypted {1:N0} bytes, {2} TOC entries" -f [long]$m['archive_bytes'], [long]$m['encrypted_bytes'], $m['toc_entries']
"INFO  pg_dump                    started $($m['pg_dump_started']), finished $($m['pg_dump_finished']), exit $($m['pg_dump_exit'])"
''
$label = if ($scope -eq 'partial') { 'VERIFY (PARTIAL BACKUP, not the complete one)' } else { 'VERIFY' }
"${label}: $($script:pass) PASS, $($script:fail) FAIL, $($script:warn) WARN"
if ($script:fail -gt 0) { exit 1 }
