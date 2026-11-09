#!/usr/bin/env python3
"""Build, validate, upload immutable APKs, then atomically publish metadata. No credentials in source."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'app.chspch.client'
CERT_SHA256 = '0729fb5fe3c473c4e648e82fd3277cdb3e60bad206f84e5586cb67cc71ca255e'
ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
BASE = 'https://tech-supp-test.ru/updates/android'


def tool(name):
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
    candidates = sorted((sdk / 'build-tools').glob('*/' + name))
    if candidates:
        return str(candidates[-1])
    found = shutil.which(name)
    if not found:
        raise RuntimeError(f'Android SDK tool missing: {name}')
    return found


def inspect_apk(path, abi):
    certs = subprocess.check_output([tool('apksigner'), 'verify', '--print-certs', str(path)], text=True)
    cert = re.search(r'certificate SHA-256 digest: ([a-f0-9]+)', certs)
    if not cert or cert.group(1) != CERT_SHA256:
        raise RuntimeError('APK signer differs from existing ChSPCh installs; publication refused')
    badging = subprocess.check_output([tool('aapt'), 'dump', 'badging', str(path)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not match or match.group(1) != PACKAGE or f"native-code: '{abi}'" not in badging:
        raise RuntimeError('APK package or architecture mismatch')
    if path.stat().st_size > 250 * 1024 * 1024:
        raise RuntimeError('APK too large')
    return int(match.group(2)), match.group(3)


REMOTE_PUBLISH = r'''
import hashlib,json,os,pathlib,shutil,sys,urllib.parse
payload=json.load(sys.stdin); manifest=payload['manifest']; stage=pathlib.Path(payload['stage'])
assert stage.parent==pathlib.Path('/opt/securevpn-staging/android-updates')
root=pathlib.Path('/var/www/chspch-updates/android'); root.mkdir(parents=True,exist_ok=True)
releases=root/'releases'; releases.mkdir(exist_ok=True)
latest=root/'latest.json'
if latest.exists():
 old=json.loads(latest.read_text())
 assert manifest['versionCode']>=old['versionCode'],'versionCode must increase'
 if manifest['versionCode']==old['versionCode']:
  assert manifest==old,'versionCode already published with different content; bump it'
for a in manifest['artifacts'].values():
 name=pathlib.PurePosixPath(urllib.parse.urlsplit(a['url']).path).name
 assert name.endswith('.apk') and '/' not in name
 source=stage/name
 assert source.stat().st_size==a['sizeBytes']
 assert hashlib.sha256(source.read_bytes()).hexdigest()==a['sha256']
 target=releases/name
 if target.exists():assert hashlib.sha256(target.read_bytes()).hexdigest()==a['sha256']
 else:os.replace(source,target)
 target.chmod(0o644)
for abi,a in manifest['artifacts'].items():
 name=pathlib.PurePosixPath(urllib.parse.urlsplit(a['url']).path).name
 link=root/('latest-'+abi+'.tmp')
 link.unlink(missing_ok=True)
 link.symlink_to('releases/'+name)
 os.replace(link,root/('latest-'+abi+'.apk'))
if latest.exists():
 history=root/'history';history.mkdir(exist_ok=True)
 shutil.copy2(latest,history/(str(json.loads(latest.read_text())['versionCode'])+'.json'))
tmp=root/('latest-'+stage.name+'.tmp')
with tmp.open('w') as f:
 json.dump(manifest,f,ensure_ascii=False,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
tmp.chmod(0o644);os.replace(tmp,latest)
shutil.rmtree(stage)
print('Published version',manifest['versionName'],'code',manifest['versionCode'])
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', help='Set versionName and increment versionCode before building')
    parser.add_argument('--notes', required=True, help='User-facing release notes')
    parser.add_argument('--abi', action='append', choices=ABIS, help='Default: arm64-v8a; repeat for more architectures')
    parser.add_argument('--host', default='moscow', help='SSH deployment alias')
    parser.add_argument('--skip-build', action='store_true', help='Use already built APKs, still verify them')
    parser.add_argument('--dry-run', action='store_true', help='Prepare and verify local release without upload')
    args = parser.parse_args()
    if len(args.notes) > 4000 or not args.notes.strip():
        parser.error('Provide release notes up to 4000 characters')
    if args.host.startswith('-') or not re.fullmatch(r'[A-Za-z0-9_.@-]+', args.host):
        parser.error('Invalid SSH host')
    gradle = ROOT / 'android-client/app/build.gradle.kts'
    if args.version:
        if args.skip_build or not re.fullmatch(r'\d+(?:\.\d+){1,3}', args.version):
            parser.error('--version requires a build and a numeric version such as 1.3')
        text = gradle.read_text()
        code = int(re.search(r'versionCode = (\d+)', text).group(1)) + 1
        text = re.sub(r'versionCode = \d+', f'versionCode = {code}', text, count=1)
        text = re.sub(r'versionName = "[^"]+"', f'versionName = "{args.version}"', text, count=1)
        gradle.write_text(text)
    if not args.skip_build:
        subprocess.run(['bash', './gradlew', '--no-daemon', 'assembleDebug', 'testDebugUnitTest'], cwd=ROOT / 'android-client', check=True)
    artifacts = {}; files = []
    version = None
    for abi in args.abi or ['arm64-v8a']:
        apk = ROOT / f'android-client/app/build/outputs/apk/debug/app-{abi}-debug.apk'
        actual = inspect_apk(apk, abi)
        if version is not None and version != actual:
            raise RuntimeError('APKs have different versions')
        version = actual
        sha = hashlib.sha256(apk.read_bytes()).hexdigest()
        name = f'ChSPCh-{actual[1]}-{actual[0]}-{abi}-{sha[:12]}.apk'
        artifact = {'url': f'{BASE}/releases/{name}', 'sha256': sha, 'sizeBytes': apk.stat().st_size}
        artifacts[abi] = artifact; files.append((apk, name))
    manifest = {'schemaVersion': 1, 'packageName': PACKAGE, 'versionCode': version[0], 'versionName': version[1],
                'notes': args.notes, 'artifacts': artifacts}
    output = ROOT / 'dist/android-updates' / str(version[0]); output.mkdir(parents=True, exist_ok=True)
    for apk, name in files:
        shutil.copy2(apk, output / name)
    (output / 'latest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    if args.dry_run:
        print('Validated local release:', output); return
    ssh = ['ssh', '-F', str(Path.home() / '.ssh/config'), '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', args.host]
    stage = '/opt/securevpn-staging/android-updates/' + uuid.uuid4().hex
    subprocess.run(ssh + ['install -d -m 755 ' + shlex.quote(stage)], check=True)
    for apk, name in files:
        compressed = output / (name + '.gz')
        with apk.open('rb') as source, gzip.open(compressed, 'wb', compresslevel=3) as target:
            shutil.copyfileobj(source, target)
        print('Uploading', name, f'({compressed.stat().st_size // 1024 // 1024} MiB compressed)', flush=True)
        with compressed.open('rb') as stream:
            subprocess.run(ssh + ['gzip -dc > ' + shlex.quote(stage + '/' + name)], stdin=stream, check=True)
        compressed.unlink()
    payload = json.dumps({'manifest': manifest, 'stage': stage})
    subprocess.run(ssh + ['python3 -c ' + shlex.quote(REMOTE_PUBLISH)], input=payload, text=True, check=True)
    with urllib.request.urlopen(BASE + '/latest.json', timeout=20) as response:
        published = json.load(response)
    if published != manifest:
        raise RuntimeError('Public HTTPS metadata does not match published release')
    for artifact in artifacts.values():
        with urllib.request.urlopen(urllib.request.Request(artifact['url'], method='HEAD'), timeout=20) as response:
            if int(response.headers.get('Content-Length', 0)) != artifact['sizeBytes']:
                raise RuntimeError('Public APK size mismatch')
    print('Public HTTPS release verified:', BASE + '/latest.json')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Publication failed:', error, file=sys.stderr)
        sys.exit(1)
