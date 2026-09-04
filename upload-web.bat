@echo off
setlocal
cd /d "%~dp0"
echo Uploading latest web files to the connected phone...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\push-web.ps1"
if errorlevel 1 (
  echo.
  echo Upload failed.
)
pause
