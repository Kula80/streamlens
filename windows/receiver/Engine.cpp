#include "Engine.h"

#include <windows.h>

#include <chrono>
#include <cstring>

#include "softcam.h"

namespace {

FILETIME lastWrite(const std::wstring& path) {
    WIN32_FILE_ATTRIBUTE_DATA data{};
    GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &data);
    return data.ftLastWriteTime;
}

}  // namespace

Engine::Engine(Receiver::Config config, float fps, std::wstring settingsPath, StatusFn onStatus)
    : config_(config), fps_(fps), settingsPath_(std::move(settingsPath)), onStatus_(std::move(onStatus)) {
    frameBytes_ = static_cast<size_t>(config_.outWidth) * config_.outHeight * 3 / 2;  // NV12
}

Engine::~Engine() { stop(); }

std::shared_ptr<const Engine::Screens> Engine::drawScreens() {
    SlateSettings settings = loadSlateSettings(settingsPath_);
    auto s = std::make_shared<Screens>();
    s->waiting = renderSlate(settings, L"Waiting for the camera", config_.outWidth, config_.outHeight);
    s->reconnecting = renderSlate(settings, L"Reconnecting", config_.outWidth, config_.outHeight);
    s->standBy = renderSlate(settings, L"Please stand by", config_.outWidth, config_.outHeight);
    return s;
}

bool Engine::start() {
    camera_ = scCreateCamera(config_.outWidth, config_.outHeight, fps_);
    if (!camera_) return false;
    screens_ = drawScreens();
    liveFrame_.assign(frameBytes_, 0);
    running_ = true;

    receiver_ = std::make_unique<Receiver>(
        config_,
        [this](const uint8_t* nv12) {
            std::lock_guard<std::mutex> g(lock_);
            // A last frame from a dropped connection can arrive just after the phone is declared gone.
            if (!status_.live) return;
            std::memcpy(liveFrame_.data(), nv12, frameBytes_);
            haveLiveFrame_ = true;
        },
        [this](Receiver::State state, const std::string& detail) {
            std::string text;
            {
                std::lock_guard<std::mutex> g(lock_);
                status_.live = state == Receiver::State::Live;
                status_.everConnected = status_.everConnected || status_.live;
                text = status_.live ? "Phone connected: " + detail
                       : !detail.empty() ? detail
                       : status_.everConnected ? "Phone dropped: viewers see the right-back screen. Waiting for it to come back..."
                                               : "Waiting for the phone on port " + std::to_string(config_.port) + "...";
            }
            report(text);
        });
    receiver_->start();
    sender_ = std::thread([this] { sendLoop(); });
    watcher_ = std::thread([this] { watchSettings(); });
    return true;
}

void Engine::stop() {
    if (!running_.exchange(false)) return;
    if (watcher_.joinable()) watcher_.join();
    if (sender_.joinable()) sender_.join();
    receiver_.reset();
    scDeleteCamera(camera_);
    camera_ = nullptr;
}

void Engine::setMode(Mode mode) { mode_ = mode; }

void Engine::reloadScreen() {
    auto fresh = drawScreens();
    std::lock_guard<std::mutex> g(lock_);
    screens_ = fresh;
}

Engine::Status Engine::status() {
    std::lock_guard<std::mutex> g(lock_);
    return status_;
}

void Engine::report(std::string text) {
    Status copy;
    {
        std::lock_guard<std::mutex> g(lock_);
        // Retries repeat the same state; report it once.
        if (text == status_.text) return;
        status_.text = std::move(text);
        copy = status_;
    }
    if (onStatus_) onStatus_(copy);
}

const std::vector<uint8_t>& Engine::shownLocked() {
    const Mode mode = mode_;
    if (mode == Mode::ShowNow) return screens_->standBy;
    if (status_.live || (mode == Mode::KeepHidden && haveLiveFrame_)) return liveFrame_;
    return status_.everConnected ? screens_->reconnecting : screens_->waiting;
}

void Engine::copyShownFrame(std::vector<uint8_t>& out) {
    out.resize(frameBytes_);
    std::lock_guard<std::mutex> g(lock_);
    if (!screens_) return;  // camera never started
    std::memcpy(out.data(), shownLocked().data(), frameBytes_);
}

void Engine::sendLoop() {
    std::vector<uint8_t> sending(frameBytes_);
    while (running_) {
        {
            std::lock_guard<std::mutex> g(lock_);
            std::memcpy(sending.data(), shownLocked().data(), frameBytes_);
        }
        // Keeps a steady frame rate whatever the network does: it paces itself, and if no new picture
        // arrived the last one is sent again.
        scSendFrame(camera_, sending.data());
    }
}

void Engine::watchSettings() {
    // Also pick up edits made to the settings file by hand.
    FILETIME seen = lastWrite(settingsPath_);
    while (running_) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        FILETIME now = lastWrite(settingsPath_);
        if (CompareFileTime(&now, &seen) == 0) continue;
        seen = now;
        reloadScreen();
    }
}
