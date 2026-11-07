#pragma once

#include <cstdint>
#include <string>
#include <vector>

// The branded "We'll be right back" screen that StreamLens Camera shows whenever there's no phone video.
// It matches the OBS script's right-back screen. Settings live in a small text file the user can edit
// (%APPDATA%\StreamLens\settings.ini) until the app has a window of its own.
struct SlateSettings {
    std::wstring message = L"Sorry for the inconvenience. We'll be right back.";
    std::wstring secondLine;
    std::wstring churchName;
    uint32_t brandColour = 0x3C3489;  // RGB
    std::wstring logo;
    std::wstring photo;
    bool audioContinues = false;
};

// Path of the settings file, creating it with the defaults (and explanations) the first time.
std::wstring slateSettingsPath();
SlateSettings loadSlateSettings(const std::wstring& path);

// Draws the screen and returns it as one NV12 frame of width * height * 3 / 2 bytes.
std::vector<uint8_t> renderSlate(const SlateSettings& settings, const std::wstring& status, int width, int height);
