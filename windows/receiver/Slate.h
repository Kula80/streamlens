#pragma once

#include <cstdint>
#include <string>
#include <vector>

// The branded "We'll be right back" screen that StreamLens Camera shows whenever there's no phone video.
// It matches the OBS script's right-back screen. Settings live in %APPDATA%\StreamLens\settings.ini, which
// the StreamLens window edits (people can also edit it by hand).
struct SlateSettings {
    std::wstring message = L"Sorry for the inconvenience. We'll be right back.";
    std::wstring secondLine;
    std::wstring churchName;
    uint32_t brandColour = 0x3C3489;  // RGB
    std::wstring logo;
    std::wstring photo;
    bool audioContinues = false;
};

// App options kept in the same file.
struct AppPrefs {
    bool camera1080p = false;  // camera size: 4K (default) or 1080p for slower computers
};

// Path of the settings file, creating it with the defaults (and explanations) the first time.
std::wstring slateSettingsPath();
SlateSettings loadSlateSettings(const std::wstring& path);
AppPrefs loadAppPrefs(const std::wstring& path);
void saveSettings(const std::wstring& path, const SlateSettings& settings, const AppPrefs& prefs);

// Draws the screen and returns it as one NV12 frame of width * height * 3 / 2 bytes.
std::vector<uint8_t> renderSlate(const SlateSettings& settings, const std::wstring& status, int width, int height);

// Draws the screen as a PNG "data:" URL, for the preview in the StreamLens window.
std::string renderSlatePreview(const SlateSettings& settings, const std::wstring& status, int width, int height);

// Shrinks one camera frame (NV12) to a JPEG "data:" URL, for the live camera preview in the StreamLens window.
std::string cameraPreview(const uint8_t* nv12, int width, int height, int outWidth, int outHeight);
