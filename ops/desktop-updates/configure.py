#!/usr/bin/env python3
"""Add only the desktop update location to the existing HTTPS server, with rollback."""
import json,shlex,subprocess
from pathlib import Path
script=r'''
import datetime,json,pathlib,subprocess,sys
snippet=pathlib.Path('/etc/nginx/snippets/chspch-desktop-updates.conf')
site=pathlib.Path('/etc/nginx/sites-available/vpn-control-plane')
old=site.read_text();previous=snippet.read_text() if snippet.exists() else None
anchor='    include /etc/nginx/snippets/chspch-android-updates.conf;'
include='    include /etc/nginx/snippets/chspch-desktop-updates.conf;'
assert anchor in old
backup=site.with_name(site.name+'.before-desktop-'+datetime.datetime.now().strftime('%Y%m%d%H%M%S'));backup.write_text(old)
snippet.write_text(json.load(sys.stdin)['snippet'])
if include not in old:site.write_text(old.replace(anchor,anchor+'\n'+include,1))
try:
 subprocess.run(['nginx','-t'],check=True)
 subprocess.run(['systemctl','reload','nginx'],check=True)
except:
 site.write_text(old)
 if previous is None:snippet.unlink()
 else:snippet.write_text(previous)
 raise
print('Desktop update location enabled; backup:',backup)
'''
subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','moscow','python3 -c '+shlex.quote(script)],input=json.dumps({'snippet':Path(__file__).with_name('nginx.conf').read_text()}),text=True,check=True)
