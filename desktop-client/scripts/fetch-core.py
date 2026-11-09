#!/usr/bin/env python3
"""Fetch a pinned upstream core, verify SHA-256, extract only runtime files."""
import argparse
import hashlib
import io
from pathlib import Path
import tarfile
import urllib.request
import zipfile

VERSION = '1.14.4'
ASSETS = {
    'linux-x64': ('linux-amd64.tar.gz', '699a1b510aa4396b2176d32d1181920399ebf1e267835da6f3b598d0f7c24968'),
    'windows-x64': ('windows-amd64.zip', '99185d75d314a07dbba4a130c8ad986cbeb629a07927975636b341ca61f991df'),
}
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('target', choices=ASSETS)
    parser.add_argument('--archive', type=Path, help='Use an already downloaded archive')
    args = parser.parse_args()
    suffix, digest = ASSETS[args.target]
    name = f'sing-box-{VERSION}-{suffix}'
    raw = args.archive.read_bytes() if args.archive else urllib.request.urlopen(
        f'https://github.com/SagerNet/sing-box/releases/download/v{VERSION}/{name}', timeout=300).read()
    if hashlib.sha256(raw).hexdigest() != digest:
        raise SystemExit('SHA-256 mismatch; nothing extracted')
    dest = Path(__file__).resolve().parents[1] / 'packaging' / args.target / 'core'
    dest.mkdir(parents=True, exist_ok=True)
    allowed = {'sing-box', 'sing-box.exe', 'libcronet.so', 'libcronet.dll', 'wintun.dll', 'LICENSE'}
    if suffix.endswith('.zip'):
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            files = [(Path(n).name, archive.read(n)) for n in archive.namelist() if Path(n).name in allowed]
    else:
        with tarfile.open(fileobj=io.BytesIO(raw), mode='r:gz') as archive:
            files = [(Path(m.name).name, archive.extractfile(m).read()) for m in archive if m.isfile() and Path(m.name).name in allowed]
    for filename, contents in files:
        target = dest / filename
        target.write_bytes(contents)
        if filename == 'sing-box': target.chmod(0o755)
    executable = 'sing-box.exe' if args.target.startswith('windows') else 'sing-box'
    if not (dest / executable).is_file(): raise SystemExit('Archive has no core executable')
    print(f'Verified sing-box {VERSION} -> {dest}')
if __name__ == '__main__': main()
