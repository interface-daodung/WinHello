param([int]$Port = 8765)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$publish = Join-Path $root 'publish'
$workerConfig = Get-Content -LiteralPath (Join-Path $root 'config\update-server.json') -Raw | ConvertFrom-Json
$workerUrl = ([string]$workerConfig.baseUrl).TrimEnd('/')
if ($workerUrl -notmatch '^https://[a-z0-9-]+(\.[a-z0-9-]+)*\.workers\.dev$') { throw 'config/update-server.json must contain a valid HTTPS workers.dev baseUrl.' }
if (-not (Test-Path (Join-Path $publish 'latest.json'))) { throw 'Build a release first with scripts\build-release.ps1.' }
$tokenFile = Join-Path $root '.worker-control-secret'
if (-not (Test-Path $tokenFile)) {
    $secret = Read-Host 'Enter the Worker CONTROL_SECRET value (the value set with wrangler secret put)'
    if ([string]::IsNullOrWhiteSpace($secret)) { throw 'CONTROL_SECRET is required.' }
    ConvertTo-SecureString $secret -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $tokenFile
}
$protectedValue = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
$protectedSecret = ConvertTo-SecureString -String $protectedValue
$secret = [System.Net.NetworkCredential]::new('', $protectedSecret).Password
$cloudflared = Get-Command cloudflared -ErrorAction SilentlyContinue
if (-not $cloudflared) { $cloudflared = Get-Item 'C:\Program Files (x86)\cloudflared\cloudflared.exe' -ErrorAction SilentlyContinue; if (-not $cloudflared) { throw 'Install cloudflared first: winget install --id Cloudflare.cloudflared --exact.' }; $cloudflared = @{ Source = $cloudflared.FullName } }
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) { throw 'Node.js is required.' }
$server = Start-Process -FilePath $node.Source -ArgumentList @((Join-Path $PSScriptRoot 'serve-publish.js'),$publish,"$Port") -WindowStyle Hidden -PassThru
$log = Join-Path $env:TEMP 'winhello-cloudflared.log'
$errorLog = Join-Path $env:TEMP 'winhello-cloudflared-error.log'
Remove-Item -LiteralPath $log,$errorLog -Force -ErrorAction SilentlyContinue
$tunnel = $null
$registered = $false
try {
    $tunnel = Start-Process -FilePath $cloudflared.Source -ArgumentList @('tunnel','--url',"http://localhost:$Port") -WindowStyle Hidden -PassThru -RedirectStandardOutput $log -RedirectStandardError $errorLog
    Write-Host 'Starting local publish server and Cloudflare Quick Tunnel...' -ForegroundColor Cyan
    while (-not $tunnel.HasExited) {
        $output = ((Get-Content -LiteralPath $log -Raw -ErrorAction SilentlyContinue) + (Get-Content -LiteralPath $errorLog -Raw -ErrorAction SilentlyContinue))
        if (-not $registered -and $output -match 'https://[a-z0-9-]+\.trycloudflare\.com') {
            $origin = $Matches[0]
            $headers = @{ Authorization = "Bearer $secret" }
            Invoke-RestMethod -Method Post -Uri "$workerUrl/__control/origin" -Headers $headers -ContentType 'application/json' -Body (@{ origin = $origin } | ConvertTo-Json -Compress) | Out-Null
            $env:WINHELLO_UPDATE_BASE_URL = $workerUrl
            [Environment]::SetEnvironmentVariable('WINHELLO_UPDATE_BASE_URL', $workerUrl, 'User')
            Write-Host "Stable update URL: $workerUrl" -ForegroundColor Green
            Write-Host "Quick Tunnel origin registered: $origin"
            Write-Host "APK: $workerUrl/$((Get-Content (Join-Path $publish 'latest.json') -Raw | ConvertFrom-Json).apk)"
            Write-Host "Status: $workerUrl/__status"
            Write-Host 'Keep this window open. Closing it stops the server and marks the Worker offline.'
            $registered = $true
        }
        Start-Sleep -Seconds 1
    }
    if ($tunnel.ExitCode -ne 0) { throw "cloudflared exited with code $($tunnel.ExitCode)." }
} finally {
    try { Invoke-RestMethod -Method Post -Uri "$workerUrl/__control/origin" -Headers @{ Authorization = "Bearer $secret" } -ContentType 'application/json' -Body '{"origin":""}' | Out-Null } catch { Write-Warning 'Could not mark Worker offline; the stale origin will return 503 until Quick Tunnel becomes reachable.' }
    if ($tunnel -and -not $tunnel.HasExited) { Stop-Process -Id $tunnel.Id -Force -ErrorAction SilentlyContinue }
    if ($server -and -not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $log,$errorLog -Force -ErrorAction SilentlyContinue
}
