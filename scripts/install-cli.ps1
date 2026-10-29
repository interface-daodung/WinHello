$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$package = if (Test-Path -LiteralPath (Join-Path $root 'package.marker')) {
    $root
} else {
    Join-Path $root 'dist\winhello-windows'
}
if (-not (Test-Path -LiteralPath (Join-Path $package 'package.marker'))) {
    throw 'Build the Windows package with scripts\build-windows.ps1 first.'
}
$target = Join-Path $env:LOCALAPPDATA 'Programs\WinHello'
New-Item -ItemType Directory -Path $target -Force | Out-Null
Copy-Item -Path (Join-Path $package '*') -Destination $target -Recurse -Force
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$parts = @($userPath -split ';' | Where-Object { $_ })
if ($parts -notcontains $target) {
    [Environment]::SetEnvironmentVariable('Path', (($parts + $target) -join ';'), 'User')
}
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class WinHelloEnvironmentNotice {
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint msg, UIntPtr wParam,
        string lParam, uint flags, uint timeout, out UIntPtr result);
}
'@
$result = [UIntPtr]::Zero
[void][WinHelloEnvironmentNotice]::SendMessageTimeout([IntPtr]0xffff, 0x1a, [UIntPtr]::Zero,
    'Environment', 2, 5000, [ref]$result)
Write-Host 'WinHello CLI ready. Open a new CMD window and type: winhello h'
