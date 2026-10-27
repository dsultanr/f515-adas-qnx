#include "tracker.h"
#include <math.h>
#include <algorithm>

static float iou(const Det& a, const Det& b) {
    float w = std::min(a.x2, b.x2) - std::max(a.x1, b.x1), h = std::min(a.y2, b.y2) - std::max(a.y1, b.y1);
    if (w <= 0 || h <= 0) return 0;
    float i = w * h;
    return i / ((a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - i);
}

// Наклон и значимость z линейной подгонки y(t). false если данных мало.
static bool fit_slope(const float* ts, const float* ys, int n, float* slope, float* z) {
    if (n < 3) return false;
    double mt = 0, my = 0;
    for (int i = 0; i < n; i++) { mt += ts[i]; my += ys[i]; }
    mt /= n; my /= n;
    double sxx = 0, sxy = 0;
    for (int i = 0; i < n; i++) { sxx += (ts[i] - mt) * (ts[i] - mt); sxy += (ts[i] - mt) * (ys[i] - my); }
    if (sxx <= 1e-12) return false;
    double k = sxy / sxx, b = my - k * mt, rss = 0;
    for (int i = 0; i < n; i++) { double r = ys[i] - (k * ts[i] + b); rss += r * r; }
    double se = sqrt(rss / (n - 2) / sxx);
    *slope = (float)k;
    *z = se > 1e-12 ? (float)(k / se) : (k > 0 ? 1e9f : -1e9f);
    return true;
}

float Tracker::corridor_half(float by, int W, int H) const {
    if (by < H * cfg_.horizon || by > H * cfg_.hood) return 0;
    float k = (by - H * cfg_.horizon) / std::max(H * (cfg_.hood - cfg_.horizon), 1.0f);
    return W * (cfg_.corr_top + (cfg_.corr_bot - cfg_.corr_top) * k) / 2.0f;
}

bool Tracker::in_corridor(const Det& b, int W, int H) const {
    float half = corridor_half(b.y2, W, H);
    return half > 0 && fabsf((b.x1 + b.x2) / 2 - W * cfg_.corr_cx) < half;
}

// Последние точки истории в окне: индексы в хронологическом порядке
static int window_pts(const Track& tr, float window, int min_pts, int* idx) {
    if (tr.n < min_pts) return 0;
    int last = (tr.head + Track::N - 1) % Track::N;
    float t_end = tr.t[last];
    int k = 0;
    for (int i = 0; i < tr.n; i++) {
        int j = (tr.head + Track::N - tr.n + i) % Track::N;
        if (tr.t[j] >= t_end - window && tr.w[j] > 1) idx[k++] = j;
    }
    if (k < min_pts || tr.t[idx[k - 1]] - tr.t[idx[0]] < 0.4f) return 0;
    return k;
}

bool Tracker::ttc(const Track& tr, float* ttc, float* z) const {
    int idx[Track::N];
    int k = window_pts(tr, cfg_.window, cfg_.min_pts, idx);
    if (!k) return false;
    float ts[Track::N], ys[Track::N];
    for (int i = 0; i < k; i++) { ts[i] = tr.t[idx[i]] - tr.t[idx[0]]; ys[i] = logf(tr.w[idx[i]]); }
    float s;
    if (!fit_slope(ts, ys, k, &s, z)) return false;
    if (s <= 1e-4f || *z < cfg_.min_z) return false;
    *ttc = 1.0f / s;
    return true;
}

bool Tracker::exit_time(const Track& tr, int W, int H, float* te) const {
    int idx[Track::N];
    int k = window_pts(tr, cfg_.window, cfg_.min_pts, idx);
    if (!k) return false;
    float ts[Track::N], rs[Track::N];
    int m = 0;
    const float axis = W * cfg_.corr_cx;
    for (int i = 0; i < k; i++) {
        float half = corridor_half(tr.by[idx[i]], W, H);
        if (half <= 1) continue;
        ts[m] = tr.t[idx[i]] - tr.t[idx[0]];
        rs[m] = fabsf(tr.cx[idx[i]] - axis) / half;
        m++;
    }
    if (m < cfg_.min_pts) return false;
    float s, z;
    if (!fit_slope(ts, rs, m, &s, &z) || s <= 1e-3f || z < 2.0f) return false;
    float r_now = rs[m - 1];
    *te = r_now >= 1 ? 0 : (1 - r_now) / s;
    return true;
}

static void push(Track& tr, const Det& b, double t) {
    tr.t[tr.head] = (float)fmod(t, 100000.0);
    tr.w[tr.head] = b.x2 - b.x1;
    tr.cx[tr.head] = (b.x1 + b.x2) / 2;
    tr.by[tr.head] = b.y2;
    tr.head = (tr.head + 1) % Track::N;
    if (tr.n < Track::N) tr.n++;
}

FcwResult Tracker::update(const std::vector<Det>& dets, double t, int W, int H) {
    std::vector<bool> used(dets.size(), false);
    for (Track& tr : tracks_) {
        float best = 0; int bi = -1;
        for (size_t i = 0; i < dets.size(); i++) {
            if (used[i]) continue;
            float v = iou(tr.box, dets[i]);
            if (v > best) { best = v; bi = (int)i; }
        }
        if (best > cfg_.iou_match) {
            tr.box = dets[bi]; tr.cls = dets[bi].cls; push(tr, dets[bi], t);
            tr.misses = 0; tr.hits++; used[bi] = true;
        } else tr.misses++;
    }
    for (size_t i = 0; i < dets.size(); i++) {
        if (used[i]) continue;
        Track tr = {};
        tr.id = next_id_++; tr.cls = dets[i].cls; tr.hits = 1; tr.box = dets[i];
        push(tr, dets[i], t);
        tracks_.push_back(tr);
    }
    tracks_.erase(std::remove_if(tracks_.begin(), tracks_.end(),
                                 [&](const Track& tr) { return tr.misses >= cfg_.max_misses; }), tracks_.end());

    FcwResult r;
    const Track* lead = nullptr;
    for (const Track& tr : tracks_) {
        if (tr.hits < 3 || tr.misses > 0) continue;
        // своя полоса: геометрический коридор или класс «CIPO L1» самой модели
        if (!in_corridor(tr.box, W, H) && tr.cls != 1) continue;
        float tt, z;
        if (ttc(tr, &tt, &z) && (r.ttc < 0 || tt < r.ttc)) { lead = &tr; r.ttc = tt; r.z = z; }
    }
    if (lead) {
        r.lead_id = lead->id;
        float te;
        if (exit_time(*lead, W, H, &te)) {
            r.t_exit = te;
            if (te < r.ttc) r.suppressed = true;
        }
    }
    float eff = (r.ttc >= 0 && !r.suppressed) ? r.ttc : -1;
    run_alarm_ = (eff >= 0 && eff <= cfg_.ttc_alarm) ? run_alarm_ + 1 : 0;
    run_warn_ = (eff >= 0 && eff <= cfg_.ttc_warn) ? run_warn_ + 1 : 0;
    r.state = run_alarm_ >= cfg_.persist ? FCW_ALARM : run_warn_ >= cfg_.persist ? FCW_WARN : FCW_NONE;
    return r;
}
