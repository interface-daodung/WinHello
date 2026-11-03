param([int]$Port = 8765)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$publish = Join-Path $root 'publish'
if (-not (Test-Path (Join-Path $publish 'latest.json'))) {
    throw 'No publish/latest.json found. Build a release first with scripts\build-release.ps1.'
}
$cloudflared = Get-Command cloudflared -ErrorAction SilentlyContinue
if (-not $cloudflared) {
    $cloudflared = Get-Item 'C:\Program Files (x86)\cloudflared\cloudflared.exe' -ErrorAction SilentlyContinue
    if (-not $cloudflared) { throw 'cloudflared is not installed. Run winget install --id Cloudflare.cloudflared --exact.' }
    $cloudflared = @{ Source = $cloudflared.FullName }
}
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) { throw 'Node.js is required to serve publish/.' }
$serverScript = Join-Path $PSScriptRoot 'serve-publish.js'
$server = Start-Process -FilePath $node.Source -ArgumentList @($serverScript,$publish,"$Port") -WindowStyle Hidden -PassThru
$log = Join-Path $env:TEMP 'winhello-cloudflared.log'
$errorLog = Join-Path $env:TEMP 'winhello-cloudflared-error.log'
Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $errorLog -Force -ErrorAction SilentlyContinue
$tunnel = $null
try {
    $tunnel = Start-Process -FilePath $cloudflared.Source -ArgumentList @('tunnel','--url',"http://localhost:$Port") -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError $errorLog
    Write-Host 'Serving publish/ through a temporary Cloudflare Quick Tunnel. Keep this window open.'
    Write-Host 'Download page: <tunnel-url>/'
    Write-Host 'APK: <tunnel-url>/<APK filename from latest.json>'
    Write-Host 'CLI: set WINHELLO_UPDATE_BASE_URL to the tunnel URL, then run winhello app or winhello update.'
    $shown = $false
    while (-not $tunnel.HasExited) {
        if (Test-Path $log) {
            $text = (Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath $errorLog -Raw -ErrorAction SilentlyContinue)
            if ($text -match 'https://[a-z0-9-]+\.trycloudflare\.com' -and -not $shown) {
                $base = $Matches[0]
                $env:WINHELLO_UPDATE_BASE_URL = $base
                [Environment]::SetEnvironmentVariable('WINHELLO_UPDATE_BASE_URL', $base, 'User')
                Write-Host "Update base URL: $base" -ForegroundColor Green
                Write-Host 'WINHELLO_UPDATE_BASE_URL is set for this process and future terminals.'
                Write-Host "APK link: $base/$((Get-Content (Join-Path $publish 'latest.json') -Raw | ConvertFrom-Json).apk)"
                $shown = $true
            }
        }
        Start-Sleep -Seconds 1
    }
} finally {
    if ($tunnel -and -not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue }
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $errorLog -Force -ErrorAction SilentlyContinue
}
