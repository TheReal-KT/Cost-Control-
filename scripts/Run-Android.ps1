#!/usr/bin/env pwsh
#Requires -Version 7.0
[CmdletBinding()]
param([switch]$InstallOnly)
$ErrorActionPreference = 'Stop'

$adbCommand = Get-Command adb -ErrorAction SilentlyContinue
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adbPath = if ($adbCommand) { $adbCommand.Source }
    else { Join-Path $sdkRoot 'platform-tools/adb.exe' }
if (-not (Test-Path -LiteralPath $adbPath)) {
    throw 'ADB not found. Set ANDROID_HOME to your Android SDK directory or add adb to PATH.'
}

$deviceOutput = & $adbPath devices
if ($LASTEXITCODE -ne 0) { throw 'Could not list Android devices.' }
$connectedDevices = @($deviceOutput | Where-Object { $_ -match '^\S+\s+device\s*$' })
if ($connectedDevices.Count -ne 1) {
    throw 'Start one emulator or connect one authorized device, then rerun this script.'
}
$serial = ($connectedDevices[0] -split '\s+')[0]

$previousJavaHome = $env:JAVA_HOME
if (-not $env:JAVA_HOME) {
    $studioJava = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
    if (Test-Path -LiteralPath (Join-Path $studioJava 'bin/java.exe')) {
        $env:JAVA_HOME = $studioJava
    }
}

Push-Location (Join-Path $PSScriptRoot '..')
try {
    # Gradle installs its current output, including a configured BUILD_OUTPUT_DIR.
    & ./gradlew.bat :app:installDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android build/install failed. See the Gradle error above.' }

    if (-not $InstallOnly) {
        $launchOutput = & $adbPath -s $serial shell am start -W `
            -n com.example.agentcostcontrol/.MainActivity 2>&1
        if ($LASTEXITCODE -ne 0 -or ($launchOutput -match '^Error:')) {
            throw "Could not launch Cost Control: $($launchOutput -join [Environment]::NewLine)"
        }
        $launchOutput | Write-Output
    }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
