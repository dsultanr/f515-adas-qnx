// UYVY кадр камеры -> вход AutoSpeed: срез верха до 2:1, билинейное уменьшение до 1024x512 (центры пикселей
// как у cv2.resize INTER_LINEAR), YUV->RGB BT.601, uint8 HWC.
#pragma once
#include <stdint.h>

class Preprocess {
public:
    // full_range: true — Y/UV в 0..255 (JPEG), false — 16..235 (видео).
    void setup(int src_w, int src_h, int crop_top, int dst_w, int dst_h, bool full_range);
    void run(const uint8_t* uyvy, int stride, uint8_t* rgb) const;
    // Диагностика диапазона яркости по разреженной сетке.
    static void luma_range(const uint8_t* uyvy, int w, int h, int stride, int* ymin, int* ymax);
    int crop_top() const { return crop_; }
private:
    int sw_ = 0, sh_ = 0, crop_ = 0, dw_ = 0, dh_ = 0;
    bool full_ = false;
    // по x: индексы левого/правого источника и вес правого (0..256); по y — то же для строк
    int xi0_[2048], xi1_[2048], xw_[2048];
    int yi0_[1024], yi1_[1024], yw_[1024];
};
