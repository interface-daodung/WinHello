$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$package = Join-Path $root 'dist\winhello-windows'
$agentProject = Join-Path $root 'apps\lan-agent\WinHello.LanAgent.csproj'
$agentPublish = Join-Path $root 'apps\lan-agent\bin\Release\net10.0\win-x64\publish'
$provider = Join-Path $root 'build\credential-provider-monorepo-llvm-mingw\WinHelloCredentialProvider.dll'

& (Join-Path $PSScriptRoot 'build-credential-provider.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Credential Provider build failed.' }
dotnet publish $agentProject -c Release -r win-x64 --self-contained true -o $agentPublish
if ($LASTEXITCODE -ne 0) { throw 'LAN Agent publish failed.' }

New-Item -ItemType Directory -Force -Path (Join-Path $package 'bin\agent'), (Join-Path $package 'bin\credential'), (Join-Path $package 'cli'), (Join-Path $package 'scripts') | Out-Null
Copy-Item -LiteralPath $provider -Destination (Join-Path $package 'bin\credential\WinHelloCredentialProvider.dll') -Force
Copy-Item -Path (Join-Path $agentPublish '*') -Destination (Join-Path $package 'bin\agent') -Recurse -Force
Copy-Item -Path (Join-Path $root 'cli\*') -Destination (Join-Path $package 'cli') -Force
Copy-Item -Path (Join-Path $root 'scripts\*.ps1') -Destination (Join-Path $package 'scripts') -Force
Copy-Item -LiteralPath (Join-Path $root 'winhello.cmd'), (Join-Path $root 'VERSION'), (Join-Path $root 'LICENSE') -Destination $package -Force
Copy-Item -LiteralPath (Join-Path $root 'assets\winhello.ico') -Destination $package -Force
Set-Content -LiteralPath (Join-Path $package 'package.marker') -Value 'prebuilt' -NoNewline
Write-Host "Windows TUI package: $package"
$archive = Join-Path $root ('dist\winhello-windows-' + (Get-Content -LiteralPath (Join-Path $root 'VERSION') -Raw).Trim() + '.zip')
Compress-Archive -Path (Join-Path $package '*') -DestinationPath $archive -Force
Write-Host "Windows TUI archive: $archive"
