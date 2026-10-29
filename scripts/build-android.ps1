$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = Join-Path $repoRoot 'tools\android-sdk'
$gradle = Join-Path $repoRoot 'tools\gradle\gradle-8.13\bin\gradle.bat'

$jdk = Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $jdk) {
    throw 'JDK 17 was not found under C:\Program Files\Eclipse Adoptium.'
}
if (-not (Test-Path $gradle)) { throw "Gradle 8.13 was not found: $gradle" }
if (-not (Test-Path (Join-Path $sdkRoot 'platforms\android-36\android.jar'))) {
    throw "Android SDK Platform 36 was not found: $sdkRoot"
}

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = $sdkRoot
$env:ANDROID_SDK_ROOT = $sdkRoot
$env:Path = "$($jdk.FullName)\bin;$sdkRoot\platform-tools;$sdkRoot\cmdline-tools\latest\bin;$env:Path"
& $gradle -p (Join-Path $repoRoot 'android') --no-daemon :app:assembleDebug
exit $LASTEXITCODE
