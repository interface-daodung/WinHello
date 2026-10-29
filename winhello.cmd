@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0cli\winhello.ps1" %*
exit /b %ERRORLEVEL%
