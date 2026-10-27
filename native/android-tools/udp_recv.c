// udp_recv port seconds — печатает принятые датаграммы (для проверки потока fcwd с QNX).
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
int main(int argc, char** argv) {
    int port = argc > 1 ? atoi(argv[1]) : 47215, secs = argc > 2 ? atoi(argv[2]) : 10;
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    struct sockaddr_in a = {0};
    a.sin_family = AF_INET; a.sin_port = htons(port); a.sin_addr.s_addr = htonl(INADDR_ANY);
    if (bind(fd, (struct sockaddr*)&a, sizeof a) < 0) { perror("bind"); return 1; }
    struct timeval tv = {1, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    time_t end = time(0) + secs;
    int n = 0; long bytes = 0;
    char buf[65536];
    while (time(0) < end) {
        ssize_t r = recv(fd, buf, sizeof buf - 1, 0);
        if (r <= 0) continue;
        buf[r] = 0; n++; bytes += r;
        if (n <= 3 || n % 50 == 0) printf("#%d %zd bytes: %s\n", n, r, buf);
    }
    printf("total %d datagrams, %ld bytes, %.1f/s\n", n, bytes, n / (double)secs);
    return 0;
}
