// Выходы FCW: PPS-объект приборки (штатный баннер) и UDP-поток для приложения на Android.
#pragma once
#include <stdint.h>
#include <vector>
#include "tracker.h"
#include "vp_fcw.h"

// /pps/vehicle/mcu2soc/VehicleAdasWarn — тот же объект, куда пишет MCU; sai_clusterhmi рисует по нему баннер.
// Значение — 2 байта base64 (код, подкод). Соответствие кодов баннерам подтверждается скриншотом приборки.
class ClusterWarn {
public:
    bool open(const char* path = "/pps/vehicle/mcu2soc/VehicleAdasWarn");
    bool set(const char* b64_value);   // пишет только при смене значения
    const char* current() const { return cur_; }
    void close();
private:
    int fd_ = -1;
    char cur_[16] = "";
};

// JSON-строки по TCP к приложению на Android. Фаервол Android (vendor_fw_input) пропускает от QNX входящий
// TCP только на порты 60000-65000, UDP — только с системных портов источника, поэтому TCP.
// Нет слушателя — переподключение раз в 2 с; не успевает читать — кадр выбрасывается.
class AppStream {
public:
    bool setup(const char* ip, int port);
    void send(const char* buf, int len, double now_ms);
    bool connected() const { return fd_ >= 0; }
    int sent = 0, dropped = 0, connects = 0, last_errno = 0;
private:
    void try_connect(double now_ms);
    int fd_ = -1;
    double next_try_ = 0;
    uint8_t addr_[16] = {};
};

int format_frame_json(char* buf, int cap, unsigned seq, double t, float speed_kmh, const FcwResult& r,
                      const std::vector<Track>& tracks, const char* warn_value, float infer_ms, float fps,
                      const VpResult& vp);
