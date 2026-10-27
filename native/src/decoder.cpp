#include "decoder.h"
#include <math.h>
#include <algorithm>

static float iou(const Det& a, const Det& b) {
    float w = std::min(a.x2, b.x2) - std::max(a.x1, b.x1), h = std::min(a.y2, b.y2) - std::max(a.y1, b.y1);
    if (w <= 0 || h <= 0) return 0;
    float i = w * h;
    return i / ((a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - i);
}

void decode_heads(const HeadMaps& m, const DecodeCfg& cfg, std::vector<Det>* out) {
    static const int stride[3] = {8, 16, 32};
    const float logit_thr = logf(cfg.score_thr / (1.0f - cfg.score_thr));
    std::vector<Det> cand;
    for (int s = 0; s < 3; s++) {
        const int H = m.h[s], W = m.w[s];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                const int p = y * W + x;
                const float* c = m.cls[s] + p * 4;
                int best = 0;
                for (int k = 1; k < 4; k++) if (c[k] > c[best]) best = k;
                if (c[best] < logit_thr) continue;
                // DFL: 4 стороны (л, в, п, н) x 16 бинов, ожидание по softmax
                const float* d = m.box[s] + p * 64;
                float dist[4];
                for (int side = 0; side < 4; side++) {
                    const float* b = d + side * 16;
                    float mx = b[0];
                    for (int i = 1; i < 16; i++) mx = std::max(mx, b[i]);
                    float sum = 0, acc = 0;
                    for (int i = 0; i < 16; i++) { float e = expf(b[i] - mx); sum += e; acc += e * i; }
                    dist[side] = acc / sum;
                }
                const float ax = x + 0.5f, ay = y + 0.5f, st = (float)stride[s];
                Det o;
                o.x1 = (ax - dist[0]) * st * cfg.scale;
                o.y1 = (ay - dist[1]) * st * cfg.scale + cfg.y_off;
                o.x2 = (ax + dist[2]) * st * cfg.scale;
                o.y2 = (ay + dist[3]) * st * cfg.scale + cfg.y_off;
                o.score = 1.0f / (1.0f + expf(-c[best]));
                o.cls = best;
                cand.push_back(o);
            }
    }
    std::sort(cand.begin(), cand.end(), [](const Det& a, const Det& b) { return a.score > b.score; });
    out->clear();
    for (const Det& d : cand) {
        bool keep = true;
        for (const Det& k : *out)
            if (k.cls == d.cls && iou(k, d) >= cfg.nms_iou) { keep = false; break; }
        if (keep) out->push_back(d);
        if (out->size() >= 32) break;
    }
}
