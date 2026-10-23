#!/usr/bin/env pwsh
#Requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$backendRoot = Join-Path $repositoryRoot 'backend'
$environmentFile = Join-Path $backendRoot '.env'
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    throw 'Install uv before preparing the backend. See backend/README.md.'
}

# Refuse to create a secret-bearing file where Git would track it.
& git -C $repositoryRoot check-ignore --quiet -- backend/.env
if ($LASTEXITCODE -ne 0) {
    throw 'backend/.env must be ignored by Git before backend setup can continue.'
}
if (-not (Test-Path -LiteralPath $environmentFile)) {
    Copy-Item -LiteralPath (Join-Path $backendRoot '.env.example') -Destination $environmentFile
    Write-Output 'Created ignored backend/.env from the template. Configure its account values locally.'
}

$existingContent = [IO.File]::ReadAllText($environmentFile)
$missingLines = @()
foreach ($name in @('COMPOSIO_API_KEY', 'COMPOSIO_GMAIL_AUTH_CONFIG_ID')) {
    if ($existingContent -notmatch ('(?m)^\s*(?:export\s+)?' + [regex]::Escape($name) + '\s*=')) {
        $missingLines += "$name="
    }
}
if ($missingLines.Count -gt 0) {
    $separator = if ($existingContent.Length -gt 0 -and -not $existingContent.EndsWith("`n")) { "`n" } else { '' }
    [IO.File]::AppendAllText($environmentFile, $separator + ($missingLines -join "`n") + "`n")
    Write-Output 'Added missing Composio configuration placeholders; existing values were preserved.'
}

Push-Location $backendRoot
try {
    & uv sync --locked
    if ($LASTEXITCODE -ne 0) { throw 'Backend dependency installation failed. See the error above.' }
    $readinessCheck = @'
import os
from agentic_subscription_manager.security import Settings

def supplied(name):
    value = os.getenv(name, '').strip()
    return bool(value) and not value.startswith('***') and not any(
        marker in value.lower() for marker in ('your_', 'placeholder', 'replace_me')
    )

settings = Settings.from_env()
account = settings.identity_configured and all(supplied(name) for name in (
    'SUPABASE_URL', 'SUPABASE_PUBLISHABLE_KEY'
))
chat = account and settings.configured and supplied('OPENROUTER_API_KEY') and supplied('AI_CONVERSATION_SECRET')
gmail = account and supplied('COMPOSIO_API_KEY')
for label, ready in (('Account API', account), ('AI chat', chat), ('Gmail discovery', gmail)):
    print(label + ': ' + ('configured' if ready else 'waiting for local configuration'))
print('Configuration presence checked; provider access still requires a live verification.')
'@
    & uv run --locked --env-file .env python -c $readinessCheck
    if ($LASTEXITCODE -ne 0) { throw 'Backend configuration validation failed. Check backend/.env locally.' }
} finally {
    Pop-Location
}
Write-Output 'Backend setup is complete. Restart the backend after changing backend/.env so it loads the new values.'
Write-Output 'For Android: start one emulator and run pwsh -File scripts/Start-LocalAi.ps1 from the repository root.'
