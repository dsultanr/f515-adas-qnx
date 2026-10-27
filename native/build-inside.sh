#!/usr/bin/env bash
set -euo pipefail
cd /work
python3 native/tools/verify-deps.py
mkdir -p native/build/stubs native/build/obj
python3 native/tools/make-stubs.py
flags=(--target=aarch64-unknown-nto-qnx7.1.0 -nostdinc++
    -isystem /usr/include/c++/v1 -isystem /usr/aarch64-linux-gnu/include
    -D_GNU_SOURCE -D_LIBCPP_HAS_THREAD_API_PTHREAD -D__QNX__ -D__QNXNTO__
    -frtti -fexceptions -fPIC -O2 -std=c++17 -Wno-deprecated-declarations
    -Inative/deps/snpe/include/zdl -Inative/deps/qcarcam -Inative/src)
objects=()
for unit in fcwd capture preprocess snpe_runner decoder tracker outputs vp_fcw qnx_compat; do
    obj="native/build/obj/$unit.o"
    clang++-14 "${flags[@]}" -c "native/src/$unit.cpp" -o "$obj"
    objects+=("$obj")
done
for unit in crt_qnx qnx_compat; do
    clang-14 --target=aarch64-unknown-nto-qnx7.1.0 -c "native/toolchain/$unit.s" -o "native/build/obj/${unit}_asm.o"
done
ld.lld-14 -m aarch64elf --dynamic-linker=/usr/lib/ldqnx-64.so.2 -pie --no-rosegment \
    --allow-shlib-undefined -z now --hash-style=gnu -e _start \
    native/build/obj/crt_qnx_asm.o native/build/obj/qnx_compat_asm.o "${objects[@]}" \
    -Lnative/build/stubs -lSNPE -lais_client -lpmem_client -lsocket -lc++ -lc -lm -o native/build/fcwd
python3 native/tools/check-symbols.py native/build/fcwd
file native/build/fcwd
sha256sum native/build/fcwd
