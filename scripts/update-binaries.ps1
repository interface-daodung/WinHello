$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell Administrator window.'
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$installDir = Join-Path $env:ProgramFiles 'WinHelloLanDemo'
$dataDir = Join-Path $env:ProgramData 'WinHelloLanDemo'
$providerDll = Join-Path $repoRoot 'build\credential-provider-monorepo-llvm-mingw\WinHelloCredentialProvider.dll'
$agentProject = Join-Path $repoRoot 'apps\lan-agent\WinHello.LanAgent.csproj'
$publishDir = Join-Path $repoRoot 'apps\lan-agent\bin\Release\net10.0\win-x64\publish'

Write-Host 'Building latest Credential Provider DLL...'
& (Join-Path $PSScriptRoot 'build-credential-provider.ps1')
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $providerDll)) { throw 'Credential Provider build failed.' }

Write-Host 'Publishing latest LAN Agent...'
$dotnetCommand = Get-Command dotnet -ErrorAction SilentlyContinue
$dotnetExe = if ($dotnetCommand) { $dotnetCommand.Source } else { 'C:\Program Files\dotnet\dotnet.exe' }
& $dotnetExe publish $agentProject -c Release -r win-x64 --self-contained true -o $publishDir
if ($LASTEXITCODE -ne 0) { throw 'LAN Agent publish failed.' }

Write-Host 'Stopping WinHelloLanAgent service...'
Stop-Service WinHelloLanAgent -Force -ErrorAction SilentlyContinue
$service = Get-Service WinHelloLanAgent -ErrorAction SilentlyContinue
if ($service -and $service.Status -ne 'Stopped') {
    $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(15))
}
Stop-Process -Name LogonUI -Force -ErrorAction SilentlyContinue

Write-Host 'Copying updated files to Program Files...'
Copy-Item -Path (Join-Path $publishDir '*') -Destination $installDir -Recurse -Force

$destDll = Join-Path $installDir 'WinHelloCredentialProvider.dll'
try {
    Copy-Item -LiteralPath $providerDll -Destination $destDll -Force
} catch {
    $backupDll = Join-Path $installDir ("WinHelloCredentialProvider.dll.old." + [Guid]::NewGuid().ToString('N'))
    Move-Item -LiteralPath $destDll -Destination $backupDll -Force
    Copy-Item -LiteralPath $providerDll -Destination $destDll -Force
    Write-Host "Replaced locked DLL by moving old binary to $(Split-Path -Leaf $backupDll)" -ForegroundColor Yellow
}
Get-ChildItem -Path $installDir -Filter 'WinHelloCredentialProvider.dll.old.*' -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host 'Starting WinHelloLanAgent service...'
Start-Service WinHelloLanAgent
(Get-Service WinHelloLanAgent).WaitForStatus('Running', [TimeSpan]::FromSeconds(15))

foreach ($oldQrFile in @('enrollment.png', 'enrollment-uri.txt')) {
    Remove-Item -LiteralPath (Join-Path $dataDir $oldQrFile) -Force -ErrorAction SilentlyContinue
}

Write-Host 'Updated WinHello binaries successfully!' -ForegroundColor Green
