@echo off
setlocal
rem Use the shipped Electron runtime as the test runner; no installed Node is needed.
set "SMOKE_RESULTS=%~1"
if not defined SMOKE_RESULTS set "SMOKE_RESULTS=%~dp0..\results"
if not exist "%SMOKE_RESULTS%" mkdir "%SMOKE_RESULTS%"
set ELECTRON_RUN_AS_NODE=1
"%~dp0artifact\Tyler.exe" "%~dp0desktop-smoke.cjs" "%~dp0artifact" "%~dp0fixture" "%SMOKE_RESULTS%" > "%SMOKE_RESULTS%\smoke.log" 2>&1
set "SMOKE_EXIT=%ERRORLEVEL%"
echo %SMOKE_EXIT% > "%SMOKE_RESULTS%\exit-code.txt"
exit /b %SMOKE_EXIT%
