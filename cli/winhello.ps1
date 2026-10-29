param([string]$Command = 'h')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$serviceName = 'WinHelloLanAgent'
$providerKey = 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{82e53a3d-6c08-42b6-9d47-8d86b281a510}'
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($Command -in @('h', 'help', '-h', '--help')) {
    Write-Host 'WinHello commands:'
    Write-Host '  winhello h           Show this help'
    Write-Host '  winhello regi        Register Windows sign-in and show TOTP QR in terminal'
    Write-Host '  winhello app         Show Android APK download QR and URL'
    Write-Host '  winhello uninstall   Remove Windows sign-in, service and enrollment data'
    Write-Host '  winhello v           Show version'
    exit 0
}
if ($Command -in @('v', 'version', '--version')) {
    Write-Host ('WinHello ' + (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim())
    exit 0
}
if ($Command -eq 'app') {
    $version = (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim()
    if ($version -notmatch '^\d+\.\d+\.\d+$') { throw "Invalid WinHello version: $version" }
    $downloadUrl = "https://github.com/interface-daodung/WinHello/releases/download/v$version/WinHello-ver$version-android-debug.apk"
    $agent = Join-Path $root 'bin\agent\WinHello.LanAgent.exe'
    if (-not (Test-Path -LiteralPath $agent)) {
        $agent = Join-Path $root 'apps\lan-agent\bin\Release\net10.0\win-x64\publish\WinHello.LanAgent.exe'
    }
    if (-not (Test-Path -LiteralPath $agent)) {
        throw 'QR renderer is missing. Build the Windows package with scripts\build-windows.ps1 first.'
    }
    Write-Host "WinHello Android v$version"
    Write-Host 'Scan this QR with your phone to download the APK:'
    $downloadUrl | & $agent --print-download-qr
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host $downloadUrl
    Write-Host "The link works after the APK is uploaded to GitHub Release v$version."
    exit 0
}
if ($Command -notin @('regi', 'uninstall')) {
    Write-Error "Unknown command: $Command. Run winhello h."
    exit 2
}

$serviceExists = $null -ne (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)
$providerExists = Test-Path -LiteralPath $providerKey
if ($Command -eq 'regi' -and $serviceExists -and $providerExists) {
    Write-Host 'WinHello is already registered.'
    exit 0
}
if ($Command -eq 'regi' -and ($serviceExists -or $providerExists)) {
    Write-Error 'Partial WinHello installation detected. Inspect or uninstall it before registering again.'
    exit 1
}
if ($Command -eq 'uninstall' -and -not ($serviceExists -or $providerExists)) {
    Write-Host 'WinHello is not installed.'
    exit 0
}

if (-not $isAdmin) {
    Write-Host 'Administrator access is required. Opening an elevated terminal...'
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', ('"' + $PSCommandPath + '"'), $Command)
    Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $arguments | Out-Null
    exit 0
}

if ($Command -eq 'regi') {
    $options = @{ IUnderstandThisChangesLogonUI = $true }
    if (Test-Path -LiteralPath (Join-Path $root 'package.marker')) { $options.Prebuilt = $true }
    & (Join-Path $root 'scripts\install-winhello.ps1') @options
} else {
    & (Join-Path $root 'scripts\uninstall-winhello.ps1')
}
if (-not $?) { exit 1 }
