@echo off
rem Removes everything "Install StreamLens.cmd" added. Asks for administrator rights.
setlocal
title Uninstall StreamLens
net session >nul 2>&1 || (
    powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
    exit /b
)

set "DEST=%ProgramFiles%\StreamLens"
echo Removing StreamLens ...
taskkill /f /im StreamLens.exe >nul 2>&1
if exist "%DEST%\StreamLensCamera.dll" regsvr32 /s /u "%DEST%\StreamLensCamera.dll"
netsh advfirewall firewall delete rule name="StreamLens" >nul 2>&1
powershell -NoProfile -Command "foreach ($dir in [Environment]::GetFolderPath('CommonDesktopDirectory'), [Environment]::GetFolderPath('CommonPrograms')) { Remove-Item (Join-Path $dir 'StreamLens.lnk') -ErrorAction SilentlyContinue }"
rmdir /s /q "%DEST%" 2>nul
if exist "%DEST%" (
    echo Some files are still in use, probably by OBS. Close OBS and run this again to finish.
) else (
    echo StreamLens has been removed.
)
echo.
pause
