#define NOMINMAX
#include "Slate.h"

#include <windows.h>
#include <shlobj.h>

#include <algorithm>
#include <cstring>
#include <cwctype>
#include <fstream>
#include <memory>
#include <sstream>

namespace Gdiplus {
using std::max;
using std::min;
}  // namespace Gdiplus
#include <gdiplus.h>

extern "C" {
#include <libswscale/swscale.h>
}

namespace {

const char kDefaultSettings[] =
    "\xEF\xBB\xBF"  // UTF-8 BOM, so Notepad keeps accents and other letters intact when saving
    "; StreamLens: what viewers see when the phone camera drops (the right-back screen).\r\n"
    "; Edit this file, save it, and StreamLens Camera updates within a few seconds.\r\n"
    ";\r\n"
    "; Leave a line empty to hide it. Logo and Photo are picture files, for example\r\n"
    ";   Logo=C:\\Users\\You\\Pictures\\church-logo.png\r\n"
    "; BrandColour is #RRGGBB. AudioContinues=yes shows \"Audio continues\" (when sound comes from the mixer).\r\n"
    "\r\n"
    "Message=Sorry for the inconvenience. We'll be right back.\r\n"
    "SecondLine=\r\n"
    "ChurchName=\r\n"
    "BrandColour=#3C3489\r\n"
    "Logo=\r\n"
    "Photo=\r\n"
    "AudioContinues=no\r\n";

std::wstring trim(std::wstring s) {
    auto notSpace = [](wchar_t c) { return !std::iswspace(c); };
    s.erase(s.begin(), std::find_if(s.begin(), s.end(), notSpace));
    s.erase(std::find_if(s.rbegin(), s.rend(), notSpace).base(), s.end());
    // People often paste paths copied with "Copy as path", which adds quotes.
    if (s.size() >= 2 && s.front() == L'"' && s.back() == L'"') s = s.substr(1, s.size() - 2);
    return s;
}

std::wstring readText(const std::wstring& path) {
    std::ifstream in(path, std::ios::binary);
    std::string bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (bytes.size() >= 2 && static_cast<uint8_t>(bytes[0]) == 0xFF && static_cast<uint8_t>(bytes[1]) == 0xFE) {
        return std::wstring(reinterpret_cast<const wchar_t*>(bytes.data() + 2), (bytes.size() - 2) / 2);
    }
    if (bytes.size() >= 3 && bytes.compare(0, 3, "\xEF\xBB\xBF") == 0) bytes.erase(0, 3);
    int n = MultiByteToWideChar(CP_UTF8, 0, bytes.data(), static_cast<int>(bytes.size()), nullptr, 0);
    std::wstring text(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, bytes.data(), static_cast<int>(bytes.size()), text.data(), n);
    return text;
}

uint32_t parseColour(const std::wstring& s, uint32_t fallback) {
    std::wstring hex = s;
    if (!hex.empty() && hex[0] == L'#') hex.erase(0, 1);
    if (hex.size() != 6 || !std::all_of(hex.begin(), hex.end(), [](wchar_t c) { return std::iswxdigit(c); })) {
        return fallback;
    }
    return static_cast<uint32_t>(std::wcstoul(hex.c_str(), nullptr, 16));
}

void ensureGdiplus() {
    static ULONG_PTR token = [] {
        ULONG_PTR t = 0;
        Gdiplus::GdiplusStartupInput input;
        Gdiplus::GdiplusStartup(&t, &input, nullptr);
        return t;
    }();
    (void)token;
}

std::unique_ptr<Gdiplus::Image> loadImage(const std::wstring& path) {
    if (path.empty()) return nullptr;
    auto img = std::make_unique<Gdiplus::Image>(path.c_str());
    if (img->GetLastStatus() != Gdiplus::Ok || img->GetWidth() == 0) return nullptr;
    return img;
}

}  // namespace

std::wstring slateSettingsPath() {
    wchar_t* appData = nullptr;
    std::wstring dir = L".";
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_RoamingAppData, 0, nullptr, &appData))) dir = appData;
    CoTaskMemFree(appData);
    dir += L"\\StreamLens";
    CreateDirectoryW(dir.c_str(), nullptr);
    std::wstring path = dir + L"\\settings.ini";
    if (GetFileAttributesW(path.c_str()) == INVALID_FILE_ATTRIBUTES) {
        std::ofstream(path, std::ios::binary) << kDefaultSettings;
    }
    return path;
}

SlateSettings loadSlateSettings(const std::wstring& path) {
    SlateSettings s;
    const SlateSettings defaults;
    std::wistringstream lines(readText(path));
    std::wstring line;
    while (std::getline(lines, line)) {
        line = trim(line);
        if (line.empty() || line[0] == L';' || line[0] == L'#' || line[0] == L'[') continue;
        auto eq = line.find(L'=');
        if (eq == std::wstring::npos) continue;
        std::wstring key = trim(line.substr(0, eq));
        std::wstring value = trim(line.substr(eq + 1));
        std::transform(key.begin(), key.end(), key.begin(), ::towlower);
        if (key == L"message") s.message = value.empty() ? defaults.message : value;
        else if (key == L"secondline") s.secondLine = value;
        else if (key == L"churchname") s.churchName = value;
        else if (key == L"brandcolour" || key == L"brandcolor") s.brandColour = parseColour(value, defaults.brandColour);
        else if (key == L"logo") s.logo = value;
        else if (key == L"photo") s.photo = value;
        else if (key == L"audiocontinues") s.audioContinues = value == L"yes" || value == L"1" || value == L"true";
    }
    return s;
}

AppPrefs loadAppPrefs(const std::wstring& path) {
    AppPrefs p;
    std::wistringstream lines(readText(path));
    std::wstring line;
    while (std::getline(lines, line)) {
        auto eq = line.find(L'=');
        if (eq == std::wstring::npos) continue;
        std::wstring key = trim(line.substr(0, eq));
        std::transform(key.begin(), key.end(), key.begin(), ::towlower);
        if (key == L"camerasize") p.camera1080p = trim(line.substr(eq + 1)) == L"1080p";
    }
    return p;
}

void saveSettings(const std::wstring& path, const SlateSettings& s, const AppPrefs& p) {
    // Each value must stay on one line of the file.
    auto oneLine = [](std::wstring v) {
        for (auto& c : v) {
            if (c == L'\r' || c == L'\n') c = L' ';
        }
        return trim(v);
    };
    wchar_t colour[8];
    swprintf(colour, 8, L"#%06X", s.brandColour & 0xFFFFFF);
    std::wstring body = L"Message=" + oneLine(s.message) + L"\r\nSecondLine=" + oneLine(s.secondLine) +
                        L"\r\nChurchName=" + oneLine(s.churchName) + L"\r\nBrandColour=" + colour +
                        L"\r\nLogo=" + oneLine(s.logo) + L"\r\nPhoto=" + oneLine(s.photo) +
                        L"\r\nAudioContinues=" + (s.audioContinues ? L"yes" : L"no") +
                        L"\r\n\r\n; Camera size: 4K, or 1080p for slower computers (takes effect when StreamLens restarts).\r\n"
                        L"CameraSize=" + (p.camera1080p ? L"1080p" : L"4K") + L"\r\n";
    // Keep the explanations at the top of the file, then the values.
    std::string header(kDefaultSettings, std::strstr(kDefaultSettings, "Message=") - kDefaultSettings);
    int n = WideCharToMultiByte(CP_UTF8, 0, body.data(), static_cast<int>(body.size()), nullptr, 0, nullptr, nullptr);
    std::string utf8(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, body.data(), static_cast<int>(body.size()), utf8.data(), n, nullptr, nullptr);
    // Write to a temporary file first, so a crash can never leave half a settings file.
    std::wstring temp = path + L".tmp";
    {
        std::ofstream out(temp, std::ios::binary | std::ios::trunc);
        out << header << utf8;
    }
    MoveFileExW(temp.c_str(), path.c_str(), MOVEFILE_REPLACE_EXISTING);
}

namespace {

std::unique_ptr<Gdiplus::Bitmap> drawSlate(const SlateSettings& settings, const std::wstring& status, int width, int height) {
    using namespace Gdiplus;
    ensureGdiplus();

    const float W = static_cast<float>(width), H = static_cast<float>(height);
    const BYTE r = (settings.brandColour >> 16) & 0xFF, g = (settings.brandColour >> 8) & 0xFF,
               b = settings.brandColour & 0xFF;

    auto result = std::make_unique<Bitmap>(width, height, PixelFormat32bppARGB);
    Bitmap& bitmap = *result;
    Graphics gfx(&bitmap);
    gfx.SetSmoothingMode(SmoothingModeAntiAlias);
    gfx.SetTextRenderingHint(TextRenderingHintAntiAliasGridFit);
    gfx.SetInterpolationMode(InterpolationModeHighQualityBicubic);

    // Background: brand colour, or the photo filling the frame under a 70% brand tint (like the OBS screen).
    SolidBrush brand(Color(255, r, g, b));
    gfx.FillRectangle(&brand, 0.0f, 0.0f, W, H);
    if (auto photo = loadImage(settings.photo)) {
        float scale = std::max(W / photo->GetWidth(), H / photo->GetHeight());
        float pw = photo->GetWidth() * scale, ph = photo->GetHeight() * scale;
        gfx.DrawImage(photo.get(), (W - pw) / 2, (H - ph) / 2, pw, ph);
        SolidBrush tint(Color(178, r, g, b));
        gfx.FillRectangle(&tint, 0.0f, 0.0f, W, H);
    }

    // Sizes follow the OBS screen, which uses % of the height.
    FontFamily family(L"Segoe UI");
    FontFamily semibold(L"Segoe UI Semibold");
    const FontFamily& messageFamily = semibold.GetLastStatus() == Ok ? semibold : family;
    Font nameFont(&family, H * 0.026f, FontStyleRegular, UnitPixel);
    Font messageFont(&messageFamily, H * 0.065f, FontStyleRegular, UnitPixel);
    Font subFont(&family, H * 0.032f, FontStyleRegular, UnitPixel);
    Font statusFont(&family, H * 0.024f, FontStyleRegular, UnitPixel);

    StringFormat centered;
    centered.SetAlignment(StringAlignmentCenter);
    const float textWidth = W * 0.8f, left = (W - textWidth) / 2;

    std::wstring name = settings.churchName;
    CharUpperBuffW(name.data(), static_cast<DWORD>(name.size()));

    auto measureAt = [&](const std::wstring& text, const Font& font, float width) {
        if (text.empty()) return 0.0f;
        RectF box;
        gfx.MeasureString(text.c_str(), -1, &font, RectF(0, 0, width, H), &centered, &box);
        return box.Height;
    };
    auto measure = [&](const std::wstring& text, const Font& font) { return measureAt(text, font, textWidth); };
    // Narrowest width that keeps the same number of lines, so a wrapped message splits evenly instead of
    // leaving one word on its own ("...We'll be right / back.").
    auto balancedLeft = [&](const std::wstring& text, const Font& font, float& width) {
        const float lines = measureAt(text, font, textWidth);
        float lo = textWidth * 0.4f, hi = textWidth;
        for (int i = 0; i < 12; i++) {
            float mid = (lo + hi) / 2;
            if (measureAt(text, font, mid) > lines + 1) lo = mid; else hi = mid;
        }
        width = hi;
        return (W - width) / 2;
    };

    auto logo = loadImage(settings.logo);
    float logoW = 0, logoH = 0;
    if (logo) {
        float scale = std::min(H * 0.22f / logo->GetWidth(), H * 0.22f / logo->GetHeight());
        logoW = logo->GetWidth() * scale;
        logoH = logo->GetHeight() * scale;
    }
    const float nameH = measure(name, nameFont), messageH = measure(settings.message, messageFont),
                subH = measure(settings.secondLine, subFont), statusH = measure(status, statusFont);
    const float total = (logo ? logoH + H * 0.04f : 0) + (nameH > 0 ? nameH + H * 0.03f : 0) + messageH +
                        (subH > 0 ? H * 0.03f + subH : 0) + H * 0.06f + statusH;

    float y = (H - total) / 2;
    if (logo) {
        gfx.DrawImage(logo.get(), (W - logoW) / 2, y, logoW, logoH);
        y += logoH + H * 0.04f;
    }
    if (nameH > 0) {
        SolidBrush faded(Color(217, 255, 255, 255));
        gfx.DrawString(name.c_str(), -1, &nameFont, RectF(left, y, textWidth, nameH), &centered, &faded);
        y += nameH + H * 0.03f;
    }
    SolidBrush white(Color(255, 255, 255, 255));
    float messageW = textWidth;
    const float messageLeft = balancedLeft(settings.message, messageFont, messageW);
    gfx.DrawString(settings.message.c_str(), -1, &messageFont, RectF(messageLeft, y, messageW, messageH), &centered, &white);
    y += messageH;
    if (subH > 0) {
        y += H * 0.03f;
        SolidBrush faded(Color(230, 255, 255, 255));
        gfx.DrawString(settings.secondLine.c_str(), -1, &subFont, RectF(left, y, textWidth, subH), &centered, &faded);
        y += subH;
    }

    // Status line with three dots, e.g. "● ● ●  Reconnecting".
    y += H * 0.06f;
    SolidBrush soft(Color(204, 255, 255, 255));
    RectF statusBox;
    gfx.MeasureString(status.c_str(), -1, &statusFont, PointF(0, 0), &statusBox);
    const float dot = H * 0.011f, gap = H * 0.008f, dotsW = 3 * dot + 2 * gap, spacing = H * 0.012f;
    float x = (W - (dotsW + spacing + statusBox.Width)) / 2;
    for (int i = 0; i < 3; i++) {
        gfx.FillEllipse(&soft, x + i * (dot + gap), y + (statusH - dot) / 2, dot, dot);
    }
    gfx.DrawString(status.c_str(), -1, &statusFont, PointF(x + dotsW + spacing, y), &soft);

    if (settings.audioContinues) {
        Font audioFont(&family, H * 0.024f, FontStyleRegular, UnitPixel);
        gfx.DrawString(L"Audio continues", -1, &audioFont, PointF(H * 0.04f, H - H * 0.04f - H * 0.035f), &soft);
    }
    return result;
}

CLSID encoderFor(const wchar_t* mime) {
    UINT count = 0, size = 0;
    Gdiplus::GetImageEncodersSize(&count, &size);
    std::vector<uint8_t> buffer(size);
    auto* codecs = reinterpret_cast<Gdiplus::ImageCodecInfo*>(buffer.data());
    Gdiplus::GetImageEncoders(count, size, codecs);
    for (UINT i = 0; i < count; i++) {
        if (wcscmp(codecs[i].MimeType, mime) == 0) return codecs[i].Clsid;
    }
    return CLSID{};
}

std::string base64(const uint8_t* data, size_t size) {
    static const char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve((size + 2) / 3 * 4);
    for (size_t i = 0; i < size; i += 3) {
        uint32_t v = data[i] << 16 | (i + 1 < size ? data[i + 1] << 8 : 0) | (i + 2 < size ? data[i + 2] : 0);
        out += table[v >> 18 & 63];
        out += table[v >> 12 & 63];
        out += i + 1 < size ? table[v >> 6 & 63] : '=';
        out += i + 2 < size ? table[v & 63] : '=';
    }
    return out;
}

// Encodes a bitmap as a "data:" URL for the StreamLens window.
std::string dataUrl(Gdiplus::Bitmap& bitmap, const wchar_t* mime, const char* prefix, const Gdiplus::EncoderParameters* params) {
    IStream* stream = nullptr;
    if (FAILED(CreateStreamOnHGlobal(nullptr, TRUE, &stream))) return {};
    CLSID encoder = encoderFor(mime);
    std::string url;
    if (bitmap.Save(stream, &encoder, params) == Gdiplus::Ok) {
        HGLOBAL memory = nullptr;
        GetHGlobalFromStream(stream, &memory);
        STATSTG stat{};
        stream->Stat(&stat, STATFLAG_NONAME);
        const auto* bytes = static_cast<const uint8_t*>(GlobalLock(memory));
        url = prefix + base64(bytes, static_cast<size_t>(stat.cbSize.QuadPart));
        GlobalUnlock(memory);
    }
    stream->Release();
    return url;
}

}  // namespace

std::string renderSlatePreview(const SlateSettings& settings, const std::wstring& status, int width, int height) {
    auto bitmap = drawSlate(settings, status, width, height);
    return dataUrl(*bitmap, L"image/png", "data:image/png;base64,", nullptr);
}

std::string cameraPreview(const uint8_t* nv12, int width, int height, int outWidth, int outHeight) {
    ensureGdiplus();
    // Shrinking 4K to preview size first keeps this cheap (a few milliseconds).
    static SwsContext* sws = nullptr;
    sws = sws_getCachedContext(sws, width, height, AV_PIX_FMT_NV12, outWidth, outHeight, AV_PIX_FMT_BGRA,
                               SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
    if (!sws) return {};
    std::vector<uint8_t> bgra(static_cast<size_t>(outWidth) * outHeight * 4);
    const uint8_t* src[2] = {nv12, nv12 + static_cast<size_t>(width) * height};
    int srcStride[2] = {width, width};
    uint8_t* dst[1] = {bgra.data()};
    int dstStride[1] = {outWidth * 4};
    sws_scale(sws, src, srcStride, 0, height, dst, dstStride);

    Gdiplus::Bitmap bitmap(outWidth, outHeight, outWidth * 4, PixelFormat32bppRGB, bgra.data());
    ULONG quality = 80;
    Gdiplus::EncoderParameters params;
    params.Count = 1;
    params.Parameter[0].Guid = Gdiplus::EncoderQuality;
    params.Parameter[0].Type = Gdiplus::EncoderParameterValueTypeLong;
    params.Parameter[0].NumberOfValues = 1;
    params.Parameter[0].Value = &quality;
    return dataUrl(bitmap, L"image/jpeg", "data:image/jpeg;base64,", &params);
}

std::vector<uint8_t> renderSlate(const SlateSettings& settings, const std::wstring& status, int width, int height) {
    using namespace Gdiplus;
    auto drawn = drawSlate(settings, status, width, height);
    Bitmap& bitmap = *drawn;

    // BGRA → NV12, the camera's format.
    std::vector<uint8_t> nv12(static_cast<size_t>(width) * height * 3 / 2);
    BitmapData data;
    Rect all(0, 0, width, height);
    if (bitmap.LockBits(&all, ImageLockModeRead, PixelFormat32bppARGB, &data) == Ok) {
        SwsContext* sws = sws_getContext(width, height, AV_PIX_FMT_BGRA, width, height, AV_PIX_FMT_NV12,
                                         SWS_BICUBIC, nullptr, nullptr, nullptr);
        const uint8_t* src[1] = {static_cast<const uint8_t*>(data.Scan0)};
        int srcStride[1] = {data.Stride};
        uint8_t* dst[2] = {nv12.data(), nv12.data() + static_cast<size_t>(width) * height};
        int dstStride[2] = {width, width};
        sws_scale(sws, src, srcStride, 0, height, dst, dstStride);
        sws_freeContext(sws);
        bitmap.UnlockBits(&data);
    }
    return nv12;
}
