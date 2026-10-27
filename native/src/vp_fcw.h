// FCW по логике Vision Pilot (safety_guardian): выбор CIPO по классам AutoSpeed -> дистанция через
// гомографию -> фильтр частиц (дистанция, скорость её изменения) -> IDM -> требуемое ускорение:
// FCW при -5..-3 м/с², AEB при < -5 м/с² (VisionPilot planning.cpp).
// Отличия от оригинала (сознательные):
//  * знак: VP передаёт в IDM d(дистанция)/dt (<0 при сближении) как delta_v, из-за чего max(0, ...)
//    обнуляет динамический член и IDM не видит скорость сближения; здесь delta_v = -d'(t). vp_sign=1 — как в VP;
//  * без члена «свободной дороги» (зависит от ограничения скорости, к опасности спереди отношения не имеет);
//  * ниже min_speed канал молчит (IDM ругается на стоящую впереди в пробке машину).
#pragma once
#include <random>
#include <vector>
#include "decoder.h"

struct VpCfg {
    // Плоскость дороги для кадра 1920x1080 (калибровка 09.2026 по оптическому потоку и скорости, сверена
    // по ширине полосы и ширине кузова): d = K/(y - y_h), боковое смещение = h (x - x_c)/(y - y_h).
    float K = 1820.f, y_h = 540.f, x_c = 989.f, cam_h = 1.51f;
    float bumper_offset = 0.f;   // вычесть из дистанции (камера -> передний бампер); в VP нет
    // фильтр частиц — значения VisionPilot LongitudinalFusion::Config
    int n_particles = 500;
    float d_max = 150.f, proc_noise_d = 2.0f, proc_noise_v = 0.5f, cipo_noise = 3.f, reset_gate = 25.f;
    // IDM — значения VisionPilot LongitudinalPlanner::Config
    float a = 1.5f, b = 3.0f, T = 1.5f, s0 = 2.0f;
    float fcw_decel = -3.f, aeb_decel = -5.f;
    float min_speed_kmh = 5.f;
    int vp_sign = 0;
};

struct VpResult {
    bool cipo = false, cut_in = false, active = false;
    float raw_d = -1, d = -1, d_sigma = 0, vel = 0;   // vel = d'(t), м/с (<0 при сближении)
    float accel = 0;                                  // требуемое ускорение IDM, м/с²
    int level = 0;                                    // 0 нет, 1 FCW, 2 AEB
};

class VpFcw {
public:
    explicit VpFcw(const VpCfg& c);
    // dets — координаты полного кадра 1920x1080; t — секунды; ego_kmh < 0 — скорость неизвестна
    VpResult update(const std::vector<Det>& dets, double t, float ego_kmh);
    float ground_dist(float x, float y) const;
private:
    struct P { float d, v, lw; };
    void init_from(float d, float sd);
    VpCfg c_;
    std::vector<P> ps_;
    bool init_ = false;
    double last_t_ = -1;
    std::mt19937 rng_;
};
