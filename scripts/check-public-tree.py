#!/usr/bin/env python3
"""Audit Git content without ever printing matched secret material."""
import argparse
import pathlib
import re
import subprocess
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--commit', help='Check a committed tree instead of the index')
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parents[1]
def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args])
if a.commit:
    records = git('ls-tree', '-rz', '--full-tree', a.commit).split(b'\0')
else:
    records = git('ls-files', '--stage', '-z').split(b'\0')
patterns = {
    'private key': rb'-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----',
    'GitHub token': rb'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})',
    'Telegram bot token': rb'\b[0-9]{7,12}:(?=[A-Za-z0-9_-]{0,34}[A-Za-z])[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])',
    'AWS access ID': rb'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b',
    'credential in URL': rb'https?://[^\s/"<>:@]+:[^\s/"<>@]+@',
}
bad = []; count = 0
for record in records:
    if not record: continue
    head, rawname = record.split(b'\t', 1)
    name = rawname.decode('utf-8'); parts = pathlib.PurePosixPath(name).parts
    obj = head.split()[2 if a.commit else 1].decode()
    count += 1
    if any(x in ('.local', 'deps', 'build', '__pycache__', '.ssh') for x in parts):
        bad.append((name, 'local/generated directory'))
    base = parts[-1].lower()
    if (base.startswith('adbkey') or base.startswith('.env') or base == 'local.properties'
        or pathlib.Path(base).suffix in ('.apk','.jks','.keystore','.p12','.pfx','.pem','.key')):
        bad.append((name, 'credential/artifact filename'))
    if head.split()[0] in (b'120000', b'160000'):
        bad.append((name, 'unexpected symlink/submodule')); continue
    content = git('cat-file', 'blob', obj)
    for label, pattern in patterns.items():
        if re.search(pattern, content): bad.append((name, label))
if bad:
    for name, reason in bad: print(name+': '+reason, file=sys.stderr)
    raise SystemExit(1)
if not count: raise SystemExit('No tracked files; stage the source tree before checking')
print('Public tree checked: %d files; no prohibited files or known secret patterns.' % count)
