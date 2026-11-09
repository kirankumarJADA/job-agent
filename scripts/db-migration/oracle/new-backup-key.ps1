#requires -Version 5.1
<#
.SYNOPSIS
  Generates the key pair for the Oracle VM's nightly backups (robin-backup.sh)
  on this laptop, in a throwaway container, so GnuPG need not be installed.

.DESCRIPTION
  Writes to -OutDir (default C:\RobinBackups\vm-backup-key, which must not
  exist yet):
    backup-public.asc   the PUBLIC key: copy it to the VM (install-backup.sh)
    backup-secret.asc   the PRIVATE key, itself encrypted with the passphrase
                        you type at the hidden prompt. It never goes to the VM.
                        Keep it with the passphrase in your password manager,
                        and a copy offline: without both, the VM's backups
                        cannot be read.
    fingerprint.txt     the key's fingerprint (install-backup.sh prints the
                        same value on the VM)
  The key is ed25519 (signing) with a cv25519 encryption subkey, and has no
  expiry. The container is removed afterwards and keeps nothing.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\db-migration\oracle\new-backup-key.ps1
#>
[CmdletBinding()]
param(
    [string] $OutDir = 'C:\RobinBackups\vm-backup-key',
    [string] $Image = 'postgres:17.11-bookworm',
    [string] $ExpectedImageId = 'sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994',
    # Tests only: take the passphrase from this file instead of a prompt.
    [string] $TestPassphraseFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\common.ps1')
if (Test-Path -LiteralPath $OutDir) { throw "$OutDir already exists: refusing to overwrite a key." }
Assert-DockerReady
Assert-Image -Image $Image -ExpectedId $ExpectedImageId | Out-Null
New-Item -ItemType Directory -Path $OutDir | Out-Null
$out = (Resolve-Path -LiteralPath $OutDir).ProviderPath
$script = @'
set -eu
umask 077
export GNUPGHOME=$(mktemp -d)
if [ -n "${ROBIN_TEST_PASSFILE:-}" ]; then pp=$(cat "$ROBIN_TEST_PASSFILE"); else
  printf 'Passphrase for the backup private key (input hidden): ' > /dev/tty; stty -echo < /dev/tty; IFS= read -r pp < /dev/tty; stty echo < /dev/tty; printf '\n' > /dev/tty
  printf 'Repeat it: ' > /dev/tty; stty -echo < /dev/tty; IFS= read -r p2 < /dev/tty; stty echo < /dev/tty; printf '\n' > /dev/tty
  [ "$pp" = "$p2" ] || { echo "the two entries differ; nothing was written" >&2; exit 1; }
fi
[ ${#pp} -ge 16 ] || { echo "use at least 16 characters" >&2; exit 1; }
printf '%s' "$pp" > "$GNUPGHOME/pp"
gpg --batch --quiet --pinentry-mode loopback --passphrase-file "$GNUPGHOME/pp" \
    --quick-gen-key 'Robin Oracle VM backups' future-default default never
gpg --armor --export > /out/backup-public.asc
gpg --batch --pinentry-mode loopback --passphrase-file "$GNUPGHOME/pp" --armor --export-secret-keys > /out/backup-secret.asc
gpg --with-colons --show-keys /out/backup-public.asc | awk -F: '$1 == "fpr" { print $10; exit }' > /out/fingerprint.txt
rm -rf "$GNUPGHOME"
echo "KEY OK $(cat /out/fingerprint.txt)"
'@
$tmp = Join-Path $out '.gen.sh'
[IO.File]::WriteAllText($tmp, ($script -replace "`r", ''))
try {
    $args_ = @('run', '--rm', '--network', 'none', '-v', "${out}:/out", '--entrypoint', 'bash')
    if ($TestPassphraseFile) {
        $args_ += @('-v', ((Resolve-Path -LiteralPath $TestPassphraseFile).ProviderPath + ':/pp:ro'), '-e', 'ROBIN_TEST_PASSFILE=/pp', $Image, '/out/.gen.sh')
    } else {
        $args_ = @('run', '--rm', '-it', '--network', 'none', '-v', "${out}:/out", '--entrypoint', 'bash', $Image, '/out/.gen.sh')
    }
    # gpg reports progress on stderr; in Windows PowerShell 5.1 a redirected
    # native stderr would become terminating error records under 'Stop'.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & docker @args_ 2>&1 | ForEach-Object { "$_" } | Out-Host; $genExit = $LASTEXITCODE } finally { $ErrorActionPreference = $prev }
    if ($genExit -ne 0) { throw "key generation failed (exit $genExit)" }
} finally {
    Remove-Item -LiteralPath $tmp -ErrorAction SilentlyContinue
}
$fpr = (Get-Content -LiteralPath (Join-Path $out 'fingerprint.txt') -TotalCount 1).Trim()
Write-Step "backup key written to $out (fingerprint $fpr)"
Write-Step 'copy backup-public.asc to the VM; store backup-secret.asc and its passphrase in your password manager, and a copy offline'
[pscustomobject]@{ OutDir = $out; Fingerprint = $fpr }
