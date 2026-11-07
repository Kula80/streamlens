// StreamLens for Windows (console for now).
// Receives the phone's video and shows it as "StreamLens Camera", which OBS, Zoom and Teams can pick
// like any webcam. The phone finds this computer by itself, just as it finds OBS. Whenever there's no
// phone video, the camera shows the branded right-back screen instead.

#include <windows.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Receiver.h"
#include "Slate.h"
#include "softcam.h"

namespace {

std::atomic<bool> g_running{true};

BOOL WINAPI onConsoleClose(DWORD) {
    g_running = false;
    return TRUE;
}

// Both versions of the right-back screen, drawn ahead of time so switching to them is instant.
struct Slates {
    std::vector<uint8_t> waiting;       // before the phone has connected
    std::vector<uint8_t> reconnecting;  // after the phone dropped
};

std::shared_ptr<const Slates> drawSlates(const std::wstring& settingsPath, int width, int height) {
    SlateSettings settings = loadSlateSettings(settingsPath);
    auto s = std::make_shared<Slates>();
    s->waiting = renderSlate(settings, L"Waiting for the camera", width, height);
    s->reconnecting = renderSlate(settings, L"Reconnecting", width, height);
    return s;
}

FILETIME lastWrite(const std::wstring& path) {
    WIN32_FILE_ATTRIBUTE_DATA data{};
    GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &data);
    return data.ftLastWriteTime;
}

}  // namespace

int main(int argc, char** argv) {
    // Show messages straight away, also when the output goes to a log file.
    setvbuf(stdout, nullptr, _IONBF, 0);
    Receiver::Config config;
    float fps = 30.0f;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a == "--1080p") {
            config.outWidth = 1920;
            config.outHeight = 1080;
        } else if (a == "--fps" && i + 1 < argc) {
            fps = std::stof(argv[++i]);
        } else if (a == "--port" && i + 1 < argc) {
            config.port = std::stoi(argv[++i]);
        } else if (a == "--latency" && i + 1 < argc) {
            config.latencyMs = std::stoi(argv[++i]);
        }
    }

    scCamera camera = scCreateCamera(config.outWidth, config.outHeight, fps);
    if (!camera) {
        std::printf("StreamLens Camera couldn't start. Is StreamLens already running?\n");
        return 1;
    }
    SetConsoleCtrlHandler(onConsoleClose, TRUE);

    const std::wstring settingsPath = slateSettingsPath();
    const size_t frameBytes = static_cast<size_t>(config.outWidth) * config.outHeight * 3 / 2;  // NV12
    std::mutex lock;
    std::shared_ptr<const Slates> slates = drawSlates(settingsPath, config.outWidth, config.outHeight);
    std::vector<uint8_t> latest = slates->waiting;
    std::vector<uint8_t> sending(frameBytes);
    // A last frame from a dropped connection can arrive just after the phone is declared gone; it
    // mustn't replace the right-back screen.
    bool live = false;
    bool everConnected = false;
    std::string lastMessage;

    Receiver receiver(
        config,
        [&](const uint8_t* nv12) {
            std::lock_guard<std::mutex> g(lock);
            if (live) std::memcpy(latest.data(), nv12, frameBytes);
        },
        [&](Receiver::State state, const std::string& detail) {
            std::lock_guard<std::mutex> g(lock);
            live = state == Receiver::State::Live;
            everConnected = everConnected || live;
            std::string message = live ? "Phone connected: " + detail
                                  : !detail.empty() ? detail
                                  : everConnected ? "Phone dropped: showing the right-back screen. Waiting for it to come back..."
                                                  : "Waiting for the phone on port " + std::to_string(config.port) + "...";
            // Retries repeat the same state; say it once.
            if (message != lastMessage) std::printf("%s\n", message.c_str());
            lastMessage = message;
            if (!live) latest = everConnected ? slates->reconnecting : slates->waiting;
        });

    std::printf("StreamLens Camera is on (%dx%d, %.0f fps). In OBS: + > Video Capture Device > StreamLens Camera.\n",
                config.outWidth, config.outHeight, fps);
    std::printf("Right-back screen settings: %ls\n", settingsPath.c_str());
    receiver.start();

    // Redraw the right-back screen when its settings file is saved.
    std::thread watcher([&] {
        FILETIME seen = lastWrite(settingsPath);
        while (g_running) {
            std::this_thread::sleep_for(std::chrono::seconds(2));
            FILETIME now = lastWrite(settingsPath);
            if (CompareFileTime(&now, &seen) == 0) continue;
            seen = now;
            auto fresh = drawSlates(settingsPath, config.outWidth, config.outHeight);
            std::lock_guard<std::mutex> g(lock);
            slates = fresh;
            if (!live) latest = everConnected ? slates->reconnecting : slates->waiting;
            std::printf("Right-back screen updated.\n");
        }
    });

    // The camera keeps a steady frame rate whatever the network does: scSendFrame paces itself, and if
    // no new picture arrived the last one is sent again.
    while (g_running) {
        {
            std::lock_guard<std::mutex> g(lock);
            std::memcpy(sending.data(), latest.data(), frameBytes);
        }
        scSendFrame(camera, sending.data());
    }

    watcher.join();
    receiver.stop();
    scDeleteCamera(camera);
    return 0;
}
