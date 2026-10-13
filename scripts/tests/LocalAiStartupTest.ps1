#!/usr/bin/env pwsh
#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$fixtureRoot = Join-Path $repositoryRoot ('.local-demo/certificate-checks-' + [Guid]::NewGuid())
$fixtureScripts = Join-Path $fixtureRoot 'scripts'
New-Item -ItemType Directory -Path $fixtureScripts, (Join-Path $fixtureRoot 'backend') -Force > $null
Copy-Item (Join-Path $repositoryRoot 'scripts/Start-LocalAi.ps1') $fixtureScripts
@'
$root = Split-Path $PSScriptRoot
Add-Content (Join-Path $root 'trace.txt') 'initialize'
if ($env:CHECK_INIT_FAIL -eq '1') { throw 'Synthetic initialization failure' }
if ($env:CHECK_RENEW -eq '1' -or -not (Test-Path (Join-Path $root 'certificate.txt'))) {
    Set-Content (Join-Path $root 'certificate.txt') ('renewed-certificate-' + [Guid]::NewGuid())
}
'@ | Set-Content (Join-Path $fixtureScripts 'Initialize-LocalDemo.ps1')
@'
param([switch]$InstallOnly)
if (-not $InstallOnly) { throw 'Startup must install without launching the app' }
$root = Split-Path $PSScriptRoot
Add-Content (Join-Path $root 'trace.txt') 'install'
if ($env:CHECK_INSTALL_FAIL -eq '1') { throw 'Synthetic installation failure' }
Copy-Item (Join-Path $root 'certificate.txt') (Join-Path $root 'installed-certificate.txt') -Force
'@ | Set-Content (Join-Path $fixtureScripts 'Run-Android.ps1')
function uv {
    Add-Content (Join-Path $fixtureRoot 'trace.txt') 'server'
    if ((Get-Content (Join-Path $fixtureRoot 'certificate.txt')) -ne
            (Get-Content (Join-Path $fixtureRoot 'installed-certificate.txt'))) {
        throw 'Server certificate does not match installed app'
    }
    $global:LASTEXITCODE = 0
}
$cases = @(
    @{Name='unchanged certificate'; Renew='0'; InitFail='0'; InstallFail='0'; Existing=$true; Expected='initialize,install,server'},
    @{Name='renewed certificate'; Renew='1'; InitFail='0'; InstallFail='0'; Existing=$true; Expected='initialize,install,server'},
    @{Name='missing certificate'; Renew='0'; InitFail='0'; InstallFail='0'; Existing=$false; Expected='initialize,install,server'},
    @{Name='installation fails after renewal'; Renew='1'; InitFail='0'; InstallFail='1'; Existing=$true; Expected='initialize,install'},
    @{Name='retry after failed installation'; Renew='0'; InitFail='0'; InstallFail='0'; Existing=$null; Expected='initialize,install,server'},
    @{Name='initialization fails'; Renew='0'; InitFail='1'; InstallFail='0'; Existing=$true; Expected='initialize'}
)
foreach ($case in $cases) {
    Set-Content (Join-Path $fixtureRoot 'trace.txt') ''
    if ($null -ne $case.Existing) {
        if ($case.Existing) { Set-Content (Join-Path $fixtureRoot 'certificate.txt') 'old-certificate' }
        else { Remove-Item -LiteralPath (Join-Path $fixtureRoot 'certificate.txt') -ErrorAction SilentlyContinue }
    }
    $env:CHECK_RENEW=$case.Renew
    $env:CHECK_INIT_FAIL=$case.InitFail
    $env:CHECK_INSTALL_FAIL=$case.InstallFail
    $failed=$false
    try { & (Join-Path $fixtureScripts 'Start-LocalAi.ps1') > $null }
    catch {
        if ($_.Exception.Message -notlike 'Synthetic * failure') { throw }
        $failed=$true
    }
    $expectedFailure = $case.InitFail -eq '1' -or $case.InstallFail -eq '1'
    if ($failed -ne $expectedFailure) { throw "$($case.Name): incorrect failure result" }
    $trace = ((Get-Content (Join-Path $fixtureRoot 'trace.txt') | Where-Object { $_ }) -join ',')
    if ($trace -ne $case.Expected) { throw "$($case.Name): unexpected order $trace" }
    Write-Output "PASS: $($case.Name)"
}

# Exercise the real install-only/default runner against harmless command stubs.
Copy-Item (Join-Path $repositoryRoot 'scripts/Run-Android.ps1') $fixtureScripts -Force
$stubBin = Join-Path $fixtureRoot 'bin'
New-Item -ItemType Directory -Path $stubBin > $null
@'
@echo off
if "%1"=="devices" (
  echo List of devices attached
  echo emulator-check device
  exit /b 0
)
echo launch>>"%~dp0..\trace.txt"
echo Status: ok
exit /b 0
'@ | Set-Content (Join-Path $stubBin 'adb.cmd')
@'
@echo off
echo install>>"%~dp0trace.txt"
if "%CHECK_INSTALL_FAIL%"=="1" exit /b 1
exit /b 0
'@ | Set-Content (Join-Path $fixtureRoot 'gradlew.bat')
$previousPath = $env:PATH
$previousJava = $env:JAVA_HOME
$previousLocation = $PWD.Path
try {
    $env:PATH = $stubBin + [IO.Path]::PathSeparator + $previousPath
    foreach ($case in @(
        @{Name='install only'; InstallOnly=$true; InstallFail='0'; Expected='install'},
        @{Name='default launch'; InstallOnly=$false; InstallFail='0'; Expected='install,launch'},
        @{Name='build failure'; InstallOnly=$true; InstallFail='1'; Expected='install'}
    )) {
        Set-Content (Join-Path $fixtureRoot 'trace.txt') ''
        $env:CHECK_INSTALL_FAIL = $case.InstallFail
        $failed = $false
        try { & (Join-Path $fixtureScripts 'Run-Android.ps1') -InstallOnly:$case.InstallOnly > $null }
        catch {
            if ($_.Exception.Message -notlike 'Android build/install failed*') { throw }
            $failed=$true
        }
        if ($failed -ne ($case.InstallFail -eq '1')) { throw "$($case.Name): incorrect failure result" }
        $trace = ((Get-Content (Join-Path $fixtureRoot 'trace.txt') | Where-Object { $_ }) -join ',')
        if ($trace -ne $case.Expected) { throw "$($case.Name): unexpected order $trace" }
        if ($env:JAVA_HOME -ne $previousJava -or $PWD.Path -ne $previousLocation) { throw 'Runner did not restore environment' }
        Write-Output "PASS: $($case.Name)"
    }
} finally {
    $env:PATH = $previousPath
}
foreach ($path in @('scripts/Run-Android.ps1','scripts/Start-LocalAi.ps1')) {
    $syntaxErrors=$null
    [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $repositoryRoot $path), [ref]$null, [ref]$syntaxErrors) > $null
    if ($syntaxErrors.Count) { throw "$path has syntax errors" }
}
Write-Output 'PASS: PowerShell syntax for both scripts'

# Keep failed fixtures for diagnosis; remove only this run's successful fixture directory.
$fixtureParent = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '.local-demo'))
if ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($fixtureRoot)) -ne $fixtureParent) {
    throw 'Test fixture cleanup must stay inside .local-demo.'
}
Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
