@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install.ps1" -Uninstall
if errorlevel 1 (
    echo.
    echo Meowpuut was not removed. See the message above.
    pause
    exit /b 1
)
pause
