// Запуск AutoSpeed (обрезанный до голов) на DSP через SNPE 1.55 прошивки QNX.
// Вход: uint8 RGB HWC 1024x512 (tf8, шкала входного слоя ~1/255). Выход: 6 карт float NHWC.
#pragma once
#include <stdint.h>
#include <stddef.h>

struct HeadMaps {
    // DFL-логиты [H*W*64] и логиты классов [H*W*4] на страйдах 8/16/32, раскладка NHWC.
    const float* box[3];
    const float* cls[3];
    int h[3], w[3];
};

class SnpeRunner {
public:
    // runtime: 0 = DSP, 1 = CPU (для отладки). Возвращает false и печатает ошибку SNPE.
    bool init(const char* dlc_path, int runtime);
    bool run(const uint8_t* rgb, HeadMaps* out);
    uint8_t* input() { return in_; }
    ~SnpeRunner();
private:
    void* impl_ = nullptr;
    uint8_t* in_ = nullptr;
};
