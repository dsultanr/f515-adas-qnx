// Тулчейн собирает с заголовками glibc, а линкует с libc QNX 7.0. Константы и структуры,
// которые у QNX отличаются, берём отсюда, а не из glibc-заголовков.
#pragma once
#include <time.h>
#include <stdint.h>

#define QNX_CLOCK_MONOTONIC 2        // glibc: 1
#define QNX_O_RDONLY  0x0000
#define QNX_O_WRONLY  0x0001
#define QNX_O_RDWR    0x0002
#define QNX_O_APPEND  0x0008
#define QNX_O_CREAT   0x0100         // glibc: 0100 (восьмеричное)
#define QNX_O_TRUNC   0x0200         // glibc: 01000
#define QNX_O_NONBLOCK 0x0080

static inline double now_ms() {
    struct timespec ts;
    clock_gettime(QNX_CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

#ifdef __cplusplus
extern "C" {
#endif
// libgcc_s (_Unwind_Resume) в sysroot нет. SNPE-заголовки требуют -fexceptions, но бросают
// только при неправильном использовании API; если до раскрутки дойдёт — честно падаем.
void _Unwind_Resume(void* exc);
// errno QNX (glibc-заголовки ведут errno в __errno_location, которого в libc QNX нет)
int* __get_errno_ptr(void);
#ifdef __cplusplus
}
#endif

static inline int qnx_errno() { return *__get_errno_ptr(); }
