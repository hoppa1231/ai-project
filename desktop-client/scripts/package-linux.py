#!/usr/bin/env python3
"""Pack the complete native Linux image, install script, notices and helper source."""
from pathlib import Path
import shutil,tarfile
root=Path(__file__).resolve().parents[1]
app=root/'build/compose/binaries/main/app/Chspch'
for source,name in [(root/'scripts/install-linux.sh','install-linux.sh'),(root/'README.md','README.txt'),(root/'THIRD_PARTY.txt','THIRD_PARTY.txt'),(root/'src/main/resources/images/icon.png','icon.png')]:
    shutil.copy2(source,app/name)
for name in ['helper','launcher']:shutil.copytree(root/name,app/'source'/name,dirs_exist_ok=True)
output=root/'build/distributions/chspch-linux-x64-1.0.5.tar.gz';output.parent.mkdir(parents=True,exist_ok=True)
with tarfile.open(output,'w:gz',compresslevel=6) as tar:
    def metadata(info):
        info.uid=info.gid=0;info.uname=info.gname='root';return info
    tar.add(app,arcname='Chspch',filter=metadata)
print(output)
