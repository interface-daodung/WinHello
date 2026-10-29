param([int]$EnrollmentTimeoutSeconds = 180)

$ErrorActionPreference = 'Stop'
$serviceName = 'WinHelloLanAgent'
$dataDir = [IO.Path]::GetFullPath((Join-Path $env:ProgramData 'WinHelloLanDemo'))
$configPath = Join-Path $dataDir 'agent.json'
$pairingPath = Join-Path $dataDir 'pairing.json'
$qrPath = Join-Path $dataDir 'enrollment.png'
$qrPayloadPath = Join-Path $dataDir 'enrollment-uri.txt'
$agentExe = Join-Path $env:ProgramFiles 'WinHelloLanDemo\WinHello.LanAgent.exe'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run from an elevated PowerShell window.'
}
if ([IO.Path]::GetFullPath($dataDir) -ne (Join-Path $env:ProgramData 'WinHelloLanDemo') -or
    -not (Test-Path -LiteralPath $configPath) -or -not (Test-Path -LiteralPath $agentExe)) {
    throw 'WinHello is not installed in the expected locations.'
}
if ($EnrollmentTimeoutSeconds -lt 30 -or $EnrollmentTimeoutSeconds -gt 900) {
    throw 'EnrollmentTimeoutSeconds must be between 30 and 900.'
}

$service = Get-Service -Name $serviceName -ErrorAction Stop
if ($service.Status -ne 'Running') { throw 'Start the WinHello LAN Agent service before resetting phone enrollment.' }
$oldConfigText = Get-Content -LiteralPath $configPath -Raw
$oldConfig = $oldConfigText | ConvertFrom-Json
$oldPairing = if (Test-Path -LiteralPath $pairingPath) { Get-Content -LiteralPath $pairingPath -Raw } else { $null }
$serviceStopped = $false
$newConfig = $null

function New-Base32Secret {
    $bytes = New-Object byte[] 20
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $bits = -join ($bytes | ForEach-Object { [Convert]::ToString($_, 2).PadLeft(8, '0') })
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567'
    $result = [Text.StringBuilder]::new()
    for ($offset = 0; $offset -lt $bits.Length; $offset += 5) {
        $result.Append($alphabet[[Convert]::ToInt32($bits.Substring($offset, 5), 2)]) | Out-Null
    }
    return $result.ToString()
}

try {
    Stop-Service -Name $serviceName -Force
    (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    $serviceStopped = $true

    $newConfig = [ordered]@{
        DeviceId = $oldConfig.DeviceId
        TotpSecret = New-Base32Secret
        CertificateThumbprint = $oldConfig.CertificateThumbprint
    }
    $newConfig | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    Remove-Item -LiteralPath $pairingPath -Force -ErrorAction SilentlyContinue

    $certificate = Get-ChildItem Cert:\LocalMachine\My |
        Where-Object { $_.Thumbprint -eq $oldConfig.CertificateThumbprint } | Select-Object -First 1
    if (-not $certificate) { throw 'The enrolled TLS certificate is missing.' }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $pin = ([BitConverter]::ToString($sha.ComputeHash($certificate.RawData))).Replace('-', '') }
    finally { $sha.Dispose() }
    $enrollmentUri = "winhello://enroll?pcid=$($oldConfig.DeviceId)&secret=$($newConfig.TotpSecret)&pin=$pin&https=54431"
    Set-Content -LiteralPath $qrPayloadPath -Value $enrollmentUri -NoNewline -Encoding ASCII
    & $agentExe --make-enrollment-qr-file $qrPayloadPath $qrPath
    Remove-Item -LiteralPath $qrPayloadPath -Force -ErrorAction SilentlyContinue
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $qrPath)) { throw 'Could not generate the new setup QR.' }

    Start-Service -Name $serviceName
    (Get-Service -Name $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
    Start-Process -FilePath $qrPath
    Write-Host 'Scan the new QR in the Android app. The previous phone secret is now invalid.'
    $deadline = (Get-Date).AddSeconds($EnrollmentTimeoutSeconds)
    $enrolled = $false
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $pairingPath) {
            try {
                $pairing = Get-Content -LiteralPath $pairingPath -Raw | ConvertFrom-Json
                if ($pairing.PhoneDeviceId -and $pairing.PublicKey) { $enrolled = $true; break }
            } catch { }
        }
        Start-Sleep -Seconds 2
    }
    if (-not $enrolled) { throw 'Phone did not confirm the new TOTP enrollment before the timeout.' }
    Remove-Item -LiteralPath $qrPath -Force -ErrorAction SilentlyContinue
    Write-Host 'Phone enrollment rotated and confirmed.'
}
catch {
    $originalError = $_
    if ($serviceStopped -or $newConfig) {
        try {
            Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
            (Get-Service -Name $serviceName).WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
            Set-Content -LiteralPath $configPath -Value $oldConfigText -Encoding UTF8
            if ($oldPairing) { Set-Content -LiteralPath $pairingPath -Value $oldPairing -Encoding UTF8 }
            else { Remove-Item -LiteralPath $pairingPath -Force -ErrorAction SilentlyContinue }
            Start-Service -Name $serviceName
        } catch { [Console]::Error.WriteLine("Rollback could not fully restore the previous enrollment: $($_.Exception.Message)") }
    }
    Remove-Item -LiteralPath $qrPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $qrPayloadPath -Force -ErrorAction SilentlyContinue
    throw $originalError
}
