// fcw_recv port seconds — TCP-приёмник потока fcwd (JSON-строки): печатает первые строки и итог.
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
int main(int argc, char** argv) {
    int port = argc > 1 ? atoi(argv[1]) : 62715, secs = argc > 2 ? atoi(argv[2]) : 10;
    int ls = socket(AF_INET, SOCK_STREAM, 0), one = 1;
    setsockopt(ls, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET; a.sin_port = htons(port); a.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(ls, (struct sockaddr*)&a, sizeof a) < 0 || listen(ls, 1) < 0) { perror("bind/listen"); return 1; }
    struct timeval tv = {1, 0};
    setsockopt(ls, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    time_t end = time(0) + secs;
    long lines = 0, bytes = 0;
    char buf[65536];
    while (time(0) < end) {
        int c = accept(ls, 0, 0);
        if (c < 0) continue;
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
        printf("connected\n");
        while (time(0) < end) {
            ssize_t r = recv(c, buf, sizeof buf - 1, 0);
            if (r == 0) break;
            if (r < 0) continue;
            buf[r] = 0; bytes += r;
            for (char* p = buf; (p = strchr(p, '\n')); p++) { lines++; }
            if (lines <= 3 || lines % 100 == 0) printf("%ld lines, last chunk: %.300s\n", lines, buf);
        }
        close(c);
    }
    printf("total %ld lines, %ld bytes, %.1f lines/s, %.1f KB/s\n", lines, bytes, lines / (double)secs, bytes / 1024.0 / secs);
    return 0;
}
