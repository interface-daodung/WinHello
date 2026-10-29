$ErrorActionPreference = 'Stop'
$serviceName = 'WinHelloLanAgent'
$clsid = '{82e53a3d-6c08-42b6-9d47-8d86b281a510}'
$firewallNames = @('WinHello LAN Demo Discovery UDP', 'WinHello LAN Demo HTTPS')
$installDir = [IO.Path]::GetFullPath((Join-Path $env:ProgramFiles 'WinHelloLanDemo'))
$dataDir = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'WinHelloLanDemo'))
$configPath = Join-Path $dataDir 'agent.json'
$markerPath = Join-Path $dataDir 'install.json'
$classKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Classes\CLSID\$clsid"
$providerKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$clsid"

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this uninstaller from an elevated PowerShell window.'
}
if ([IO.Path]::GetFullPath($installDir) -ne (Join-Path $env:ProgramFiles 'WinHelloLanDemo') -or
    [IO.Path]::GetFullPath($dataDir) -ne (Join-Path $env:ProgramData 'WinHelloLanDemo')) {
    throw 'Refusing to remove data outside the fixed WinHello installation directories.'
}

$thumbprint = $null
if (Test-Path -LiteralPath $configPath) {
    try { $thumbprint = (Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json).CertificateThumbprint } catch { }
}
if (Test-Path -LiteralPath $markerPath) {
    try {
        $marker = Get-Content -LiteralPath $markerPath -Raw | ConvertFrom-Json
        if ($marker.InstallDirectory -and [IO.Path]::GetFullPath($marker.InstallDirectory) -ne $installDir) {
            throw 'Install marker points outside the expected application directory.'
        }
        if ($marker.DataDirectory -and [IO.Path]::GetFullPath($marker.DataDirectory) -ne $dataDir) {
            throw 'Install marker points outside the expected data directory.'
        }
        if (-not $thumbprint) { $thumbprint = $marker.CertificateThumbprint }
    } catch { throw "Cannot validate the install marker; refusing data cleanup: $($_.Exception.Message)" }
}

$service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
if ($service) {
    if ($service.Status -ne 'Stopped') {
        Stop-Service -Name $serviceName -Force
        (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }
    & sc.exe delete $serviceName | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Windows could not delete service $serviceName; app files were left in place." }
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        if (-not (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Seconds 1
    }
    if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
        throw "Service $serviceName is marked for deletion but still present; app files were left in place. Restart Windows and rerun uninstall."
    }
}

if (Test-Path -LiteralPath $providerKey) { Remove-Item -LiteralPath $providerKey -Recurse -Force }
if (Test-Path -LiteralPath $classKey) { Remove-Item -LiteralPath $classKey -Recurse -Force }
$demoRules = Get-NetFirewallRule -DisplayName $firewallNames[0], $firewallNames[1] -ErrorAction SilentlyContinue
if ($demoRules) { $demoRules | Remove-NetFirewallRule -ErrorAction Stop }

if ($thumbprint) {
    Get-ChildItem Cert:\LocalMachine\My -ErrorAction SilentlyContinue |
        Where-Object { $_.Thumbprint -eq $thumbprint } |
        Remove-Item -Force -ErrorAction Stop
}

if (Test-Path -LiteralPath $installDir) { Remove-Item -LiteralPath $installDir -Recurse -Force }
if (Test-Path -LiteralPath $dataDir) { Remove-Item -LiteralPath $dataDir -Recurse -Force }
Write-Host 'WinHello LAN demo removed: service, provider registration, firewall rules, TLS certificate, TOTP secret, pairing record, and installed files.'
