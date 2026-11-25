@echo off
setlocal EnableExtensions DisableDelayedExpansion
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\Start-System.ps1" -BuildIfMissing -OpenBrowser
set "courseExitCode=%errorlevel%"
if not "%courseExitCode%"=="0" pause
exit /b %courseExitCode%
