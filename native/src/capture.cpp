#include "capture.h"
#include <stdio.h>
#include <string.h>
#include "qcarcam.h"

extern "C" {
// libpmem_client.so (сигнатуры по incs/qnx/pmem.h Hexagon SDK; аргументы — как у штатного qcarcam_test)
void* pmem_malloc_ext_v2(uint32_t size, uint32_t id, uint32_t flags, uint32_t alignment,
                         uint32_t vmid_mask, void** handlep, void* rsv1);
int pmem_free(void* vaddr);
}

namespace {
// В AIS прошивки (QNX 7.0) элемент qcarcam_query_inputs — 320 байт (шаг в qcarcam_test), а в найденном
// заголовке 4.7 — 316: смещения полей те же (res 164, color_fmt 288, flags 312), в конце 4 байта.
struct QnxInput {
    qcarcam_input_t in;
    unsigned int pad;
};
static_assert(sizeof(QnxInput) == 320, "qcarcam_input_t layout");
static_assert(sizeof(qcarcam_frame_info_t) == 48, "qcarcam_frame_info_t layout");
static_assert(sizeof(qcarcam_buffer_t) == 80, "qcarcam_buffer_t layout");
const uint32_t PMEM_ID_CAMERA = 5;          // как в qcarcam_test
const uint32_t PMEM_FLAGS = 0x10 | 0x40;    // PHYS_NON_CONTIG | SHMEM, без кеша (как в qcarcam_test)
const uint32_t PMEM_ALIGN_4K = 12;
}

bool Capture::open(int desc, int nbufs) {
    qcarcam_init_t init;
    memset(&init, 0, sizeof init);
    init.version = QCARCAM_VERSION;   // 0x407, совпадает с qcarcam_test прошивки
    init.debug_tag = "fcwd";
    qcarcam_ret_t r = qcarcam_initialize(&init);
    if (r != QCARCAM_RET_OK) { fprintf(stderr, "[cam] initialize: %d\n", r); return false; }

    static QnxInput qin[16];
    unsigned int n = 0;
    r = qcarcam_query_inputs(&qin[0].in, 16, &n);
    if (r != QCARCAM_RET_OK) { fprintf(stderr, "[cam] query_inputs: %d\n", r); return false; }
    int found = -1;
    for (unsigned i = 0; i < n; i++) {
        const qcarcam_input_t& q = qin[i].in;
        fprintf(stderr, "[cam] input desc=%d '%s' %ux%u fmt 0x%x\n", (int)q.desc, q.name, q.res[0].width, q.res[0].height, q.color_fmt[0]);
        if ((int)q.desc == desc) found = i;
    }
    if (found < 0) { fprintf(stderr, "[cam] input %d not found among %u\n", desc, n); return false; }
    const qcarcam_input_t& in = qin[found].in;
    w_ = in.res[0].width;
    h_ = in.res[0].height;
    fprintf(stderr, "[cam] input %d '%s': %ux%u @%.1f, fmt 0x%x\n", desc, in.name, w_, h_, in.res[0].fps, in.color_fmt[0]);
    if (in.color_fmt[0] != QCARCAM_FMT_UYVY_8) { fprintf(stderr, "[cam] unexpected color format\n"); return false; }

    hndl_ = qcarcam_open((qcarcam_input_desc_t)desc);
    if (!hndl_) { fprintf(stderr, "[cam] open failed\n"); return false; }

    n_ = nbufs;
    stride_ = w_ * 2;
    unsigned size = stride_ * h_;
    static qcarcam_buffer_t bufs[20];
    memset(bufs, 0, sizeof bufs);
    for (int i = 0; i < n_; i++) {
        vaddr_[i] = pmem_malloc_ext_v2(size, PMEM_ID_CAMERA, PMEM_FLAGS, PMEM_ALIGN_4K, 0, &pmh_[i], nullptr);
        if (!vaddr_[i]) { fprintf(stderr, "[cam] pmem alloc failed\n"); return false; }
        bufs[i].n_planes = 1;
        bufs[i].planes[0].width = w_;
        bufs[i].planes[0].height = h_;
        bufs[i].planes[0].stride = stride_;
        bufs[i].planes[0].size = size;
        bufs[i].planes[0].p_buf = pmh_[i];   // как в qcarcam_test: pmem-handle, а не адрес
    }
    qcarcam_buffers_t b;
    memset(&b, 0, sizeof b);
    b.color_fmt = QCARCAM_FMT_UYVY_8;
    b.buffers = bufs;
    b.n_buffers = n_;
    b.flags = QCARCAM_BUFFER_FLAG_OS_HNDL;
    r = qcarcam_s_buffers(hndl_, &b);
    if (r != QCARCAM_RET_OK) { fprintf(stderr, "[cam] s_buffers: %d\n", r); return false; }
    r = qcarcam_start(hndl_);
    if (r != QCARCAM_RET_OK) { fprintf(stderr, "[cam] start: %d\n", r); return false; }
    return true;
}

bool Capture::get(CamFrame* f, int timeout_ms, int* err) {
    qcarcam_frame_info_t fi;
    memset(&fi, 0, sizeof fi);
    qcarcam_ret_t r = qcarcam_get_frame(hndl_, &fi, (unsigned long long)timeout_ms * 1000000ULL, 0);
    *err = 0;
    if (r == QCARCAM_RET_TIMEOUT) return false;
    if (r != QCARCAM_RET_OK || fi.idx < 0 || fi.idx >= n_) { *err = r ? (int)r : -1; return false; }
    f->data = (const uint8_t*)vaddr_[fi.idx];
    f->width = w_;
    f->height = h_;
    f->stride = stride_;
    f->idx = fi.idx;
    f->ts_ns = fi.timestamp;
    f->seq = fi.seq_no;
    return true;
}

void Capture::release(const CamFrame& f) {
    qcarcam_release_frame(hndl_, f.idx);
}

void Capture::close() {
    if (hndl_) {
        qcarcam_stop(hndl_);
        qcarcam_close(hndl_);
        hndl_ = nullptr;
    }
    for (int i = 0; i < n_; i++) if (vaddr_[i]) pmem_free(vaddr_[i]);
    n_ = 0;
    qcarcam_uninitialize();
}
