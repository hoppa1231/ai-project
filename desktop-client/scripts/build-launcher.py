#!/usr/bin/env python3
import os, subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
out=root/'build/windows-launcher/Chspch.exe';out.parent.mkdir(parents=True,exist_ok=True)
env=os.environ.copy();env.update(GOOS='windows',GOARCH='amd64',CGO_ENABLED='0')
subprocess.run(['go','build','-trimpath','-ldflags','-s -w -H windowsgui','-o',str(out),'.'],cwd=root/'launcher',env=env,check=True)
print(out)
