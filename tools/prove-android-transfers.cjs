#!/usr/bin/env node
'use strict';
// Ordinary Android Files / Code OSS Explorer transfer proof. Own the selected
// emulator before using --run. No filesystem or native bridge test hook is used
// to perform transfers; independent adb / Docker reads only check the results.
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
process.env.VSTAB_ANDROID_SERIAL ||= 'emulator-5584';
process.env.VSTAB_CDP_PORT ||= '9234';
const f = require('./feature-proof.cjs');
const ROOT = path.resolve(__dirname, '..');
const OUT = path.join(ROOT, 'artifacts/transfers');
const SOURCE = '/sdcard/Documents/vstab-transfers-proof';
const mode = process.env.VSTAB_TRANSFER_MODE || 'local';
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const fixtureBytes = name => {
  if (name === 'android-empty.dat') return Buffer.alloc(0);
  const data = Buffer.alloc(name === 'android-large.bin' ? 33 * 1024 * 1024 + 37 : name === 'android-upload.bin' ? 262181 : 131101);
  for (let i = 0; i < data.length; i++) data[i] = (i * 31 + Math.floor(i / 257) + name.length) & 255;
  return data;
};
const fixtureNames = ['android-upload.bin', 'android-paste.bin', 'android-menu-paste.bin', 'android-empty.dat'];
function adb(...args) {
  const binary=process.env.ANDROID_HOME?path.join(process.env.ANDROID_HOME,'platform-tools/adb'):'adb';
  return execFileSync(binary,['-s',process.env.VSTAB_ANDROID_SERIAL,...args],{maxBuffer:96*1024*1024,timeout:45000});
}
function screenshot(name) {
  fs.mkdirSync(OUT, {recursive: true});
  const file = path.join(OUT, name + '.png');
  fs.writeFileSync(file, adb('exec-out', 'screencap', '-p'));
  return path.relative(ROOT, file);
}
function seed() {
  fs.mkdirSync(OUT, {recursive: true});
  adb('shell', 'mkdir', '-p', SOURCE);
  for (const name of fixtureNames) {
    const local = path.join(OUT, name);
    fs.writeFileSync(local, fixtureBytes(name));
    adb('push', local, SOURCE + '/' + name);
  }
}
function xmlDecode(value) {
  return value.replace(/&(amp|lt|gt|quot|apos);/g, (_, key) => ({amp: '&', lt: '<', gt: '>', quot: '"', apos: "'"}[key]));
}
function uiNodes() {
  adb('shell', 'uiautomator', 'dump', '--compressed', '/sdcard/vstab-transfer-ui.xml');
  return [...adb('exec-out', 'cat', '/sdcard/vstab-transfer-ui.xml').toString().matchAll(/<node\b[^>]*>/g)]
    .map(([tag]) => Object.fromEntries([...tag.matchAll(/([\w-]+)="([^"]*)"/g)].map(([, key, value]) => [key, xmlDecode(value)])));
}
function center(node) {
  const n = node.bounds?.match(/\d+/g)?.map(Number);
  assert.equal(n?.length, 4, 'Native UI node must have bounds');
  return {x: Math.round((n[0] + n[2]) / 2), y: Math.round((n[1] + n[3]) / 2)};
}
function tap(node, long = false) {
  const {x, y} = center(node);
  if (long) adb('shell', 'input', 'swipe', String(x), String(y), String(x), String(y), '900');
  else adb('shell', 'input', 'tap', String(x), String(y));
}
function nativeText(text, {long = false, content = false} = {}) {
  let nodes = uiNodes().filter(n => (n.text === text || n['content-desc'] === text) && n.enabled === 'true');
  if (content) nodes = nodes.filter(n => n['resource-id'] === 'android:id/title');
  nodes.sort((a, b) => content ? center(b).x - center(a).x : center(a).x - center(b).x);
  assert.ok(nodes.length, 'Native Android UI does not show ' + text);
  tap(nodes[0], long);
}
async function until(callback, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const result = await callback(); if (result) return result; await f.delay(250); }
  throw new Error('Timed out waiting for Android transfer state');
}
async function androidSourceFolder() {
  let nodes = uiNodes();
  if (nodes.some(n => n.text === 'Files in vstab-transfers-proof')) return;
  const drawer = nodes.find(n => ['Show roots', 'Open navigation drawer'].includes(n['content-desc']));
  if (drawer) { tap(drawer); await f.delay(250); }
  nodes = uiNodes();
  const storage = nodes.find(n => n.text === 'sdk_gphone64_x86_64') || nodes.find(n => n.text === 'Internal storage');
  assert.ok(storage, 'The Android picker must expose internal shared storage');
  tap(storage); await f.delay(300);
  nativeText('Documents', {content: true}); await f.delay(250);
  nativeText('vstab-transfers-proof', {content: true}); await f.delay(250);
}
async function attach() {
  const desired = await until(async () => {
    const list = await (await fetch('http://127.0.0.1:' + process.env.VSTAB_CDP_PORT + '/json/list')).json();
    return list.find(t => {
      try { const d = JSON.parse(t.description || '{}'); return t.type === 'page' && d.visible && d.attached && (new URL(t.url).hostname === 'localhost') === (mode === 'local'); }
      catch { return false; }
    });
  }, 120000);
  const c = await f.CDP.connect(desired.id);
  await c.waitFor('!!document.querySelector(".monaco-workbench")', 90000);
  return c;
}
async function explorer(c) {
  await c.key('Escape', 'Escape', 27);
  await c.key('E', 'KeyE', 69, 10);
  await c.waitFor('!!document.querySelector(".explorer-folders-view")?.offsetWidth');
}
async function explorerContext(c, filename = null) {
  await explorer(c);
  const point = await c.evaluate(`(() => {
    const labels = [...document.querySelectorAll('.explorer-folders-view .label-name')];
    const label = ${filename === null ? 'null' : 'labels.find(e=>e.textContent.trim()===' + JSON.stringify(filename) + ')'};
    if (!label && ${filename !== null}) throw Error('Explorer item missing');
    const row = label?.closest('.monaco-list-row') || label || document.querySelector('.explorer-folders-view .monaco-list');
    const r=row.getBoundingClientRect();return {x:r.x+Math.min(r.width/2,150),y:${filename === null ? 'r.bottom-20' : 'r.y+r.height/2'}};
  })()`);
  await c.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'right', buttons: 2, clickCount: 1});
  await c.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'right', buttons: 0, clickCount: 1});
  await c.waitFor('!!document.querySelector(".context-view .monaco-menu")');
}
async function menu(c, title) {
  // Dispatch the final menu selection through Android itself. This keeps the
  // picker user gesture tied to the foreground Activity, including after a
  // context menu has just acquired focus.
  const point=await c.evaluate(`(()=>{const e=[...document.querySelectorAll('.context-view .action-label')].find(e=>e.textContent.trim()===${JSON.stringify(title)}&&e.checkVisibility({checkVisibilityCSS:true}));if(!e)throw Error('Missing visible Explorer menu action');const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth,height:innerHeight};})()`);
  const view=uiNodes().find(n=>n.class==='android.webkit.WebView'&&n.bounds&&Number(n.bounds.match(/\d+/g)?.[3])>300);
  assert.ok(view,'Visible Android workbench WebView is required');
  const [left,top,right,bottom]=view.bounds.match(/\d+/g).map(Number);
  adb('shell','input','tap',String(Math.round(left+point.x*(right-left)/point.width)),String(Math.round(top+point.y*(bottom-top)/point.height)));
}
function workspaceBytes(folder, name) {
  if (mode === 'local') return adb('exec-out', 'run-as', 'app.vstab', 'cat', folder + '/' + name);
  const config = JSON.parse(fs.readFileSync(path.join(ROOT, '.local/demo/connection.json'), 'utf8'));
  return execFileSync('docker', ['exec', '--user', config.username, config.container, 'cat', folder + '/' + name],{maxBuffer:96*1024*1024});
}
async function checkWorkspace(folder, name) {
  const expected = fixtureBytes(name);
  const bytes = await until(() => {
    try { const value=workspaceBytes(folder,name);return value.equals(expected) ? value : false; } catch { return false; }
  });
  assert.equal(digest(bytes), digest(expected));
  return {name, bytes: bytes.length, sha256: digest(bytes), independentRead: mode === 'local' ? 'adb run-as app.vstab cat' : 'isolated Docker SSH-host cat'};
}
async function upload(c, folder, cancel = false) {
  await explorerContext(c);
  await menu(c, 'Upload...');
  await until(()=>/topResumedActivity=.*documentsui/.test(adb('shell','dumpsys','activity','activities').toString()),60000);
  await f.delay(300);
  if (cancel) {
    screenshot(mode + '-upload-cancel-picker');
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await until(()=>/topResumedActivity=.*app\.vstab\//.test(adb('shell','dumpsys','activity','activities').toString()));
    assert.equal(await c.evaluate('new URL(location.href).searchParams.get("folder")'), folder);
    return {cancelled: true, workspacePreserved: true, appForegroundAfterCancel: true};
  }
  await androidSourceFolder();
  screenshot(mode + '-upload-android-picker');
  nativeText('android-upload.bin', {content: true});
  const result = await checkWorkspace(folder, 'android-upload.bin');
  screenshot(mode + '-upload-explorer');
  return result;
}
async function paste(c, folder, useMenu = false) {
  const taskDump = adb('shell', 'dumpsys', 'activity', 'activities').toString();
  const taskId = taskDump.match(/topResumedActivity=.*app\.vstab\/[^\s]+ t(\d+)/)?.[1];
  assert.ok(taskId, 'The app task must be known before opening Android Files');
  adb('shell', 'am', 'start', '-n', 'com.google.android.documentsui/com.android.documentsui.LauncherActivity');
  await f.delay(300); await androidSourceFolder();
  const name = useMenu ? 'android-menu-paste.bin' : 'android-paste.bin';
  nativeText(name, {content: true, long: true});
  adb('shell', 'input', 'keycombination', 'KEYCODE_CTRL_LEFT', 'KEYCODE_C');
  await f.delay(300); screenshot(mode + '-copied-in-android-files');
  adb('shell', 'am', 'task', 'focus', taskId);
  await until(()=>/topResumedActivity=.*app\.vstab\//.test(adb('shell','dumpsys','activity','activities').toString()));
  await f.delay(400); await explorer(c);
  if (useMenu) { await explorerContext(c); await menu(c, 'Paste from Android'); }
  else { await c.click('.explorer-folders-view .monaco-list'); await c.key('v', 'KeyV', 86, 2); }
  await until(async()=>{
    if(await c.evaluate(`!!document.querySelector('.monaco-dialog-box')?.offsetHeight && document.querySelector('.monaco-dialog-box').innerText.includes('Are you sure you want to paste')`)){
      await c.clickText('Paste','.monaco-dialog-box .monaco-button');
      return true;
    }
    try{return workspaceBytes(folder,name).equals(fixtureBytes(name));}catch{return false;}
  });
  const result = await checkWorkspace(folder, name);
  screenshot(mode + (useMenu ? '-pasted-in-explorer-menu' : '-pasted-in-explorer'));
  return {...result, source: 'Android Files selected actual file and Ctrl+C', paste: useMenu ? 'ordinary Explorer Paste from Android context action' : 'ordinary Explorer focus and Ctrl+V'};
}
async function terminal(c) {
  await f.command(c, 'Terminal: Create New Terminal');
  await c.waitFor('!!document.querySelector(".terminal-wrapper.active .xterm-screen")');
  await f.terminal(c, 'sha256sum android-upload.bin android-paste.bin android-menu-paste.bin');
  for (const name of fixtureNames.filter(name => name !== 'android-empty.dat')) {
    await c.waitFor('document.body.innerText.includes(' + JSON.stringify(digest(fixtureBytes(name)) + '  ' + name) + ')');
  }
  screenshot(mode + '-terminal-file-access');
  return {command: 'sha256sum android-upload.bin android-paste.bin android-menu-paste.bin', output: await c.evaluate('document.body.innerText.slice(-5000)')};
}
async function download(c, folder, cancel = false, sourceName = 'android-upload.bin') {
  await explorerContext(c, sourceName);
  await menu(c, 'Download...');
  await until(()=>/topResumedActivity=.*documentsui/.test(adb('shell','dumpsys','activity','activities').toString()),120000);
  await f.delay(300);
  if (cancel) {
    screenshot(mode + '-download-cancel-picker');
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await until(()=>/topResumedActivity=.*app\.vstab\//.test(adb('shell','dumpsys','activity','activities').toString()));
    assert.equal(await c.evaluate('new URL(location.href).searchParams.get("folder")'), folder);
    return {cancelled: true, workspacePreserved: true, appForegroundAfterCancel: true};
  }
  let nodes = uiNodes();
  const downloads = nodes.find(n => n.text === 'Downloads');
  if (downloads) { tap(downloads); await f.delay(300); }
  nodes = uiNodes();
  const field = nodes.find(n => n.class === 'android.widget.EditText' && (n['resource-id'] === 'android:id/title' || n.text.endsWith('.bin')));
  assert.ok(field, 'Android save picker must show an editable filename');
  tap(field);
  adb('shell', 'input', 'keycombination', 'KEYCODE_CTRL_LEFT', 'KEYCODE_A');
  const name = 'vstab-' + mode + (sourceName === 'android-large.bin' ? '-large-download.bin' : '-download.bin');
  adb('shell', 'rm', '-f', '/sdcard/Download/' + name);
  adb('shell', 'input', 'text', name);
  screenshot(mode + '-download-android-save-picker');
  const save = uiNodes().find(n => /^save$/i.test(n.text) && n.enabled === 'true');
  assert.ok(save, 'Android save picker must expose Save'); tap(save);
  const original = fixtureBytes(sourceName);
  const destination = '/sdcard/Download/' + name;
  const bytes = await until(() => { try {const value=adb('exec-out','cat',destination);return value.equals(original)?value:false;} catch {return false;} },120000);
  screenshot(mode + '-download-completed');
  return {source: folder + '/' + sourceName, destination, bytes: bytes.length, sha256: digest(bytes), independentRead: 'adb shared-storage cat'};
}
async function downloadLarge(c, folder) {
  const name = 'android-large.bin';
  const bytes = fixtureBytes(name);
  const file = path.join(OUT, name);
  fs.writeFileSync(file, bytes);
  if (mode === 'local') {
    adb('push', file, '/data/local/tmp/vstab-transfer-large.bin');
    adb('shell', 'run-as', 'app.vstab', 'cp', '/data/local/tmp/vstab-transfer-large.bin', folder + '/' + name);
  } else {
    const config = JSON.parse(fs.readFileSync(path.join(ROOT, '.local/demo/connection.json'), 'utf8'));
    execFileSync('docker', ['exec', '-i', '--user', config.username, config.container, 'python3', '-c',
      'import pathlib,sys;pathlib.Path(' + JSON.stringify(folder + '/' + name) + ').write_bytes(sys.stdin.buffer.read())'], {input: bytes});
  }
  assert.equal(digest(workspaceBytes(folder, name)), digest(bytes), 'Large fixture seed is exact');
  await c.waitFor('document.querySelector(".explorer-folders-view")?.innerText.includes("android-large.bin")');
  return {...await download(c, folder, false, name), fixtureSeedOnly: 'Large source file staged directly before ordinary Explorer Download'};
}
async function run() {
  assert.ok(['local', 'ssh'].includes(mode));
  seed();
  const candidate = process.env.VSTAB_CANDIDATE_APK;
  assert.ok(candidate, 'Select the immutable candidate with VSTAB_CANDIDATE_APK');
  const sha = digest(fs.readFileSync(candidate));
  const apkPath = adb('shell', 'pm', 'path', 'app.vstab').toString().trim().split('\n')[0].replace(/^package:/, '');
  assert.equal(adb('exec-out','sha256sum',apkPath).toString().split(/\s/)[0], sha);
  let record = {capturedAt: new Date().toISOString(), serial: process.env.VSTAB_ANDROID_SERIAL, mode, apkSha256: sha, checks: {}};
  let c;
  try {
    c = await attach();
    record.workspace = await c.evaluate('new URL(location.href).searchParams.get("folder")');
    assert.ok(record.workspace, 'Open a controlled proof workspace first');
    const outputFile = path.join(OUT,mode+'-proof.json');
    if (fs.existsSync(outputFile)) {
      const previous=JSON.parse(fs.readFileSync(outputFile,'utf8'));
      if(previous.apkSha256===sha&&previous.serial===record.serial&&previous.workspace===record.workspace){
        record={...previous,checks:previous.checks||{},continuedAt:new Date().toISOString()};delete record.failure;delete record.completed;
      }
    }
    const flows=(process.env.VSTAB_TRANSFER_FLOWS || 'upload-cancel,upload,paste,paste-menu,download-cancel,download,download-large,terminal').split(',');
    for(const flow of flows){
      console.log('Running ' + mode + ' ' + flow + ' through ordinary Android UI');
      if (flow === 'upload-cancel') record.checks.uploadCancel=await upload(c,record.workspace,true);
      else if (flow === 'upload') record.checks.upload=await upload(c,record.workspace);
      else if (flow === 'paste') record.checks.paste=await paste(c,record.workspace);
      else if (flow === 'paste-menu') record.checks.pasteMenu=await paste(c,record.workspace,true);
      else if (flow === 'download-cancel') record.checks.downloadCancel=await download(c,record.workspace,true);
      else if (flow === 'download') record.checks.download=await download(c,record.workspace);
      else if (flow === 'download-large') record.checks.downloadLarge=await downloadLarge(c,record.workspace);
      else if (flow === 'terminal') record.checks.terminal=await terminal(c);
      else throw Error('Unknown proof flow: '+flow);
      console.log('PASS '+mode+' '+flow);
      fs.writeFileSync(path.join(OUT,mode+'-proof.json'),JSON.stringify(record,null,2)+'\n');
    }
    record.visibleText=await c.evaluate('document.body.innerText.slice(-9000)');
    record.completed=true;
  } catch(error){record.failure=error.stack||error.message;screenshot(mode+'-failure');throw error;}
  finally {c?.close();fs.writeFileSync(path.join(OUT,mode+'-proof.json'),JSON.stringify(record,null,2)+'\n');}
}
if(require.main===module){
  if(process.argv.includes('--run'))run().catch(error=>{console.error(error.stack||error.message);process.exitCode=1;});
  else console.log('Prepared transfer proof; use --run only after emulator ownership and candidate installation.');
}
module.exports={seed,uiNodes,nativeText,tap,until,attach,explorer,explorerContext,menu,androidSourceFolder,upload,paste,download,workspaceBytes,fixtureBytes,screenshot};
