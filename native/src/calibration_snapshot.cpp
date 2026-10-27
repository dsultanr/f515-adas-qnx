#include "calibration_snapshot.h"
#include "capture.h"
#include "preprocess.h"
#include "qnx_compat.h"
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <vector>

namespace {
void put32(unsigned char* p, uint32_t value) {
    for (int i = 0; i < 4; ++i) p[i] = (value >> (8 * i)) & 255;
}
void put64(unsigned char* p, uint64_t value) {
    for (int i = 0; i < 8; ++i) p[i] = (value >> (8 * i)) & 255;
}
}

int calibration_snapshot(const char* path, int input) {
    Capture camera;
    if (!camera.open(input)) {
        camera.close();
        fprintf(stderr, "[snapshot] camera open failed\n");
        return 1;
    }
    // The calibration and production preprocessing are defined for this camera mode.
    if (camera.width() != 1920 || camera.height() != 1080) {
        camera.close();
        fprintf(stderr, "[snapshot] expected camera mode 1920x1080\n");
        return 1;
    }
    CamFrame frame = {}; int error = 0; bool got = false;
    for (int attempt = 0; attempt < 10 && !got; ++attempt) {
        got = camera.get(&frame, 500, &error);
        if (error) break;
    }
    if (!got) {
        camera.close();
        fprintf(stderr, "[snapshot] no camera frame (%d)\n", error);
        return 1;
    }
    const int width = frame.width, height = frame.height, stride = width * 2;
    if (!frame.data || frame.stride < stride) {
        camera.release(frame); camera.close();
        fprintf(stderr, "[snapshot] invalid camera buffer\n");
        return 1;
    }
    std::vector<uint8_t> raw((size_t)stride * height);
    for (int y = 0; y < height; ++y)
        memcpy(raw.data() + (size_t)y * stride, frame.data + (size_t)y * frame.stride, stride);
    // Release the AIS buffer before processing or any filesystem I/O.
    camera.release(frame);
    camera.close();

    std::vector<uint8_t> rgb(1024 * 512 * 3);
    Preprocess pre;
    pre.setup(width, height, 120, 1024, 512, false);
    pre.run(raw.data(), stride, rgb.data());

    // Stable little-endian wire format, independent of compiler struct padding.
    unsigned char header[64] = {};
    memcpy(header, "F515CAL1", 8);
    put32(header + 8, width); put32(header + 12, height);
    put32(header + 16, stride); put32(header + 20, frame.seq);
    put64(header + 24, frame.ts_ns);
    put32(header + 32, input); put32(header + 36, 120);
    put32(header + 40, 1024); put32(header + 44, 512);
    put32(header + 48, raw.size()); put32(header + 52, rgb.size());
    put32(header + 56, 1); // UYVY, BT.601 limited range; reserved word remains zero.
    FILE* out = fopen(path, "wb");
    if (!out) { fprintf(stderr, "[snapshot] cannot create output (%d)\n", qnx_errno()); return 1; }
    bool ok = fwrite(header, 1, sizeof header, out) == sizeof header
        && fwrite(raw.data(), 1, raw.size(), out) == raw.size()
        && fwrite(rgb.data(), 1, rgb.size(), out) == rgb.size();
    if (fflush(out) != 0) ok = false;
    if (fclose(out) != 0) ok = false;
    if (!ok) {
        unlink(path);
        fprintf(stderr, "[snapshot] incomplete output\n");
        return 1;
    }
    fprintf(stderr, "[snapshot] captured %dx%d, sequence %u\n", width, height, frame.seq);
    return 0;
}
