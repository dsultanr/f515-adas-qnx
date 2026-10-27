#include "outputs.h"
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include "qnx_compat.h"

bool ClusterWarn::open(const char* path) {
    fd_ = ::open(path, QNX_O_WRONLY);
    return fd_ >= 0;
}

bool ClusterWarn::set(const char* v) {
    if (fd_ < 0) return false;
    if (!strcmp(v, cur_)) return true;
    char line[128];
    int n = snprintf(line, sizeof line, "McuToSocAdasWarn:json:{\"len\":2,\"value\":\"%s\"}\n", v);
    if (write(fd_, line, n) != n) return false;
    snprintf(cur_, sizeof cur_, "%s", v);
    return true;
}

void ClusterWarn::close() {
    if (fd_ >= 0) ::close(fd_);
    fd_ = -1;
}

bool AppStream::setup(const char* ip, int port) {
    unsigned a, b, c, d;
    if (sscanf(ip, "%u.%u.%u.%u", &a, &b, &c, &d) != 4) return false;
    // BSD sockaddr_in: len, family, port (BE), addr (BE), zero[8]
    addr_[0] = 16; addr_[1] = 2;
    addr_[2] = (uint8_t)(port >> 8); addr_[3] = (uint8_t)port;
    addr_[4] = (uint8_t)a; addr_[5] = (uint8_t)b; addr_[6] = (uint8_t)c; addr_[7] = (uint8_t)d;
    return true;
}

void AppStream::try_connect(double now) {
    if (now < next_try_) return;
    next_try_ = now + 2000;
    int fd = socket(2 /*AF_INET*/, 1 /*SOCK_STREAM*/, 0);
    if (fd < 0) { last_errno = qnx_errno(); return; }
    // соединение внутри гипервизора: отказ (нет слушателя) приходит сразу, блокирующий connect не тормозит цикл
    if (connect(fd, (const struct sockaddr*)addr_, 16) < 0) { last_errno = qnx_errno(); ::close(fd); return; }
    int fl = fcntl(fd, 3 /*F_GETFL*/);
    fcntl(fd, 4 /*F_SETFL*/, fl | QNX_O_NONBLOCK);
    fd_ = fd;
    connects++;
}

void AppStream::send(const char* buf, int len, double now) {
    if (fd_ < 0) try_connect(now);
    if (fd_ < 0) return;
    int r = ::send(fd_, buf, len, 0);
    if (r == len) { sent++; return; }
    int e = r < 0 ? qnx_errno() : 0;
    if (r < 0 && (e == 35 /*EAGAIN/EWOULDBLOCK в QNX*/ || e == 11)) { dropped++; return; }
    // частичная запись или ошибка: строка порвана, проще переподключиться
    last_errno = e;
    ::close(fd_);
    fd_ = -1;
}

int format_frame_json(char* buf, int cap, unsigned seq, double t, float speed, const FcwResult& r,
                      const std::vector<Track>& tracks, const char* warn, float infer_ms, float fps,
                      const VpResult& vp) {
    int n = snprintf(buf, cap,
                     "{\"v\":1,\"seq\":%u,\"t\":%.3f,\"w\":1920,\"h\":1080,\"speed\":%.0f,\"state\":%d,\"warn\":\"%s\","
                     "\"lead\":%d,\"ttc\":%.2f,\"z\":%.1f,\"t_exit\":%.2f,\"sup\":%d,\"infer_ms\":%.1f,\"fps\":%.1f,"
                     "\"vp\":{\"cipo\":%d,\"cut_in\":%d,\"raw_d\":%.1f,\"d\":%.1f,\"d_sd\":%.1f,\"vel\":%.2f,\"accel\":%.2f,\"lvl\":%d,\"active\":%d},"
                     "\"objs\":[",
                     seq, t, speed, (int)r.state, warn, r.lead_id, r.ttc, r.z, r.t_exit, r.suppressed ? 1 : 0, infer_ms, fps,
                     vp.cipo, vp.cut_in, vp.raw_d, vp.d, vp.d_sigma, vp.vel, vp.accel, vp.level, vp.active);
    bool first = true;
    for (const Track& tr : tracks) {
        if (tr.misses > 0 || n > cap - 120) continue;
        n += snprintf(buf + n, cap - n, "%s{\"id\":%d,\"c\":%d,\"s\":%.2f,\"b\":[%d,%d,%d,%d]}", first ? "" : ",", tr.id,
                      tr.cls, tr.box.score, (int)tr.box.x1, (int)tr.box.y1, (int)tr.box.x2, (int)tr.box.y2);
        first = false;
    }
    n += snprintf(buf + n, cap - n, "]}\n");
    return n;
}
