@echo off
setlocal
set "DHU=%~dp0..\Android\extras\google\auto\desktop-head-unit.exe"

echo Installing APK...
echo If this repo already matches origin, install starts immediately.
echo If there are unpushed commits, they are pushed first, then the script
echo waits 4 minutes so GitHub Actions can build the APK.
echo Uncommitted files are left alone and will not be in this APK.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install-apk.ps1" %*
if errorlevel 1 (
  echo Install failed.
  pause
  exit /b 1
)

echo.
echo Starting Android Auto desktop head unit...
adb forward tcp:5277 tcp:5277
if errorlevel 1 (
  echo adb forward failed. Plug the phone in with USB debugging on.
  pause
  exit /b 1
)

if not exist "%DHU%" (
  echo desktop-head-unit.exe not found:
  echo %DHU%
  pause
  exit /b 1
)

start "" "%DHU%"
echo DHU launched.
exit /b 0
