#!/usr/bin/env python3
"""Verify pinned assets; --refresh explicitly regenerates metadata after replacing assets."""
import hashlib
import pathlib
import sys

root = pathlib.Path(__file__).resolve().parents[1] / 'app/assets/qnx'
lines = []
for name, mode in [('fcwd', '755'), ('autospeed_heads3o_int8.dlc', '644')]:
    data = (root / name).read_bytes()
    if not data or (name == 'fcwd' and (data[:5] != b'\x7fELF\x02' or data[18:20] != b'\xb7\x00')):
        raise SystemExit('Invalid QNX asset: ' + name)
    lines.append(f'{name}={len(data)},{mode},{hashlib.sha256(data).hexdigest()}\n')
expected = ''.join(lines)
manifest = root / 'manifest.properties'
if sys.argv[1:] == ['--refresh']:
    manifest.write_text(expected)
elif sys.argv[1:]:
    raise SystemExit('Usage: qnx-assets.py [--refresh]')
elif manifest.read_text() != expected:
    raise SystemExit('QNX assets differ from manifest. Review changes, then run scripts/qnx-assets.py --refresh.')
print('QNX payload: size, SHA-256 and AArch64 ELF verified')
