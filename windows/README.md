# StreamLens for Windows

Receives the phone's video and shows it as **StreamLens Camera**, which OBS (Video Capture Device),
Zoom, Teams and browsers can pick like any webcam. People install it with **StreamLens-Setup.exe**.

## What people get

- **StreamLens window** (`receiver/App.cpp`, page in `app/ui/index.html`, shown with WebView2): whether
  the phone is connected, this computer's address, a warning with the exact fix when Windows Firewall
  would block the phone (it reads the real firewall rules for StreamLens.exe), right-back screen
  settings with a live preview, Automatic / Show now / Keep hidden, camera size, start with Windows.
  Closing the window keeps StreamLens running in the tray; only one copy runs at a time.
- **Right-back screen** (`receiver/Slate.cpp`): shown whenever there's no phone video (before it
  connects, or after a 2-second drop), matching the OBS script's screen. Settings live in
  `%APPDATA%\StreamLens\settings.ini`, written by the window (hand edits also work).
- **StreamLens-Setup.exe** (`installer/StreamLens.iss`, Inno Setup): installs to Program Files,
  registers the camera, adds a firewall rule for Private/Domain networks, shortcuts, optional start
  with Windows, and an Apps & features uninstaller.

## How it works

- `receiver/Receiver.cpp`: waits for the phone as an SRT listener on port 9710 (the phone finds it
  automatically), decodes on the graphics card (D3D11VA) and fits the picture into the camera frame.
  Receiving and output run on separate threads, so a slow computer skips frames rather than building
  up delay.
- `receiver/Engine.cpp`: feeds the camera at a steady frame rate and picks what it shows: phone, right-back
  screen, or (Keep hidden) the last phone picture.
- `camera/`: the DirectShow virtual camera, built from [softcam](https://github.com/tshino/softcam)
  (MIT), renamed, with its own ID, and changed from RGB24 to NV12 (half the data, no colour conversion
  for a 4K phone). See `camera/NOTICE.md`.
- `receiver/main.cpp` builds `streamlens-cli.exe`: the same engine printing to a console, for tests and
  troubleshooting.

## Build

Needs Visual Studio 2022 Build Tools (C++ tools, Windows SDK, CMake), the FFmpeg `win64-lgpl-shared`
developer build, the Microsoft.Web.WebView2 NuGet package (unpacked) and Inno Setup 6.

1. `build.cmd` (set `FFMPEG_DIR` / `WEBVIEW2_DIR` if they aren't under `C:\Projects\tools`).
2. `package.ps1`: builds `dist\StreamLens-Setup.exe` and the pen-drive kit `dist\StreamLens-Windows`
   (setup, phone app, READ ME), also zipped.

Tested on an older dual-core laptop with built-in Intel graphics: 4K HEVC 40 Mbps uses about half of one processor core, with
no delay build-up.
