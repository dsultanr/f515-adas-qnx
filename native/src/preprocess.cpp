#include "preprocess.h"
#include <string.h>

static inline void lin_table(int src, int dst, int* i0, int* i1, int* w, int offset) {
    double scale = (double)src / dst;
    for (int d = 0; d < dst; d++) {
        double s = (d + 0.5) * scale - 0.5;
        if (s < 0) s = 0;
        int a = (int)s;
        if (a > src - 1) a = src - 1;
        int b = a + 1 < src ? a + 1 : src - 1;
        i0[d] = a + offset;
        i1[d] = b + offset;
        w[d] = (int)((s - a) * 256 + 0.5);
    }
}

void Preprocess::setup(int src_w, int src_h, int crop_top, int dst_w, int dst_h, bool full_range) {
    sw_ = src_w; sh_ = src_h; crop_ = crop_top; dw_ = dst_w; dh_ = dst_h; full_ = full_range;
    lin_table(src_w, dst_w, xi0_, xi1_, xw_, 0);
    lin_table(src_h - crop_top, dst_h, yi0_, yi1_, yw_, crop_top);
}

static inline uint8_t clamp8(int v) { return v < 0 ? 0 : v > 255 ? 255 : (uint8_t)v; }

void Preprocess::run(const uint8_t* uyvy, int stride, uint8_t* rgb) const {
    // Коэффициенты BT.601 в 1/1024. Ограниченный диапазон: Y'=(Y-16)*1.164, UV*1.138.
    const int ky = full_ ? 1024 : 1192, yoff = full_ ? 0 : 16;
    const int rv = full_ ? 1436 : 1634, gu = full_ ? 352 : 401, gv = full_ ? 731 : 833, bu = full_ ? 1815 : 2066;
    // Буферы AIS некешируемые: побайтное чтение вразброс стоило ~200 мс на кадр. Каждую нужную строку
    // источника один раз копируем целиком в кешируемую память (соседние строки выхода делят строки источника).
    static uint8_t rowbuf[2][4096];
    int have[2] = {-1, -1};
    const int row_bytes = sw_ * 2;
    auto fetch = [&](int sy) -> const uint8_t* {
        for (int k = 0; k < 2; k++) if (have[k] == sy) return rowbuf[k];
        int k = (have[0] == -1 || (have[1] != -1 && have[0] < have[1])) ? 0 : 1;
        memcpy(rowbuf[k], uyvy + (long)sy * stride, row_bytes);
        have[k] = sy;
        return rowbuf[k];
    };
    for (int y = 0; y < dh_; y++) {
        const uint8_t* r0 = fetch(yi0_[y]);
        const uint8_t* r1 = fetch(yi1_[y]);
        const int wy = yw_[y], wy0 = 256 - wy;
        uint8_t* o = rgb + (long)y * dw_ * 3;
        for (int x = 0; x < dw_; x++) {
            const int a = xi0_[x], b = xi1_[x], wx = xw_[x], wx0 = 256 - wx;
            // Y: байт 2x+1; U/V: макропиксель (x/2)*4 + 0 / + 2 (общие для пары пикселей)
            const int ya = (r0[2 * a + 1] * wx0 + r0[2 * b + 1] * wx) * wy0 + (r1[2 * a + 1] * wx0 + r1[2 * b + 1] * wx) * wy;
            const int ua = (r0[(a >> 1) * 4] * wx0 + r0[(b >> 1) * 4] * wx) * wy0 + (r1[(a >> 1) * 4] * wx0 + r1[(b >> 1) * 4] * wx) * wy;
            const int va = (r0[(a >> 1) * 4 + 2] * wx0 + r0[(b >> 1) * 4 + 2] * wx) * wy0 + (r1[(a >> 1) * 4 + 2] * wx0 + r1[(b >> 1) * 4 + 2] * wx) * wy;
            const int Y = ((ya + 32768) >> 16) - yoff, U = ((ua + 32768) >> 16) - 128, V = ((va + 32768) >> 16) - 128;
            const int c = Y * ky;
            o[0] = clamp8((c + rv * V + 512) >> 10);
            o[1] = clamp8((c - gu * U - gv * V + 512) >> 10);
            o[2] = clamp8((c + bu * U + 512) >> 10);
            o += 3;
        }
    }
}

void Preprocess::luma_range(const uint8_t* uyvy, int w, int h, int stride, int* ymin, int* ymax) {
    int lo = 255, hi = 0;
    for (int y = 0; y < h; y += 8)
        for (int x = 0; x < w; x += 8) {
            int v = uyvy[(long)y * stride + 2 * x + 1];
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
    *ymin = lo; *ymax = hi;
}
