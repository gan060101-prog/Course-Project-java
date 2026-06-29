@echo off
setlocal
set PORT=%1
if "%PORT%"=="" set PORT=8080
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run.ps1" -Port %PORT%
if errorlevel 1 pause
endlocal
