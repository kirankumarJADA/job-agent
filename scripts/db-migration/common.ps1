# Shared helpers for the Stage A-C database migration scripts.
# Windows PowerShell 5.1 compatible. Dot-sourced; defines functions only.
#
# Callers run with $ErrorActionPreference = 'Stop'. In Windows PowerShell 5.1
# a redirected native stderr stream (2>$null, 2>&1) becomes error records that
# 'Stop' turns into exceptions, so every helper that redirects docker's stderr
# lowers the preference locally (function scope only).

function Write-Step([string] $Message) {
    Write-Host ("[{0:HH:mm:ss}] {1}" -f (Get-Date), $Message)
}

function Get-UtcStamp {
    [DateTime]::UtcNow.ToString("yyyyMMdd'T'HHmmss'Z'")
}

function New-HexSecret([int] $Bytes = 24) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    -join ($buffer | ForEach-Object { $_.ToString('x2') })
}

function Invoke-Docker([string[]] $Arguments) {
    $output = & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker $($Arguments[0]) failed with exit code $LASTEXITCODE" }
    $output
}

function Remove-Container([string] $Name) {
    $ErrorActionPreference = 'Continue'
    & docker rm -f -v $Name 2>$null | Out-Null
}

function Remove-DockerNetwork([string] $Name) {
    $ErrorActionPreference = 'Continue'
    & docker network rm $Name 2>$null | Out-Null
}

function Assert-DockerReady {
    $ErrorActionPreference = 'Continue'
    $version = & docker version --format '{{.Server.Version}}' 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $version) {
        throw 'The Docker engine is not running. Start Docker Desktop and try again.'
    }
}

function Assert-Image([string] $Image, [string] $ExpectedId) {
    $ErrorActionPreference = 'Continue'
    $id = & docker image inspect --format '{{.Id}}' $Image 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $id) { throw "Image $Image is not present. Run: docker pull $Image" }
    if ($ExpectedId -and $id -ne $ExpectedId) {
        throw "Image $Image is $id, expected $ExpectedId. Refusing to run an unreviewed image."
    }
    $id
}

function Assert-BackupRoot([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "Backup folder $Path does not exist." }
    $full = (Resolve-Path -LiteralPath $Path).ProviderPath
    foreach ($synced in @($env:OneDrive, $env:OneDriveConsumer, $env:OneDriveCommercial)) {
        if ($synced -and $full.StartsWith($synced, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Backup folder $full is inside OneDrive ($synced). Use a folder that is not synced."
        }
    }
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).ProviderPath
    if ($full.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Backup folder $full is inside the repository. Use a folder outside it."
    }
}

function Copy-WorkFiles([string] $Container, [string[]] $Files) {
    Invoke-Docker @('exec', $Container, 'mkdir', '-p', '/work', '/out', '/in') | Out-Null
    $targets = @()
    foreach ($relative in $Files) {
        $source = Join-Path $PSScriptRoot $relative
        Invoke-Docker @('cp', $source, "${Container}:/work/") | Out-Null
        $targets += '/work/' + (Split-Path $relative -Leaf)
    }
    # A Windows checkout may hold these with CRLF; bash and psql need LF.
    Invoke-Docker (@('exec', $Container, 'sed', '-i', '-e', 's/\r$//') + $targets) | Out-Null
}

function Wait-PostgresReady([string] $Container, [string] $User = 'postgres', [int] $TimeoutSeconds = 180) {
    $ErrorActionPreference = 'Continue'
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $logs = & docker logs $Container 2>&1 | Out-String
        if ($logs -match 'PostgreSQL init process complete; ready for start up') {
            & docker exec $Container pg_isready -q -U $User 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { return }
        }
        Start-Sleep -Seconds 2
    }
    throw "PostgreSQL in $Container did not become ready within $TimeoutSeconds s."
}

function Write-Summary([string] $RunDir, [string] $ChecksFile, [bool] $Passed, [string] $What) {
    if (Test-Path $ChecksFile) {
        $lines = Get-Content $ChecksFile
        $fails = @($lines | Where-Object { $_ -like 'FAIL|*' })
        $passes = @($lines | Where-Object { $_ -like 'PASS|*' })
        Write-Step ("{0} checks: {1} passed, {2} failed" -f $What, $passes.Count, $fails.Count)
        foreach ($f in $fails) { Write-Step "  $f" }
    }
    if ($Passed) { Write-Step "$What OK - $RunDir" } else { Write-Step "$What FAILED - see $RunDir" }
}

function Invoke-FlywayHarness {
    # Runs backend ExternalDatabaseFlywayIT (application Flyway configuration)
    # against a database on this machine. Returns the report line it wrote.
    param(
        [Parameter(Mandatory)] [string] $JdbcUrl,
        [Parameter(Mandatory)] [string] $User,
        [Parameter(Mandatory)] [string] $Password,
        [ValidateSet('validate', 'migrate')] [string] $Action = 'validate',
        [Parameter(Mandatory)] [string] $ReportFile
    )
    $ErrorActionPreference = 'Continue'
    $pom = (Resolve-Path (Join-Path $PSScriptRoot '..\..\backend\pom.xml')).ProviderPath
    if (Test-Path $ReportFile) { Remove-Item -LiteralPath $ReportFile }
    $mvnArgs = @('-f', $pom, '-B', '-q',
        '-Dtest=none', '-Dsurefire.failIfNoSpecifiedTests=false',
        '-Dit.test=ExternalDatabaseFlywayIT', '-Dfailsafe.failIfNoSpecifiedTests=false',
        "-Drobin.flyway.external.url=$JdbcUrl",
        "-Drobin.flyway.external.user=$User",
        "-Drobin.flyway.external.password=$Password",
        "-Drobin.flyway.external.action=$Action",
        "-Drobin.flyway.external.report=$ReportFile")
    if ($Action -eq 'migrate') { $mvnArgs += '-Drobin.flyway.external.allow-migrate=dry-run-only' }
    $mvnArgs += 'verify'
    & mvn @mvnArgs | Out-Host
    $exit = $LASTEXITCODE
    $report = if (Test-Path $ReportFile) { (Get-Content $ReportFile -TotalCount 1) } else { '' }
    [pscustomobject]@{ ExitCode = $exit; Report = $report }
}
