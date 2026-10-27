#!/usr/bin/env python3
"""Generate link-only ELF stubs; these must NEVER be deployed to QNX."""
import re
import subprocess
from pathlib import Path
from elf_exports import exports
root = Path(__file__).resolve().parents[1]
out = root/'build/stubs'
out.mkdir(parents=True, exist_ok=True)
for lib in sorted((root/'deps/runtime').iterdir()):
    dynamic = subprocess.check_output(['llvm-readelf-14', '-d', str(lib)], text=True)
    match = re.search(r'\(SONAME\).*\[(.*?)\]', dynamic)
    if not match: raise SystemExit('No SONAME: '+lib.name)
    soname = match[1]
    if Path(soname).name != soname: raise SystemExit('Invalid SONAME')
    symbols = sorted(exports(lib))
    if not symbols: raise SystemExit('Empty export set: '+lib.name)
    src = out/(lib.name+'.c'); obj = out/(lib.name+'.o')
    # Assembly labels preserve C++ names and identifiers that are C keywords.
    src.write_text(''.join('void stub_%d(void) __asm__("%s"); void stub_%d(void){}\n' % (i, s, i)
                           for i, s in enumerate(symbols) if re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', s)))
    subprocess.run(['clang-14', '--target=aarch64-unknown-nto-qnx7.1.0', '-fPIC', '-c', str(src), '-o', str(obj)], check=True)
    subprocess.run(['ld.lld-14', '-m', 'aarch64elf', '-shared', '-soname', soname, '-o', str(out/soname), str(obj)], check=True)
    link = re.sub(r'\.so(?:\..*)?$', '.so', soname)
    if link != soname:
        (out/link).unlink(missing_ok=True)
        (out/link).symlink_to(soname)
