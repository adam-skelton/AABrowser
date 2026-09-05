@echo off
setlocal
set "DHU=%~dp0..\Android\extras\google\auto\desktop-head-unit.exe"

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
