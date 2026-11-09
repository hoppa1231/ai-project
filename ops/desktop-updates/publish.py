#!/usr/bin/env python3
"""Publish verified desktop archives and stable links. Existing Android updates are unchanged."""
import argparse,hashlib,json,re,shlex,subprocess,urllib.request,uuid,tarfile,zipfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE='https://tech-supp-test.ru/updates/desktop'
REMOTE=r'''
import hashlib,json,os,pathlib,shutil,sys,urllib.parse
p=json.load(sys.stdin);m=p['manifest'];stage=pathlib.Path(p['stage'])
assert stage.parent==pathlib.Path('/opt/securevpn-staging/desktop-updates')
root=pathlib.Path('/var/www/chspch-updates/desktop');root.mkdir(parents=True,exist_ok=True)
(root/'releases').mkdir(exist_ok=True)
latest=root/'latest.json'
if latest.exists():
 old=json.loads(latest.read_text())
 assert tuple(map(int,m['versionName'].split('.'))) >= tuple(map(int,old['versionName'].split('.'))),'downgrade refused'
 if m['versionName']==old['versionName']:assert m==old,'version already published with different bytes; bump version'
for platform,a in m['artifacts'].items():
 name=pathlib.PurePosixPath(urllib.parse.urlsplit(a['url']).path).name
 source=stage/name
 assert source.stat().st_size==a['sizeBytes']
 assert hashlib.sha256(source.read_bytes()).hexdigest()==a['sha256']
 target=root/'releases'/name
 if target.exists():assert hashlib.sha256(target.read_bytes()).hexdigest()==a['sha256']
 else:os.replace(source,target)
 target.chmod(0o644)
 suffix='.zip' if platform=='windows-x64' else '.tar.gz'
 temp=root/('latest-'+platform+'.tmp');temp.unlink(missing_ok=True);temp.symlink_to('releases/'+name)
 os.replace(temp,root/('latest-'+platform+suffix))
if latest.exists():
 (root/'history').mkdir(exist_ok=True);shutil.copy2(latest,root/'history'/(old['versionName']+'.json'))
index=root/'index.html'
index.write_text('<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>ЧСПЧ для ПК</title><style>body{background:#35150d;color:#e2c275;max-width:680px;margin:60px auto;padding:24px;font:18px sans-serif}a{color:#ffe39c}li{margin:24px 0}</style><h1>ЧСПЧ для ПК</h1><p>Версия '+m['versionName']+'</p><ul><li><a href="latest-windows-x64.zip">Скачать для Windows 10/11 x64</a><li><a href="latest-linux-x64.tar.gz">Скачать для Linux x64</a></ul><p>Распакуйте всю папку. Windows: закройте прежний ЧСПЧ и запустите Install.exe. Linux: запустите install-linux.sh. Вход и настройки сохраняются.</p><p>Начиная с версии 1.0.4 приложение уведомляет о новых версиях и предлагает загрузку.</p></html>')
index.chmod(0o644)
temp=root/('latest-'+stage.name+'.tmp');temp.write_text(json.dumps(m,ensure_ascii=False,indent=2)+'\n');temp.chmod(0o644);os.replace(temp,latest)
shutil.rmtree(stage)
print('Published desktop',m['versionName'])
'''
def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--notes',required=True);p.add_argument('--host',default='moscow');p.add_argument('--skip-build',action='store_true');p.add_argument('--dry-run',action='store_true');p.add_argument('--jre',type=Path);p.add_argument('--resume-stage',help='Resume a failed staging directory UUID')
 a=p.parse_args()
 if not re.fullmatch(r'[A-Za-z0-9_.@-]+',a.host) or not a.notes.strip() or len(a.notes)>4000:p.error('invalid host or notes')
 version=re.search(r'DESKTOP_VERSION = "([0-9]+\.[0-9]+\.[0-9]+)"',(ROOT/'desktop-client/src/main/kotlin/app/chspch/desktop/DesktopApi.kt').read_text()).group(1)
 cwd=ROOT/'desktop-client'
 def run(cmd):subprocess.run(cmd,cwd=cwd,check=True)
 if not a.skip_build:
  run(['bash','./gradlew','test','createDistributable','prepareWindowsJars']);run(['python3','scripts/build-helper.py','windows-x64']);run(['python3','scripts/build-launcher.py']);run(['python3','scripts/package-linux.py'])
  run(['python3','scripts/package-windows.py']+(['--jre',str(a.jre.resolve())] if a.jre else []))
 manifest={'schemaVersion':1,'product':'ChspchDesktop','versionName':version,'notes':a.notes,'artifacts':{}}
 files=[]
 for platform,extension in [('windows-x64','zip'),('linux-x64','tar.gz')]:
  source=cwd/f'build/distributions/chspch-{platform}-{version}.{extension}'
  if extension=='zip':
   with zipfile.ZipFile(source) as archive:
    assert archive.testzip() is None
    names=archive.namelist()
  else:
   with tarfile.open(source) as archive:names=archive.getnames()
  assert not any(Path(n).name in ('session.json','.env') or Path(n).name.startswith('tunnel-') for n in names),'private state in archive'
  data=source.read_bytes();sha=hashlib.sha256(data).hexdigest();name=f'chspch-{platform}-{version}-{sha[:12]}.{extension}'
  if not 0<len(data)<=250*1024*1024:raise RuntimeError('archive size invalid')
  manifest['artifacts'][platform]={'url':f'{BASE}/releases/{name}','sizeBytes':len(data),'sha256':sha};files.append((source,name))
 out=ROOT/'dist/desktop-updates'/version;out.mkdir(parents=True,exist_ok=True);(out/'latest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
 if a.dry_run:print(out);return
 ssh=['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=10',a.host]
 if a.resume_stage and not re.fullmatch(r'[a-f0-9]{32}',a.resume_stage):p.error('invalid resume UUID')
 stage='/opt/securevpn-staging/desktop-updates/'+(a.resume_stage or uuid.uuid4().hex)
 (out/'staging.json').write_text(json.dumps({'stage':stage,'host':a.host}))
 subprocess.run(ssh+['install -d -m 755 '+shlex.quote(stage)],check=True)
 for source,name in files:
  print('Uploading',name,flush=True)
  target=stage+'/'+name
  for attempt in range(400):
   result=subprocess.run(ssh+['stat -c %s '+shlex.quote(target)+' 2>/dev/null || true'],capture_output=True,text=True)
   if result.returncode:time.sleep(1);continue
   offset=int(result.stdout.strip() or 0)
   if offset==source.stat().st_size:break
   if offset>source.stat().st_size:raise RuntimeError('staged archive too large')
   process=subprocess.Popen(ssh+['cat >> '+shlex.quote(target)],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
   try:
    with source.open('rb') as stream:
     stream.seek(offset)
     while chunk:=stream.read(65536):
      process.stdin.write(chunk);process.stdin.flush();time.sleep(0.04)
   except BrokenPipeError:pass
   finally:
    try:process.stdin.close()
    except BrokenPipeError:pass
    process.wait()
   print('Transfer attempt',attempt+1,'finished',flush=True)
  else:raise RuntimeError('transfer retry limit exceeded')
 subprocess.run(ssh+['python3 -c '+shlex.quote(REMOTE)],input=json.dumps({'manifest':manifest,'stage':stage}),text=True,check=True)
 with urllib.request.urlopen(BASE+'/latest.json',timeout=25) as r:assert json.load(r)==manifest
 for artifact in manifest['artifacts'].values():
  with urllib.request.urlopen(urllib.request.Request(artifact['url'],method='HEAD'),timeout=25) as r:assert int(r.headers['Content-Length'])==artifact['sizeBytes']
 print('Public desktop feed and archive sizes verified:',BASE+'/')
if __name__=='__main__':main()
