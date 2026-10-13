#!/usr/bin/env pwsh
#Requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    throw 'Install uv before starting the local AI server. See backend/README.md.'
}

# Forwarding must be restored whenever the emulator or ADB restarts.
& (Join-Path $PSScriptRoot 'Initialize-LocalDemo.ps1')
# Install every time so renewal and retries after a failed install use the current certificate.
Write-Output 'Building/installing debug so the app trusts the current local HTTPS certificate.'
& (Join-Path $PSScriptRoot 'Run-Android.ps1') -InstallOnly
Push-Location (Join-Path $PSScriptRoot '../backend')
try {
    Write-Output 'Starting local AI. Keep this terminal open while using Chat.'
    & uv run --locked --env-file .env uvicorn agentic_subscription_manager.api:create_app `
        --factory --host 127.0.0.1 --port 8443 `
        --ssl-keyfile ../.local-demo/server.key.pem --ssl-certfile ../.local-demo/server.cert.pem `
        --no-access-log
    if ($LASTEXITCODE -ne 0) { throw 'Local AI could not start. Check the server error above.' }
} finally {
    Pop-Location
}
