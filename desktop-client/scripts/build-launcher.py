#!/usr/bin/env python3
"""Cross-build the Windows launcher with the application icon embedded."""
import os, shutil, subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
stage = root / 'build/windows-launcher/source'
if stage.exists():
    shutil.rmtree(stage)
stage.mkdir(parents=True)
for source in (root / 'launcher').iterdir():
    if source.suffix == '.go' or source.name in ('go.mod', 'go.sum'):
        shutil.copy2(source, stage / source.name)
shutil.copy2(root / 'packaging/windows-x64/chspch.ico', stage / 'chspch.ico')
(stage / 'icon.rc').write_text('1 ICON "chspch.ico"\n')
windres = next((path for name in ('x86_64-w64-mingw32-windres', 'llvm-windres', 'windres')
                if (path := shutil.which(name))), None)
if not windres:
    raise SystemExit('Windows icon compiler required: install LLVM (llvm-windres) or MinGW windres')
subprocess.run([windres, '--target=pe-x86-64', '--input=icon.rc',
                '--output-format=coff', '--output=icon_windows_amd64.syso'], cwd=stage, check=True)
out = root / 'build/windows-launcher/Chspch.exe'
env = os.environ.copy()
env.update(GOOS='windows', GOARCH='amd64', CGO_ENABLED='0')
subprocess.run(['go', 'build', '-trimpath', '-ldflags', '-s -w -H windowsgui',
                '-o', str(out), '.'], cwd=stage, env=env, check=True)
print(out)
