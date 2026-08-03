@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\bootstrap-maven.ps1" %*
exit /b %ERRORLEVEL%
