@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-apk.ps1" %*
pause
exit /b %ERRORLEVEL%
