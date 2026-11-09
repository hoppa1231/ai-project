#!/usr/bin/env python3
"""Linux packaged smoke with abrupt JVM exit; isolated guest config, no user session."""
import json, os, shutil, subprocess, tempfile, time, urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
app=root/'build/compose/binaries/main/app/Chspch'
cfg=app/'lib/app/Chspch.cfg';original=cfg.read_text()
state=Path(tempfile.mkdtemp(prefix='chspch-watchdog-'));state.chmod(0o700)
env=os.environ.copy();env.update(CHSPCH_SMOKE_TUN='1',CHSPCH_SMOKE_CRASH='1',CHSPCH_SMOKE_NODE='Франция',CHSPCH_SMOKE_STATE=str(state),CHSPCH_SING_BOX=str(app/'lib/app/resources/core/sing-box'))
revoked=False
try:
    if Path('/sys/class/net/chspch0').exists():raise SystemExit('Stop the existing TUN before this test')
    cfg.write_text(original.replace('app.mainclass=app.chspch.desktop.MainKt','app.mainclass=app.chspch.desktop.SmokeKt'))
    result=subprocess.run([str(app/'bin/Chspch')],env=env,timeout=180)
    if result.returncode!=23:raise RuntimeError('Smoke did not reach the intentional abrupt exit')
    for _ in range(20):
        if not Path('/sys/class/net/chspch0').exists():break
        time.sleep(1)
    assert not Path('/sys/class/net/chspch0').exists(), 'TUN interface remained after JVM exit'
    rules=json.loads(subprocess.check_output(['ip','-j','rule']))
    assert not any(r.get('table')==2022 for r in rules), 'TUN routing table remains'
    print('Abrupt JVM exit: interface removed and TUN rules cleaned')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open('https://example.com',timeout=15) as response:
        print('Network after cleanup: HTTP',response.status)
finally:
    cfg.write_text(original)
    file=state/'session.json'
    if file.exists():
        session=json.loads(file.read_text())
        if session.get('configId'):
            body=json.dumps({'configId':session['configId'],'reason':'desktop_watchdog_cleanup'}).encode()
            req=urllib.request.Request('https://tech-supp-test.ru/vpn/revoke',data=body,headers={'Authorization':'Bearer '+session['accessToken'],'Content-Type':'application/json'})
            try:
                with urllib.request.urlopen(req,timeout=30) as response:
                    result=json.load(response);revoked=result.get('status') in ['REVOKED','EXPIRED','FAILED']
                print('Guest config revoke confirmed:',revoked)
            except Exception:print('Guest revoke pending; private state retained at',state)
        else:revoked=True
    else:revoked=True
    if revoked:shutil.rmtree(state)
