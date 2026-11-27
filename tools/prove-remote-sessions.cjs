#!/usr/bin/env node
'use strict';
/** Ordinary Android SSH connect → named terminal → disconnect → reconnect proof.
 * Credentials are read privately; no bridge/internal command hooks are invoked.
 * Independent Docker reads establish the remote shell and generated server bytes.
 * Run only after the emulator owner hands it off: source .local/env.sh; node tools/prove-remote-sessions.cjs --run
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const f = require('./feature-proof.cjs');
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'artifacts/features');
const wait = f.delay;
const runId = Date.now().toString();
const sessionName = 'SSH reconnect proof ' + runId;
let privateConfig;
const clean = value => String(value).replaceAll(privateConfig?.password || 'NEVER_A_REAL_SECRET_PLACEHOLDER', '<redacted>');
const remote = script => {
  try { return execFileSync('docker', ['exec', '--user', privateConfig.username, '-w', privateConfig.workspace,
    privateConfig.container, 'python3', '-c', script], {encoding: 'utf8', timeout: 30000, maxBuffer: 2 * 1024 * 1024}); }
  catch (error) { throw new Error('Independent SSH fixture check failed (exit ' + error.status + ').'); }
};
async function until(predicate, timeout = 90000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await predicate(); if (value) return value; await wait(250); }
  throw new Error('Timed out waiting for the ordinary Android SSH flow.');
}
async function targets() {
  return (await (await fetch('http://127.0.0.1:' + (process.env.VSTAB_CDP_PORT || '9227') + '/json/list')).json())
    .filter(t => t.type === 'page' && /^http:\/\/(?:localhost|127\.0\.0\.[1-4]):/.test(t.url));
}
async function attachWorkspace(local) {
  const selected = await until(async () => {
    const list = await targets();
    return list.find(t => {
      try {
        const descriptor = JSON.parse(t.description);
        return descriptor.visible && descriptor.attached && (new URL(t.url).hostname === 'localhost') === local;
      } catch { return false; }
    });
  }, 120000);
  const client = await f.CDP.connect(selected.id);
  await client.waitFor('!!document.querySelector(".monaco-workbench")', 120000);
  return client;
}
async function visibleInput(c, password = false) {
  return c.evaluate(`(() => { const q = document.querySelector('.quick-input-widget'); const i = q?.querySelector('input'); return !!(q?.offsetHeight && i && ${password ? "i.type === 'password'" : "i.type !== 'password'"}); })()`);
}
async function chooseFixture(c) {
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .monaco-list-row')].some(e=>e.innerText.includes('developer@10.0.2.2:22227'))`);
  const point = await c.evaluate(`(() => {
    const endpoint = ${JSON.stringify('developer@10.0.2.2:22227')};
    const row = [...document.querySelectorAll('.quick-input-widget .monaco-list-row')].find(e => e.innerText.includes(endpoint));
    if (!row) throw Error('The ordinary host picker has no matching isolated SSH fixture.');
    const label = row.querySelector('.label-name')?.textContent.trim();
    const r = row.getBoundingClientRect(); return {x: r.x+r.width/2, y: r.y+r.height/2, label};
  })()`);
  await c.send('Input.dispatchMouseEvent', {type: 'mousePressed', x: point.x, y: point.y, button: 'left', clickCount: 1});
  await c.send('Input.dispatchMouseEvent', {type: 'mouseReleased', x: point.x, y: point.y, button: 'left', clickCount: 1});
  return point.label;
}
async function authenticate(c, report) {
  const known = execFileSync('ssh-keygen', ['-lf', path.join(ROOT, '.local/demo/known_hosts')], {encoding: 'utf8'});
  const fingerprint = known.match(/SHA256:[A-Za-z0-9+/]+/)?.[0];
  assert.ok(fingerprint, 'Independent fixture fingerprint must be available.');
  const deadline = Date.now() + 120000;
  let answered = false;
  while (Date.now() < deadline) {
    const list = await targets();
    if (list.some(t => {
      try { const d = JSON.parse(t.description); return d.visible && d.attached && new URL(t.url).hostname !== 'localhost'; } catch { return false; }
    })) return;
    if (await visibleInput(c, true)) {
      assert.ok(!answered, 'An authentication retry is a real failure, not another unrecorded attempt.');
      await f.typeKeys(c, privateConfig.password);
      await c.key('Enter', 'Enter', 13);
      answered = true; report.passwordPromptAnsweredPrivately = (report.passwordPromptAnsweredPrivately || 0) + 1;
    }
    const modal = await c.evaluate(`(() => {
      const box = document.querySelector('.monaco-dialog-box');
      return box?.offsetHeight ? {text: box.innerText, buttons: [...box.querySelectorAll('.monaco-button')].map(e=>e.textContent.trim())} : null;
    })()`);
    if (modal?.text.includes('Compare this SHA-256 fingerprint')) {
      assert.ok(modal.text.includes(fingerprint), 'Displayed host fingerprint must match the independent fixture.');
      await c.clickText('Continue', '.monaco-dialog-box .monaco-button');
      report.hostFingerprintVerified = true;
    }
    const errorText = await c.evaluate(`document.body.innerText`);
    if (/SSH authentication failed|Built-in workspace tools failed|Remote server download failed|built-in tools directory is incomplete/.test(errorText)) {
      throw new Error('The Android SSH UI reported a connection failure.');
    }
    await wait(300);
  }
  throw new Error('SSH authentication/setup did not open a remote workspace.');
}
async function connect(c, report) {
  await f.command(c, 'Remote-SSH: Connect to Host...');
  report.hostAlias = await chooseFixture(c);
  console.log('Selected isolated SSH host through normal IDE picker.');
  await authenticate(c, report); c.close();
  const remoteClient = await attachWorkspace(false);
  await ensureProject(remoteClient);
  return remoteClient;
}
async function ensureProject(c) {
  const actual = await c.evaluate('new URL(location.href).searchParams.get("folder")');
  if (actual === privateConfig.workspace || await c.evaluate('document.title.includes(" - project - ") || document.title.startsWith("project - ")')) return;
  await f.command(c, 'Remote-SSH: Open Folder...');
  await c.waitFor('document.querySelector(".quick-input-widget input")?.getAttribute("aria-label") === "Folder path - Open Folder"');
  await wait(200); await c.click('.quick-input-widget input');
  await c.key('a', 'KeyA', 65, 2); await f.typeKeys(c, privateConfig.workspace + '/');
  assert.equal(await c.evaluate('document.querySelector(".quick-input-widget input").value'), privateConfig.workspace + '/');
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .monaco-button')].some(e=>e.textContent.trim()==='OK')`);
  await c.clickText('OK', '.quick-input-widget .monaco-button');
  await wait(1500);
}
async function trustFixture(c) {
  const button = await c.evaluate(`(() => {
    const box = document.querySelector('.monaco-dialog-box');
    return box?.innerText.includes('trust') ? [...box.querySelectorAll('.monaco-button')].map(e=>e.textContent.trim()).find(t=>/Yes, I trust/.test(t)) : null;
  })()`);
  if (button) await c.clickText(button, '.monaco-dialog-box .monaco-button');
  if (await c.evaluate('document.body.innerText.includes("Restricted Mode")')) {
    const restricted = '[aria-label="Restricted Mode: Some features are disabled because this folder is not trusted."]';
    if (await c.evaluate(`!!document.querySelector(${JSON.stringify(restricted)})`)) {
      await c.click(restricted);
      await c.waitFor('[...document.querySelectorAll("a.monaco-button")].some(e=>e.textContent.trim()==="Trust")');
      await c.clickText('Trust'); await c.key('Escape', 'Escape', 27);
    }
  }
}
function inspectRemote(featureId) {
  return JSON.parse(remote(`
import os,json,pathlib,hashlib
p=pathlib.Path.cwd()
def text(name):
 q=p/name
 return q.read_text() if q.exists() else None
before=text('.vstab-remote-before-${runId}.pid')
after=text('.vstab-remote-after-${runId}.pid')
pid=int(before.strip()) if before else 0
alive=False
try:
 if pid: os.kill(pid,0); alive=True
except ProcessLookupError: pass
parentPid=int(pathlib.Path('/proc/'+str(pid)+'/stat').read_text().rsplit(')',1)[1].split()[1]) if alive else 0
daemonCommand=pathlib.Path('/proc/'+str(parentPid)+'/cmdline').read_bytes().decode().replace(chr(0),' ') if parentPid else ''
root=pathlib.Path.home()/'.local/share/vstab'
variant=root/'server'/('4.146.0-vstab-${featureId}')
state=root/'state'/('4.146.0-${featureId}')
manifest=variant/'lib/vscode/extensions/vstab-first-party.json'
filesVerified=False
if manifest.exists():
 m=json.loads(manifest.read_text())
 filesVerified=all(hashlib.sha256((variant/'lib/vscode/extensions'/name).read_bytes()).hexdigest()==entry['sha256'] for name,entry in m['files'].items())
print(json.dumps({'beforePid':pid,'afterPid':int(after.strip()) if after else None,'shellAlive':alive,
 'shellCwd':os.readlink('/proc/'+str(pid)+'/cwd') if alive else None,
 'daemonPid':parentPid or None,'version2EngineNamespace':'vstab-pty-v2-' in daemonCommand,
 'daemonFromCurrentFeatureBuild':'4.146.0-vstab-${featureId}' in daemonCommand,
 'commandMarker':text('.vstab-remote-once-${runId}.txt'),'detachedJobMarker':text('.vstab-remote-job-${runId}.txt'),
 'serverPid':(state/'pid').read_text().strip() if (state/'pid').exists() else None,
 'serverPort':(state/'port').read_text().strip() if (state/'port').exists() else None,
 'immutableFeatureBuildPresent':variant.exists(),'builtinSourceHashesVerified':filesVerified,
 'builtinExtensionIds':[json.loads((variant/'lib/vscode/extensions'/n/'package.json').read_text())['publisher']+'.'+json.loads((variant/'lib/vscode/extensions'/n/'package.json').read_text())['name'] for n in ['vstab-session-tools','vstab-compatibility']] if manifest.exists() else []}))
`));
}
async function selectNamed(c) {
  const selector = '.monaco-list-row[aria-label*=' + JSON.stringify(sessionName) + ']';
  const mode = await until(async () => c.evaluate(`(() => {
    const row = document.querySelector(${JSON.stringify(selector)});
    if (row?.offsetHeight) return 'row';
    if (document.querySelectorAll('.terminal-wrapper').length === 1 && document.body.innerText.includes(${JSON.stringify(sessionName)})) return 'single';
    return null;
  })()`));
  if (mode === 'row') await c.click(selector);
  await c.waitFor('!!document.querySelector(".terminal-wrapper.active .xterm-screen")');
}
async function capture(name) {
  const ime = f.adb('shell', 'dumpsys', 'input_method').toString();
  if (/\bmInputShown=true\b/.test(ime)) f.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await wait(700);
  return path.relative(ROOT, f.screenshot(name));
}
async function main() {
  if (!process.argv.includes('--run')) { console.log('Prepared ordinary Android SSH reconnect proof; use --run only after emulator handoff.'); return; }
  privateConfig = JSON.parse(fs.readFileSync(path.join(ROOT, '.local/demo/connection.json'), 'utf8'));
  assert.equal(privateConfig.container, 'vstab-demo-ssh'); assert.equal(privateConfig.workspace, '/home/developer/project');
  fs.mkdirSync(OUTPUT, {recursive: true});
  const apkPath = path.resolve(process.env.VSTAB_CANDIDATE_APK || path.join(ROOT, 'android/app/build/outputs/apk/debug/app-debug.apk'));
  const metadata = JSON.parse(execFileSync('python3', ['-c', 'import zipfile,sys; print(zipfile.ZipFile(sys.argv[1]).read("assets/server-tools.json").decode())', apkPath], {encoding:'utf8'}));
  const featureId = metadata.payloadSha256.slice(0, 20);
  const apkSha256 = require('node:crypto').createHash('sha256').update(fs.readFileSync(apkPath)).digest('hex');
  const report = {schema: 1, capturedAt: new Date().toISOString(), environment: 'Actual Android API36 tablet emulator app.vstab WebView + isolated Linux SSH host',
    proofMethod: 'Ordinary IDE host picker, masked password prompt, persistent-terminal command and Close Remote Connection; independent remote PID/file/source-hash readback.',
    runId, sessionName, featureId, apkSha256, screenshots: [], checks: {}, limits: ['Emulator proof does not establish physical ARM64/tablet qualification.', 'Remote shells depend on the SSH host remaining alive; no reboot survival is claimed.']};
  let c, failure;
  try {
    const installedPath = f.adb('shell', 'pm', 'path', 'app.vstab').toString().trim().split('\n')[0].replace(/^package:/, '');
    const installedSha = f.adb('exec-out', 'sha256sum', installedPath).toString().match(/^[a-f0-9]{64}/)?.[0];
    report.installedApkSha256 = installedSha;
    assert.equal(installedSha, apkSha256, 'The installed APK must match the candidate APK being reported.');
    if (process.argv.includes('--connected')) {
      c = await attachWorkspace(false); await ensureProject(c); report.startedInConnectedFixture = true;
    } else {
      c = await attachWorkspace(true); c = await connect(c, report);
    }
    c.close(); c = await attachWorkspace(false);
    await trustFixture(c);
    console.log('Remote project opened through native SSH; creating named PTY.');
    await f.command(c, 'vstab: New Persistent Terminal...');
    await c.waitFor('document.querySelector(".quick-input-widget")?.offsetHeight > 0 && document.querySelector(".quick-input-widget input")?.value === "Project shell"');
    await wait(250); await c.click('.quick-input-widget input');
    await c.key('a', 'KeyA', 65, 2); await wait(100); await f.typeKeys(c, sessionName);
    assert.equal(await c.evaluate('document.querySelector(".quick-input-widget input").value'), sessionName);
    await c.key('Enter', 'Enter', 13);
    await c.waitFor('!!document.querySelector(".terminal-wrapper.active .xterm-screen")');
    await selectNamed(c);
    await f.terminal(c, `trap "" HUP TERM; echo $$ > .vstab-remote-before-${runId}.pid; echo COMMAND_ONCE >> .vstab-remote-once-${runId}.txt; (sleep 8; echo OUTPUT_WHILE_SSH_DISCONNECTED_${runId}; echo JOB_COMPLETED > .vstab-remote-job-${runId}.txt) &`);
    await until(() => inspectRemote(featureId).beforePid > 0);
    report.before = inspectRemote(featureId);
    console.log('Remote named PTY independently verified, PID ' + report.before.beforePid + '.');
    assert.ok(report.before.shellAlive && report.before.builtinSourceHashesVerified && report.before.version2EngineNamespace && report.before.daemonFromCurrentFeatureBuild);
    assert.equal(report.before.shellCwd, privateConfig.workspace);
    report.screenshots.push(await capture('08-remote-named-session-before-disconnect.png'));
    await f.command(c, 'Remote-SSH: Close Remote Connection'); c.close();
    c = await attachWorkspace(true);
    console.log('Ordinary Close Remote Connection returned to Android workspace.');
    report.disconnectedAt = new Date().toISOString();
    report.checks.returnedToLocalWorkbench = (await c.evaluate('location.hostname')) === 'localhost';
    report.whileDisconnected = await until(() => { const state = inspectRemote(featureId); return state.detachedJobMarker ? state : null; }, 20000);
    assert.ok(report.whileDisconnected.shellAlive);
    report.screenshots.push(await capture('09-local-workspace-while-remote-job-runs.png'));
    c = await connect(c, report); c.close(); c = await attachWorkspace(false);
    await c.waitFor(`document.body.innerText.includes(${JSON.stringify(sessionName)})`, 90000);
    await selectNamed(c);
    await c.waitFor(`document.body.innerText.includes(${JSON.stringify('OUTPUT_WHILE_SSH_DISCONNECTED_' + runId)})`, 90000);
    await f.terminal(c, `echo $$ > .vstab-remote-after-${runId}.pid; echo SAME_SHELL_AFTER_SSH_RECONNECT=$$`);
    await until(() => inspectRemote(featureId).afterPid > 0);
    report.after = inspectRemote(featureId);
    report.checks.samePtyPidAfterSshReconnect = report.after.beforePid === report.after.afterPid;
    report.checks.sameRemoteServerReused = report.after.serverPid === report.before.serverPid && report.after.serverPort === report.before.serverPort;
    report.checks.commandNotReplayed = report.after.commandMarker === 'COMMAND_ONCE\n';
    report.checks.detachedJobCompleted = report.after.detachedJobMarker === 'JOB_COMPLETED\n';
    report.checks.namedTabAutomaticallyRestored = await c.evaluate(`document.body.innerText.includes(${JSON.stringify(sessionName)})`);
    report.checks.outputWhileDisconnectedRecovered = await c.evaluate(`document.body.innerText.includes(${JSON.stringify('OUTPUT_WHILE_SSH_DISCONNECTED_' + runId)})`);
    report.checks.builtinSourceBytesVerified = report.after.builtinSourceHashesVerified;
    assert.ok(Object.values(report.checks).every(Boolean), 'Every reconnect check must pass.');
    report.visibleTerminalText = clean(await c.evaluate('document.body.innerText')).slice(-12000);
    report.screenshots.push(await capture('10-remote-named-session-restored-after-ssh.png'));
    // Complete the normal management flow on this proof-owned session only.
    await f.command(c, 'vstab: Rename Persistent Terminal...');
    await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(sessionName)})`);
    await c.clickText(sessionName, '.quick-input-widget .label-name');
    await c.waitFor(`document.querySelector('.quick-input-widget input')?.value === ${JSON.stringify(sessionName)}`);
    await wait(250); await c.click('.quick-input-widget input');
    const renamed = 'SSH restored and renamed ' + runId;
    await c.key('a', 'KeyA', 65, 2); await f.typeKeys(c, renamed);
    assert.equal(await c.evaluate('document.querySelector(".quick-input-widget input").value'), renamed);
    await c.key('Enter', 'Enter', 13);
    await c.waitFor(`document.body.innerText.includes(${JSON.stringify(renamed)})`);
    report.checks.renamedThroughNormalUi = inspectRemote(featureId).shellAlive;
    report.screenshots.push(await capture('11-remote-session-renamed.png'));
    await f.command(c, 'vstab: Stop Persistent Terminal...');
    await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(renamed)})`);
    await c.clickText(renamed, '.quick-input-widget .label-name');
    await c.waitFor(`[...document.querySelectorAll('.monaco-dialog-box .monaco-button')].some(e=>e.textContent.trim()==='Stop Session')`);
    await c.clickText('Stop Session', '.monaco-dialog-box .monaco-button');
    await until(() => !inspectRemote(featureId).shellAlive, 10000);
    report.checks.explicitStopEndedOwnShell = true;
    assert.ok(Object.values(report.checks).every(Boolean));
  } catch (error) {
    failure = error; report.error = clean(error.message);
    try { report.screenshots.push(await capture('remote-session-failure.png')); } catch {}
  } finally {
    c?.close(); report.result = failure ? 'failed' : 'passed';
    fs.writeFileSync(path.join(OUTPUT, process.env.VSTAB_REMOTE_PROOF_REPORT || 'persistent-terminals-ssh-android.json'), JSON.stringify(report, null, 2) + '\n');
    console.log('Android SSH session reconnect proof: ' + report.result + ' — artifacts/features/persistent-terminals-ssh-android.json');
  }
  if (failure) throw new Error(clean(failure.message));
}
main().catch(error => { console.error(clean(error.message)); process.exitCode = 1; });
