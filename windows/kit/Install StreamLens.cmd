@echo off
rem Installs StreamLens from this folder (pen drive, download, ...) into Program Files, so the camera keeps
rem working after the pen drive is removed. Asks for administrator rights: cameras are registered for all apps.
setlocal
title Install StreamLens
net session >nul 2>&1 || (
    powershell -NoProfile -Command "Start-Process -Verb RunAs -FilePath '%~f0'"
    exit /b
)

set "SRC=%~dp0StreamLens"
set "DEST=%ProgramFiles%\StreamLens"
if not exist "%SRC%\StreamLens.exe" (
    echo Can't find the StreamLens folder next to this file. Copy the whole kit, not just this file.
    pause
    exit /b 1
)

echo Installing StreamLens to %DEST% ...
taskkill /f /im StreamLens.exe >nul 2>&1
if exist "%DEST%\StreamLensCamera.dll" regsvr32 /s /u "%DEST%\StreamLensCamera.dll"
robocopy "%SRC%" "%DEST%" /e /njh /njs /nfl /ndl /np >nul
if errorlevel 8 (
    echo Copying failed. Is StreamLens or a program using StreamLens Camera still open?
    pause
    exit /b 1
)

regsvr32 /s "%DEST%\StreamLensCamera.dll"
if errorlevel 1 (
    echo Couldn't register StreamLens Camera with Windows.
    pause
    exit /b 1
)

rem Let the phone reach StreamLens on home, church and work networks (Windows calls them Private/Domain).
netsh advfirewall firewall delete rule name="StreamLens" >nul 2>&1
netsh advfirewall firewall add rule name="StreamLens" dir=in action=allow program="%DEST%\StreamLens.exe" enable=yes profile=private,domain >nul

powershell -NoProfile -Command "$s = New-Object -ComObject WScript.Shell; foreach ($dir in [Environment]::GetFolderPath('CommonDesktopDirectory'), [Environment]::GetFolderPath('CommonPrograms')) { $l = $s.CreateShortcut((Join-Path $dir 'StreamLens.lnk')); $l.TargetPath = '%DEST%\StreamLens.exe'; $l.WorkingDirectory = '%DEST%'; $l.Save() }"

echo.
echo StreamLens is installed.
echo  1. Open StreamLens from the desktop.
echo  2. In OBS: + ^> Video Capture Device ^> Device: StreamLens Camera.
echo  3. On the phone, tap Go live.
echo.
echo If the phone can't find this laptop, set this Wi-Fi to Private:
echo Settings ^> Network ^& Internet ^> your Wi-Fi ^> Network profile: Private.
echo.
pause
