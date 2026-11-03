param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Stop', 'Start')]
    [string]$Action,
    [Parameter(Mandatory = $true)]
    [string]$StateFile
)

$ErrorActionPreference = 'Stop'
$service = Get-Service -Name 'WinHelloLanAgent' -ErrorAction SilentlyContinue
if ($Action -eq 'Stop') {
    $wasRunning = $service -and $service.Status -ne 'Stopped'
    Set-Content -LiteralPath $StateFile -Value ([string]$wasRunning) -NoNewline
    if ($wasRunning) {
        Stop-Service -Name 'WinHelloLanAgent' -Force
        $service = Get-Service -Name 'WinHelloLanAgent'
        $service.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(30))
    }
    exit 0
}

if ((Test-Path -LiteralPath $StateFile) -and (Get-Content -LiteralPath $StateFile -Raw).Trim() -eq 'True' -and $service) {
    Start-Service -Name 'WinHelloLanAgent'
    (Get-Service -Name 'WinHelloLanAgent').WaitForStatus('Running', [TimeSpan]::FromSeconds(30))
}
