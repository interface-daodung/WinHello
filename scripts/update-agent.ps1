$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell window.'
}

$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'apps\lan-agent\bin\Release\net10.0\win-x64\publish\WinHello.LanAgent.dll'
$installed = Join-Path $env:ProgramFiles 'WinHelloLanDemo\WinHello.LanAgent.dll'
if (-not (Test-Path -LiteralPath $source)) { throw "Published agent not found: $source" }
if (-not (Test-Path -LiteralPath $installed)) { throw "Installed agent not found: $installed" }

$backup = "$installed.backup-$(Get-Date -Format yyyyMMdd-HHmmss)"
Copy-Item -LiteralPath $installed -Destination $backup -Force
$service = Get-Service WinHelloLanAgent -ErrorAction Stop
try {
    Stop-Service WinHelloLanAgent -Force
    $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(20))
    Copy-Item -LiteralPath $source -Destination $installed -Force
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne
        (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash) {
        throw 'Installed agent hash differs from the publish output.'
    }
    Start-Service WinHelloLanAgent
    $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(20))
    Write-Host "Updated LAN Agent. Previous DLL saved at: $backup"
} catch {
    Stop-Service WinHelloLanAgent -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $backup -Destination $installed -Force
    Start-Service WinHelloLanAgent -ErrorAction SilentlyContinue
    throw
}
