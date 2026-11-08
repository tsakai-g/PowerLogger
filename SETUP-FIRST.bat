@echo off
setlocal
cd /d "%~dp0"
echo Installing the official Gradle 8.13 wrapper JAR (with SHA-256 check)...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-gradle-wrapper.ps1"
if errorlevel 1 (
  echo Setup failed. See the error above. Do not bypass checksum verification.
  pause
  exit /b 1
)
echo Wrapper setup complete. Open this folder in Android Studio.
pause
