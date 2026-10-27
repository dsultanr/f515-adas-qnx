// Проверка SnpeRunner на QNX: snpe_test <dlc> <frame.rgb 1024x512x3> <runtime 0|1> <n> <out_prefix>
#include <stdio.h>
#include <stdlib.h>
#include "qnx_compat.h"
#include "snpe_runner.h"

int main(int argc, char** argv) {
    if (argc < 6) { fprintf(stderr, "usage: %s dlc frame.rgb runtime n out_prefix\n", argv[0]); return 2; }
    SnpeRunner r;
    double t0 = now_ms();
    if (!r.init(argv[1], atoi(argv[3]))) return 1;
    fprintf(stderr, "init %.0f ms\n", now_ms() - t0);
    FILE* f = fopen(argv[2], "rb");
    if (!f || fread(r.input(), 1, 1024 * 512 * 3, f) != 1024 * 512 * 3) { fprintf(stderr, "bad frame\n"); return 1; }
    fclose(f);
    HeadMaps m;
    int n = atoi(argv[4]);
    double best = 1e9, sum = 0;
    for (int i = 0; i < n; i++) {
        double t = now_ms();
        if (!r.run(r.input(), &m)) return 1;
        double d = now_ms() - t;
        sum += d;
        if (d < best) best = d;
    }
    fprintf(stderr, "execute: %d runs, mean %.1f ms, best %.1f ms\n", n, sum / n, best);
    const char* nm[6] = {"box8", "box16", "box32", "cls8", "cls16", "cls32"};
    for (int k = 0; k < 6; k++) {
        int s = k % 3, c = k < 3 ? 64 : 4;
        char path[256];
        snprintf(path, sizeof path, "%s_%s.raw", argv[5], nm[k]);
        FILE* o = fopen(path, "wb");
        fwrite(k < 3 ? m.box[s] : m.cls[s], 4, (size_t)m.h[s] * m.w[s] * c, o);
        fclose(o);
    }
    return 0;
}
