#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

struct AVFrame;

// Waits for the phone as an SRT listener, decodes its video (on the graphics card when possible) and
// hands out frames already fitted to the camera's size. When the phone stops, it goes back to waiting.
//
// Two threads, so a slow computer drops frames instead of falling behind: one receives and decodes and
// keeps only the newest picture; the other turns that picture into a camera frame.
class Receiver {
public:
    enum class State { Waiting, Live };

    struct Config {
        int port = 9710;
        int latencyMs = 300;
        int outWidth = 3840;
        int outHeight = 2160;
    };

    // nv12: outWidth * outHeight * 3 / 2 bytes (Y plane, then interleaved UV). Called on the output thread.
    using FrameFn = std::function<void(const uint8_t* nv12)>;
    // detail: e.g. "3840x2160 HEVC (graphics card)" when live.
    using StateFn = std::function<void(State, const std::string& detail)>;

    Receiver(Config config, FrameFn onFrame, StateFn onState);
    ~Receiver();

    void start();
    void stop();

private:
    void receiveLoop();
    // Returns false if the port couldn't even be opened.
    bool session();
    void outputLoop();
    void offer(AVFrame* frame, const std::string& detail);

    Config config_;
    FrameFn onFrame_;
    StateFn onState_;
    std::thread receiveThread_;
    std::thread outputThread_;
    std::atomic<bool> stopping_{false};

    // Newest decoded picture, waiting for the output thread. Older ones are simply replaced.
    std::mutex pendingLock_;
    std::condition_variable pendingReady_;
    AVFrame* pending_ = nullptr;
    std::string pendingDetail_;
    bool announce_ = false;
};
