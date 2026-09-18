# Personal AI Job Agent — CLI wrapper for PowerShell (Feature 7 companion).
#
# Usage:
#   .\cli.ps1 status
#   .\cli.ps1 login <email> <password>
#   .\cli.ps1 jobs [status] [query]
#   .\cli.ps1 job <job-id>
#   .\cli.ps1 cover-letter <job-id>
#   .\cli.ps1 answer <job-id> "<question>"
#   .\cli.ps1 ats
#   .\cli.ps1 notifications [unread]
param(
    [Parameter(Position = 0)]
    [string]$Command = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"
$BaseUrl = if ($env:BACKEND_BASE_URL) { $env:BACKEND_BASE_URL } else { "http://localhost:8080" }
$SessionPath = if ($env:CLI_SESSION_FILE) { $env:CLI_SESSION_FILE } else { Join-Path $env:TEMP "jobagent-session.txt" }

function Invoke-Api {
    param([string]$Method, [string]$Url, [string]$Body)
    $headers = @{ "Content-Type" = "application/json" }
    $resp = Invoke-WebRequest -Uri $Url -Method $Method -Headers $headers -Body $Body `
        -WebSession $script:webSession -SessionVariable sv -UseBasicParsing -ErrorAction SilentlyContinue
    if (-not $script:webSession) { $script:webSession = $sv }
    Write-Output $resp.Content
}

switch ($Command) {
    "status"        { Invoke-Api "GET" "$BaseUrl/cli/status" $null }
    "login" {
        $email = $Arguments[0]; $password = $Arguments[1]
        if (-not $email -or -not $password) { throw "usage: .\cli.ps1 login <email> <password>" }
        $body = @{ email = $email; password = $password } | ConvertTo-Json
        Invoke-Api "POST" "$BaseUrl/api/v1/auth/login" $body | Out-Null
        Write-Output "Logged in as $email (session stored)."
    }
    "jobs"          { Invoke-Api "GET" "$BaseUrl/cli/jobs?limit=50" $null }
    "job"           { Invoke-Api "GET" "$BaseUrl/cli/jobs/$($Arguments[0])" $null }
    "cover-letter"  { Invoke-Api "POST" "$BaseUrl/cli/cover-letter/$($Arguments[0])" "{}" }
    "answer"        { $q = [uri]::EscapeDataString($Arguments[1]); Invoke-Api "POST" "$BaseUrl/cli/answer/$($Arguments[0])?q=$q" "{}" }
    "ats"           { Invoke-Api "GET" "$BaseUrl/cli/ats" $null }
    "notifications" {
        $extra = if ($Arguments -contains "unread") { "&unread=true" } else { "" }
        Invoke-Api "GET" "$BaseUrl/api/v1/notifications?limit=20$extra" $null
    }
    default {
        Write-Output "Commands: status | login | jobs | job | cover-letter | answer | ats | notifications"
    }
}
