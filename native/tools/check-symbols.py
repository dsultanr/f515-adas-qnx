#!/usr/bin/env python3
import subprocess
import sys
from pathlib import Path
from elf_exports import exports
root = Path(__file__).resolve().parents[1]
available = set()
for f in (root/'deps/runtime').iterdir(): available.update(exports(f))
text = subprocess.check_output(['llvm-readelf-14', '--dyn-syms', sys.argv[1]], text=True)
needed = set(); own = set()
for line in text.splitlines():
    fields = line.split()
    if len(fields) < 8 or not fields[0].rstrip(':').isdigit(): continue
    symbol = fields[7].split('@')[0]
    if fields[6] == 'UND': needed.add(symbol)
    else: own.add(symbol)
if not needed: raise SystemExit('No dynamic imports found; refusing an empty check')
missing = needed - available - own
if missing: raise SystemExit('Missing QNX exports: '+', '.join(sorted(missing)))
print('QNX symbols verified: %d imports' % len(needed))
