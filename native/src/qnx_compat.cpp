#include "qnx_compat.h"
#include <stdio.h>
#include <stdlib.h>
extern "C" void _Unwind_Resume(void*) {
    fprintf(stderr, "fatal: C++ exception unwinding is not supported in this build\n");
    abort();
}
