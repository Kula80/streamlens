@echo off
rem Builds StreamLens for Windows into windows\build: StreamLens.exe (the app), streamlens-cli.exe
rem (same engine, console) and StreamLensCamera.dll.
rem Needs Visual Studio 2022 Build Tools (C++), the FFmpeg shared developer build and the WebView2 SDK.
setlocal
if "%FFMPEG_DIR%"=="" set "FFMPEG_DIR=C:\Projects\tools\ffmpeg\ffmpeg-n9.0.2-48-gd4070a14ce-win64-lgpl-shared-9.0"
if "%WEBVIEW2_DIR%"=="" set "WEBVIEW2_DIR=C:\Projects\tools\webview2"
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
for /f "usebackq delims=" %%i in (`call "%%VSWHERE%%" -latest -products * -property installationPath`) do set "VS=%%i"
call "%VS%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release "-DFFMPEG_DIR=%FFMPEG_DIR%" "-DWEBVIEW2_DIR=%WEBVIEW2_DIR%" || exit /b 1
cmake --build build || exit /b 1
