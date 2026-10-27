// fcwd — предупреждение об опасном сближении для Seres F515 на QNX (SA8155P):
// камера AIS (вход 4) -> препроцессинг на CPU -> AutoSpeed INT8 на Hexagon DSP (SNPE 1.55 прошивки) ->
// трекер и TTC -> баннер приборки через PPS + UDP-поток JSON для приложения на Android.
//
// Запуск: fcwd dlc=/var/log/fcw/autospeed_heads3o_int8.dlc [ключ=значение ...], ключи — в parse_args().
#include <fcntl.h>
#include <math.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <algorithm>
#include <vector>
#include "capture.h"
#include "calibration_snapshot.h"
#include "decoder.h"
#include "outputs.h"
#include "preprocess.h"
#include "qnx_compat.h"
#include "snpe_runner.h"
#include "tracker.h"
#include "vp_fcw.h"
#include <sys/socket.h>

namespace {
volatile sig_atomic_t g_stop = 0;
void on_signal(int) { g_stop = 1; }

struct Args {
    const char* snapshot = nullptr; // one-shot capture, before SNPE/PPS initialization
    const char* dlc = "/var/log/fcw/autospeed_heads3o_int8.dlc";
    int input = 4;
    float fps = 15;              // частота обработки; камера даёт 30
    int pps = 1;                 // писать баннер в приборку
    // Значение PPS (код, подкод) -> ADASWarningID по таблице AdasWarnList в sai_clusterhmi (.rodata 0x6d8e8):
    // 07 01 -> 33 -> вероятно ADAS_TEXT_029 «Риск столкновения» (подтвердить скриншотом при зажигании)
    const char* warn_code = "BwE=";
    // 08 01 -> ADASWarningID 32 «Экстренное торможение»: машина при этом сама не тормозит, текст вводит
    // в заблуждение, поэтому по умолчанию тревога тоже «Риск столкновения»; уровни различает приложение.
    const char* alarm_code = "BwE=";
    const char* clear_code = "/wA=";  // ff 00 — нет предупреждения
    int hold_ms = 2000;          // минимальное время показа баннера
    float min_speed = 0;         // ниже — не предупреждать (0 = выключено, пока раскладка скорости не подтверждена)
    const char* app_ip = "172.16.1.3";   // Android (eth0:1) по внутренней сети гипервизора
    int app_port = 62715;                // TCP, диапазон 60000-65000 пропускает фаервол Android
    int stats_s = 5;
    const char* test_warn = nullptr;     // только показать код на N мс и снять
    int test_ms = 4000;
    const char* replay = nullptr;        // ip:port — кадры RGB 1024x512 по TCP вместо камеры (проверка на записях)
    float replay_fps = 15;
    int verbose = 0;                     // 1 — строка лога на каждый кадр
    float replay_speed = -1;             // км/ч для replay (на стоянке PPS даёт 0)
    const char* replay_speeds = nullptr; // файл: скорость км/ч на каждый кадр replay
    int vp = 1;                          // канал Vision Pilot (дистанция + IDM)
    VpCfg vpc;
    DecodeCfg dec;
    FcwCfg fcw;
};

void parse_args(int argc, char** argv, Args* a) {
    for (int i = 1; i < argc; i++) {
        char* eq = strchr(argv[i], '=');
        if (!eq) continue;
        *eq = 0;
        const char* k = argv[i]; const char* v = eq + 1;
        if (!strcmp(k, "dlc")) a->dlc = v;
        else if (!strcmp(k, "input")) a->input = atoi(v);
        else if (!strcmp(k, "fps")) a->fps = atof(v);
        else if (!strcmp(k, "pps")) a->pps = atoi(v);
        else if (!strcmp(k, "warn_code")) a->warn_code = v;
        else if (!strcmp(k, "alarm_code")) a->alarm_code = v;
        else if (!strcmp(k, "clear_code")) a->clear_code = v;
        else if (!strcmp(k, "hold_ms")) a->hold_ms = atoi(v);
        else if (!strcmp(k, "min_speed")) a->min_speed = atof(v);
        else if (!strcmp(k, "app")) { static char ip[32]; snprintf(ip, sizeof ip, "%s", v); char* c = strchr(ip, ':'); if (c) { *c = 0; a->app_port = atoi(c + 1); } a->app_ip = ip; }
        else if (!strcmp(k, "score")) a->dec.score_thr = atof(v);
        else if (!strcmp(k, "ttc_warn")) a->fcw.ttc_warn = atof(v);
        else if (!strcmp(k, "ttc_alarm")) a->fcw.ttc_alarm = atof(v);
        else if (!strcmp(k, "min_z")) a->fcw.min_z = atof(v);
        else if (!strcmp(k, "window")) a->fcw.window = atof(v);
        else if (!strcmp(k, "persist")) a->fcw.persist = atoi(v);
        else if (!strcmp(k, "stats")) a->stats_s = atoi(v);
        else if (!strcmp(k, "test_warn")) a->test_warn = v;
        else if (!strcmp(k, "test_ms")) a->test_ms = atoi(v);
        else if (!strcmp(k, "replay")) a->replay = v;
        else if (!strcmp(k, "replay_fps")) a->replay_fps = atof(v);
        else if (!strcmp(k, "verbose")) a->verbose = atoi(v);
        else if (!strcmp(k, "replay_speed")) a->replay_speed = atof(v);
        else if (!strcmp(k, "replay_speeds")) a->replay_speeds = v;
        else if (!strcmp(k, "snapshot")) a->snapshot = v;
        else if (!strcmp(k, "vp")) a->vp = atoi(v);
        else if (!strcmp(k, "vp_sign")) a->vpc.vp_sign = atoi(v);
        else if (!strcmp(k, "bumper")) a->vpc.bumper_offset = atof(v);
        else if (!strcmp(k, "vp_min_speed")) a->vpc.min_speed_kmh = atof(v);
        else if (!strcmp(k, "fcw_decel")) a->vpc.fcw_decel = atof(v);
        else if (!strcmp(k, "aeb_decel")) a->vpc.aeb_decel = atof(v);
        else fprintf(stderr, "unknown option %s\n", k);
    }
}

// Скорость из /pps/vehicle/mcu2soc/VehicleGauges: GaugesData base64, 8 байт. sai_clusterhmi собирает
// Gauge.ActualSpeed как (b<<8)+b из двух байтов своей структуры (функция 0x36580) — берём байты 0-1
// big-endian, км/ч. Что это именно байты 0-1 GaugesData — предположение (на стоянке 0), проверить на ходу.
float read_speed() {
    int fd = open("/pps/vehicle/mcu2soc/VehicleGauges", QNX_O_RDONLY);
    if (fd < 0) return -1;
    char buf[512];
    int n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    const char* p = strstr(buf, "\"value\":\"");
    if (!p) return -1;
    p += 9;
    static const char* tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    uint8_t out[16]; int on = 0; unsigned acc = 0; int bits = 0;
    for (; *p && *p != '"' && on < 16; p++) {
        const char* q = strchr(tbl, *p);
        if (!q || *p == '=') break;
        acc = (acc << 6) | (unsigned)(q - tbl); bits += 6;
        if (bits >= 8) { bits -= 8; out[on++] = (uint8_t)(acc >> bits); }
    }
    if (on < 2) return -1;
    return (float)((out[0] << 8) | out[1]);
}

// Источник кадров для replay: TCP-подключение к frame_server на Android, кадры RGB подряд.
int replay_connect(const char* spec) {
    char ip[32]; snprintf(ip, sizeof ip, "%s", spec);
    char* c = strchr(ip, ':'); if (!c) return -1; *c = 0; int port = atoi(c + 1);
    unsigned a, b, cc, d;
    if (sscanf(ip, "%u.%u.%u.%u", &a, &b, &cc, &d) != 4) return -1;
    uint8_t sa[16] = {16, 2, (uint8_t)(port >> 8), (uint8_t)port, (uint8_t)a, (uint8_t)b, (uint8_t)cc, (uint8_t)d};
    int fd = socket(2, 1, 0);
    if (fd < 0 || connect(fd, (const struct sockaddr*)sa, 16) < 0) return -1;
    return fd;
}

bool replay_read(int fd, uint8_t* dst, int n) {
    int got = 0;
    while (got < n) { int r = read(fd, dst + got, n - got); if (r <= 0) return false; got += r; }
    return true;
}

void logf_ts(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void logf_ts(const char* fmt, ...) {
    char buf[512];
    va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    fprintf(stderr, "[%10.3f] %s\n", now_ms() / 1000.0, buf);
}
}  // namespace

int main(int argc, char** argv) {
    Args a;
    parse_args(argc, argv, &a);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGHUP, SIG_IGN);    // запуск из telnet-сессии (приложение, hu.sh): её закрытие не должно останавливать демон
    signal(SIGPIPE, SIG_IGN);   // обрыв соединения с приложением не должен ронять демон

    if (a.snapshot) return calibration_snapshot(a.snapshot, a.input);

    ClusterWarn cw;
    if (a.pps || a.test_warn) {
        if (!cw.open()) { logf_ts("cannot open PPS VehicleAdasWarn"); if (a.test_warn) return 1; a.pps = 0; }
    }
    if (a.test_warn) {
        logf_ts("test: warn value %s for %d ms", a.test_warn, a.test_ms);
        cw.set(a.test_warn);
        usleep(a.test_ms * 1000);
        cw.set(a.clear_code);
        cw.close();
        return 0;
    }

    SnpeRunner net;
    if (!net.init(a.dlc, 0)) return 1;
    Capture cam;
    int replay_fd = -1, replay_idx = 0;
    if (a.replay) {
        replay_fd = replay_connect(a.replay);
        if (replay_fd < 0) { logf_ts("replay: cannot connect %s", a.replay); return 1; }
    } else if (!cam.open(a.input)) return 1;
    static Preprocess pp;
    if (!a.replay) pp.setup(cam.width(), cam.height(), 120, 1024, 512, false);
    Tracker trk(a.fcw);
    VpFcw vpf(a.vpc);
    int vp_run_fcw = 0, vp_run_aeb = 0;
    AppStream app;
    if (!app.setup(a.app_ip, a.app_port)) logf_ts("bad app address %s", a.app_ip);
    if (a.pps) cw.set(a.clear_code);
    logf_ts("running: input %d, %.0f fps, pps %d, app %s:%d, score %.2f, ttc %.1f/%.1f", a.input, a.fps, a.pps,
            a.app_ip, a.app_port, a.dec.score_thr, a.fcw.ttc_warn, a.fcw.ttc_alarm);

    std::vector<float> rspeeds;
    if (a.replay_speeds) {
        FILE* sf = fopen(a.replay_speeds, "r");
        char line[64];
        while (sf && fgets(line, sizeof line, sf)) rspeeds.push_back(strtof(line, nullptr));
        if (sf) fclose(sf);
        logf_ts("replay speeds: %zu values", rspeeds.size());
    }
    std::vector<Det> dets;
    static char json[4096];
    const double t0 = now_ms();
    double last_proc = -1e9, last_stats = now_ms(), last_speed = 0, warn_since = 0;
    float speed = -1;
    unsigned seq = 0;
    int n_cam = 0, n_proc = 0, errs = 0;
    double sum_pre = 0, sum_inf = 0, sum_post = 0;
    FcwState shown = FCW_NONE;
    const double period = 1000.0 / a.fps;

    while (!g_stop) {
        double now, t_frame;
        if (replay_fd >= 0) {
            if (!replay_read(replay_fd, net.input(), 1024 * 512 * 3)) { logf_ts("replay: end after %d frames", replay_idx); break; }
            now = now_ms();
            t_frame = replay_idx++ / a.replay_fps;
            n_cam++;
        } else {
            CamFrame f; int err;
            if (!cam.get(&f, 500, &err)) {
                if (err && ++errs > 20) { logf_ts("camera errors, exiting"); break; }
                continue;
            }
            n_cam++;
            now = now_ms();
            if (now - last_proc < period - 5) { cam.release(f); continue; }
            last_proc = now;
            t_frame = f.ts_ns ? f.ts_ns / 1e9 : now / 1000.0;
            pp.run(f.data, f.stride, net.input());
            cam.release(f);
        }
        double t1 = now_ms();
        HeadMaps hm;
        if (!net.run(net.input(), &hm)) { if (g_stop || ++errs > 20) break; continue; }
        double t2 = now_ms();
        decode_heads(hm, a.dec, &dets);
        static double t_base = t_frame;
        FcwResult r = trk.update(dets, t_frame - t_base, 1920, 1080);
        if (!rspeeds.empty()) speed = rspeeds[std::min((size_t)std::max(replay_idx - 1, 0), rspeeds.size() - 1)];
        else if (a.replay_speed >= 0) speed = a.replay_speed;
        else if (now - last_speed > 500) { speed = read_speed(); last_speed = now; }
        VpResult vr;
        if (a.vp) {
            vr = vpf.update(dets, t_frame - t_base, speed);
            vp_run_aeb = vr.level >= 2 ? vp_run_aeb + 1 : 0;
            vp_run_fcw = vr.level >= 1 ? vp_run_fcw + 1 : 0;
            FcwState vs = vp_run_aeb >= a.fcw.persist ? FCW_ALARM : vp_run_fcw >= a.fcw.persist ? FCW_WARN : FCW_NONE;
            if (vs > r.state) r.state = vs;
        }
        if (a.min_speed > 0 && speed >= 0 && speed < a.min_speed) r.state = FCW_NONE;

        // баннер: поднимаем сразу, повышаем сразу, снимаем не раньше hold_ms после последнего подтверждения.
        // Время — по кадрам (в replay кадры идут быстрее реального времени).
        const double tf_ms = (t_frame - t_base) * 1000.0;
        if (r.state != FCW_NONE) warn_since = tf_ms;
        FcwState want = r.state != FCW_NONE ? (r.state > shown ? r.state : shown)
                                            : (tf_ms - warn_since < a.hold_ms ? shown : FCW_NONE);
        if (want != shown) {
            const char* code = want == FCW_ALARM ? a.alarm_code : want == FCW_WARN ? a.warn_code : a.clear_code;
            if (a.pps) cw.set(code);
            logf_ts("STATE %d -> %d (code %s): lead %d ttc %.2f z %.1f t_exit %.2f | vp d %.1f v %+.2f a %+.2f lvl %d | speed %.0f",
                    shown, want, code, r.lead_id, r.ttc, r.z, r.t_exit, vr.d, vr.vel, vr.accel, vr.level, speed);
            shown = want;
        }
        if (a.verbose) {
            const Track* L = nullptr;
            for (const Track& tr : trk.tracks()) if (tr.id == r.lead_id) L = &tr;
            logf_ts("f t=%.2f dets %zu lead %d w %.0f ttc %.2f z %.1f exit %.2f sup %d | vp raw %.1f d %.1f±%.1f v %+.2f a %+.2f lvl %d cut %d | state %d shown %d",
                    t_frame - t_base, dets.size(), r.lead_id, L ? L->box.x2 - L->box.x1 : 0.f, r.ttc, r.z, r.t_exit,
                    r.suppressed, vr.raw_d, vr.d, vr.d_sigma, vr.vel, vr.accel, vr.level, vr.cut_in, r.state, shown);
        }
        double t3 = now_ms();
        n_proc++;
        sum_pre += t1 - now; sum_inf += t2 - t1; sum_post += t3 - t2;
        float fps_now = now - last_stats > 500 ? 1000.0f * n_proc / (float)(now - last_stats) : 0;
        int jn = format_frame_json(json, sizeof json, seq++, t_frame - t_base, speed, r, trk.tracks(),
                                   a.pps ? cw.current() : "", (float)(t2 - t1), fps_now, vr);
        app.send(json, jn, now);

        if (now - last_stats > a.stats_s * 1000) {
            double el = (now - last_stats) / 1000;
            logf_ts("stats: cam %.1f fps, proc %.1f fps, pre %.1f ms, dsp %.1f ms, post %.2f ms, tracks %zu, dets %zu, speed %.0f, app %s sent %d dropped %d",
                    n_cam / el, n_proc / el, sum_pre / n_proc, sum_inf / n_proc, sum_post / n_proc, trk.tracks().size(),
                    dets.size(), speed, app.connected() ? "up" : "down", app.sent, app.dropped);
            n_cam = n_proc = 0; sum_pre = sum_inf = sum_post = 0; last_stats = now;
        }
    }
    if (a.pps) cw.set(a.clear_code);
    cw.close();
    if (!a.replay) cam.close();
    logf_ts("stopped");
    return 0;
}
