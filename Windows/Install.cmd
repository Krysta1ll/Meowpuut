@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install.ps1" -Prebuilt
if errorlevel 1 (
    echo.
    echo Meowpuut installation failed. See the message above.
    pause
    exit /b 1
)
echo.
echo Meowpuut Pinyin is available under Traditional Chinese. Press Win+Space to switch.
pause
