# StreamLens

Turn a phone into a high-quality, reliable live camera for OBS: 4K, high bitrate, pro manual
controls, and a branded "We'll be right back" screen that viewers see automatically if the phone
drops out.

```
Phone (Android app)  ──SRT over Wi-Fi / Ethernet / USB tethering──►  OBS (Media Source)  ──►  YouTube / Facebook
                                                                       └─ streamlens.lua: right-back screen
```

## What's in version 1

**Android app** (`android/`)

- 4K, 1080p or 720p at 24/25/30/50/60 fps, HEVC or H.264, 2–100 Mbps. Steps down by itself if the
  phone can't do the chosen format.
- Pro controls: exposure lock, EV, manual ISO and shutter, white balance presets and lock, manual
  focus, zoom (uses the telephoto lens on phones that support it), and **Look: natural**, which
  turns off the phone's noise reduction and over-sharpening.
- SRT streaming that repairs lost packets, reconnects every second until it's back, and restarts
  cleanly from a keyframe.
- Adaptive bitrate: lowers quality when the network struggles instead of freezing, then recovers.
- Heat protection: lowers bitrate when the phone gets hot instead of crashing.
- Service mode while live: screen stays on, Wi-Fi power saving off, optional Do Not Disturb.
- Network check with plain-language advice (2.4 vs 5 GHz, weak signal, mobile data).
- Status bar: link state, bitrate, round-trip time, packet loss, phone temperature and battery.

**OBS script** (`obs/streamlens.lua`)

- One click creates the phone camera source and the right-back screen.
- Right-back screen with your message (default: "Sorry for the inconvenience. We'll be right
  back."), second line, channel or event name, brand colour, logo, background photo and an optional
  "Audio continues" note.
- Shows automatically after 2 seconds without video, returns to the camera once the phone has
  been steady for 2 seconds. Manual show/hide buttons too.

## Set up OBS (once)

1. OBS → **Tools → Scripts → +** → pick `obs/streamlens.lua`.
2. Fill in your branding, then click **Set up phone camera**. This adds *StreamLens camera* and
   *StreamLens right-back screen* to the current scene.
3. Allow OBS through Windows Firewall when asked, on **Private networks** (it listens on UDP port
   9710 by default). Without this the phone can't find or reach OBS.

## Go live from the phone

1. Install the app (see *Build* below) and open it.
2. Tap **GO LIVE**. The phone finds OBS by itself and remembers it. If the computer later gets a
   new address, the phone finds it again.
3. Optional: **Settings** to pick quality, or to type the address if it can't be found (for example
   when the phone and computer are on different networks).

How finding works: OBS waits for the phone as an SRT listener, and every SRT listener answers the
first packet of an SRT connection. The phone sends that packet to each address on its network
(at most 254) and connects to whichever answers. Nothing extra runs on the computer.

Phone and PC must be on the same network. Guest Wi-Fi networks often block devices from talking to
each other; use the main network or a dedicated router.

## Connection options

| Connection | Notes |
| --- | --- |
| USB-C hub with Ethernet and power passthrough | Best for fixed setups: up to 100 m, no Wi-Fi interference, phone charges |
| 5 GHz / Wi-Fi 6 | Good with a strong signal near an access point |
| USB tethering | Turn on USB tethering on the phone, then use the PC's address on that link (SRT uses UDP, so `adb reverse` doesn't work) |

## Build

Needs JDK 17+ and the Android SDK.

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app-debug.apk`, or copy it to the phone.

## Roadmap

- **v1.1:** 10-bit / HLG, phone audio, automatic discovery of the OBS computer, local backup
  recording, phone health shown inside OBS.
- **v2:** branding pack: starting soon (countdown), prayer in progress, thank you, overlays, saved
  messages, templates. Could reuse `church-overlay`.
- **v3:** direct mode: stream from the phone straight to YouTube or Facebook.
- iPhone app.
