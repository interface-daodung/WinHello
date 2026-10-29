param(
    [switch]$IUnderstandThisChangesLogonUI,
    [int]$EnrollmentTimeoutSeconds = 180,
    [string]$WindowsUsername = $env:USERNAME,
    [string]$WindowsDomain = $env:USERDOMAIN,
    [string]$WindowsPassword = '',
    [switch]$Prebuilt
)

$ErrorActionPreference = 'Stop'
$serviceName = 'WinHelloLanAgent'
$clsid = '{82e53a3d-6c08-42b6-9d47-8d86b281a510}'
$providerName = 'WinHello LAN Unlock'
$firewallNames = @('WinHello LAN Demo Discovery UDP', 'WinHello LAN Demo HTTPS')
$installDir = Join-Path $env:ProgramFiles 'WinHelloLanDemo'
$dataDir = Join-Path $env:ProgramData 'WinHelloLanDemo'
$configPath = Join-Path $dataDir 'agent.json'
$pairingPath = Join-Path $dataDir 'pairing.json'
$markerPath = Join-Path $dataDir 'install.json'
$classKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Classes\CLSID\$clsid"
$providerKey = "Registry::HKEY_LOCAL_MACHINE\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\$clsid"
$thumbprint = $null
$serviceCreated = $false
$registered = $false
$firewallCreated = $false

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this installer from an elevated PowerShell window.'
    }
}

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

function Remove-PartialInstall {
    if ($serviceCreated) {
        Stop-Service -Name $serviceName -Force -ErrorAction SilentlyContinue
        $createdService = Get-Service -Name $serviceName -ErrorAction SilentlyContinue
        if ($createdService -and $createdService.Status -ne 'Stopped') {
            $createdService.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
        }
        & sc.exe delete $serviceName | Out-Null
        for ($attempt = 0; $attempt -lt 15; $attempt++) {
            if (-not (Get-Service -Name $serviceName -ErrorAction SilentlyContinue)) { break }
            Start-Sleep -Seconds 1
        }
        if (Get-Service -Name $serviceName -ErrorAction SilentlyContinue) {
            throw 'Rollback stopped but could not remove the Windows Service. Keep installed files and rerun the uninstaller after reboot.'
        }
    }
    if ($registered) {
        if (Test-Path -LiteralPath $providerKey) { Remove-Item -LiteralPath $providerKey -Recurse -Force }
        if (Test-Path -LiteralPath $classKey) { Remove-Item -LiteralPath $classKey -Recurse -Force }
    }
    if ($firewallCreated) {
        $rules = Get-NetFirewallRule -DisplayName $firewallNames[0], $firewallNames[1] -ErrorAction SilentlyContinue
        if ($rules) { $rules | Remove-NetFirewallRule -ErrorAction Stop }
    }
    if ($thumbprint) {
        Get-ChildItem Cert:\LocalMachine\My -ErrorAction SilentlyContinue |
            Where-Object Thumbprint -EQ $thumbprint |
            Remove-Item -ErrorAction Stop
    }
    if (Test-Path -LiteralPath $installDir) {
        Remove-Item -LiteralPath $installDir -Recurse -Force
    }
    if (Test-Path -LiteralPath $dataDir) {
        Remove-Item -LiteralPath $dataDir -Recurse -Force
    }
}

Assert-Administrator
if (-not $IUnderstandThisChangesLogonUI) {
    throw 'This adds a Windows logon tile. Re-run with -IUnderstandThisChangesLogonUI only on a disposable test VM after confirming a recovery sign-in method works.'
}
if ([Environment]::Is64BitProcess -eq $false) {
    throw 'Use 64-bit PowerShell so the x64 Credential Provider is registered in the correct registry view.'
}
if ((Get-Service -Name $serviceName -ErrorAction SilentlyContinue) -or (Test-Path -LiteralPath $installDir) -or (Test-Path -LiteralPath $dataDir)) {
    throw 'WinHello appears to be installed already. Run scripts\uninstall-winhello.ps1 first.'
}
if ((Test-Path -LiteralPath $classKey) -or (Test-Path -LiteralPath $providerKey) -or
    (Get-NetFirewallRule -DisplayName $firewallNames[0], $firewallNames[1] -ErrorAction SilentlyContinue)) {
    throw 'A WinHello registration or firewall rule already exists; inspect it or run the WinHello uninstaller before continuing.'
}
if ($EnrollmentTimeoutSeconds -lt 30 -or $EnrollmentTimeoutSeconds -gt 900) {
    throw 'EnrollmentTimeoutSeconds must be between 30 and 900.'
}

try {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    if ($Prebuilt) {
        $publishDir = Join-Path $repoRoot 'bin\agent'
        $providerDll = Join-Path $repoRoot 'bin\credential\WinHelloCredentialProvider.dll'
        if (-not (Test-Path -LiteralPath $providerDll)) { throw 'Packaged Credential Provider DLL is missing.' }
    } else {
        $agentProject = Join-Path $repoRoot 'apps\lan-agent\WinHello.LanAgent.csproj'
        $publishDir = Join-Path $repoRoot 'apps\lan-agent\bin\Release\net10.0\win-x64\publish'
        $dotnetCommand = Get-Command dotnet -ErrorAction SilentlyContinue
        $dotnetExe = if ($dotnetCommand) { $dotnetCommand.Source } else { 'C:\Program Files\dotnet\dotnet.exe' }
        if (-not (Test-Path -LiteralPath $dotnetExe)) { throw '.NET 10 SDK is required to install/build the LAN Agent.' }
        $providerDll = Join-Path $repoRoot 'build\credential-provider-monorepo-llvm-mingw\WinHelloCredentialProvider.dll'
        & (Join-Path $PSScriptRoot 'build-credential-provider.ps1')
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $providerDll)) { throw 'Credential Provider build failed.' }
        & $dotnetExe publish $agentProject -c Release -r win-x64 --self-contained true -o $publishDir
        if ($LASTEXITCODE -ne 0) { throw 'LAN Agent publish failed.' }
    }
    if (-not (Test-Path (Join-Path $publishDir 'WinHello.LanAgent.exe'))) {
        throw 'Published LAN Agent executable is missing.'
    }

    New-Item -ItemType Directory -Force -Path $installDir, $dataDir | Out-Null
    Copy-Item -Path (Join-Path $publishDir '*') -Destination $installDir -Recurse -Force
    Copy-Item -LiteralPath $providerDll -Destination (Join-Path $installDir 'WinHelloCredentialProvider.dll') -Force

    $acl = Get-Acl -LiteralPath $dataDir
    $acl.SetAccessRuleProtection($true, $false)
    $systemSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-18')
    $adminsSid = [Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
    foreach ($sid in @($systemSid, $adminsSid)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
        $acl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $dataDir -AclObject $acl

    $certificate = New-SelfSignedCertificate -Type SSLServerAuthentication `
        -DnsName $env:COMPUTERNAME -CertStoreLocation Cert:\LocalMachine\My `
        -KeyAlgorithm RSA -KeyLength 2048 -KeyExportPolicy NonExportable `
        -NotAfter (Get-Date).AddYears(2) -FriendlyName 'WinHello LAN Demo TLS'
    $thumbprint = $certificate.Thumbprint
    $deviceId = [Guid]::NewGuid().ToString('D')
    $totpSecret = New-Base32Secret
    $config = [ordered]@{
        DeviceId = $deviceId
        TotpSecret = $totpSecret
        CertificateThumbprint = $thumbprint
    }
    $config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
    $configAcl = Get-Acl -LiteralPath $configPath
    $configAcl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @($systemSid, $adminsSid)) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow')
        $configAcl.AddAccessRule($rule)
    }
    Set-Acl -LiteralPath $configPath -AclObject $configAcl

    $credPath = Join-Path $dataDir 'credential.enc'
    if (-not $WindowsPassword) {
        $secPass = Read-Host -Prompt "Enter Windows password for $WindowsDomain\$WindowsUsername to enable REAL unlock (leave empty to skip)" -AsSecureString
        $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($secPass)
        $WindowsPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }

    if ($WindowsPassword) {
        $rsa = [System.Security.Cryptography.X509Certificates.RSACertificateExtensions]::GetRSAPublicKey($certificate)
        $credPayload = [System.Text.Encoding]::UTF8.GetBytes("$WindowsDomain|$WindowsUsername|$WindowsPassword")
        $encryptedCred = $rsa.Encrypt($credPayload, [System.Security.Cryptography.RSAEncryptionPadding]::OaepSHA256)
        [System.IO.File]::WriteAllBytes($credPath, $encryptedCred)
        $credAcl = Get-Acl -LiteralPath $credPath
        $credAcl.SetAccessRuleProtection($true, $false)
        foreach ($sid in @($systemSid, $adminsSid)) {
            $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow')
            $credAcl.AddAccessRule($rule)
        }
        Set-Acl -LiteralPath $credPath -AclObject $credAcl
        Write-Host "Windows credential encrypted and saved for real logon ($WindowsDomain\$WindowsUsername)."
    }

    $sha = [Security.Cryptography.SHA256]::Create()
    try { $pin = ([BitConverter]::ToString($sha.ComputeHash($certificate.RawData))).Replace('-', '') }
    finally { $sha.Dispose() }
    $enrollmentUri = "winhello://enroll?pcid=$deviceId&secret=$totpSecret&pin=$pin&https=54431"
    $enrollmentUri | & (Join-Path $installDir 'WinHello.LanAgent.exe') --print-enrollment-qr
    if ($LASTEXITCODE -ne 0) { throw 'Could not print the setup QR code.' }

    New-Service -Name $serviceName -DisplayName 'WinHello LAN Agent' `
        -BinaryPathName ('"' + (Join-Path $installDir 'WinHello.LanAgent.exe') + '"') `
        -StartupType Automatic -Description 'LAN discovery and phone approval demo for WinHello.' | Out-Null
    $serviceCreated = $true
    $firewallCreated = $true
    New-NetFirewallRule -DisplayName $firewallNames[0] -Direction Inbound -Action Allow `
        -Protocol UDP -LocalPort 45876 -Profile Private | Out-Null
    New-NetFirewallRule -DisplayName $firewallNames[1] -Direction Inbound -Action Allow `
        -Protocol TCP -LocalPort 54431 -Profile Private | Out-Null
    Start-Service -Name $serviceName
    (Get-Service -Name $serviceName).WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
    Write-Host 'Scan the QR above in the Android app now. Waiting for TOTP enrollment confirmation...'
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
    if (-not $enrolled) { throw 'Phone did not confirm TOTP enrollment before the timeout.' }

    $registered = $true
    New-Item -Path "$classKey\InprocServer32" -Force | Out-Null
    Set-Item -Path "$classKey\InprocServer32" -Value (Join-Path $installDir 'WinHelloCredentialProvider.dll')
    New-ItemProperty -Path "$classKey\InprocServer32" -Name ThreadingModel -Value Apartment -PropertyType String -Force | Out-Null
    New-Item -Path $providerKey -Force | Out-Null
    Set-Item -Path $providerKey -Value $providerName
    [ordered]@{
        ServiceName = $serviceName
        InstallDirectory = $installDir
        DataDirectory = $dataDir
        CertificateThumbprint = $thumbprint
        ProviderClsid = $clsid
        InstalledAtUtc = [DateTime]::UtcNow.ToString('O')
    } | ConvertTo-Json | Set-Content -LiteralPath $markerPath -Encoding UTF8
    Write-Host 'WinHello LAN demo installed and phone enrollment confirmed.'
    Write-Host 'The extra tile only records TOTP/phone approval. It does not unlock a Windows account.'
    Write-Host 'To remove all demo components and enrollment data, run scripts\uninstall-winhello.ps1 as Administrator.'
}
catch {
    $installError = $_
    [Console]::Error.WriteLine("Install failed; rolling back installed components: $($installError.Exception.Message)")
    try { Remove-PartialInstall }
    catch { [Console]::Error.WriteLine("Rollback incomplete: $($_.Exception.Message)") }
    throw $installError
}
