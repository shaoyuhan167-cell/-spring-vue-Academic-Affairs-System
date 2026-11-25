@echo off
setlocal EnableExtensions DisableDelayedExpansion
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\windows\Stop-System.ps1"
set "courseExitCode=%errorlevel%"
if not "%courseExitCode%"=="0" pause
exit /b %courseExitCode%
