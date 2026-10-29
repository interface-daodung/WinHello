param(
    [switch]$Remove
)

$ErrorActionPreference = 'Stop'
$rules = @(
    @{ Name = 'WinHello LAN Demo Discovery UDP'; Protocol = 'UDP'; Port = 45876 },
    @{ Name = 'WinHello LAN Demo HTTPS'; Protocol = 'TCP'; Port = 54431 }
)

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this script from an elevated PowerShell window.'
}

foreach ($rule in $rules) {
    if ($Remove) {
        Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue |
            Remove-NetFirewallRule
    } else {
        Get-NetFirewallRule -DisplayName $rule.Name -ErrorAction SilentlyContinue |
            Remove-NetFirewallRule
        New-NetFirewallRule -DisplayName $rule.Name -Direction Inbound -Action Allow `
            -Protocol $rule.Protocol -LocalPort $rule.Port -Profile Private | Out-Null
    }
}

if ($Remove) { Write-Host 'Removed WinHello LAN demo firewall rules.' }
else { Write-Host 'Added inbound rules for Private networks only (UDP 45876, TCP 54431).' }
