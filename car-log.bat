@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\car-log.ps1" %*
pause
exit /b %ERRORLEVEL%
