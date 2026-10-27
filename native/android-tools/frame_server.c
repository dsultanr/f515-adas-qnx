// frame_server port file.rgb — отдаёт по TCP подключившемуся (fcwd replay=...) кадры RGB 1024x512 подряд.
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>
int main(int argc, char** argv) {
    int port = atoi(argv[1]);
    int ls = socket(AF_INET, SOCK_STREAM, 0), one = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET; a.sin_port = htons(port); a.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(ls, (struct sockaddr*)&a, sizeof a) < 0 || listen(ls, 1) < 0) { perror("bind/listen"); return 1; }
    int c = accept(ls, 0, 0);
    FILE* f = fopen(argv[2], "rb");
    static char buf[1 << 20];
    size_t n; long total = 0;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0) {
        size_t off = 0;
        while (off < n) { ssize_t w = send(c, buf + off, n - off, 0); if (w <= 0) goto out; off += w; }
        total += n;
    }
out:
    printf("sent %ld bytes (%ld frames)\n", total, total / (1024 * 512 * 3));
    close(c);
    return 0;
}
