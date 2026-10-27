// Захват дорожной камеры через Qualcomm AIS (qcarcam 4.7) на QNX: свои pmem-буферы, опрос qcarcam_get_frame.
// AIS раздаёт вход нескольким клиентам, регистратор при этом не теряет кадров (проверено 2026-09-01).
#pragma once
#include <stdint.h>

struct CamFrame {
    const uint8_t* data;   // UYVY, stride bytes per row
    int width, height, stride;
    int idx;               // индекс буфера, вернуть через Capture::release
    uint64_t ts_ns;        // монотонная метка AIS
    unsigned seq;
};

class Capture {
public:
    bool open(int input_desc, int nbufs = 5);
    // timeout_ms: сколько ждать кадр. false — таймаут или ошибка (err != 0 при ошибке).
    bool get(CamFrame* f, int timeout_ms, int* err);
    void release(const CamFrame& f);
    void close();
    int width() const { return w_; }
    int height() const { return h_; }
private:
    void* hndl_ = nullptr;
    int w_ = 0, h_ = 0, stride_ = 0, n_ = 0;
    void* vaddr_[20] = {};
    void* pmh_[20] = {};
};
