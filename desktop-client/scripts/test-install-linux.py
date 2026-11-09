#!/usr/bin/env python3
"""Regression: update read-only bundled Java files and keep one rollback version."""
import os,shutil,subprocess,tempfile
from pathlib import Path
script=Path(__file__).with_name('install-linux.sh')
base=Path(tempfile.mkdtemp(prefix='chspch-installer-test-'))
try:
    source=base/'source';source.mkdir();shutil.copy2(script,source/'install-linux.sh')
    for name in ['bin/Chspch','lib/app/resources/core/sing-box','lib/app/resources/helper/chspch-tun-helper','icon.png']:
        file=source/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text('synthetic fixture')
    legal=source/'lib/runtime/legal';legal.mkdir(parents=True);(legal/'LICENSE').write_text('fixture')
    (legal/'LICENSE').chmod(0o444);legal.chmod(0o555)
    env=os.environ.copy();env['XDG_DATA_HOME']=str(base/'data')
    target=base/'data/chspch'
    for revision in ['first','second','third']:
        (source/'revision').write_text(revision)
        subprocess.run(['bash',str(source/'install-linux.sh')],env=env,check=True,stdout=subprocess.DEVNULL)
        assert (target/'revision').read_text()==revision
        assert (target/'lib/runtime/legal/LICENSE').read_text()=='fixture'
    assert (base/'data/chspch.previous/revision').read_text()=='second'
    assert (base/'data/applications/chspch.desktop').is_file()
    assert not list((base/'data').glob('chspch.new.*'))
    print('Linux installer: fresh install + two read-only-runtime updates + rollback + shortcut OK')
finally:
    for directory,dirs,files in os.walk(base):
        Path(directory).chmod(0o700)
        for name in files:(Path(directory)/name).chmod(0o600)
    shutil.rmtree(base)
