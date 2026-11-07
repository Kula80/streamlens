#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Receiver.h"
#include "Slate.h"

// Everything behind StreamLens Camera: receives the phone, feeds the camera at a steady frame rate, and shows
// the branded right-back screen whenever there's no phone video (or when asked to).
class Engine {
public:
    // Automatic: right-back screen whenever the phone is gone. ShowNow: always the screen.
    // KeepHidden: never the screen; viewers keep seeing the last camera picture.
    enum class Mode { Automatic, ShowNow, KeepHidden };

    struct Status {
        bool live = false;
        bool everConnected = false;
        std::string text;  // plain words, e.g. "Phone connected: 3840x2160 HEVC (graphics card)"
    };
    using StatusFn = std::function<void(const Status&)>;

    Engine(Receiver::Config config, float fps, std::wstring settingsPath, StatusFn onStatus);
    ~Engine();

    // False if StreamLens Camera couldn't be created (for example, another StreamLens is running).
    bool start();
    void stop();

    void setMode(Mode mode);
    Mode mode() const { return mode_; }
    // Re-reads the settings file and redraws the right-back screen.
    void reloadScreen();
    Status status();

    // Copies the picture StreamLens Camera is showing right now (NV12, width() × height()), for the
    // preview in the StreamLens window.
    void copyShownFrame(std::vector<uint8_t>& out);
    int width() const { return config_.outWidth; }
    int height() const { return config_.outHeight; }

private:
    struct Screens {
        std::vector<uint8_t> waiting;       // before the phone has connected
        std::vector<uint8_t> reconnecting;  // after the phone dropped
        std::vector<uint8_t> standBy;       // shown on request ("Show now")
    };
    std::shared_ptr<const Screens> drawScreens();
    // What the camera shows now: phone, right-back screen, or the last phone picture. Needs lock_.
    const std::vector<uint8_t>& shownLocked();
    void sendLoop();
    void watchSettings();
    void report(std::string text);

    Receiver::Config config_;
    float fps_;
    std::wstring settingsPath_;
    StatusFn onStatus_;
    void* camera_ = nullptr;
    std::unique_ptr<Receiver> receiver_;
    std::thread sender_;
    std::thread watcher_;
    std::atomic<bool> running_{false};
    std::atomic<Mode> mode_{Mode::Automatic};

    std::mutex lock_;
    size_t frameBytes_ = 0;
    std::shared_ptr<const Screens> screens_;
    std::vector<uint8_t> liveFrame_;
    bool haveLiveFrame_ = false;
    Status status_;
};
