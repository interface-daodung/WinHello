$ErrorActionPreference = 'Stop'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell window.'
}

$root = Split-Path -Parent $PSScriptRoot
$source = Join-Path $root 'build\credential-provider-monorepo-llvm-mingw\WinHelloCredentialProvider.dll'
$installed = Join-Path $env:ProgramFiles 'WinHelloLanDemo\WinHelloCredentialProvider.dll'
if (-not (Test-Path -LiteralPath $source)) { throw "Built provider not found: $source" }
if (-not (Test-Path -LiteralPath $installed)) { throw "Installed provider not found: $installed" }

$backup = "$installed.backup-$(Get-Date -Format yyyyMMdd-HHmmss)"
Copy-Item -LiteralPath $installed -Destination $backup -Force
try {
    Stop-Process -Name LogonUI -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $source -Destination $installed -Force
    $expected = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
    $actual = (Get-FileHash -LiteralPath $installed -Algorithm SHA256).Hash
    if ($actual -ne $expected) { throw 'Installed DLL hash differs from the build.' }
    Write-Host "Updated Credential Provider: $actual"
    Write-Host "Previous version saved at: $backup"
} catch {
    Copy-Item -LiteralPath $backup -Destination $installed -Force
    throw
}
