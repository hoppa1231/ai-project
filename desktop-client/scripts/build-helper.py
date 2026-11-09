#!/usr/bin/env python3
"""Compile minimal platform helper against SHA-256 of the verified core bytes."""
import argparse, hashlib, os, subprocess, shutil
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('target',choices=['linux-x64','windows-x64']);a=p.parse_args()
root=Path(__file__).resolve().parents[1];win=a.target.startswith('windows');suffix='.exe' if win else ''
resources=root/'packaging'/a.target
core=resources/'core'/('sing-box'+suffix)
if not core.is_file():raise SystemExit('Run fetch-core.py first')
digest=hashlib.sha256(core.read_bytes()).hexdigest()
out=resources/'helper'/('chspch-tun-helper'+suffix);out.parent.mkdir(parents=True,exist_ok=True)
env=os.environ.copy();env.update(GOOS='windows' if win else 'linux',GOARCH='amd64',CGO_ENABLED='0')
flags=f'-s -w -X main.coreHash={digest}'+(' -H windowsgui' if win else '')
subprocess.run(['go','build','-trimpath','-ldflags',flags,'-o',str(out),'.'],cwd=root/'helper',env=env,check=True)
goroot=Path(subprocess.check_output(['go','env','GOROOT'],env=env,text=True).strip())
license_file=next((p for p in [goroot/'LICENSE', Path('/usr/share/licenses/go/LICENSE')] if p.is_file()), None)
if license_file is None: raise SystemExit('Go distribution LICENSE not found')
shutil.copy2(license_file, out.parent/'GO-LICENSE.txt')
print(f'Built {out.name} for {a.target}')
