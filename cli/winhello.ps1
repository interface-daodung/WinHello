param([string]$Command = 'h', [string]$Argument)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$serviceName = 'WinHelloLanAgent'
$providerKey = 'Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{82e53a3d-6c08-42b6-9d47-8d86b281a510}'
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
$serverConfigPath = Join-Path $root 'config\update-server.json'
$defaultUpdateBaseUrl = if (Test-Path -LiteralPath $serverConfigPath) { (Get-Content -LiteralPath $serverConfigPath -Raw | ConvertFrom-Json).baseUrl } else { $null }

if ($Command -in @('h', 'help', '-h', '--help')) {
    Write-Host 'WinHello commands:'
    Write-Host '  winhello h           Show this help'
    Write-Host '  winhello regi        Register Windows sign-in and show TOTP QR in terminal'
    Write-Host '  winhello app         Show Android APK download QR and URL'
    Write-Host '  winhello pair        List the phone paired with this PC'
    Write-Host '  winhello rm          Reset all WinHello configuration on this PC'
    Write-Host '  winhello update      Download and install the latest Windows version'
    Write-Host '  winhello fast        Show fast unlock status'
    Write-Host '  winhello fast <1-30> Show activation QR (use "on" for 3 days)'
    Write-Host '  winhello fast off    Revoke fast unlock on this PC'
    Write-Host '  winhello uninstall   Remove Windows sign-in, service and enrollment data'
    Write-Host '  winhello v           Show version'
    exit 0
}
if ($Command -in @('v', 'version', '--version')) {
    Write-Host ('WinHello ' + (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim())
    exit 0
}
function Get-SemanticVersion([string]$v) {
    if ([string]::IsNullOrWhiteSpace($v)) { return [version]'0.0.0' }
    $clean = ($v.Trim() -replace '^[vV]', '') -replace '-.*', ''
    $parts = $clean.Split('.')
    $major = if ($parts.Length -gt 0 -and $parts[0] -match '^\d+$') { [int]$parts[0] } else { 0 }
    $minor = if ($parts.Length -gt 1 -and $parts[1] -match '^\d+$') { [int]$parts[1] } else { 0 }
    $patch = if ($parts.Length -gt 2 -and $parts[2] -match '^\d+$') { [int]$parts[2] } else { 0 }
    return [version]"$major.$minor.$patch"
}

if ($Command -eq 'app') {
    $baseUrl = if ($env:WINHELLO_UPDATE_BASE_URL) { $env:WINHELLO_UPDATE_BASE_URL } else { $defaultUpdateBaseUrl }
    if (-not $baseUrl) { throw 'Set WINHELLO_UPDATE_BASE_URL to the HTTPS update server URL.' }

    $bestVersion = '0.0.0'
    $bestApk = $null

    # 1. Tu dong kiem tra va dong bo ban build moi nhat tu repo sang publish neu co
    $candidateRoots = @($root, 'C:\Users\MiniPC\Project\WinHello') | Select-Object -Unique
    foreach ($r in $candidateRoots) {
        if (-not (Test-Path -LiteralPath $r)) { continue }
        $vFile = Join-Path $r 'VERSION'
        $localVer = if (Test-Path -LiteralPath $vFile) { (Get-Content -LiteralPath $vFile -Raw).Trim() } else { $null }
        if ($localVer -and (Get-SemanticVersion $localVer) -gt (Get-SemanticVersion $bestVersion)) {
            $bestVersion = $localVer
            $bestApk = "WinHello-ver$bestVersion-android.apk"
        }

        $builtApk = Join-Path $r 'apps\android\app\build\outputs\apk\release\app-release.apk'
        $pubDir = Join-Path $r 'publish'
        $pubLatest = Join-Path $pubDir 'latest.json'
        if ((Test-Path -LiteralPath $builtApk) -and (Test-Path -LiteralPath $pubDir) -and $bestVersion -ne '0.0.0') {
            $targetApk = Join-Path $pubDir "WinHello-ver$bestVersion-android.apk"
            if (-not (Test-Path -LiteralPath $targetApk) -or ((Get-Item $builtApk).LastWriteTime -gt (Get-Item $targetApk).LastWriteTime)) {
                Copy-Item -LiteralPath $builtApk -Destination $targetApk -Force
                try {
                    $apkHash = (Get-FileHash -LiteralPath $targetApk -Algorithm SHA256).Hash.ToLowerInvariant()
                    $existingWin = if (Test-Path -LiteralPath $pubLatest) { (Get-Content -LiteralPath $pubLatest -Raw | ConvertFrom-Json).windows } else { $null }
                    $newManifest = [ordered]@{
                        version = $bestVersion
                        apk = "WinHello-ver$bestVersion-android.apk"
                        sha256 = $apkHash
                        windows = if ($existingWin) { $existingWin } else { @{ file = ""; sha256 = "" } }
                    }
                    [System.IO.File]::WriteAllText($pubLatest, ($newManifest | ConvertTo-Json -Depth 4), [System.Text.UTF8Encoding]::new($false))
                } catch { }
            }
        }

        if (Test-Path -LiteralPath $pubLatest) {
            try {
                $pj = Get-Content -LiteralPath $pubLatest -Raw | ConvertFrom-Json
                if ($pj.version -and (Get-SemanticVersion $pj.version) -gt (Get-SemanticVersion $bestVersion)) {
                    $bestVersion = [string]$pj.version
                    $bestApk = [string]$pj.apk
                }
            } catch { }
        }
    }

    # 2. Thu lay tu update server voi cache buster de tranh bi proxy/cloudflare cache ban cu
    try {
        $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $manifestUrl = "$($baseUrl.TrimEnd('/'))/latest.json?_t=$timestamp"
        $headers = @{ 'Cache-Control' = 'no-cache, no-store, must-revalidate'; 'Pragma' = 'no-cache' }
        $serverLatest = Invoke-RestMethod -Uri $manifestUrl -Headers $headers -TimeoutSec 4 -ErrorAction Stop
        if ($serverLatest.version -and (Get-SemanticVersion $serverLatest.version) -gt (Get-SemanticVersion $bestVersion)) {
            $bestVersion = [string]$serverLatest.version
            $bestApk = [string]$serverLatest.apk
        }
    } catch { }

    if (-not $bestApk -or $bestVersion -eq '0.0.0') {
        try {
            $latest = Invoke-RestMethod -Uri ($baseUrl.TrimEnd('/') + '/latest.json')
            $bestVersion = $latest.version
            $bestApk = $latest.apk
        } catch {
            throw "Khong the xac dinh phien ban APK moi nhat: $_"
        }
    }

    $downloadUrl = [uri]::new([uri]($baseUrl.TrimEnd('/') + '/'), [string]$bestApk).AbsoluteUri
    $agent = Join-Path $root 'bin\agent\WinHello.LanAgent.exe'
    if (-not (Test-Path -LiteralPath $agent)) {
        $agent = Join-Path $root 'apps\lan-agent\bin\Release\net10.0\win-x64\publish\WinHello.LanAgent.exe'
    }
    if (-not (Test-Path -LiteralPath $agent)) {
        $agent = 'C:\Program Files\WinHelloLanDemo\WinHello.LanAgent.exe'
    }
    if (-not (Test-Path -LiteralPath $agent)) {
        throw 'QR renderer is missing. Build the Windows package with scripts\build-windows.ps1 first.'
    }
    Write-Host "WinHello Android v$bestVersion"
    Write-Host 'Scan this QR with your phone to download the APK:'
    $downloadUrl | & $agent --print-download-qr
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    Write-Host $downloadUrl
    exit 0
}
if ($Command -eq 'update') {
    $baseUrl = if ($env:WINHELLO_UPDATE_BASE_URL) { $env:WINHELLO_UPDATE_BASE_URL } else { $defaultUpdateBaseUrl }
    if (-not $baseUrl) { throw 'Set WINHELLO_UPDATE_BASE_URL to the HTTPS update server URL.' }
    $latest = Invoke-RestMethod -Uri ($baseUrl.TrimEnd('/') + '/latest.json')
    if ($latest.version -notmatch '^\d+\.\d+\.\d+$' -or -not $latest.windows -or
        $latest.windows.file -notmatch '^WinHello-ver\d+\.\d+\.\d+-windows-x64-setup\.exe$' -or
        $latest.windows.sha256 -notmatch '^[0-9a-fA-F]{64}$') {
        throw 'The update manifest is invalid or does not include a Windows installer.'
    }
    $current = (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim()
    if ([version]$latest.version -le [version]$current) { Write-Host "WinHello $current is current (server: $($latest.version))."; exit 0 }
    $installerUrl = [uri]::new([uri]($baseUrl.TrimEnd('/') + '/'), [string]$latest.windows.file).AbsoluteUri
    $tempInstaller = Join-Path $env:TEMP ("WinHello-update-" + [guid]::NewGuid().ToString('N') + '.exe')
    Invoke-WebRequest -Uri $installerUrl -OutFile $tempInstaller
    $actualHash = (Get-FileHash -LiteralPath $tempInstaller -Algorithm SHA256).Hash
    if ($actualHash -ne $latest.windows.sha256.ToUpperInvariant()) { Remove-Item -LiteralPath $tempInstaller -Force; throw 'Installer SHA-256 mismatch. Update canceled.' }
    Write-Host "Downloaded WinHello $($latest.version). Windows will ask you to confirm the installer."
    Start-Process -FilePath $tempInstaller -Verb RunAs
    exit 0
}
if ($Command -eq 'rm') {
    if ($Argument) { throw 'Use only: winhello rm.' }
    if (-not $isAdmin) {
        Write-Host 'Administrator access is required to reset WinHello. Opening an elevated terminal...'
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', ('"' + $PSCommandPath + '"'), 'rm') | Out-Null
        exit 0
    }
    Write-Host 'This removes the pairing, TOTP secret, TLS certificate, saved Windows credential, service and sign-in provider from this PC.' -ForegroundColor Yellow
    Write-Host 'The WinHello CLI stays installed. Run winhello regi afterward to set it up again and enter the Windows password.'
    if ((Read-Host 'Type RESET to erase this PC configuration') -cne 'RESET') { Write-Host 'Canceled.'; exit 1 }
    & (Join-Path $root 'scripts\uninstall-winhello.ps1')
    if (-not $?) { exit 1 }
    Write-Host 'WinHello configuration reset. Run winhello regi to enroll again.' -ForegroundColor Green
    exit 0
}
if ($Command -eq 'pair') {
    $service = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
    if (-not $service) { throw 'WinHello service is not installed on this PC.' }
    if (-not $isAdmin) {
        Write-Host 'Administrator access is required to inspect the pairing. Opening an elevated terminal...'
        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', ('"' + $PSCommandPath + '"'), 'pair')
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $arguments | Out-Null
        exit 0
    }
    $pipe = [System.IO.Pipes.NamedPipeClientStream]::new('.', 'WinHello.LanAgent', [System.IO.Pipes.PipeDirection]::InOut)
    try {
        $pipe.Connect(3000)
        $writer = [System.IO.StreamWriter]::new($pipe, [System.Text.UTF8Encoding]::new($false))
        $writer.AutoFlush = $true
        $reader = [System.IO.StreamReader]::new($pipe, [System.Text.Encoding]::UTF8)
        $writer.WriteLine('PAIRING_STATUS')
        $response = $reader.ReadLine()
    } finally { $pipe.Dispose() }
    if ($response -eq 'UNPAIRED') { Write-Host 'No phone is paired with this PC.'; exit 0 }
    $parts = $response -split '\|', 4
    if ($parts.Length -ne 4 -or $parts[0] -ne 'PAIRED') { throw "Unexpected service response: $response" }
    $deviceName = if ($parts[2]) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($parts[2])) } else { '(name unavailable; use the device ID)' }
    Write-Host 'Paired phone on this PC:'
    Write-Host "  Name: $deviceName"
    Write-Host "  Device ID: $($parts[1])"
    Write-Host "  Paired at (UTC): $($parts[3])"
    exit 0
}
if ($Command -notin @('regi', 'uninstall', 'fast')) {
    Write-Error "Unknown command: $Command. Run winhello h."
    exit 2
}

$serviceExists = $null -ne (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)
$providerExists = Test-Path -LiteralPath $providerKey

if ($Command -eq 'fast') {
    if (-not $serviceExists) { throw 'WinHello is not installed. Run winhello regi first.' }
    if ($Argument -and $Argument -notin @('off', 'on') -and ($Argument -notmatch '^\d+$' -or [int]$Argument -lt 1 -or [int]$Argument -gt 30)) {
        throw 'Days must be an integer from 1 to 30. Use winhello fast on for 3 days.'
    }
    if (-not $isAdmin) {
        Write-Host 'Administrator access is required. Opening an elevated terminal...'
        $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-File', ('"' + $PSCommandPath + '"'), 'fast')
        if ($Argument) { $arguments += $Argument }
        Start-Process -FilePath 'powershell.exe' -Verb RunAs -ArgumentList $arguments | Out-Null
        exit 0
    }
    $pipe = [System.IO.Pipes.NamedPipeClientStream]::new('.', 'WinHello.LanAgent', [System.IO.Pipes.PipeDirection]::InOut)
    try {
        $pipe.Connect(3000)
        $writer = [System.IO.StreamWriter]::new($pipe, [System.Text.UTF8Encoding]::new($false))
        $writer.AutoFlush = $true
        $reader = [System.IO.StreamReader]::new($pipe, [System.Text.Encoding]::UTF8)
        $request = if (-not $Argument) { 'FAST_STATUS' } elseif ($Argument -eq 'off') { 'FAST_OFF' } else {
            $days = if ($Argument -eq 'on') { 3 } else { [int]$Argument }
            "FAST_CREATE $days"
        }
        $writer.WriteLine($request)
        $response = $reader.ReadLine()
    } finally { $pipe.Dispose() }
    if ($response -eq 'ERROR|not_paired') { throw 'This PC is not paired with a phone. Complete winhello regi and phone pairing first.' }
    if ($response -like 'ERROR|*') { throw "Fast unlock failed: $response" }
    if (-not $Argument) {
        if ($response -eq 'INACTIVE') { Write-Host 'Fast unlock is off.' } else {
            if ($response -like 'ACTIVE|*') {
                $activeUntil = ($response -split '\|')[2]
                Write-Host "Fast unlock active until $activeUntil UTC."
            } else { throw "Unexpected agent response: $response" }
        }
        exit 0
    }
    if ($Argument -eq 'off') {
        if ($response -ne 'OK') { throw "Unexpected agent response: $response" }
        Write-Host 'Fast unlock has been revoked on this PC.'
        exit 0
    }
    $parts = $response -split '\|'
    if ($parts.Length -ne 5 -or $parts[0] -ne 'TICKET') { throw "Unexpected agent response: $response" }
    $agent = Join-Path $root 'bin\agent\WinHello.LanAgent.exe'
    if (-not (Test-Path -LiteralPath $agent)) { $agent = Join-Path $root 'apps\lan-agent\bin\Debug\net10.0\WinHello.LanAgent.exe' }
    if (-not (Test-Path -LiteralPath $agent)) { throw 'QR renderer is missing. Build WinHello first.' }
    $pcName = [uri]::EscapeDataString($env:COMPUTERNAME)
    $fastUri = "winhello://fast-unlock?pcid=$($parts[1])&ticket=$($parts[2])&days=$($parts[3])&pin=$($parts[4])&name=$pcName"
    Write-Host "Scan this QR with your paired phone within 10 minutes to enable fast unlock for $($parts[3]) days:"
    $fastUri | & $agent --print-fast-qr
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    exit 0
}
if ($Command -eq 'regi' -and $serviceExists -and $providerExists) {
    Write-Host 'WinHello is already registered. Run winhello rm to erase the saved configuration, then winhello regi to enroll again.'
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
