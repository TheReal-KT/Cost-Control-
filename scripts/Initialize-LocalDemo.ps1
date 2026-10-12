#!/usr/bin/env pwsh
# Generate local TLS material without installing a machine-wide certificate authority.
#Requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$demoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$demoDirectory = Join-Path $demoRoot '.local-demo'
$certificatePath = Join-Path $demoDirectory 'server.cert.pem'
$privateKeyPath = Join-Path $demoDirectory 'server.key.pem'
$propertiesPath = Join-Path $demoRoot 'local.properties'
if (-not (Test-Path -LiteralPath (Join-Path $demoRoot 'backend/.env'))) {
    throw 'Configure ignored backend/.env first. Do not put model keys in Android.'
}
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    throw 'Configure Android local.properties first.'
}
New-Item -ItemType Directory -Path $demoDirectory -Force | Out-Null
$renewCertificate = -not (Test-Path -LiteralPath $certificatePath) -or -not (Test-Path -LiteralPath $privateKeyPath)
if (-not $renewCertificate) {
    $existingCertificate = [Security.Cryptography.X509Certificates.X509Certificate2]::CreateFromPemFile(
        $certificatePath, $privateKeyPath)
    try { $renewCertificate = $existingCertificate.NotAfter.ToUniversalTime() -le [DateTime]::UtcNow.AddDays(1) }
    finally { $existingCertificate.Dispose() }
}
if ($renewCertificate) {
    $demoRsa = [Security.Cryptography.RSA]::Create(2048)
    try {
        $demoRequest = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
            'CN=Cost Control local development', $demoRsa,
            [Security.Cryptography.HashAlgorithmName]::SHA256,
            [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $demoSan = [Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
        $demoSan.AddIpAddress([Net.IPAddress]::Parse('127.0.0.1'))
        $demoSan.AddDnsName('localhost')
        $demoRequest.CertificateExtensions.Add($demoSan.Build())
        $demoRequest.CertificateExtensions.Add(
            [Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($false, $false, 0, $true))
        $demoCertificate = $demoRequest.CreateSelfSigned(
            [DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddDays(30))
        try {
            [IO.File]::WriteAllText($certificatePath, $demoCertificate.ExportCertificatePem())
            [IO.File]::WriteAllText($privateKeyPath, $demoRsa.ExportPkcs8PrivateKeyPem())
        } finally { $demoCertificate.Dispose() }
    } finally { $demoRsa.Dispose() }
}
$demoLines = @(Get-Content -LiteralPath $propertiesPath | Where-Object {
    $_ -notmatch '^(AI_ENDPOINT_URL|LOCAL_AI_CERT_FILE)='
})
$demoLines += 'AI_ENDPOINT_URL=https://127.0.0.1:8443/ai/chat'
$demoLines += 'LOCAL_AI_CERT_FILE=' + $certificatePath.Replace('\', '/')
[IO.File]::WriteAllLines($propertiesPath, $demoLines)
$demoAdb = Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $demoAdb)) { throw 'Android SDK adb not found at the default location.' }
& $demoAdb reverse tcp:8443 tcp:8443
if ($LASTEXITCODE -ne 0) { throw 'Start one emulator or connect one device, then rerun initialization.' }
Write-Output 'Local HTTPS configured. Rebuild/install debug, then start the backend using the README command.'
