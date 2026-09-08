@echo off
setlocal
echo.
echo AABrowser install
echo If this repo already matches origin, install starts immediately.
echo If there are unpushed commits, they are pushed first, then the script
echo waits 4 minutes so GitHub Actions can build the APK.
echo Uncommitted files are left alone and will not be in this APK.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-apk.ps1" %*
pause
exit /b %ERRORLEVEL%
