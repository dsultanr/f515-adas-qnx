// udp_test ip port — 5 датаграмм; печатает результат sendto/connect и локальный адрес сокета.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include "qnx_compat.h"
int main(int argc, char** argv) {
    unsigned a, b, c, d; sscanf(argv[1], "%u.%u.%u.%u", &a, &b, &c, &d); int port = atoi(argv[2]);
    unsigned char sa[16] = {16, 2, (unsigned char)(port >> 8), (unsigned char)port, (unsigned char)a, (unsigned char)b, (unsigned char)c, (unsigned char)d};
    int fd = socket(2, 2, 0);
    printf("socket %d errno %d\n", fd, fd < 0 ? qnx_errno() : 0);
    int r = connect(fd, (struct sockaddr*)sa, 16);
    printf("connect %d errno %d\n", r, r < 0 ? qnx_errno() : 0);
    unsigned char me[16] = {}; socklen_t ml = 16;
    getsockname(fd, (struct sockaddr*)me, &ml);
    printf("local: len %u fam %u port %u ip %u.%u.%u.%u\n", me[0], me[1], me[2] << 8 | me[3], me[4], me[5], me[6], me[7]);
    for (int i = 0; i < 5; i++) {
        char m[64]; int n = snprintf(m, sizeof m, "hello %d\n", i);
        r = send(fd, m, n, 0);
        printf("send %d errno %d\n", r, r < 0 ? qnx_errno() : 0);
        usleep(200000);
    }
    return 0;
}
