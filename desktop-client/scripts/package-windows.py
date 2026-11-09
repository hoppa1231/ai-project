#!/usr/bin/env python3
"""Cross-package portable Windows x64 app. Runtime execution still needs Windows QA."""
import argparse, hashlib, io, shutil, urllib.request, zipfile
from pathlib import Path
URL='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jre_x64_windows_hotspot_21.0.13_9.zip'
SHA256='2332c0b4cff6bed722da3567c91d5e47568bcba863fa4945d0a8d6fc76158628'
p=argparse.ArgumentParser();p.add_argument('--jre',type=Path);a=p.parse_args()
root=Path(__file__).resolve().parents[1]
raw=a.jre.read_bytes() if a.jre else urllib.request.urlopen(URL,timeout=300).read()
if hashlib.sha256(raw).hexdigest()!=SHA256:raise SystemExit('JRE SHA-256 mismatch')
dest=root/'build/windows-portable/Chspch'
if dest.exists():shutil.rmtree(dest)
(dest/'runtime').mkdir(parents=True)
with zipfile.ZipFile(io.BytesIO(raw)) as z:
    for name in z.namelist():
        parts=Path(name).parts[1:]
        if not parts or '..' in parts or name.endswith('/'):continue
        target=dest/'runtime'/Path(*parts);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
shutil.copytree(root/'build/windows-jars',dest/'app')
shutil.copytree(root/'packaging/windows-x64',dest/'resources')
if not (dest/'resources/helper/chspch-tun-helper.exe').exists():raise SystemExit('Build Windows helper first')
shutil.copy2(root/'build/windows-launcher/Chspch.exe', dest/'Chspch.exe')
shutil.copy2(root/'build/windows-launcher/Chspch.exe', dest/'Install.exe')
shutil.copytree(root/'helper', dest/'source/helper')
shutil.copytree(root/'launcher', dest/'source/launcher')
shutil.copy2(root/'WINDOWS-CHECK.md', dest/'WINDOWS-CHECK.md')
shutil.copy2(root/'THIRD_PARTY.txt', dest/'THIRD_PARTY.txt')
(dest/'README.txt').write_text('ЧСПЧ для Windows x64\n\nРаспакуйте всю папку. Install.exe устанавливает в профиль пользователя и создаёт ярлык.\nChspch.exe запускает переносную версию без установки. Java и VPN-ядро включены.\nДля TUN подтвердите штатный запрос UAC. Исходники помощника: source/helper/.\nЭтот пакет собран на Linux; запуск и TUN нужно проверить на Windows.\n',encoding='utf-8-sig')
output=root/'build/distributions/chspch-windows-x64-1.0.5.zip';output.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for file in dest.rglob('*'):
        if file.is_file():z.write(file,Path('Chspch')/file.relative_to(dest))
print(output)
