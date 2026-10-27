#!/usr/bin/env python3
"""Convert prepared binary RGB PPM images to SNPE float32 HWC inputs."""
import argparse
from pathlib import Path
import numpy as np
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('images', type=Path)
p.add_argument('output', type=Path)
a = p.parse_args()
files = sorted(a.images.glob('*.ppm'))
if not files: p.error('No calibration images')
a.output.mkdir(parents=True, exist_ok=True)
names = []
for index, f in enumerate(files):
    data = f.read_bytes()
    # Read four PPM header tokens, allowing comments, then exactly one delimiter.
    pos = 0; tokens = []
    while len(tokens) < 4:
        while pos < len(data) and data[pos] in b' \t\r\n': pos += 1
        if pos >= len(data): p.error('Incomplete PPM: '+str(f))
        if data[pos] == 35:
            end = data.find(b'\n', pos)
            if end < 0: p.error('Incomplete PPM comment: '+str(f))
            pos = end + 1; continue
        start = pos
        while pos < len(data) and data[pos] not in b' \t\r\n': pos += 1
        tokens.append(data[start:pos])
    if tokens != [b'P6', b'1024', b'512', b'255'] or pos >= len(data):
        p.error('Expected binary RGB PPM, 1024x512, maxval 255: '+str(f))
    pos += 2 if data[pos:pos+2] == b'\r\n' else 1
    pixels = data[pos:]
    if len(pixels) != 1024*512*3: p.error('Wrong PPM payload size: '+str(f))
    out = a.output/('%05d.raw' % index)
    (np.frombuffer(pixels, dtype=np.uint8).astype(np.float32)/255.).astype('<f4').tofile(str(out))
    names.append(str(out.resolve()))
(a.output/'list.txt').write_text('\n'.join(names)+'\n')
print('Calibration images:',len(files))
