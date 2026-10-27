// Сопровождение машин и TTC — перенос офлайн-прототипа ~/f515/fcw/scripts/fcw.py (проверен на записях 09.2026).
//   TTC   = 1 / (d ln w / dt) по окну, калибровка не нужна (w ~ 1/d);
//   z     = значимость наклона ln w — режет мнимые TTC от дрожания рамки;
//   t_exit— время до выхода цели из коридора своей полосы; если цель уйдёт раньше, чем мы доедем,
//           это обгон/перестроение, а не опасность.
#pragma once
#include <stdint.h>
#include <vector>
#include "decoder.h"

struct FcwCfg {
    // коридор своей полосы в долях кадра 1920x1080 (замерено 09.2026); капот 0.95, а не 0.72 —
    // иначе близкая цель пропадает как раз тогда, когда она опаснее всего
    float horizon = 0.50f, hood = 0.95f, corr_cx = 0.515f, corr_top = 0.03f, corr_bot = 0.34f;
    float window = 1.5f;     // окно подгонки, с
    int min_pts = 8;
    float min_z = 3.0f;
    float ttc_warn = 3.0f, ttc_alarm = 2.0f;
    int persist = 3;         // кадров подряд за порогом до тревоги
    float iou_match = 0.3f;
    int max_misses = 8;
};

struct Track {
    int id, cls, hits, misses;
    Det box;
    // история: t, ширина, центр x, низ рамки
    static const int N = 64;
    float t[N], w[N], cx[N], by[N];
    int n, head;
};

enum FcwState { FCW_NONE = 0, FCW_WARN = 1, FCW_ALARM = 2 };

struct FcwResult {
    int lead_id = -1;
    float ttc = -1, z = 0, t_exit = -1;
    bool suppressed = false;
    FcwState state = FCW_NONE;
};

class Tracker {
public:
    explicit Tracker(const FcwCfg& c) : cfg_(c) {}
    // t — секунды (монотонные), W/H — размер кадра
    FcwResult update(const std::vector<Det>& dets, double t, int W, int H);
    const std::vector<Track>& tracks() const { return tracks_; }
    bool in_corridor(const Det& b, int W, int H) const;
private:
    float corridor_half(float by, int W, int H) const;
    bool ttc(const Track& tr, float* ttc, float* z) const;
    bool exit_time(const Track& tr, int W, int H, float* te) const;
    FcwCfg cfg_;
    std::vector<Track> tracks_;
    int next_id_ = 1, run_warn_ = 0, run_alarm_ = 0;
};
