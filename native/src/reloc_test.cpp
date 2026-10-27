// Проверка перемещений R_AARCH64_RELATIVE в самодельном тулчейне: глобальный массив указателей на строки.
#include <stdio.h>
namespace { const char* kA[4] = {"alpha", "beta", "gamma", "delta"}; }
const char* kB[3] = {"one", "two", "three"};
static int f1() { return 1; } static int f2() { return 2; } static int f3() { return 3; }
int (*kF[3])() = {f1, f2, f3};
int main() {
    for (int i = 0; i < 4; i++) printf("kA[%d]=%p %.8s\n", i, (const void*)kA[i], kA[i]);
    for (int i = 0; i < 3; i++) printf("kB[%d]=%p %.8s\n", i, (const void*)kB[i], kB[i]);
    for (int i = 0; i < 3; i++) printf("kF[%d]()=%d\n", i, kF[i]());
    return 0;
}
