// Декодер голов YOLOv8 (AutoSpeed): DFL-рамки + логиты классов -> детекции в координатах полного кадра камеры.
#pragma once
#include <vector>
#include "snpe_runner.h"

struct Det {
    float x1, y1, x2, y2;   // пиксели полного кадра 1920x1080
    float score;
    int cls;                // 0..3; 1 = цель в своей полосе (CIPO L1), 2 = соседняя полоса, 3 = прочие
};

struct DecodeCfg {
    float score_thr = 0.40f;   // INT8 на DSP занижает уверенность ~на 0.2 против FP32 (eval: 0.4 -> 35/38)
    float nms_iou = 0.45f;
    float scale = 1.875f;      // 1920/1024 = 960/512
    float y_off = 120.0f;      // срез верха кадра
};

void decode_heads(const HeadMaps& m, const DecodeCfg& cfg, std::vector<Det>* out);
