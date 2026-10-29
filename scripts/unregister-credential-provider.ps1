& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'uninstall-winhello.ps1')
exit $LASTEXITCODE
