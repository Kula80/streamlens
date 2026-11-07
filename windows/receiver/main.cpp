// streamlens-cli: StreamLens without a window, printing what happens. Used for testing and troubleshooting;
// people use StreamLens.exe (App.cpp), which runs the same Engine.

#include <windows.h>

#include <atomic>
#include <cstdio>
#include <string>
#include <thread>

#include "Engine.h"
#include "Slate.h"

namespace {

std::atomic<bool> g_running{true};

BOOL WINAPI onConsoleClose(DWORD) {
    g_running = false;
    return TRUE;
}

}  // namespace

int main(int argc, char** argv) {
    // Show messages straight away, also when the output goes to a log file.
    setvbuf(stdout, nullptr, _IONBF, 0);
    // Clicking in a console window starts "Select" mode, which freezes any program that then prints.
    HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
    DWORD consoleMode = 0;
    if (GetConsoleMode(input, &consoleMode)) {
        SetConsoleMode(input, (consoleMode | ENABLE_EXTENDED_FLAGS) & ~ENABLE_QUICK_EDIT_MODE);
    }

    Receiver::Config config;
    float fps = 30.0f;
    Engine::Mode mode = Engine::Mode::Automatic;
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
        } else if (a == "--show-screen") {
            mode = Engine::Mode::ShowNow;
        } else if (a == "--keep-hidden") {
            mode = Engine::Mode::KeepHidden;
        }
    }

    const std::wstring settingsPath = slateSettingsPath();
    Engine engine(config, fps, settingsPath, [](const Engine::Status& s) { std::printf("%s\n", s.text.c_str()); });
    if (!engine.start()) {
        std::printf("StreamLens Camera couldn't start. Is StreamLens already running?\n");
        return 1;
    }
    engine.setMode(mode);
    SetConsoleCtrlHandler(onConsoleClose, TRUE);
    std::printf("StreamLens Camera is on (%dx%d, %.0f fps). In OBS: + > Video Capture Device > StreamLens Camera.\n",
                config.outWidth, config.outHeight, fps);
    std::printf("Right-back screen settings: %ls\n", settingsPath.c_str());

    while (g_running) std::this_thread::sleep_for(std::chrono::milliseconds(200));
    engine.stop();
    return 0;
}
