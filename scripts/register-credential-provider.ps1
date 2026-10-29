param(
    [switch]$IUnderstandThisChangesLogonUI,
    [int]$EnrollmentTimeoutSeconds = 180,
    [string]$WindowsUsername = $env:USERNAME,
    [string]$WindowsDomain = $env:USERDOMAIN,
    [string]$WindowsPassword = ''
)
$installArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'install-winhello.ps1'), '-EnrollmentTimeoutSeconds', $EnrollmentTimeoutSeconds)
if ($IUnderstandThisChangesLogonUI) { $installArgs += '-IUnderstandThisChangesLogonUI' }
if ($WindowsPassword) { $installArgs += @('-WindowsPassword', $WindowsPassword, '-WindowsUsername', $WindowsUsername, '-WindowsDomain', $WindowsDomain) }
& powershell.exe @installArgs
exit $LASTEXITCODE
