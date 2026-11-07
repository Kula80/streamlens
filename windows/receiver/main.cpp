// StreamLens for Windows (milestone 1: console).
// Receives the phone's video and shows it as "StreamLens Camera", which OBS, Zoom and Teams can pick
// like any webcam. The phone finds this computer by itself, just as it finds OBS.

#include <windows.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "Receiver.h"
#include "softcam.h"

namespace {

std::atomic<bool> g_running{true};

BOOL WINAPI onConsoleClose(DWORD) {
    g_running = false;
    return TRUE;
}

// Until the right-back screen arrives (next milestone), a plain dark navy frame while waiting (NV12).
void fillWaiting(std::vector<uint8_t>& nv12, int width, int height) {
    const size_t luma = static_cast<size_t>(width) * height;
    std::memset(nv12.data(), 39, luma);
    for (size_t i = luma; i + 1 < nv12.size(); i += 2) {
        nv12[i] = 135;      // U
        nv12[i + 1] = 127;  // V
    }
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

    const size_t frameBytes = static_cast<size_t>(config.outWidth) * config.outHeight * 3 / 2;  // NV12
    std::mutex lock;
    std::vector<uint8_t> latest(frameBytes);
    std::vector<uint8_t> sending(frameBytes);
    fillWaiting(latest, config.outWidth, config.outHeight);
    // A last frame from a dropped connection can arrive just after "waiting"; it mustn't replace the screen.
    bool live = false;
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
            std::string message = live ? "Phone connected: " + detail
                                  : !detail.empty() ? detail
                                  : "Waiting for the phone on port " + std::to_string(config.port) + "...";
            // Retries repeat the same state; say it once.
            if (message != lastMessage) std::printf("%s\n", message.c_str());
            lastMessage = message;
            if (!live) fillWaiting(latest, config.outWidth, config.outHeight);
        });

    std::printf("StreamLens Camera is on (%dx%d, %.0f fps). In OBS: + > Video Capture Device > StreamLens Camera.\n",
                config.outWidth, config.outHeight, fps);
    receiver.start();

    // The camera keeps a steady frame rate whatever the network does: scSendFrame paces itself, and if
    // no new picture arrived the last one is sent again.
    while (g_running) {
        {
            std::lock_guard<std::mutex> g(lock);
            std::memcpy(sending.data(), latest.data(), frameBytes);
        }
        scSendFrame(camera, sending.data());
    }

    receiver.stop();
    scDeleteCamera(camera);
    return 0;
}
