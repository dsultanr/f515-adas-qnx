FROM debian:bookworm
RUN apt-get update && apt-get install -y --no-install-recommends \
    clang-14 lld-14 llvm-14 libc++-14-dev libc6-dev-arm64-cross python3 binutils file \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /work
