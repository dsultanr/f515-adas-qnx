// cap_test <input_desc> <seconds> <out.rgb> — частота кадров, время препроцессинга, один кадр RGB 1024x512.
#include <stdio.h>
#include <stdlib.h>
#include "capture.h"
#include "preprocess.h"
#include "qnx_compat.h"

int main(int argc, char** argv) {
    int desc = argc > 1 ? atoi(argv[1]) : 4;
    double secs = argc > 2 ? atof(argv[2]) : 5;
    const char* out = argc > 3 ? argv[3] : "/dev/shmem/fcw_cap.rgb";
    Capture cam;
    if (!cam.open(desc)) return 1;
    static Preprocess pp;
    pp.setup(cam.width(), cam.height(), 120, 1024, 512, false);
    static uint8_t rgb[1024 * 512 * 3];
    double t0 = now_ms(), pre_sum = 0;
    int frames = 0, timeouts = 0, lo = 0, hi = 0;
    while (now_ms() - t0 < secs * 1000) {
        CamFrame f; int err;
        if (!cam.get(&f, 200, &err)) {
            if (err) { fprintf(stderr, "get_frame error %d\n", err); break; }
            timeouts++; continue;
        }
        if (frames % 10 == 0) {
            double t = now_ms();
            pp.run(f.data, f.stride, rgb);
            pre_sum += now_ms() - t;
            if (frames == 30) {
                Preprocess::luma_range(f.data, f.width, f.height, f.stride, &lo, &hi);
                FILE* o = fopen(out, "wb"); fwrite(rgb, 1, sizeof rgb, o); fclose(o);
            }
        }
        frames++;
        cam.release(f);
    }
    double el = (now_ms() - t0) / 1000;
    fprintf(stderr, "frames %d in %.1f s = %.1f fps, timeouts %d, preprocess %.1f ms avg, luma %d..%d\n",
            frames, el, frames / el, timeouts, pre_sum / ((frames + 9) / 10), lo, hi);
    cam.close();
    return 0;
}
