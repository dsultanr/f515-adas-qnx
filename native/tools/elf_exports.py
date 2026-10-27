#!/usr/bin/env python3
"""Экспортируемые символы ELF64 без заголовков секций (библиотеки QNX stripped): по PT_DYNAMIC → DT_SYMTAB/STRTAB/HASH."""
import struct, sys
def exports(path):
    b = open(path, 'rb').read()
    phoff, = struct.unpack_from('<Q', b, 0x20); phnum, = struct.unpack_from('<H', b, 0x38)
    loads = []; dyn = None
    for i in range(phnum):
        t, fl, off, va, pa, fsz, msz, al = struct.unpack_from('<IIQQQQQQ', b, phoff + i * 56)
        if t == 1: loads.append((va, off, fsz))
        if t == 2: dyn = (off, fsz)
    v2o = lambda va: next(off + va - v for v, off, sz in loads if v <= va < v + sz)
    d = {}
    for k in range(0, dyn[1], 16):
        tag, val = struct.unpack_from('<qQ', b, dyn[0] + k)
        if tag == 0: break
        d.setdefault(tag, val)
    symtab, strtab = v2o(d[6]), v2o(d[5])
    if 4 in d: n = struct.unpack_from('<I', b, v2o(d[4]) + 4)[0]          # DT_HASH: nchain
    else:                                                                # DT_GNU_HASH
        h = v2o(d[0x6ffffef5]); nb, so, bs, _ = struct.unpack_from('<IIII', b, h)
        bk = h + 16 + bs * 8; ch = bk + nb * 4
        n = max(struct.unpack_from('<%dI' % nb, b, bk) or [0])
        if n:
            while not (struct.unpack_from('<I', b, ch + (n - so) * 4)[0] & 1): n += 1
            n += 1
    out = set()
    for i in range(1, n):
        nm, info, oth, shn, val, sz = struct.unpack_from('<IBBHQQ', b, symtab + i * 24)
        if shn != 0 and (info >> 4) in (1, 2):
            e = b.index(b'\0', strtab + nm); out.add(b[strtab + nm:e].decode())
    return out
if __name__ == '__main__':
    for p in sys.argv[1:]:
        for s in sorted(exports(p)): print(s)
