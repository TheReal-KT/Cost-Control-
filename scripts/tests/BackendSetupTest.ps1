#!/usr/bin/env pwsh
#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fixtureRoot = Join-Path $repositoryRoot ('.local-demo/backend-setup-checks-' + [Guid]::NewGuid())
$fixtureScripts = Join-Path $fixtureRoot 'scripts'
$fixtureBackend = Join-Path $fixtureRoot 'backend'
New-Item -ItemType Directory -Path $fixtureScripts, $fixtureBackend -Force > $null
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'scripts/Initialize-Backend.ps1') -Destination $fixtureScripts
Copy-Item -LiteralPath (Join-Path $repositoryRoot 'backend/.env.example') -Destination $fixtureBackend
$environmentFile = Join-Path $fixtureBackend '.env'
$trace = [Collections.Generic.List[string]]::new()
$installFails = $false
$ignored = $true
function uv {
    if ($args[0] -eq 'sync') {
        $trace.Add('sync')
        $global:LASTEXITCODE = if ($installFails) { 1 } else { 0 }
    } else {
        $trace.Add('readiness')
        $global:LASTEXITCODE = 0
        Write-Output 'Synthetic configuration status'
    }
}
function git { $global:LASTEXITCODE = if ($ignored) { 0 } else { 1 } }
function Initialize-Fixture { & (Join-Path $fixtureScripts 'Initialize-Backend.ps1') }

$output = Initialize-Fixture
if (-not (Test-Path -LiteralPath $environmentFile)) { throw 'Setup did not create the environment file' }
if (($trace -join ',') -ne 'sync,readiness') { throw 'Setup did not install before checking configuration' }
Write-Output 'PASS: missing environment created and dependencies installed before readiness'

$original = "SUPABASE_URL=https://project.example.invalid`nexport COMPOSIO_API_KEY='synthetic-private-key'"
[IO.File]::WriteAllText($environmentFile, $original)
$trace.Clear()
$output = Initialize-Fixture
$updated = [IO.File]::ReadAllText($environmentFile)
if (-not $updated.StartsWith($original + "`n")) { throw 'Existing configuration was changed' }
if (([regex]::Matches($updated, 'COMPOSIO_API_KEY')).Count -ne 1) { throw 'Existing exported key was overwritten' }
if ($updated -notmatch '(?m)^COMPOSIO_GMAIL_AUTH_CONFIG_ID=$') { throw 'Missing optional placeholder not appended' }
if (($output -join "`n").Contains('synthetic-private-key')) { throw 'Setup exposed a credential' }
Write-Output 'PASS: existing values preserved and missing placeholder appended without displaying secrets'

$output = Initialize-Fixture
if ([IO.File]::ReadAllText($environmentFile) -cne $updated) { throw 'Repeated setup changed existing values' }
Write-Output 'PASS: repeated setup is idempotent'

$installFails = $true
$trace.Clear()
$failed = $false
try { Initialize-Fixture > $null } catch {
    if ($_.Exception.Message -notlike 'Backend dependency installation failed*') { throw }
    $failed = $true
}
if (-not $failed -or ($trace -join ',') -ne 'sync') { throw 'Failed installation did not stop readiness' }
Write-Output 'PASS: installation failure stops setup'

$installFails = $false
$ignored = $false
$trace.Clear()
$failed = $false
try { Initialize-Fixture > $null } catch {
    if ($_.Exception.Message -notlike 'backend/.env must be ignored*') { throw }
    $failed = $true
}
if (-not $failed -or $trace.Count -ne 0 -or [IO.File]::ReadAllText($environmentFile) -cne $updated) {
    throw 'Unignored environment was modified or used'
}
Write-Output 'PASS: setup refuses an environment file that Git would track'
