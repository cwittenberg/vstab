#!/usr/bin/env node
'use strict';
// Use the ordinary Ports UI and Android browser, never tunnel RPCs or editor
// command hooks. Own the selected emulator before running this proof.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
process.env.VSTAB_ANDROID_SERIAL ||= 'emulator-5582';
process.env.VSTAB_CDP_PORT ||= '9228';
const f = require('./feature-proof.cjs');
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'artifacts/port-forwarding');
const runId = Date.now().toString();
const marker = 'VSTAB_BROWSER_PORT_' + runId;
const visiblePortsTab = `[...document.querySelectorAll('a.action-label[aria-label^="Ports"]')].some(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&e.checkVisibility({checkVisibilityCSS:true});})`;
let config;
function remote(script) {
  try {
    return execFileSync('docker', ['exec', '--user', config.username, config.container, 'python3', '-c', script],
      {encoding: 'utf8', timeout: 30000, maxBuffer: 1024 * 1024});
  } catch (error) { throw new Error('Independent remote port fixture failed (exit ' + error.status + ').'); }
}
async function until(check, timeout = 30000, label = 'port forwarding state') {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await check(); if (value) return value; await f.delay(300); }
  throw new Error('Timed out waiting for ' + label + '.');
}
function capture(name) {
  const file = path.join(OUT, name + '.png');
  fs.writeFileSync(file, f.adb('exec-out', 'screencap', '-p'));
  return path.relative(ROOT, file);
}
function nodes() {
  f.adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/vstab-port-proof.xml');
  return [...f.adb('exec-out', 'cat', '/sdcard/vstab-port-proof.xml').toString().matchAll(/<node\b[^>]*>/g)]
    .map(([tag]) => Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)]
      .map(([, key, value]) => [key, value.replace(/&(amp|lt|gt|quot|apos);/g, (_, entity) =>
        ({amp: '&', lt: '<', gt: '>', quot: '"', apos: "'"}[entity]))])));
}
function tap(node) {
  const [left, top, right, bottom] = node.bounds.match(/\d+/g).map(Number);
  f.adb('shell', 'input', 'tap', String(Math.round((left + right) / 2)), String(Math.round((top + bottom) / 2)));
}
async function workspace(local) {
  const target = await until(async () => {
    const list = await (await fetch('http://127.0.0.1:' + process.env.VSTAB_CDP_PORT + '/json/list')).json();
    return list.find(t => {
      try { const d = JSON.parse(t.description); return t.type === 'page' && d.visible && d.attached &&
        /^http:\/\/(localhost|127\.0\.0\.[1-4]):/.test(t.url) && (new URL(t.url).hostname === 'localhost') === local; }
      catch { return false; }
    });
  }, 120000, local ? 'the local workbench' : 'the SSH workbench');
  const c = await f.CDP.connect(target.id);
  await c.waitFor('!!document.querySelector(".monaco-workbench")', 90000);
  return c;
}
async function connect(c, report) {
  const fingerprint = execFileSync('ssh-keygen', ['-lf', path.join(ROOT, '.local/demo/known_hosts')],
    {encoding: 'utf8'}).match(/SHA256:[A-Za-z0-9+/]+/)?.[0];
  assert.ok(fingerprint);
  const alias = 'vstab-port-proof-' + runId;
  await f.command(c, 'Remote-SSH: Add New SSH Host...');
  await c.waitFor(`!!document.querySelector('.quick-input-widget')?.offsetHeight && document.body.innerText.includes('Enter an SSH connection command')`);
  await f.delay(200); await c.click('.quick-input-widget input');
  await f.typeKeys(c, 'ssh -o HostName=10.0.2.2 -o PreferredAuthentications=password -p 22227 developer@' + alias);
  await c.key('Enter', 'Enter', 13);
  await c.waitFor(`document.body.innerText.includes('SSH host added to ~/.ssh/config.')`);
  report.fixtureAlias = alias;
  await f.command(c, 'Remote-SSH: Connect to Host...');
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(alias)})`);
  const host = await c.evaluate(`(() => {const e=[...document.querySelectorAll('.quick-input-widget .label-name')].find(e=>e.textContent.trim()===${JSON.stringify(alias)});const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2};})()`);
  await c.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...host, button: 'left', clickCount: 1});
  await c.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...host, button: 'left', clickCount: 1});
  let passwordAnswered = false;
  await until(async () => {
    const targets = await (await fetch('http://127.0.0.1:' + process.env.VSTAB_CDP_PORT + '/json/list')).json();
    if (targets.some(t => { try { const d = JSON.parse(t.description); return d.visible && d.attached && /^http:\/\/127\.0\.0\.[1-4]:/.test(t.url); } catch { return false; } })) return true;
    if (await c.evaluate(`!!document.querySelector('.quick-input-widget')?.offsetHeight && document.querySelector('.quick-input-widget input')?.type==='password'`)) {
      assert.ok(!passwordAnswered, 'An authentication retry is a real failure.');
      const prompt = await c.evaluate(`document.querySelector('.quick-input-message')?.textContent || ''`);
      assert.ok(!/passphrase/i.test(prompt), 'The dedicated password fixture must not ask for a key passphrase.');
      await f.delay(200); await c.click('.quick-input-widget input');
      await f.typeKeys(c, config.password); await c.key('Enter', 'Enter', 13);
      passwordAnswered = true; report.checks.passwordEnteredThroughMaskedPrompt = true;
      return false;
    }
    const modal = await c.evaluate(`(()=>{const e=document.querySelector('.monaco-dialog-box');return e?.offsetHeight?e.innerText:'';})()`);
    if (modal.includes('Compare this SHA-256 fingerprint')) {
      assert.ok(modal.includes(fingerprint), 'The fingerprint must match the independent SSH fixture.');
      await c.clickText('Continue', '.monaco-dialog-box .monaco-button');
      report.checks.hostFingerprintVerified = true;
    }
    if (passwordAnswered && await c.evaluate(`/SSH authentication failed|Remote server download failed|built-in tools directory is incomplete|Incorrect passphrase/.test(document.body.innerText+' '+[...document.querySelectorAll('.statusbar-item')].map(e=>e.getAttribute('aria-label')).join(' '))`))
      throw new Error('The ordinary SSH connection reported a failure.');
    return false;
  }, 180000, 'SSH authentication and remote setup');
  c.close(); console.log('Connected to the isolated SSH fixture through the ordinary host picker.'); return workspace(false);
}
async function removeProofHost(c, alias) {
  if (!alias) return;
  await f.command(c, 'Remote-SSH: Remove SSH Host');
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(alias)})`);
  await c.clickText(alias, '.quick-input-widget .label-name');
  await c.waitFor(`document.querySelector('.monaco-dialog-box')?.innerText.includes(${JSON.stringify(alias)})`);
  await c.clickText('Remove', '.monaco-dialog-box .monaco-button');
}
async function ensureProject(c) {
  if (await c.evaluate(`new URL(location.href).searchParams.get('folder')===${JSON.stringify(config.workspace)}`)) return;
  await f.command(c, 'Remote-SSH: Open Folder...');
  await c.waitFor(`document.querySelector('.quick-input-widget input')?.getAttribute('aria-label')==='Folder path - Open Folder'`);
  await f.delay(200); await c.click('.quick-input-widget input');
  await c.key('a', 'KeyA', 65, 2); await f.typeKeys(c, config.workspace + '/');
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .monaco-button')].some(e=>e.textContent.trim()==='OK')`);
  await c.clickText('OK', '.quick-input-widget .monaco-button');
  await f.delay(1200);
}
const serviceSource = String.raw`
import base64, hashlib, http.server, json, os, pathlib, socket, struct, threading, time
ROOT = pathlib.Path(__file__).parent
MARKER = '__MARKER__'
LOCK = threading.Lock()
PAGE = '''<!doctype html><html><head><title>VStab remote browser proof</title></head>
<body><h1>Remote port opened in Android browser</h1><p id="result">Checking asset and WebSocket...</p>
<script>
const marker=__MARKER_JSON__;
Promise.all([fetch('/asset.txt').then(r=>r.text()).then(t=>{if(t!==marker)throw Error('Wrong asset')}),
new Promise((resolve,reject)=>{const s=new WebSocket('ws://'+location.host+'/echo');s.onopen=()=>s.send(marker);s.onmessage=e=>{if(e.data!==marker){reject(Error('Wrong echo'));return;}s.close();resolve();};s.onerror=()=>reject(Error('WebSocket failed'));})])
.then(()=>document.getElementById('result').textContent='Browser passed: '+marker)
.catch(e=>document.getElementById('result').textContent='Browser failed: '+e.message);
</script></body></html>'''
class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        with LOCK:
            with (ROOT/'requests.jsonl').open('a') as log:
                log.write(json.dumps({'path':self.path,'userAgent':self.headers.get('User-Agent',''),'upgrade':self.headers.get('Upgrade','')})+'\n')
        if self.path == '/echo' and self.headers.get('Upgrade','').lower() == 'websocket':
            accept=base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
            self.send_response(101);self.send_header('Upgrade','websocket');self.send_header('Connection','Upgrade');self.send_header('Sec-WebSocket-Accept',accept);self.end_headers()
            self.connection.settimeout(15)
            first=self.rfile.read(2)
            if len(first)!=2:return
            length=first[1]&127
            if length==126:length=struct.unpack('!H',self.rfile.read(2))[0]
            if length==127:length=struct.unpack('!Q',self.rfile.read(8))[0]
            if length>4096:return
            mask=self.rfile.read(4) if first[1]&128 else b''
            data=self.rfile.read(length)
            if mask:data=bytes(value^mask[i%4] for i,value in enumerate(data))
            self.wfile.write(bytes([129,len(data)])+data);self.wfile.flush()
            with LOCK:
                (ROOT/'websocket.json').write_text(json.dumps({'echoedExpectedMarker':data.decode()==MARKER}))
            return
        body=(MARKER if self.path=='/asset.txt' else PAGE).encode()
        self.send_response(200);self.send_header('Content-Type','text/plain' if self.path=='/asset.txt' else 'text/html');self.send_header('Cache-Control','no-store');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
reserved=socket.socket();reserved.bind(('127.0.0.1',0));port=reserved.getsockname()[1]
(ROOT/'ready.json').write_text(json.dumps({'pid':os.getpid(),'host':'127.0.0.1','port':port,'serving':False}))
while not (ROOT/'serve').exists():time.sleep(0.1)
reserved.close()
server=http.server.ThreadingHTTPServer(('127.0.0.1',port),Handler)
(ROOT/'ready.json').write_text(json.dumps({'pid':os.getpid(),'host':'127.0.0.1','port':port,'serving':True}))
server.serve_forever()
`;
function startService() {
  const source = serviceSource.replace('__MARKER__', marker).replace('__MARKER_JSON__', JSON.stringify(marker));
  remote(`import pathlib,subprocess,sys,base64
p=pathlib.Path('/tmp/vstab-port-proof-${runId}');p.mkdir(mode=0o700)
s=p/'server.py';s.write_bytes(base64.b64decode(${JSON.stringify(Buffer.from(source).toString('base64'))}))
log=(p/'server.log').open('ab')
subprocess.Popen([sys.executable,str(s)],stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
`);
}
function serviceState() {
  return JSON.parse(remote(`import pathlib,json
p=pathlib.Path('/tmp/vstab-port-proof-${runId}')
r=json.loads((p/'ready.json').read_text()) if (p/'ready.json').exists() else {}
r['requests']=[json.loads(line) for line in (p/'requests.jsonl').read_text().splitlines()] if (p/'requests.jsonl').exists() else []
r['websocket']=json.loads((p/'websocket.json').read_text()) if (p/'websocket.json').exists() else None
print(json.dumps(r))`));
}
function cleanupService() {
  remote(`import pathlib,json,os,signal,shutil
p=pathlib.Path('/tmp/vstab-port-proof-${runId}')
if (p/'ready.json').exists():
 pid=json.loads((p/'ready.json').read_text())['pid']
 cmd=pathlib.Path('/proc/'+str(pid)+'/cmdline')
 if cmd.exists() and str(p/'server.py').encode() in cmd.read_bytes():os.kill(pid,signal.SIGTERM)
if p.exists():shutil.rmtree(p)`);
}
function rowExpression(remotePort) {
  return `[...document.querySelectorAll('.monaco-list-row')].find(e=>e.offsetHeight && e.innerText.includes(${JSON.stringify(String(remotePort))}) && /127\\.0\\.0\\.1[1-4]:[0-9]+/.test(e.innerText))`;
}
async function forward(c, remotePort) {
  await c.key('Escape', 'Escape', 27);
  await c.waitFor(visiblePortsTab);
  await c.click('a.action-label[aria-label^="Ports"]');
  await f.delay(300);
  await c.clickText('Add Port', '.monaco-list-row a.monaco-button');
  await f.delay(250);
  if (!await c.evaluate(`!![...document.querySelectorAll('.monaco-table input')].find(e=>e.offsetHeight)`))
    await c.clickText('Add Port', '.monaco-list-row a.monaco-button');
  await c.waitFor(`!![...document.querySelectorAll('.monaco-table input')].find(e=>e.offsetHeight)`);
  await f.delay(200); await c.click('.monaco-table input');
  await f.typeKeys(c, String(remotePort)); await c.key('Enter', 'Enter', 13);
  await c.waitFor(`!!(${rowExpression(remotePort)})`, 30000);
  return c.evaluate(`(()=>{const e=${rowExpression(remotePort)};return{text:e.innerText,address:e.innerText.match(/127\\.0\\.0\\.1[1-4]:[0-9]+/)[0]};})()`);
}
async function openBrowser(c, remotePort) {
  const point = await c.evaluate(`(()=>{const e=${rowExpression(remotePort)};const r=e.getBoundingClientRect();return{x:r.x+Math.min(r.width/2,140),y:r.y+r.height/2};})()`);
  await c.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'right', buttons: 2, clickCount: 1});
  await c.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'right', buttons: 0, clickCount: 1});
  await c.waitFor(`[...document.querySelectorAll('.context-view .action-label')].some(e=>e.offsetHeight && e.textContent.trim()==='Open in Browser')`);
  await f.delay(250);
  await c.clickText('Open in Browser', '.context-view .action-label');
}
async function browserResult(c) {
  return until(async () => {
    const confirm = await c.evaluate(`(()=>{const e=document.querySelector('.monaco-dialog-box');return !!e?.offsetHeight && e.innerText.includes('open the external website');})()`);
    if (confirm) { await c.clickText('Open', '.monaco-dialog-box .monaco-button'); return null; }
    const ui = nodes();
    const success = ui.find(n => (n.text || '').includes('Browser passed: ' + marker));
    if (success) return {package: success.package, text: success.text,
      address: ui.find(n => /url_bar$/.test(n['resource-id'] || ''))?.text};
    // Chrome onboarding is handled through its normal controls if this is the
    // emulator's first launch. Never sign in or accept optional data sharing.
    for (const label of ['Use without an account', 'Accept & continue', 'No thanks', 'Not now', 'Got it']) {
      const button = ui.find(n => (n.text === label || n['content-desc'] === label) && n.enabled === 'true' && n.clickable === 'true');
      if (button) { tap(button); return null; }
    }
    return null;
  }, 90000, 'the external Android browser page, asset and WebSocket');
}
function listening(address) {
  const [host, port] = address.split(':');
  try { f.adb('shell', 'toybox', 'nc', '-z', '-w', '2', host, port); return true; }
  catch (error) { if (error.status === 1) return false; throw error; }
}
async function returnToApp() {
  f.adb('shell', 'am', 'start', '-n', 'com.vstab/app.vstab.MainActivity', '--activity-reorder-to-front');
  return workspace(false);
}
async function stopForward(c, remotePort) {
  const point = await c.evaluate(`(()=>{const e=${rowExpression(remotePort)};const r=e.getBoundingClientRect();return{x:r.x+Math.min(r.width/2,140),y:r.y+r.height/2};})()`);
  await c.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'right', buttons: 2, clickCount: 1});
  await c.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'right', buttons: 0, clickCount: 1});
  await c.waitFor(`[...document.querySelectorAll('.context-view .action-label')].some(e=>e.offsetHeight && e.textContent.trim()==='Stop Forwarding Port')`);
  await f.delay(250);
  await c.clickText('Stop Forwarding Port', '.context-view .action-label');
  await c.waitFor(`!(${rowExpression(remotePort)})`, 15000);
}
async function main() {
  if (!process.argv.includes('--run')) { console.log('Prepared ordinary Ports → Android browser proof; use --run after emulator handoff.'); return; }
  config = JSON.parse(fs.readFileSync(path.join(ROOT, '.local/demo/connection.json'), 'utf8'));
  assert.equal(config.container, 'vstab-demo-ssh'); assert.equal(config.username, 'developer');
  fs.mkdirSync(OUT, {recursive: true});
  const apk = path.resolve(process.env.VSTAB_CANDIDATE_APK || 'android/app/build/outputs/apk/debug/app-debug.apk');
  const report = {schema: 1, runId, marker, capturedAt: new Date().toISOString(),
    environment: 'Android API36 x86_64 tablet emulator, actual com.vstab APK and external Android browser, isolated Linux SSH fixture',
    proofMethod: 'Normal SSH host picker, Ports Add Port, Ports row Open in Browser, Stop Forwarding Port, Close Remote Connection; independent remote HTTP/WebSocket logs and Android TCP probes.',
    apkSha256: crypto.createHash('sha256').update(fs.readFileSync(apk)).digest('hex'), screenshots: [], checks: {},
    limits: ['Emulator proof does not establish physical Android ARM64 qualification.', 'Forwarding lasts while the Android app and SSH connection remain alive.']};
  let c, failure, started = false;
  try {
    const installedPath = f.adb('shell', 'pm', 'path', 'com.vstab').toString().trim().split('\n')[0].replace(/^package:/, '');
    report.installedApkSha256 = f.adb('exec-out', 'sha256sum', installedPath).toString().match(/^[a-f0-9]{64}/)?.[0];
    assert.equal(report.installedApkSha256, report.apkSha256, 'The installed APK must match the candidate.');
    startService(); started = true;
    const service = await until(() => { const s = serviceState(); return s.port ? s : null; });
    report.remoteService = {host: service.host, port: service.port, pid: service.pid};
    c = await workspace(process.argv.includes('--connected') ? false : true);
    if (!process.argv.includes('--connected')) c = await connect(c, report);
    await ensureProject(c);
    c.close(); c = await workspace(false);
    const trust = await c.evaluate(`(()=>{const e=document.querySelector('.monaco-dialog-box');return e?.offsetHeight && e.innerText.includes('trust')?[...e.querySelectorAll('.monaco-button')].map(b=>b.textContent.trim()).find(t=>t.includes('Yes, I trust')):null;})()`);
    if (trust) await c.clickText(trust, '.monaco-dialog-box .monaco-button');
    const forwarded = await forward(c, service.port);
    report.forwarded = forwarded; report.checks.nativeLoopbackAddressInPortsPanel = true;
    assert.ok(listening(forwarded.address), 'A real local TCP listener must exist on Android.');
    report.checks.androidTcpListener = true;
    console.log('Ports panel forwarded remote port ' + service.port + ' to Android ' + forwarded.address + '.');
    report.screenshots.push(capture('01-ports-native-ssh-listener'));
    remote(`import pathlib;pathlib.Path('/tmp/vstab-port-proof-${runId}/serve').touch()`);
    await until(() => serviceState().serving, 10000, 'the remote fixture to start serving after manual forwarding');
    await openBrowser(c, service.port);
    const browser = await browserResult(c);
    report.browser = browser;
    assert.equal(browser.package, 'com.android.chrome', 'The page must be in the external Android Chrome app.');
    assert.ok(!browser.address || (!browser.address.includes('/proxy/') && !browser.address.includes('/login')));
    const observed = serviceState(); report.observedRequests = observed.requests; report.websocket = observed.websocket;
    const chrome = observed.requests.filter(r => /Chrome\//.test(r.userAgent) && !/; wv\)/.test(r.userAgent));
    assert.ok(chrome.some(r => r.path === '/') && chrome.some(r => r.path === '/asset.txt') && chrome.some(r => r.path === '/echo'));
    assert.equal(observed.websocket?.echoedExpectedMarker, true);
    report.checks.externalChromeLoadedPage = true; report.checks.relativeAssetLoaded = true; report.checks.webSocketEcho = true;
    console.log('External Android Chrome loaded the remote page, relative asset and WebSocket echo.');
    report.screenshots.push(capture('02-external-chrome-remote-page'));
    c.close(); c = await returnToApp();
    if (process.argv.includes('--reload')) {
      await f.command(c, 'Developer: Reload Window'); c.close(); c = await workspace(false);
      await c.waitFor(visiblePortsTab, 30000);
      await c.click('a.action-label[aria-label^="Ports"]');
      await c.waitFor(`!!(${rowExpression(service.port)})`, 30000);
      assert.ok(listening(forwarded.address)); report.checks.forwardingRestoredAfterWorkbenchReload = true;
      console.log('Native port listener and Ports row survived the ordinary editor reload.');
      report.screenshots.push(capture('03-ports-after-editor-reload'));
    }
    await stopForward(c, service.port);
    await until(() => !listening(forwarded.address), 15000, 'Stop Forwarding to close the Android listener');
    report.checks.stopForwardingClosesListener = true;
    report.screenshots.push(capture('04-forwarding-stopped'));
    const again = await forward(c, service.port);
    assert.ok(listening(again.address));
    await f.command(c, 'Remote-SSH: Close Remote Connection'); c.close(); c = await workspace(true);
    await until(() => !listening(again.address), 15000, 'SSH disconnect to close the Android listener');
    report.checks.sshDisconnectClosesListener = true;
    await removeProofHost(c, report.fixtureAlias); report.temporaryFixtureAliasRemoved = !!report.fixtureAlias;
    assert.ok(Object.values(report.checks).every(Boolean));
    report.status = 'passed';
  } catch (error) {
    failure = error; report.status = 'failed';
    report.error = String(error.message).replaceAll(config.password, '<redacted>');
    try { report.screenshots.push(capture('failure')); } catch { }
  } finally {
    c?.close();
    if (started) { try { cleanupService(); report.temporaryRemoteServiceRemoved = true; } catch (error) { report.status = 'failed'; report.cleanupError = error.message; failure ||= error; } }
    report.completedAt = new Date().toISOString();
    fs.writeFileSync(path.join(OUT, 'proof-' + runId + '.json'), JSON.stringify(report, null, 2) + '\n');
    fs.writeFileSync(path.join(OUT, 'latest.json'), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify({status: report.status, checks: report.checks, report: path.relative(ROOT, path.join(OUT, 'latest.json')), error: report.error}));
  }
  if (failure) process.exitCode = 1;
}
main().catch(error => { console.error(String(error.message).replaceAll(config?.password || '__NO_PASSWORD__', '<redacted>')); process.exitCode = 1; });
