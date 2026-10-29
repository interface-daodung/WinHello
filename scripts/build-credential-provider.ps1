$ErrorActionPreference = 'Stop'

$wingetPackages = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
if (-not (Test-Path $wingetPackages)) {
    $wingetPackages = Join-Path $env:USERPROFILE 'AppData\Local\Microsoft\WinGet\Packages'
}
$llvmPackage = Get-ChildItem $wingetPackages -Directory |
    Where-Object Name -Like 'MartinStorsjo.LLVM-MinGW.UCRT*' |
    Select-Object -First 1
if (-not $llvmPackage) {
    throw 'LLVM-MinGW UCRT was not found. Install MartinStorsjo.LLVM-MinGW.UCRT with Winget first.'
}

$llvmRoot = Get-ChildItem $llvmPackage.FullName -Directory |
    Where-Object Name -Like 'llvm-mingw*-ucrt-x86_64' |
    Select-Object -First 1
if (-not $llvmRoot) {
    throw 'The LLVM-MinGW toolchain folder was not found in the Winget package.'
}
$env:LLVM_MINGW_ROOT = $llvmRoot.FullName
$env:Path = "$($llvmRoot.FullName)\bin;$env:Path"

$ninjaPackage = Get-ChildItem $wingetPackages -Directory |
    Where-Object Name -Like 'Ninja-build.Ninja*' |
    Select-Object -First 1
if ($ninjaPackage) {
    $ninjaExe = Get-ChildItem $ninjaPackage.FullName -Filter ninja.exe -Recurse |
        Select-Object -First 1
    if ($ninjaExe) {
        $env:Path = "$($ninjaExe.DirectoryName);$env:Path"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$toolchainFile = Join-Path $repoRoot 'cmake\llvm-mingw-toolchain.cmake'
$buildDirectory = Join-Path $repoRoot 'build\credential-provider-monorepo-llvm-mingw'

if (-not (Get-Command cmake -ErrorAction SilentlyContinue)) {
    $cmakeBin = 'C:\Program Files\CMake\bin'
    if (Test-Path (Join-Path $cmakeBin 'cmake.exe')) {
        $env:Path = "$cmakeBin;$env:Path"
    }
}

cmake -S (Join-Path $repoRoot 'apps\credential-provider') `
    -B $buildDirectory -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$toolchainFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

cmake --build $buildDirectory
exit $LASTEXITCODE
