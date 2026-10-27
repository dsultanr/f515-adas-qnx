#include "vp_fcw.h"
#include <math.h>
#include <algorithm>

VpFcw::VpFcw(const VpCfg& c) : c_(c), rng_(12345) {}

float VpFcw::ground_dist(float x, float y) const {
    float dy = y - c_.y_h;
    if (dy < 2) return c_.d_max;
    float fwd = c_.K / dy, lat = c_.cam_h * (x - c_.x_c) / dy;
    return std::min(c_.d_max, sqrtf(fwd * fwd + lat * lat));   // как project_dist в VP: евклидова норма
}

void VpFcw::init_from(float d, float sd) {
    std::normal_distribution<float> nd(d, sd), nv(0.f, 2.f);
    ps_.resize(c_.n_particles);
    for (P& p : ps_) { p.d = std::min(std::max(nd(rng_), 0.f), c_.d_max); p.v = nv(rng_); p.lw = 0; }
}

VpResult VpFcw::update(const std::vector<Det>& dets, double t, float ego_kmh) {
    VpResult r;
    // CIPO: ближайший класс 1 (своя полоса); класс 2 (соседняя) ближе — перестроение
    float best1 = 1e9f, best2 = 1e9f;
    for (const Det& d : dets) {
        if (d.cls != 1 && d.cls != 2) continue;
        float dist = ground_dist((d.x1 + d.x2) / 2, d.y2);
        if (d.cls == 1) best1 = std::min(best1, dist); else best2 = std::min(best2, dist);
    }
    bool have = false; float meas = 0;
    if (best2 < 1e9f && best1 < 1e9f && best2 < best1) { meas = best2; have = true; r.cut_in = true; }
    else if (best1 < 1e9f) { meas = best1; have = true; }
    r.cipo = have; r.raw_d = have ? meas : -1;

    float dt = last_t_ < 0 ? 0.1f : (float)(t - last_t_);
    last_t_ = t;
    if (dt <= 1e-4f || dt > 1.f) dt = 0.1f;
    if (!have) { init_ = false; ps_.clear(); return r; }   // VP: нет цели -> сброс фильтра
    if (!init_) { init_from(meas, c_.cipo_noise); init_ = true; }
    else {
        std::normal_distribution<float> nd(0.f, c_.proc_noise_d), nv(0.f, c_.proc_noise_v);
        float mean = 0;
        for (P& p : ps_) { p.d = std::min(std::max(p.d + p.v * dt + nd(rng_), 0.f), c_.d_max); p.v += nv(rng_); mean += p.d; }
        mean /= ps_.size();
        if (fabsf(meas - mean) > c_.reset_gate) init_from(meas, c_.cipo_noise);
    }
    for (P& p : ps_) { float z = (meas - p.d) / c_.cipo_noise; p.lw += -0.5f * z * z; }
    // нормированные веса, эффективное число частиц, систематический ресэмплинг (как в VP)
    float mx = -1e30f;
    for (const P& p : ps_) mx = std::max(mx, p.lw);
    std::vector<float> w(ps_.size());
    float sum = 0;
    for (size_t i = 0; i < ps_.size(); i++) { w[i] = expf(ps_[i].lw - mx); sum += w[i]; }
    for (float& x : w) x /= sum;
    float ss = 0;
    for (float x : w) ss += x * x;
    float md = 0, mv = 0, var = 0;
    for (size_t i = 0; i < ps_.size(); i++) { md += w[i] * ps_[i].d; mv += w[i] * ps_[i].v; }
    for (size_t i = 0; i < ps_.size(); i++) var += w[i] * (ps_[i].d - md) * (ps_[i].d - md);
    if (1.f / ss < 0.5f * ps_.size()) {
        std::vector<P> np; np.reserve(ps_.size());
        const int N = ps_.size();
        std::uniform_real_distribution<float> u(0.f, 1.f / N);
        float u0 = u(rng_), cs = w[0]; int j = 0;
        for (int i = 0; i < N; i++) {
            float thr = u0 + (float)i / N;
            while (j < N - 1 && cs < thr) cs += w[++j];
            np.push_back({ps_[j].d, ps_[j].v, 0.f});
        }
        ps_.swap(np);
    }
    r.d = std::max(0.f, md - c_.bumper_offset); r.vel = mv; r.d_sigma = sqrtf(std::max(0.f, var));

    // IDM: требуемое ускорение при текущем зазоре и скорости сближения
    if (ego_kmh < 0 || ego_kmh < c_.min_speed_kmh) return r;
    r.active = true;
    float v = ego_kmh / 3.6f;
    float closing = c_.vp_sign ? mv : -mv;
    float s_star = c_.s0 + std::max(0.f, v * c_.T + v * closing / (2.f * sqrtf(c_.a * c_.b)));
    float s = std::max(0.5f, r.d);
    r.accel = c_.a * (1.f - (s_star / s) * (s_star / s));
    r.level = r.accel < c_.aeb_decel ? 2 : r.accel <= c_.fcw_decel ? 1 : 0;
    return r;
}
