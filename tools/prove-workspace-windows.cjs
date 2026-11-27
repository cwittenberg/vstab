#!/usr/bin/env node
'use strict';
// Actual Android UI proof. Start on the isolated SSH fixture after emulator handoff.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const f = require('./feature-proof.cjs');
const root = path.resolve(__dirname, '..');
const output = path.join(root, 'artifacts/features');
const localFolder = '/data/user/0/app.vstab/files/workspaces/window-proof';
const remoteFile = 'window-remote-proof.js';
const localText = 'console.log("saved in the Android window");';
const remoteText = 'console.log("saved in the SSH window");';
const fixture = script => execFileSync('docker', ['exec', '--user', 'developer', '-w', '/home/developer/project',
  'vstab-demo-ssh', 'python3', '-c', script], {encoding: 'utf8'});
async function targets() {
  return (await (await fetch('http://127.0.0.1:9227/json/list')).json()).filter(t => {
    try { const d = JSON.parse(t.description); return t.type === 'page' && d.visible && d.attached; }
    catch { return false; }
  });
}
async function until(callback, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await callback(); if (value) return value; await f.delay(300); }
  throw new Error('Timed out waiting for independent workspace windows.');
}
function native(action) {
  return execFileSync('python3', ['-c', `
import importlib.util,sys
s=importlib.util.spec_from_file_location('demo','tools/android-demo.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
action=sys.argv[1]
tree=m.ui()
if action=='project':
 m.tap(next(n for n in tree.iter('node') if n.attrib.get('text')=='Android project'))
elif action=='path':
 n=next(n for n in tree.iter('node') if n.attrib.get('class')=='android.widget.EditText')
 m.tap(n);m.adb('shell','input','keycombination','KEYCODE_CTRL_LEFT','KEYCODE_A')
 m.adb('shell','input','text',sys.argv[2])
 tree=m.ui();n=next(n for n in tree.iter('node') if n.attrib.get('class')=='android.widget.EditText')
 assert n.attrib.get('text')==sys.argv[2], 'Project path did not reach native input'
 m.tap(next(n for n in tree.iter('node') if n.attrib.get('text','').lower()=='open window'))
`, action, localFolder], {cwd: root, encoding: 'utf8'});
}
async function hideKeyboard() {
  if (/\bmInputShown=true\b/.test(f.adb('shell', 'dumpsys', 'input_method').toString())) {
    f.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); await f.delay(500);
  }
}
async function showEditor(c) {
  if (await c.evaluate('!!document.querySelector(".part.panel")?.offsetHeight')) await c.key('j', 'KeyJ', 74, 2);
  if (await c.evaluate('!!document.querySelector(".part.auxiliarybar")?.offsetWidth')) await c.key('b', 'KeyB', 66, 3);
  await f.delay(300);
}
(async () => {
  if (!process.argv.includes('--run')) { console.log('Use --run after emulator handoff on the isolated SSH fixture.'); return; }
  const candidate = process.env.VSTAB_CANDIDATE_APK;
  assert.ok(candidate, 'Select an immutable APK with VSTAB_CANDIDATE_APK.');
  const apkSha256 = crypto.createHash('sha256').update(fs.readFileSync(candidate)).digest('hex');
  const installedPath = f.adb('shell', 'pm', 'path', 'app.vstab').toString().trim().split('\n')[0].replace(/^package:/, '');
  assert.equal(f.adb('exec-out', 'sha256sum', installedPath).toString().split(/\s/)[0], apkSha256);
  const record = {capturedAt: new Date().toISOString(), apkSha256,
    environment: 'Actual Android API36 x86_64 tablet emulator; controlled Linux SSH fixture', checks: {}};
  let main, second, error;
  try {
    main = await f.CDP.connect();
    assert.equal(await main.evaluate('new URL(location.href).searchParams.get("folder")'), '/home/developer/project');
    assert.match(await main.evaluate('location.hostname'), /^127\.0\.0\.[1-4]$/);
    const primary = main.target.id;
    fixture(`from pathlib import Path;Path(${JSON.stringify(remoteFile)}).write_text('console.log("before remote edit");')`);
    f.adb('shell', 'run-as', 'app.vstab', 'mkdir', '-p', localFolder);
    const seed = path.join(output, 'window-local-fixture.js');
    fs.writeFileSync(seed, 'console.log("before local edit");');
    f.adb('push', seed, '/data/local/tmp/vstab-window-local-fixture.js');
    f.adb('shell', 'run-as', 'app.vstab', 'cp', '/data/local/tmp/vstab-window-local-fixture.js', localFolder + '/second.js');
    await showEditor(main);
    await f.openFile(main, remoteFile); await f.replaceFile(main, remoteFile, remoteText);
    record.remoteBeforeSecondWindow = fixture(`from pathlib import Path;print(Path(${JSON.stringify(remoteFile)}).read_text())`).trim();
    assert.equal(record.remoteBeforeSecondWindow, remoteText);
    await f.command(main, 'vstab: New Workspace Window...');
    await f.delay(300); native('project'); await f.delay(300); native('path');
    const selected = await until(async () => (await targets()).find(t => t.id !== primary && decodeURIComponent(t.url).includes('/workspaces/window-proof')));
    second = await f.CDP.connect(selected.id);
    await second.waitFor('!!document.querySelector(".monaco-workbench")', 90000);
    await showEditor(second);
    await f.openFile(second, 'second.js'); await f.replaceFile(second, 'second.js', localText);
    await hideKeyboard(); await f.delay(600);
    record.localBytes = f.adb('exec-out', 'run-as', 'app.vstab', 'cat', localFolder + '/second.js').toString().trim();
    record.remoteBytes = fixture(`from pathlib import Path;print(Path(${JSON.stringify(remoteFile)}).read_text())`).trim();
    assert.equal(record.localBytes, localText); assert.equal(record.remoteBytes, remoteText);
    record.windows = (await targets()).map(t => ({id: t.id, url: t.url, bounds: JSON.parse(t.description)}));
    assert.equal(record.windows.length, 2);
    assert.ok(record.windows.every(t => t.bounds.width > 500 && t.bounds.height > 500));
    assert.notEqual(record.windows[0].bounds.screenX, record.windows[1].bounds.screenX);
    record.checks.explicitSecondWindowAction = true;
    record.checks.sshAndAndroidVisibleTogether = true;
    record.checks.distinctFilesSavedAndIndependentlyRead = true;
    record.screenshot = path.relative(root, f.screenshot('03-mixed-workspace-windows-final-apk.png'));
    f.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
    await until(async () => { const current = await targets(); return current.length === 1 && current[0].id === primary; });
    await main.waitFor('!!document.querySelector(".monaco-workbench")');
    const afterClose = 'console.log("SSH window still saves after closing Android window");';
    await f.replaceFile(main, remoteFile, afterClose);
    record.remoteBytesAfterClose = fixture(`from pathlib import Path;print(Path(${JSON.stringify(remoteFile)}).read_text())`).trim();
    assert.equal(record.remoteBytesAfterClose, afterClose);
    record.checks.closingSecondLeavesSshEditorUsable = true;
    record.checks.primaryOriginUnchanged = await main.evaluate('location.origin') === new URL(record.windows.find(t => t.id === primary).url).origin;
    assert.ok(Object.values(record.checks).every(Boolean));
  } catch (e) { error = e; record.error = e.message; try { f.screenshot('workspace-windows-failure.png'); } catch {} }
  finally {
    main?.close(); second?.close(); record.result = error ? 'failed' : 'passed';
    fs.writeFileSync(path.join(output, 'multiwindow-final-apk.json'), JSON.stringify(record, null, 2) + '\n');
    console.log('Final APK mixed-window proof: ' + record.result);
  }
  if (error) throw error;
})().catch(e => { console.error(e.message); process.exitCode = 1; });
