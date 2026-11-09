import hashlib,importlib.util,json,pathlib,subprocess,sys,tempfile,unittest
spec=importlib.util.spec_from_file_location('desktop_publish',pathlib.Path(__file__).with_name('publish.py'));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class PublishTests(unittest.TestCase):
 def test_atomic_feed_immutable_versions_and_verified_archives(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=pathlib.Path(tmp);staging=root/'staging';staging.mkdir();public=root/'public'
   code=module.REMOTE.replace('/opt/securevpn-staging/desktop-updates',str(staging)).replace('/var/www/chspch-updates/desktop',str(public))
   def publish(version='1.0.4',content=b'archive',bad_hash=False):
    stage=staging/('s'+str(len(list(staging.iterdir()))));stage.mkdir()
    name='archive.zip';(stage/name).write_bytes(content)
    manifest={'schemaVersion':1,'product':'ChspchDesktop','versionName':version,'notes':'test','artifacts':{'windows-x64':{'url':'https://tech-supp-test.ru/updates/desktop/releases/'+name,'sizeBytes':len(content),'sha256':('0'*64 if bad_hash else hashlib.sha256(content).hexdigest())}}}
    result=subprocess.run([sys.executable,'-c',code],input=json.dumps({'manifest':manifest,'stage':str(stage)}),text=True,capture_output=True)
    return result,manifest
   result,manifest=publish();self.assertEqual(0,result.returncode,result.stderr)
   original=(public/'latest.json').read_bytes()
   self.assertEqual(manifest,json.loads(original));self.assertEqual(b'archive',(public/'latest-windows-x64.zip').read_bytes())
   for version,data,bad in [('1.0.3',b'archive',False),('1.0.4',b'different',False),('1.0.5',b'archive',True)]:
    result,_=publish(version,data,bad);self.assertNotEqual(0,result.returncode);self.assertEqual(original,(public/'latest.json').read_bytes())
if __name__=='__main__':unittest.main()
