@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-worker-tunnel.ps1" %*
if errorlevel 1 (
  echo.
  echo WinHello update server failed to start. See the message above.
  pause
)
