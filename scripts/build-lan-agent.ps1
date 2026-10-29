$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$env:DOTNET_CLI_HOME = Join-Path $repoRoot '.dotnet-cli'

$dotnetCommand = Get-Command dotnet -ErrorAction SilentlyContinue
if ($dotnetCommand) {
    $dotnet = $dotnetCommand.Source
} else {
    $dotnet = 'C:\Program Files\dotnet\dotnet.exe'
}
if (-not (Test-Path $dotnet)) {
    throw '.NET 10 SDK was not found. Install Microsoft.DotNet.SDK.10 with Winget first.'
}

& $dotnet build (Join-Path $repoRoot 'apps\lan-agent\WinHello.LanAgent.csproj')
exit $LASTEXITCODE
