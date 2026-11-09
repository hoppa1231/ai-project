const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../../main/kotlin/com/example/modules/admin/AdminRoutes.kt'), 'utf8');
const script = source.split('private val adminPanelHtml = """')[1].split('<script>')[1].split('</script>')[0];
function setup(fetch) {
  const values = new Map(), elements = new Map();
  const el = id => {
    if (!elements.has(id)) elements.set(id, {value:'', innerHTML:'', textContent:'', addEventListener(){}, classList:{add(){},remove(){}}});
    return elements.get(id);
  };
  const context = vm.createContext({document:{getElementById:el,hidden:false}, navigator:{}, window:{addEventListener(){}},
    localStorage:{getItem:k=>values.get(k)||null, setItem:(k,v)=>values.set(k,v), removeItem:k=>values.delete(k)},
    setInterval(){}, fetch, crypto:{randomUUID:()=> 'test-browser-id'}, Date, Map, Set, Number, String, JSON, encodeURIComponent});
  vm.runInContext(script, context);
  vm.runInContext(`state.token='old';localStorage.setItem('adminToken','old');localStorage.setItem('adminRefreshToken','refresh-old');`,context);
  return {run:s=>vm.runInContext(s,context), values, el};
}
const response = (status, data) => ({ok:status<400,status,statusText:status===401?'Unauthorized':'Error',text:async()=>JSON.stringify(data)});
test('concurrent 401 responses rotate refresh once and retry both requests', async()=>{
  let refreshes=0, requests=0;
  const h=setup(async (url, opts)=>{
    if(url==='/auth/refresh'){refreshes++; await new Promise(r=>setTimeout(r,5));return response(200,{accessToken:'new',refreshToken:'refresh-new'});}
    requests++;
    return response(opts.headers.Authorization==='Bearer new'?200:401,{ok:true});
  });
  await h.run(`Promise.all([request('/admin/test'),request('/admin/test')])`);
  assert.equal(refreshes,1);assert.equal(requests,4);
  assert.equal(h.values.get('adminRefreshToken'),'refresh-new');
});
test('expired refresh clears session and stops monitor network polling', async()=>{
  let calls=0;
  const h=setup(async()=>{calls++;return response(401,{});});
  await assert.rejects(h.run(`request('/admin/test')`),e=>e.sessionExpired===true);
  assert.equal(h.values.has('adminToken'),false);
  const before=calls;await h.run('loadMonitor()');assert.equal(calls,before);
  assert.match(h.el('monitorMessage').textContent,/Войдите заново/);
});
test('legacy session without refresh asks for login instead of repeating 401', async()=>{
  const h=setup(async()=>response(401,{}));h.values.delete('adminRefreshToken');
  await assert.rejects(h.run(`request('/admin/test')`),e=>e.sessionExpired===true);
  assert.equal(h.values.has('adminToken'),false);
});
test('network error while refreshing preserves login for a later retry', async()=>{
  const h=setup(async url=>{if(url==='/auth/refresh')throw Error('offline');return response(401,{});});
  await assert.rejects(h.run(`request('/admin/test')`),/offline/);
  assert.equal(h.values.get('adminToken'),'old');assert.equal(h.values.get('adminRefreshToken'),'refresh-old');
});
test('logout during refresh cannot restore old session', async()=>{
  let release; const gate=new Promise(r=>release=r);
  const h=setup(async url=>{if(url==='/auth/refresh'){await gate;return response(200,{accessToken:'new',refreshToken:'new-refresh'});}return response(401,{});});
  const pending=h.run(`request('/admin/test')`);
  await new Promise(r=>setTimeout(r,5));h.run('clearSession()');release();
  await assert.rejects(pending,e=>e.sessionExpired===true);assert.equal(h.values.has('adminToken'),false);
});
test('successful response arriving after logout cannot refill private tables', async()=>{
  let release;const gate=new Promise(r=>release=r);
  const h=setup(async()=>{await gate;return response(200,{private:true});});
  const pending=h.run(`request('/admin/test')`);h.run('clearSession()');release();
  await assert.rejects(pending,e=>e.sessionExpired===true);
});
test('new login obtains refresh token by binding a stable browser identity', async()=>{
  const fingerprints=[];
  const h=setup(async (url,opts)=>{
    if(url==='/auth/login')return response(200,{accessToken:'login-token'});
    if(url==='/devices/bind'){fingerprints.push(JSON.parse(opts.body).deviceFingerprint);return response(200,{accessToken:'bound',refreshToken:'bound-refresh'});}
    if(url==='/admin/nodes')return response(200,[]);
    if(url==='/admin/monitor/sync')return response(200,{clients:[],failedNodes:[]});
    throw Error('unexpected '+url);
  });
  await h.run('login()');await h.run('login()');
  assert.equal(h.values.get('adminRefreshToken'),'bound-refresh');
  assert.equal(fingerprints.length,2);assert.equal(fingerprints[0],fingerprints[1]);
});
