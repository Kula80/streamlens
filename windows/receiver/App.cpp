// StreamLens for Windows: the app people install.
//
// Runs StreamLens Camera (see Engine) and shows a window where people see whether the phone is connected,
// get help when it can't connect, and set up the right-back screen with a live preview. Closing the window
// keeps StreamLens running next to the clock, so the camera keeps working in OBS.
//
// The window's contents are a small web page (ui\index.html) shown with Microsoft WebView2. The page and
// this file talk through short messages: the page sends "command?key=value&...", the app sends JSON.

#define NOMINMAX
#include <winsock2.h>  // before windows.h, which would otherwise pull in the old winsock.h
#include <windows.h>

#include <iphlpapi.h>
#include <netfw.h>
#include <netlistmgr.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <wrl.h>

#include <algorithm>
#include <cstdio>
#include <deque>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "Engine.h"
#include "Slate.h"
#include "WebView2.h"

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace {

constexpr wchar_t kWindowClass[] = L"StreamLensWindow";
constexpr wchar_t kMutexName[] = L"StreamLensAppMutex";  // also used by the installer to see if it's running
constexpr wchar_t kRunKey[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
constexpr UINT WM_TRAY = WM_APP + 1;
constexpr UINT WM_ENGINE_STATUS = WM_APP + 2;
constexpr UINT WM_SHOW_WINDOW = WM_APP + 3;
constexpr UINT_PTR kNetworkTimer = 1;
constexpr UINT_PTR kPreviewTimer = 2;  // live camera preview, about 10 pictures a second
constexpr UINT kTrayOpen = 1, kTrayQuit = 2;

HWND g_window = nullptr;
std::unique_ptr<Engine> g_engine;
ComPtr<ICoreWebView2Controller> g_controller;
ComPtr<ICoreWebView2> g_webview;
NOTIFYICONDATAW g_tray{};
std::wstring g_settingsPath, g_exePath, g_dataDir;
std::deque<std::string> g_events;  // recent status messages, newest last
std::string g_lastNetwork;
bool g_quitting = false, g_trayHintShown = false, g_restartNeeded = false;
HANDLE g_mutex = nullptr;

// ---- Text helpers ---------------------------------------------------------------------------------

std::string utf8(const std::wstring& w) {
    int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), s.data(), n, nullptr, nullptr);
    return s;
}

std::wstring wide(const std::string& s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), w.data(), n);
    return w;
}

std::string json(const std::string& s) {
    std::string out = "\"";
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof buf, "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out + "\"";
}
std::string json(const std::wstring& s) { return json(utf8(s)); }
const char* json(bool b) { return b ? "true" : "false"; }

// "a=1&b=hello%20there" (as made by the page's URLSearchParams) → map of UTF-8 strings.
std::map<std::string, std::string> parseQuery(const std::string& q) {
    std::map<std::string, std::string> out;
    size_t pos = 0;
    while (pos <= q.size()) {
        size_t end = q.find('&', pos);
        if (end == std::string::npos) end = q.size();
        std::string pair = q.substr(pos, end - pos);
        size_t eq = pair.find('=');
        auto decode = [](const std::string& s) {
            std::string r;
            for (size_t i = 0; i < s.size(); i++) {
                if (s[i] == '+') r += ' ';
                else if (s[i] == '%' && i + 2 < s.size()) {
                    r += static_cast<char>(std::stoi(s.substr(i + 1, 2), nullptr, 16));
                    i += 2;
                } else r += s[i];
            }
            return r;
        };
        if (!pair.empty()) out[decode(pair.substr(0, eq))] = eq == std::string::npos ? "" : decode(pair.substr(eq + 1));
        pos = end + 1;
    }
    return out;
}

// ---- Settings and preferences ---------------------------------------------------------------------

SlateSettings settingsFrom(std::map<std::string, std::string>& q) {
    SlateSettings s;
    if (!q["message"].empty()) s.message = wide(q["message"]);
    s.secondLine = wide(q["secondLine"]);
    s.churchName = wide(q["churchName"]);
    s.logo = wide(q["logo"]);
    s.photo = wide(q["photo"]);
    s.audioContinues = q["audio"] == "1";
    const std::string& c = q["colour"];
    if (c.size() == 7 && c[0] == '#') s.brandColour = std::stoul(c.substr(1), nullptr, 16);
    return s;
}

std::string colourHex(uint32_t rgb) {
    char buf[8];
    std::snprintf(buf, sizeof buf, "#%06X", rgb & 0xFFFFFF);
    return buf;
}

bool autostartOn() {
    wchar_t value[MAX_PATH * 2];
    DWORD size = sizeof value;
    return RegGetValueW(HKEY_CURRENT_USER, kRunKey, L"StreamLens", RRF_RT_REG_SZ, nullptr, value, &size) == ERROR_SUCCESS;
}

void setAutostart(bool on) {
    HKEY key;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, kRunKey, 0, KEY_SET_VALUE, &key) != ERROR_SUCCESS) return;
    if (on) {
        std::wstring command = L"\"" + g_exePath + L"\" --minimized";
        RegSetValueExW(key, L"StreamLens", 0, REG_SZ, reinterpret_cast<const BYTE*>(command.c_str()),
                       static_cast<DWORD>((command.size() + 1) * sizeof(wchar_t)));
    } else {
        RegDeleteValueW(key, L"StreamLens");
    }
    RegCloseKey(key);
}

// ---- Network: this computer's addresses, and whether Windows calls the network "Public" -------------

struct BlockedNetwork {
    std::string name;
    bool isPublic;
};

struct NetworkInfo {
    std::vector<std::string> addresses;  // e.g. "192.168.1.20 (Wi-Fi)"
    std::vector<BlockedNetwork> blocked;   // connected networks where Windows Firewall would stop the phone
};

// Whether Windows Firewall lets the phone reach this StreamLens.exe on networks of this kind
// (NET_FW_PROFILE2_PUBLIC, _PRIVATE or _DOMAIN). Block rules win over allow rules, as in Windows.
bool firewallAllows(INetFwPolicy2* fw, NET_FW_PROFILE_TYPE2 profile) {
    VARIANT_BOOL on = VARIANT_FALSE;
    if (FAILED(fw->get_FirewallEnabled(profile, &on)) || on == VARIANT_FALSE) return true;
    NET_FW_ACTION fallback = NET_FW_ACTION_BLOCK;
    fw->get_DefaultInboundAction(profile, &fallback);
    bool allow = fallback == NET_FW_ACTION_ALLOW, block = false;

    ComPtr<INetFwRules> rules;
    ComPtr<IUnknown> unknown;
    ComPtr<IEnumVARIANT> each;
    if (FAILED(fw->get_Rules(&rules)) || FAILED(rules->get__NewEnum(&unknown)) || FAILED(unknown.As(&each))) return true;
    VARIANT v;
    VariantInit(&v);
    while (each->Next(1, &v, nullptr) == S_OK) {
        ComPtr<INetFwRule> rule;
        if (v.vt == VT_DISPATCH && SUCCEEDED(v.pdispVal->QueryInterface(IID_PPV_ARGS(&rule)))) {
            BSTR app = nullptr;
            NET_FW_RULE_DIRECTION direction;
            VARIANT_BOOL enabled;
            long profiles = 0;
            NET_FW_ACTION action;
            if (SUCCEEDED(rule->get_ApplicationName(&app)) && app && _wcsicmp(app, g_exePath.c_str()) == 0 &&
                SUCCEEDED(rule->get_Direction(&direction)) && direction == NET_FW_RULE_DIR_IN &&
                SUCCEEDED(rule->get_Enabled(&enabled)) && enabled == VARIANT_TRUE &&
                SUCCEEDED(rule->get_Profiles(&profiles)) && (profiles & profile) &&
                SUCCEEDED(rule->get_Action(&action))) {
                (action == NET_FW_ACTION_ALLOW ? allow : block) = true;
            }
            SysFreeString(app);
        }
        VariantClear(&v);
    }
    return allow && !block;
}

NetworkInfo readNetwork() {
    NetworkInfo info;
    ULONG size = 16 * 1024;
    std::vector<uint8_t> buffer(size);
    auto* adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
    if (GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
                             nullptr, adapters, &size) == ERROR_BUFFER_OVERFLOW) {
        buffer.resize(size);
        adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(buffer.data());
        GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER, nullptr,
                             adapters, &size);
    }
    for (auto* a = adapters; a; a = a->Next) {
        if (a->OperStatus != IfOperStatusUp || a->IfType == IF_TYPE_SOFTWARE_LOOPBACK) continue;
        for (auto* u = a->FirstUnicastAddress; u; u = u->Next) {
            auto* sin = reinterpret_cast<sockaddr_in*>(u->Address.lpSockaddr);
            const uint8_t* b = reinterpret_cast<const uint8_t*>(&sin->sin_addr);
            if (b[0] == 169 && b[1] == 254) continue;  // no network yet
            char ip[32];
            std::snprintf(ip, sizeof ip, "%u.%u.%u.%u", b[0], b[1], b[2], b[3]);
            info.addresses.push_back(std::string(ip) + " (" + utf8(a->FriendlyName) + ")");
        }
    }

    ComPtr<INetworkListManager> nlm;
    ComPtr<INetFwPolicy2> fw;
    CoCreateInstance(__uuidof(NetFwPolicy2), nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&fw));
    if (SUCCEEDED(CoCreateInstance(CLSID_NetworkListManager, nullptr, CLSCTX_ALL, IID_PPV_ARGS(&nlm)))) {
        ComPtr<IEnumNetworks> networks;
        if (SUCCEEDED(nlm->GetNetworks(NLM_ENUM_NETWORK_CONNECTED, &networks))) {
            ComPtr<INetwork> n;
            while (networks->Next(1, &n, nullptr) == S_OK) {
                NLM_NETWORK_CATEGORY category;
                BSTR name = nullptr;
                if (fw && SUCCEEDED(n->GetCategory(&category)) && SUCCEEDED(n->GetName(&name))) {
                    NET_FW_PROFILE_TYPE2 profile = category == NLM_NETWORK_CATEGORY_PUBLIC ? NET_FW_PROFILE2_PUBLIC
                                                   : category == NLM_NETWORK_CATEGORY_PRIVATE ? NET_FW_PROFILE2_PRIVATE
                                                                                              : NET_FW_PROFILE2_DOMAIN;
                    if (!firewallAllows(fw.Get(), profile)) {
                        info.blocked.push_back({utf8(name), category == NLM_NETWORK_CATEGORY_PUBLIC});
                    }
                }
                SysFreeString(name);
                n.Reset();
            }
        }
    }
    return info;
}

// ---- Talking to the page --------------------------------------------------------------------------

void post(const std::string& message) {
    if (g_webview) g_webview->PostWebMessageAsJson(wide(message).c_str());
}

void sendStatus() {
    Engine::Status s = g_engine ? g_engine->status() : Engine::Status{};
    std::string events = "[";
    for (size_t i = 0; i < g_events.size(); i++) events += (i ? "," : "") + json(g_events[i]);
    events += "]";
    post("{\"type\":\"status\",\"live\":" + std::string(json(s.live)) + ",\"everConnected\":" + json(s.everConnected) +
         ",\"text\":" + json(s.text) + ",\"events\":" + events + "}");
}

void sendNetwork(bool force) {
    NetworkInfo n = readNetwork();
    std::string addresses = "[", blocked = "[";
    for (size_t i = 0; i < n.addresses.size(); i++) addresses += (i ? "," : "") + json(n.addresses[i]);
    for (size_t i = 0; i < n.blocked.size(); i++) {
        blocked += std::string(i ? "," : "") + "{\"name\":" + json(n.blocked[i].name) + ",\"public\":" +
                   json(n.blocked[i].isPublic) + "}";
    }
    std::string message = "{\"type\":\"network\",\"addresses\":" + addresses + "],\"blocked\":" + blocked + "]}";
    if (force || message != g_lastNetwork) post(message);
    g_lastNetwork = message;
}

void sendMode() {
    const char* names[] = {"automatic", "show", "hide"};
    post(std::string("{\"type\":\"mode\",\"value\":\"") + names[static_cast<int>(g_engine->mode())] + "\"}");
}

void sendPrefs() {
    AppPrefs p = loadAppPrefs(g_settingsPath);
    post(std::string("{\"type\":\"prefs\",\"camera1080p\":") + json(p.camera1080p) + ",\"autostart\":" +
         json(autostartOn()) + ",\"restartNeeded\":" + json(g_restartNeeded) + "}");
}

void sendSettings() {
    SlateSettings s = loadSlateSettings(g_settingsPath);
    post("{\"type\":\"settings\",\"message\":" + json(s.message) + ",\"secondLine\":" + json(s.secondLine) +
         ",\"churchName\":" + json(s.churchName) + ",\"colour\":" + json(colourHex(s.brandColour)) +
         ",\"logo\":" + json(s.logo) + ",\"photo\":" + json(s.photo) + ",\"audio\":" + json(s.audioContinues) + "}");
}

// The picture StreamLens Camera is sending right now, like Iriun's preview. Only while the window is on
// screen, so it costs nothing during a service with the window closed.
void sendCameraPreview() {
    if (!g_webview || !g_engine || !IsWindowVisible(g_window) || IsIconic(g_window)) return;
    static std::vector<uint8_t> frame;
    g_engine->copyShownFrame(frame);
    Engine::Status s = g_engine->status();
    const Engine::Mode mode = g_engine->mode();
    const char* showing = mode == Engine::Mode::ShowNow ? "screen-manual"
                          : s.live ? "phone"
                          : mode == Engine::Mode::KeepHidden && s.everConnected ? "frozen"
                                                                                : "screen";
    post(std::string("{\"type\":\"camera\",\"showing\":\"") + showing + "\",\"url\":" +
         json(cameraPreview(frame.data(), g_engine->width(), g_engine->height(), 854, 480)) + "}");
}

void sendPreview(const SlateSettings& s) {
    post("{\"type\":\"preview\",\"url\":" + json(renderSlatePreview(s, L"Reconnecting", 960, 540)) + "}");
}

std::wstring pickImage() {
    std::wstring result;
    ComPtr<IFileOpenDialog> dialog;
    if (FAILED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dialog)))) return result;
    COMDLG_FILTERSPEC types[] = {{L"Pictures", L"*.png;*.jpg;*.jpeg;*.bmp;*.gif"}};
    dialog->SetFileTypes(1, types);
    dialog->SetTitle(L"Choose a picture");
    if (SUCCEEDED(dialog->Show(g_window))) {
        ComPtr<IShellItem> item;
        PWSTR path = nullptr;
        if (SUCCEEDED(dialog->GetResult(&item)) && SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path))) {
            result = path;
            CoTaskMemFree(path);
        }
    }
    return result;
}

void restartApp() {
    g_engine->stop();  // frees StreamLens Camera for the new copy
    ReleaseMutex(g_mutex);
    CloseHandle(g_mutex);
    g_mutex = nullptr;
    ShellExecuteW(nullptr, L"open", g_exePath.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
    g_quitting = true;
    DestroyWindow(g_window);
}

void onPageMessage(const std::string& message) {
    size_t q = message.find('?');
    std::string command = message.substr(0, q);
    auto args = parseQuery(q == std::string::npos ? "" : message.substr(q + 1));

    if (command == "ready") {
        sendSettings();
        sendPrefs();
        sendMode();
        sendStatus();
        sendNetwork(true);
        sendPreview(loadSlateSettings(g_settingsPath));
    } else if (command == "preview") {
        sendPreview(settingsFrom(args));
    } else if (command == "save") {
        // The camera picks up the saved file by itself within half a second.
        SlateSettings s = settingsFrom(args);
        saveSettings(g_settingsPath, s, loadAppPrefs(g_settingsPath));
        sendPreview(s);
    } else if (command == "pick") {
        std::wstring path = pickImage();
        if (!path.empty()) post("{\"type\":\"picked\",\"field\":" + json(args["field"]) + ",\"path\":" + json(path) + "}");
    } else if (command == "mode") {
        const std::string& v = args["value"];
        g_engine->setMode(v == "show" ? Engine::Mode::ShowNow : v == "hide" ? Engine::Mode::KeepHidden : Engine::Mode::Automatic);
        sendMode();
    } else if (command == "autostart") {
        setAutostart(args["on"] == "1");
        sendPrefs();
    } else if (command == "camerasize") {
        AppPrefs p = loadAppPrefs(g_settingsPath);
        bool want1080p = args["value"] == "1080p";
        if (p.camera1080p != want1080p) {
            p.camera1080p = want1080p;
            saveSettings(g_settingsPath, loadSlateSettings(g_settingsPath), p);
            g_restartNeeded = !g_restartNeeded;
        }
        sendPrefs();
    } else if (command == "restart") {
        restartApp();
    } else if (command == "networksettings") {
        ShellExecuteW(nullptr, L"open", L"ms-settings:network-status", nullptr, nullptr, SW_SHOWNORMAL);
    } else if (command == "quit") {
        g_quitting = true;
        DestroyWindow(g_window);
    }
}

// ---- Window, tray and WebView2 ----------------------------------------------------------------------

void showWindow() {
    ShowWindow(g_window, IsIconic(g_window) ? SW_RESTORE : SW_SHOW);
    SetForegroundWindow(g_window);
}

void addTrayIcon() {
    g_tray.cbSize = sizeof g_tray;
    g_tray.hWnd = g_window;
    g_tray.uID = 1;
    g_tray.uFlags = NIF_ICON | NIF_TIP | NIF_MESSAGE;
    g_tray.uCallbackMessage = WM_TRAY;
    g_tray.hIcon = static_cast<HICON>(LoadImageW(GetModuleHandleW(nullptr), MAKEINTRESOURCEW(1), IMAGE_ICON,
                                                 GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0));
    wcscpy_s(g_tray.szTip, L"StreamLens: phone camera");
    Shell_NotifyIconW(NIM_ADD, &g_tray);
}

void trayBalloon(const wchar_t* title, const wchar_t* text) {
    g_tray.uFlags = NIF_INFO;
    wcscpy_s(g_tray.szInfoTitle, title);
    wcscpy_s(g_tray.szInfo, text);
    g_tray.dwInfoFlags = NIIF_INFO;
    Shell_NotifyIconW(NIM_MODIFY, &g_tray);
    g_tray.uFlags = NIF_ICON | NIF_TIP | NIF_MESSAGE;
}

void createWebView() {
    std::wstring uiDir = g_exePath.substr(0, g_exePath.find_last_of(L'\\')) + L"\\ui";
    std::wstring userData = g_dataDir + L"\\WebView2";
    HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
        nullptr, userData.c_str(), nullptr,
        Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
            [uiDir](HRESULT result, ICoreWebView2Environment* env) -> HRESULT {
                if (FAILED(result)) return result;
                return env->CreateCoreWebView2Controller(
                    g_window, Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                                  [uiDir](HRESULT result, ICoreWebView2Controller* controller) -> HRESULT {
                                      if (FAILED(result) || !controller) return result;
                                      g_controller = controller;
                                      g_controller->get_CoreWebView2(&g_webview);
                                      ComPtr<ICoreWebView2Settings> settings;
                                      g_webview->get_Settings(&settings);
                                      settings->put_AreDefaultContextMenusEnabled(FALSE);
                                      settings->put_IsStatusBarEnabled(FALSE);
                                      settings->put_IsZoomControlEnabled(FALSE);
                                      RECT bounds;
                                      GetClientRect(g_window, &bounds);
                                      g_controller->put_Bounds(bounds);

                                      g_webview->add_WebMessageReceived(
                                          Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                                              [](ICoreWebView2*, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                                                  LPWSTR text = nullptr;
                                                  if (SUCCEEDED(args->TryGetWebMessageAsString(&text)) && text) {
                                                      onPageMessage(utf8(text));
                                                      CoTaskMemFree(text);
                                                  }
                                                  return S_OK;
                                              })
                                              .Get(),
                                          nullptr);

                                      ComPtr<ICoreWebView2_3> webview3;
                                      if (SUCCEEDED(g_webview.As(&webview3))) {
                                          webview3->SetVirtualHostNameToFolderMapping(
                                              L"app.streamlens", uiDir.c_str(), COREWEBVIEW2_HOST_RESOURCE_ACCESS_KIND_DENY);
                                      }
                                      g_webview->Navigate(L"https://app.streamlens/index.html");
                                      return S_OK;
                                  })
                                  .Get());
            })
            .Get());
    if (FAILED(hr)) {
        MessageBoxW(g_window,
                    L"StreamLens needs Microsoft Edge WebView2 to show its window. StreamLens Camera keeps working.\n\n"
                    L"Get it free from: https://go.microsoft.com/fwlink/p/?LinkId=2124703",
                    L"StreamLens", MB_ICONINFORMATION);
    }
}

LRESULT CALLBACK windowProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_SIZE:
            if (g_controller) {
                RECT bounds;
                GetClientRect(hwnd, &bounds);
                g_controller->put_Bounds(bounds);
                // A minimised window's page is paused, so it uses no processor time.
                g_controller->put_IsVisible(wp != SIZE_MINIMIZED);
            }
            return 0;
        case WM_SHOWWINDOW:
            // Same when the window is closed to the tray (hidden).
            if (g_controller) g_controller->put_IsVisible(wp ? TRUE : FALSE);
            break;
        case WM_GETMINMAXINFO: {
            auto* info = reinterpret_cast<MINMAXINFO*>(lp);
            const UINT dpi = GetDpiForWindow(hwnd);
            info->ptMinTrackSize = {MulDiv(760, dpi, 96), MulDiv(560, dpi, 96)};
            return 0;
        }
        case WM_CLOSE:
            if (g_quitting) break;
            // Keep running next to the clock: OBS still needs the camera.
            ShowWindow(hwnd, SW_HIDE);
            if (!g_trayHintShown) {
                g_trayHintShown = true;
                trayBalloon(L"StreamLens is still running",
                            L"StreamLens Camera keeps working. Open or quit StreamLens from this icon.");
            }
            return 0;
        case WM_TRAY:
            if (LOWORD(lp) == WM_LBUTTONUP || LOWORD(lp) == WM_LBUTTONDBLCLK) {
                showWindow();
            } else if (LOWORD(lp) == WM_RBUTTONUP || LOWORD(lp) == WM_CONTEXTMENU) {
                HMENU menu = CreatePopupMenu();
                AppendMenuW(menu, MF_STRING, kTrayOpen, L"Open StreamLens");
                AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
                AppendMenuW(menu, MF_STRING, kTrayQuit, L"Quit StreamLens (turns StreamLens Camera off)");
                POINT pt;
                GetCursorPos(&pt);
                SetForegroundWindow(hwnd);
                UINT choice = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, pt.x, pt.y, 0, hwnd, nullptr);
                DestroyMenu(menu);
                if (choice == kTrayOpen) showWindow();
                if (choice == kTrayQuit) {
                    g_quitting = true;
                    DestroyWindow(hwnd);
                }
            }
            return 0;
        case WM_SHOW_WINDOW:
            showWindow();
            return 0;
        case WM_ENGINE_STATUS: {
            Engine::Status s = g_engine->status();
            g_events.push_back(s.text);
            while (g_events.size() > 8) g_events.pop_front();
            wcscpy_s(g_tray.szTip, s.live ? L"StreamLens: phone connected" : L"StreamLens: waiting for the phone");
            Shell_NotifyIconW(NIM_MODIFY, &g_tray);
            sendStatus();
            return 0;
        }
        case WM_TIMER:
            if (wp == kNetworkTimer) sendNetwork(false);
            if (wp == kPreviewTimer) sendCameraPreview();
            return 0;
        case WM_DESTROY:
            KillTimer(hwnd, kNetworkTimer);
            KillTimer(hwnd, kPreviewTimer);
            Shell_NotifyIconW(NIM_DELETE, &g_tray);
            g_controller.Reset();
            g_webview.Reset();
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

std::wstring appDataDir() {
    wchar_t* local = nullptr;
    std::wstring dir = L".";
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &local))) dir = local;
    CoTaskMemFree(local);
    dir += L"\\StreamLens";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir;
}

}  // namespace

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR cmdLine, int) {
    // Only one StreamLens at a time (there is only one StreamLens Camera): bring the open one forward.
    g_mutex = CreateMutexW(nullptr, TRUE, kMutexName);
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        if (HWND existing = FindWindowW(kWindowClass, nullptr)) PostMessageW(existing, WM_SHOW_WINDOW, 0, 0);
        return 0;
    }

    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    wchar_t exe[MAX_PATH];
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    g_exePath = exe;
    g_dataDir = appDataDir();
    g_settingsPath = slateSettingsPath();
    const bool minimized = cmdLine && wcsstr(cmdLine, L"--minimized");

    WNDCLASSEXW wc{sizeof wc};
    wc.lpfnWndProc = windowProc;
    wc.hInstance = instance;
    wc.hIcon = LoadIconW(instance, MAKEINTRESOURCEW(1));
    wc.hIconSm = wc.hIcon;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = CreateSolidBrush(RGB(0x16, 0x17, 0x1F));
    wc.lpszClassName = kWindowClass;
    RegisterClassExW(&wc);

    const UINT dpi = GetDpiForSystem();
    g_window = CreateWindowExW(0, kWindowClass, L"StreamLens", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                               MulDiv(1040, dpi, 96), MulDiv(760, dpi, 96), nullptr, nullptr, instance, nullptr);
    addTrayIcon();

    AppPrefs prefs = loadAppPrefs(g_settingsPath);
    Receiver::Config config;
    if (prefs.camera1080p) {
        config.outWidth = 1920;
        config.outHeight = 1080;
    }
    g_engine = std::make_unique<Engine>(config, 30.0f, g_settingsPath, [](const Engine::Status&) {
        PostMessageW(g_window, WM_ENGINE_STATUS, 0, 0);  // engine threads → window thread
    });
    if (!g_engine->start()) {
        MessageBoxW(g_window,
                    L"StreamLens Camera couldn't start. Is another copy of StreamLens open?\n\n"
                    L"If StreamLens was just installed, try restarting the computer.",
                    L"StreamLens", MB_ICONWARNING);
    }

    if (!minimized) ShowWindow(g_window, SW_SHOW);
    createWebView();
    SetTimer(g_window, kNetworkTimer, 3000, nullptr);
    SetTimer(g_window, kPreviewTimer, 100, nullptr);

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    g_engine.reset();
    if (g_mutex) CloseHandle(g_mutex);
    CoUninitialize();
    return 0;
}
