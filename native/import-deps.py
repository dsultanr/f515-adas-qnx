#!/usr/bin/env python3
"""Import licensed SDK headers and firmware libraries into ignored native/deps/."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--snpe', required=True, type=Path, help='SNPE 1.55.0.2958 SDK root')
p.add_argument('--qcarcam', required=True, type=Path, help='Directory containing qcarcam.h and qcarcam_types.h (4.7)')
p.add_argument('--runtime', required=True, type=Path, help='Local firmware/sysroot tree; searched recursively')
a = p.parse_args()
root = Path(__file__).resolve().parent
lock = json.loads((root/'dependencies.json').read_text())
copies = []
for name, expected in lock['runtime'].items():
    found = [f for f in a.runtime.rglob(name) if f.is_file() and hashlib.sha256(f.read_bytes()).hexdigest() == expected]
    if not found:
        p.error('Missing reference firmware library (or hash differs): '+name)
    copies.append((found[0], root/'deps/runtime'/name))
for name, expected in lock['qcarcam'].items():
    f = a.qcarcam/name
    if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest() != expected:
        p.error('Missing or incompatible QCARCAM 4.7 header: '+name)
    copies.append((f, root/'deps/qcarcam'/name))
inc = a.snpe/'include/zdl'
if not (inc/'SNPE/SNPE.hpp').is_file(): p.error('Expected SNPE SDK include/zdl/SNPE/SNPE.hpp')
for rel, expected in lock['snpe_headers'].items():
    f = inc/rel
    if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest() != expected:
        p.error('SNPE headers differ from 1.55.0.2958: '+rel)
    copies.append((f, root/'deps/snpe/include/zdl'/rel))
for source, dest in copies:
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, dest)
print('Imported verified local dependencies into native/deps/ (not committed).')
