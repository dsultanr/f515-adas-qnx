#!/usr/bin/env python3
import hashlib
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
lock = json.loads((root/'dependencies.json').read_text())
for section, subdir in [('runtime', 'runtime'), ('qcarcam', 'qcarcam'), ('snpe_headers', 'snpe/include/zdl')]:
    for name, digest in lock[section].items():
        f = root/'deps'/subdir/name
        if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest() != digest:
            raise SystemExit('Missing/changed dependency '+section+'/'+name+'; run native/import-deps.py (docs/BUILD.md)')
print('Dependency hashes verified.')
