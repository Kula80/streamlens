#include "Receiver.h"

#include <chrono>
#include <cstring>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/hwcontext.h>
#include <libswscale/swscale.h>
}

namespace {

using Clock = std::chrono::steady_clock;

// No video for this long means the phone has gone; viewers then see the right-back screen.
constexpr auto kSilenceTimeout = std::chrono::seconds(2);

struct Interrupt {
    const std::atomic<bool>* stopping;
    // Zero while waiting for the phone to connect: waiting is allowed to take forever.
    Clock::time_point lastPacket{};
};

int interruptCallback(void* opaque) {
    auto* i = static_cast<Interrupt*>(opaque);
    if (i->stopping->load()) return 1;
    return i->lastPacket != Clock::time_point{} && Clock::now() - i->lastPacket > kSilenceTimeout;
}

enum AVPixelFormat pickHardwareFormat(AVCodecContext*, const enum AVPixelFormat* formats) {
    for (const enum AVPixelFormat* f = formats; *f != AV_PIX_FMT_NONE; f++) {
        if (*f == AV_PIX_FMT_D3D11) return *f;
    }
    return formats[0];
}

std::string codecLabel(AVCodecID id) {
    return id == AV_CODEC_ID_HEVC ? "HEVC" : id == AV_CODEC_ID_H264 ? "H.264" : avcodec_get_name(id);
}

// Fits the picture inside the camera frame without stretching (black bars if the shapes differ).
struct Fit {
    int x, y, w, h;
};

Fit fitInside(int srcW, int srcH, int dstW, int dstH) {
    int w = dstW, h = static_cast<int>(static_cast<int64_t>(dstW) * srcH / srcW);
    if (h > dstH) {
        h = dstH;
        w = static_cast<int>(static_cast<int64_t>(dstH) * srcW / srcH);
    }
    w &= ~1;
    h &= ~1;
    return {((dstW - w) / 2) & ~1, ((dstH - h) / 2) & ~1, w, h};
}

void fillBlack(std::vector<uint8_t>& nv12, int width, int height) {
    const size_t luma = static_cast<size_t>(width) * height;
    std::memset(nv12.data(), 16, luma);
    std::memset(nv12.data() + luma, 128, luma / 2);
}

}  // namespace

Receiver::Receiver(Config config, FrameFn onFrame, StateFn onState)
    : config_(config), onFrame_(std::move(onFrame)), onState_(std::move(onState)) {
    // Expected hiccups (a damaged packet, a busy port) are reported in plain words through onState instead.
    av_log_set_level(AV_LOG_FATAL);
}

Receiver::~Receiver() { stop(); }

void Receiver::start() {
    stopping_ = false;
    outputThread_ = std::thread([this] { outputLoop(); });
    receiveThread_ = std::thread([this] { receiveLoop(); });
}

void Receiver::stop() {
    stopping_ = true;
    pendingReady_.notify_all();
    if (receiveThread_.joinable()) receiveThread_.join();
    if (outputThread_.joinable()) outputThread_.join();
    av_frame_free(&pending_);
}

void Receiver::receiveLoop() {
    bool portWasBusy = false;
    while (!stopping_) {
        if (!portWasBusy) onState_(State::Waiting, "");  // while busy, keep showing why
        const auto began = Clock::now();
        const bool opened = session();
        // Listening normally waits until the phone connects; failing at once means the port is taken.
        const bool busy = !opened && !stopping_ && Clock::now() - began < std::chrono::seconds(1);
        if (busy && !portWasBusy) {
            onState_(State::Waiting, "Port " + std::to_string(config_.port) +
                                         " is in use by another program, e.g. the StreamLens camera source "
                                         "from the OBS script. Remove that source, or use another port.");
        }
        portWasBusy = busy;
        if (busy) std::this_thread::sleep_for(std::chrono::seconds(2));
        {
            // Don't show a stale picture from this session when the next one starts.
            std::lock_guard<std::mutex> g(pendingLock_);
            av_frame_free(&pending_);
        }
        // A short pause so a broken listener can't spin the CPU.
        if (!stopping_) std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }
}

void Receiver::offer(AVFrame* frame, const std::string& detail) {
    std::lock_guard<std::mutex> g(pendingLock_);
    if (!pending_) pending_ = av_frame_alloc();
    av_frame_unref(pending_);  // the output thread hasn't taken the previous one: skip it
    av_frame_move_ref(pending_, frame);
    if (!detail.empty()) {
        pendingDetail_ = detail;
        announce_ = true;
    }
    pendingReady_.notify_one();
}

bool Receiver::session() {
    Interrupt interrupt{&stopping_};
    AVFormatContext* fmt = avformat_alloc_context();
    fmt->interrupt_callback = {interruptCallback, &interrupt};

    const std::string url = "srt://0.0.0.0:" + std::to_string(config_.port) +
                            "?mode=listener&latency=" + std::to_string(config_.latencyMs * 1000);
    AVDictionary* opts = nullptr;
    // Start fast: the phone sends a keyframe with its settings first.
    av_dict_set(&opts, "probesize", "2000000", 0);
    av_dict_set(&opts, "analyzeduration", "500000", 0);
    av_dict_set(&opts, "fflags", "nobuffer", 0);
    const AVInputFormat* mpegts = av_find_input_format("mpegts");

    // Blocks until the phone connects (or we're stopping).
    int err = avformat_open_input(&fmt, url.c_str(), mpegts, &opts);
    av_dict_free(&opts);
    if (err < 0) {
        return false;  // fmt is freed by avformat_open_input on failure
    }
    interrupt.lastPacket = Clock::now();

    AVCodecContext* dec = nullptr;
    AVBufferRef* hw = nullptr;
    AVFrame* frame = av_frame_alloc();
    AVPacket* pkt = av_packet_alloc();
    bool announced = false;

    do {
        // The phone's stream announces its codec up front, and the decoder reads the picture size from the
        // keyframe. Only fall back to FFmpeg's slow stream analysis (it decodes 4K on the processor) when
        // the codec isn't known yet.
        int videoIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        if (videoIndex < 0 || fmt->streams[videoIndex]->codecpar->codec_id == AV_CODEC_ID_NONE) {
            interrupt.lastPacket = {};  // analysis may take a while; don't treat it as the phone going quiet
            if (avformat_find_stream_info(fmt, nullptr) < 0) break;
            interrupt.lastPacket = Clock::now();
            videoIndex = av_find_best_stream(fmt, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        }
        if (videoIndex < 0) break;
        AVStream* stream = fmt->streams[videoIndex];

        const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
        if (!codec) break;
        dec = avcodec_alloc_context3(codec);
        avcodec_parameters_to_context(dec, stream->codecpar);
        dec->flags |= AV_CODEC_FLAG_LOW_DELAY;
        // 4K HEVC is heavy for a laptop processor, so decode on the graphics card when there is one.
        if (av_hwdevice_ctx_create(&hw, AV_HWDEVICE_TYPE_D3D11VA, nullptr, nullptr, 0) == 0) {
            dec->hw_device_ctx = av_buffer_ref(hw);
            dec->get_format = pickHardwareFormat;
            dec->extra_hw_frames = 2;  // the output thread holds on to one picture
        } else {
            dec->thread_count = 0;  // all cores
        }
        if (avcodec_open2(dec, codec, nullptr) < 0) break;

        while (!stopping_ && av_read_frame(fmt, pkt) >= 0) {
            interrupt.lastPacket = Clock::now();
            if (pkt->stream_index != videoIndex) {
                av_packet_unref(pkt);
                continue;
            }
            int sent = avcodec_send_packet(dec, pkt);
            av_packet_unref(pkt);
            if (sent < 0 && sent != AVERROR(EAGAIN)) continue;  // a damaged packet; the next keyframe repairs it

            while (avcodec_receive_frame(dec, frame) == 0) {
                std::string detail;
                if (!announced) {
                    announced = true;
                    detail = std::to_string(frame->width) + "x" + std::to_string(frame->height) + " " +
                             codecLabel(dec->codec_id) +
                             (frame->format == AV_PIX_FMT_D3D11 ? " (graphics card)" : " (processor)");
                }
                offer(frame, detail);
            }
        }
    } while (false);

    av_packet_free(&pkt);
    av_frame_free(&frame);
    avcodec_free_context(&dec);
    av_buffer_unref(&hw);
    avformat_close_input(&fmt);
    return true;
}

void Receiver::outputLoop() {
    const int W = config_.outWidth, H = config_.outHeight;
    std::vector<uint8_t> out(static_cast<size_t>(W) * H * 3 / 2);
    fillBlack(out, W, H);
    AVFrame* picture = av_frame_alloc();
    AVFrame* cpu = av_frame_alloc();
    SwsContext* sws = nullptr;
    int lastW = 0, lastH = 0;

    while (true) {
        std::string detail;
        {
            std::unique_lock<std::mutex> g(pendingLock_);
            pendingReady_.wait(g, [&] { return stopping_ || (pending_ && pending_->buf[0]); });
            if (stopping_) break;
            av_frame_move_ref(picture, pending_);
            if (announce_) {
                announce_ = false;
                detail = pendingDetail_;
            }
        }

        AVFrame* src = picture;
        if (picture->format == AV_PIX_FMT_D3D11) {
            // Copy the picture from the graphics card. It arrives as NV12, the camera's own format.
            av_frame_unref(cpu);
            if (av_hwframe_transfer_data(cpu, picture, 0) < 0) {
                av_frame_unref(picture);
                continue;
            }
            src = cpu;
        }

        if (src->width != lastW || src->height != lastH) {
            fillBlack(out, W, H);  // clear old bars before drawing in the new place
            lastW = src->width;
            lastH = src->height;
        }
        uint8_t* outY = out.data();
        uint8_t* outUV = out.data() + static_cast<size_t>(W) * H;

        if (src->format == AV_PIX_FMT_NV12 && src->width == W && src->height == H) {
            // Same size as the camera (the usual 4K case): a straight copy, no colour maths.
            for (int y = 0; y < H; y++) std::memcpy(outY + static_cast<size_t>(y) * W, src->data[0] + static_cast<size_t>(y) * src->linesize[0], W);
            for (int y = 0; y < H / 2; y++) std::memcpy(outUV + static_cast<size_t>(y) * W, src->data[1] + static_cast<size_t>(y) * src->linesize[1], W);
        } else {
            Fit fit = fitInside(src->width, src->height, W, H);
            sws = sws_getCachedContext(sws, src->width, src->height, static_cast<AVPixelFormat>(src->format), fit.w,
                                       fit.h, AV_PIX_FMT_NV12, SWS_BILINEAR, nullptr, nullptr, nullptr);
            if (sws) {
                uint8_t* dst[2] = {outY + static_cast<size_t>(fit.y) * W + fit.x,
                                   outUV + static_cast<size_t>(fit.y / 2) * W + fit.x};
                int dstStride[2] = {W, W};
                sws_scale(sws, src->data, src->linesize, 0, src->height, dst, dstStride);
            }
        }
        av_frame_unref(picture);

        if (!detail.empty()) onState_(State::Live, detail);
        onFrame_(out.data());
    }

    sws_freeContext(sws);
    av_frame_free(&cpu);
    av_frame_free(&picture);
}
