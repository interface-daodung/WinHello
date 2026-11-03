$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim()
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'VERSION must use major.minor.patch.' }
$androidBuildFile = Join-Path $root 'apps\android\app\build.gradle.kts'
if ((Get-Content -LiteralPath $androidBuildFile -Raw) -notmatch 'versionName\s*=\s*appVersion' -or
    (Get-Content -LiteralPath $androidBuildFile -Raw) -notmatch 'versionCode\s*=\s*appVersionCode') {
    throw 'Android versionName and versionCode must be derived from VERSION before publishing.'
}
$publish = Join-Path $root 'publish'
$package = Join-Path $root 'dist\winhello-windows'
$inno = Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 7\ISCC.exe'
if (-not (Test-Path -LiteralPath $inno)) {
    $inno = 'C:\Program Files\Inno Setup 7\ISCC.exe'
}
if (-not (Test-Path -LiteralPath $inno)) {
    throw 'Inno Setup 7 x64 ISCC.exe is required. Install JRSoftware.InnoSetup.7 first.'
}

New-Item -ItemType Directory -Path $publish -Force | Out-Null
& (Join-Path $PSScriptRoot 'build-windows.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Windows package build failed.' }

& $inno "/DAppVersion=$version" "/DPackageDir=$package" "/DOutputDir=$publish" `
    "/DIconPath=$(Join-Path $root 'assets\winhello.ico')" (Join-Path $root 'packaging\WinHello.iss')
if ($LASTEXITCODE -ne 0) { throw 'Inno Setup build failed.' }

& (Join-Path $PSScriptRoot 'build-android.ps1') -Configuration Release
if ($LASTEXITCODE -ne 0) { throw 'Android APK build failed.' }
$apk = Join-Path $root 'apps\android\app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Android APK is missing.' }
$androidOutput = Join-Path $publish "WinHello-ver$version-android.apk"
Copy-Item -LiteralPath $apk -Destination $androidOutput -Force

$windowsOutput = Join-Path $publish "WinHello-ver$version-windows-x64-setup.exe"
if (-not (Test-Path -LiteralPath $windowsOutput)) { throw 'Windows installer is missing.' }
$files = @($windowsOutput, $androidOutput)
$checksums = foreach ($file in $files) {
    $hash = Get-FileHash -LiteralPath $file -Algorithm SHA256
    "$($hash.Hash.ToLowerInvariant())  $(Split-Path -Leaf $file)"
}
Set-Content -LiteralPath (Join-Path $publish "WinHello-ver$version-SHA256SUMS.txt") -Value $checksums -Encoding ASCII
$apkHash = (Get-FileHash -LiteralPath $androidOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$windowsHash = (Get-FileHash -LiteralPath $windowsOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$latest = [ordered]@{
    version = $version
    apk = (Split-Path -Leaf $androidOutput)
    sha256 = $apkHash
    windows = [ordered]@{ file = (Split-Path -Leaf $windowsOutput); sha256 = $windowsHash }
}
[System.IO.File]::WriteAllText((Join-Path $publish 'latest.json'), ($latest | ConvertTo-Json -Depth 4), [System.Text.UTF8Encoding]::new($false))
Get-Item -LiteralPath $files | Select-Object Name,Length,FullName
