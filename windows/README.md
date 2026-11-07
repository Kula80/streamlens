# StreamLens for Windows

Receives the phone's video and shows it as **StreamLens Camera**, which OBS (Video Capture Device),
Zoom, Teams and browsers can pick like any webcam. No OBS script needed.

Whenever there's no phone video (before it connects, or after a drop of 2 seconds), the camera shows
the branded right-back screen, drawn by `receiver/Slate.cpp` to match the OBS one. Its text, colour,
logo and photo come from `%APPDATA%\StreamLens\settings.ini` (created on first run); saving the file
updates the camera within seconds. When the phone reconnects, the camera switches back by itself.

Status: console app plus a pen-drive kit (`package.ps1`, `kit/`). Still to come: a proper window with
tray icon and "start with Windows" (and settings in it), and a signed installer.

## How it works

- `receiver/`: waits for the phone as an SRT listener on port 9710 (the phone finds it automatically),
  decodes on the graphics card (D3D11VA) and fits the picture into the camera frame. Receiving and
  output run on separate threads, so a slow computer skips frames rather than building up delay.
- `camera/`: the DirectShow virtual camera, built from [softcam](https://github.com/tshino/softcam)
  (MIT), renamed, with its own ID, and changed from RGB24 to NV12 (half the data, no colour conversion
  for a 4K phone). See `camera/NOTICE.md`.

## Build

Needs Visual Studio 2022 Build Tools (C++ tools, Windows SDK, CMake) and the FFmpeg
`win64-lgpl-shared` developer build. Then run `build.cmd` (set `FFMPEG_DIR` if FFmpeg isn't in
`C:\Projects\tools\ffmpeg\...`). Output: `build\StreamLens.exe`, `build\StreamLensCamera.dll` and
FFmpeg's DLLs.

## Try it

1. Register the camera once, as administrator: `regsvr32 build\StreamLensCamera.dll`
   (undo with `regsvr32 /u`).
2. Run `build\StreamLens.exe` (options: `--1080p`, `--fps 60`, `--port 9710`, `--latency 300`).
3. In OBS: **+ → Video Capture Device → Device: StreamLens Camera**.
4. On the phone, tap **Go live**.

Only one program can listen on a port: remove the OBS script's *StreamLens camera* media source (or
use another port), or StreamLens will say the port is in use.

Tested on an older dual-core laptop with built-in Intel graphics: 4K HEVC 40 Mbps uses about half of one processor core, with
no delay build-up.
