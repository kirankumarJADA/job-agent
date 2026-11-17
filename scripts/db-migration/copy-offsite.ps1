#requires -Version 5.1
<#
.SYNOPSIS
  Copies encrypted backup run folders to a second device (an external drive,
  another machine's share, or, only if you allow it, a OneDrive folder) and
  proves every copied file is identical. The source folders are only read.

.DESCRIPTION
  For each -RunDir it copies the archive set: the *.gpg archives, their
  *.sha256 files and the metadata files (*.txt, *.log: counts, hashes, object
  names, checks; no row data, no host, no user, no credentials). It refuses
  a run folder that holds any unencrypted archive.

  Every copied file's SHA-256 must equal the source file's, and every *.gpg
  must also equal the hash recorded next to it when the backup was made. The
  copies are then marked read-only, and offsite-receipt.txt in each
  destination folder lists what was copied, with hashes and the time.

  Running it again for a folder that is already at the destination copies
  nothing and verifies the copy instead; a destination file that differs is
  reported and never overwritten.

  Refused unless explicitly allowed:
    - a destination on the same volume as the source (-AllowSameVolume, for
      tests): a copy on the same disk does not survive the disk;
    - a destination inside OneDrive (-AllowOneDrive): that uploads the
      encrypted archives to Microsoft, which is your decision to make.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\copy-offsite.ps1 `
    -RunDir C:\RobinBackups\20261108T155443Z-live-partial -Destination E:\RobinOffsite
  (Several folders: separate them with commas and no spaces when using -File.)
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string[]] $RunDir,
    [Parameter(Mandatory)] [string] $Destination,
    [switch] $AllowSameVolume,
    [switch] $AllowOneDrive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
# powershell -File passes "a,b" as one string: accept comma-separated lists.
$RunDir = @($RunDir | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })

function Get-Sha([string] $Path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }
function Get-VolumeRoot([string] $Path) { [IO.Path]::GetPathRoot([IO.Path]::GetFullPath($Path)).ToUpperInvariant() }

$destFull = [IO.Path]::GetFullPath($Destination)
foreach ($synced in @($env:OneDrive, $env:OneDriveConsumer, $env:OneDriveCommercial)) {
    if ($synced -and $destFull.StartsWith($synced, [StringComparison]::OrdinalIgnoreCase) -and -not $AllowOneDrive) {
        throw "Destination $destFull is inside OneDrive ($synced): copying there uploads the archives to Microsoft. Pass -AllowOneDrive if that is your decision."
    }
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).ProviderPath
if ($destFull.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "Destination $destFull is inside the repository." }
if (-not (Test-Path -LiteralPath $destFull)) { New-Item -ItemType Directory -Path $destFull | Out-Null }

$results = @()
foreach ($rd in $RunDir) {
    $src = (Resolve-Path -LiteralPath $rd).ProviderPath
    if ((Get-VolumeRoot $src) -eq (Get-VolumeRoot $destFull) -and -not $AllowSameVolume) {
        throw "Destination $destFull is on the same volume as $src ($(Get-VolumeRoot $src)): that copy would not survive the disk. Use another drive."
    }
    $plain = @(Get-ChildItem -LiteralPath $src -File | Where-Object { $_.Name -match '\.dump$' -or $_.Name -like '*.bin' })
    if ($plain.Count -gt 0) { throw "$src holds unencrypted files ($($plain.Name -join ', ')); nothing is copied from it." }
    $set = @(Get-ChildItem -LiteralPath $src -File | Where-Object { $_.Extension -in '.gpg', '.sha256', '.txt', '.log', '.timing' } | Sort-Object Name)
    if (-not @($set | Where-Object { $_.Extension -eq '.gpg' }).Count) { throw "$src has no *.gpg archive." }

    # The archive must still match the hash recorded when it was made.
    foreach ($g in @($set | Where-Object { $_.Extension -eq '.gpg' })) {
        $rec = Join-Path $src ($g.Name + '.sha256')
        if (-not (Test-Path -LiteralPath $rec)) { throw "$($g.FullName) has no recorded hash ($($g.Name).sha256)." }
        $want = ((Get-Content -LiteralPath $rec -TotalCount 1) -split '\s+')[0].ToLowerInvariant()
        $have = Get-Sha $g.FullName
        if ($have -ne $want) { throw "$($g.FullName) no longer matches its recorded hash ($have, recorded $want); not copied." }
    }

    $dst = Join-Path $destFull (Split-Path $src -Leaf)
    if (-not (Test-Path -LiteralPath $dst)) { New-Item -ItemType Directory -Path $dst | Out-Null }
    $lines = @()
    $copied = 0; $verified = 0; $problems = @()
    foreach ($f in $set) {
        $target = Join-Path $dst $f.Name
        $srcSha = Get-Sha $f.FullName
        if (Test-Path -LiteralPath $target) {
            $dstSha = Get-Sha $target
            if ($dstSha -ne $srcSha) { $problems += "$($f.Name): the copy already there differs (source $srcSha, copy $dstSha); left as it is"; continue }
            $verified++
        } else {
            $tmp = "$target.partial"
            Copy-Item -LiteralPath $f.FullName -Destination $tmp
            $tmpSha = Get-Sha $tmp
            if ($tmpSha -ne $srcSha) { Remove-Item -LiteralPath $tmp; $problems += "$($f.Name): copied bytes differ (source $srcSha, copy $tmpSha)"; continue }
            Move-Item -LiteralPath $tmp -Destination $target
            $copied++
        }
        (Get-Item -LiteralPath $target).IsReadOnly = $true
        $lines += '{0}  {1}  {2}' -f $srcSha, $f.Length, $f.Name
    }
    $receipt = Join-Path $dst 'offsite-receipt.txt'
    $body = @("format|robin-offsite-receipt-v1", "source|$src", "copied_utc|$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))",
        "files|$($set.Count)", "copied|$copied", "already_present_and_verified|$verified", "problems|$($problems.Count)") + $lines
    if (Test-Path -LiteralPath $receipt) { (Get-Item -LiteralPath $receipt).IsReadOnly = $false }
    Set-Content -LiteralPath $receipt -Value $body -Encoding ascii
    (Get-Item -LiteralPath $receipt).IsReadOnly = $true
    foreach ($p in $problems) { Write-Step "  PROBLEM $p" }
    $ok = ($problems.Count -eq 0)
    Write-Step ("{0}: {1} file(s) copied, {2} already there and verified, {3} problem(s) -> {4}" -f (Split-Path $src -Leaf), $copied, $verified, $problems.Count, $(if ($ok) { 'VERIFIED' } else { 'NOT VERIFIED' }))
    $results += [pscustomobject]@{ RunDir = $src; Destination = $dst; Copied = $copied; Verified = $verified; Problems = $problems.Count; Ok = $ok }
}
$results
if (@($results | Where-Object { -not $_.Ok }).Count) { exit 1 }
