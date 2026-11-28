#!/usr/bin/env node
'use strict';
// Actual Android Remote Explorer key import and ordinary Git Clone/Pull/Push.
// All network writes target the explicitly prepared, task-owned demo fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const f = require('./feature-proof.cjs');
const ROOT = path.resolve(__dirname, '..');
const output = path.join(ROOT, 'artifacts/git-ssh');
const fixturePath = path.resolve(process.env.VSTAB_GIT_FIXTURE || path.join(ROOT, '.local/git-proof/fixture.json'));
const fixture = fs.existsSync(fixturePath) ? JSON.parse(fs.readFileSync(fixturePath)) : null;
const keys = process.env.VSTAB_GIT_FIXTURE_KEYS || '/home/zs/vspad/.local/demo/sidebar-keys';
const afterPull = process.argv.includes('--resume-after-pull');
const afterClone = afterPull || process.argv.includes('--resume-after-clone');
const filename = 'vstab-git-fixture-encrypted.key';
const quote = value => "'" + String(value).replaceAll("'", "'\\''") + "'";
const android = (...args) => f.adb('shell', args.map(quote).join(' ')).toString().trim();
const read = relative => f.adb('exec-out', 'run-as', 'com.vstab', 'cat', relative).toString();
const remote = script => execFileSync('docker', ['exec', '--user', 'developer', fixture.container,
  'python3', '-c', script, JSON.stringify(fixture)], {encoding: 'utf8', timeout: 30000});
const wait = f.delay;
let c;
const report = {schema: 1, capturedAt: new Date().toISOString(),
  environment: 'Actual com.vstab Android WebView, APK-native Git/OpenSSH and controlled Linux SSH fixture',
  proofMethod: 'Remote Explorer Import SSH Private Key, Android document picker, Git Clone, Git Pull and Git Push through Code OSS UI',
  checks: {}, screenshots: [], credentialPrompts: 0};

async function until(fn, timeout = 45000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const value = await fn(); if (value) return value; await wait(200); }
  throw Error('Timed out waiting for the ordinary Android Git UI workflow.');
}
async function visible(c, selector) { return c.evaluate(`!!document.querySelector(${JSON.stringify(selector)})?.checkVisibility({checkVisibilityCSS:true})`); }
async function input(value) {
  await c.click('.quick-input-widget input');
  await c.key('a', 'KeyA', 65, 2);
  await c.text(value);
  assert.equal(await c.evaluate('document.querySelector(".quick-input-widget input").value'), value);
}
async function enter(value) { await input(value); await c.key('Enter', 'Enter', 13); }
async function capture(name) {
  fs.mkdirSync(output, {recursive: true});
  const destination = path.join(output, name);
  fs.writeFileSync(destination, f.adb('exec-out', 'screencap', '-p'));
  report.screenshots.push(path.relative(ROOT, destination));
}
async function view() {
  return c.evaluate(`({quick:document.querySelector('.quick-input-widget')?.offsetHeight?document.querySelector('.quick-input-widget').innerText:'',
    masked:!!document.querySelector('.quick-input-widget input[type=password]')?.checkVisibility({checkVisibilityCSS:true}),
    dialog:document.querySelector('.monaco-dialog-box')?.offsetHeight?document.querySelector('.monaco-dialog-box').innerText:'',
    notifications:[...document.querySelectorAll('.notification-list-item')].map(e=>e.innerText)})`);
}
async function authenticateUntil(done, label) {
  const deadline = Date.now() + 90000;
  let answered = false;
  while (Date.now() < deadline) {
    if (await done()) return;
    const state = await view();
    if (state.masked && /passphrase|SSH Key/i.test(state.quick)) {
      assert.equal(answered, false, 'A repeated passphrase prompt is a real authentication failure.');
      await capture(label + '-encrypted-key-prompt.png');
      await c.click('.quick-input-widget input[type=password]');
      assert.equal(await c.evaluate('document.activeElement?.type'), 'password');
      const passphrase = fs.readFileSync(path.join(keys, 'encrypted.passphrase'), 'utf8').trim();
      await f.typeKeys(c, passphrase); await c.key('Enter', 'Enter', 13);
      answered = true; report.credentialPrompts++;
    } else if (/Are you sure you want to continue connecting/.test(state.quick)) {
      const expected = execFileSync('ssh-keygen', ['-lf', '/home/zs/vspad/.local/demo/known_hosts'], {encoding: 'utf8'}).match(/SHA256:[A-Za-z0-9+/]+/)[0];
      assert.ok(state.quick.includes(expected), 'Git must show the controlled host fingerprint.');
      await c.clickText('yes', '.quick-input-widget .label-name');
      report.hostFingerprintCompared = true;
    }
    const errors = state.notifications.filter(text => /Permission denied|Could not read from remote repository|Authentication failed|unable to fork|cannot run ssh|Could not resolve hostname/.test(text));
    assert.deepEqual(errors, [], 'The actual Git UI reported an operation failure.');
    await wait(200);
  }
  throw Error('Git UI did not complete: ' + label);
}
function nativeNodes() {
  f.adb('shell', 'uiautomator', 'dump', '/sdcard/vstab-git-proof-window.xml');
  const xml = f.adb('exec-out', 'cat', '/sdcard/vstab-git-proof-window.xml').toString();
  return [...xml.matchAll(/<node\b[^>]*>/g)].map(match => Object.fromEntries(
    [...match[0].matchAll(/([\w-]+)="([^"]*)"/g)].map(item => [item[1], item[2]])));
}
function tapNative(node) {
  const bounds = node.bounds.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/).slice(1).map(Number);
  f.adb('shell', 'input', 'tap', String(Math.round((bounds[0] + bounds[2]) / 2)), String(Math.round((bounds[1] + bounds[3]) / 2)));
}
async function selectNativeKey() {
  await until(async () => nativeNodes().some(node => /documentsui/.test(node.package || '')));
  let nodes = nativeNodes();
  let chosen = nodes.find(node => node.text === filename);
  if (!chosen) {
    const drawer = nodes.find(node => node['content-desc'] === 'Show roots');
    if (drawer) { tapNative(drawer); await wait(500); nodes = nativeNodes(); }
    const downloads = nodes.find(node => node.text === 'Downloads');
    assert.ok(downloads, 'Android document picker must expose Downloads.');
    tapNative(downloads); await wait(500);
    chosen = await until(async () => nativeNodes().find(node => node.text === filename));
  }
  tapNative(chosen);
}
function native(...args) {
  const state = JSON.parse(fs.readFileSync(path.join(ROOT, '.local/git-proof/native-smoke-final.json')));
  assert.equal(state.installedApkSha256, report.apkSha256, 'The native CLI fixture must target this exact installed APK.');
  const environment = ['HOME=' + state.home, 'SHELL=' + state.usr + '/bin/bash',
    'PATH=' + state.native + ':' + state.usr + '/bin:/system/bin',
    'LD_LIBRARY_PATH=' + state.native + ':' + state.usr + '/lib',
    'GIT_EXEC_PATH=' + state.usr + '/lib/git-core', 'GIT_TEMPLATE_DIR=' + state.usr + '/share/git-core/templates'];
  return android('run-as', 'com.vstab', 'env', ...environment, '/system/bin/sh', '-c', 'exec "$@"', 'vstab-git-proof', ...args);
}
async function trustFixture() {
  const state = await view();
  if (/Do you trust|trust the authors/.test(state.dialog)) {
    const label = await c.evaluate(`[...document.querySelectorAll('.monaco-dialog-box .monaco-button')].map(e=>e.textContent.trim()).find(t=>/Yes, I trust/.test(t))`);
    assert.ok(label); await c.clickText(label, '.monaco-dialog-box .monaco-button');
  }
  const restricted = '[aria-label="Restricted Mode: Some features are disabled because this folder is not trusted."]';
  if (await visible(c, restricted)) {
    await c.click(restricted);
    await c.waitFor(`[...document.querySelectorAll('a.monaco-button')].some(e=>e.textContent.trim()==='Trust')`);
    await c.clickText('Trust'); await c.key('Escape', 'Escape', 27);
  }
}

(async () => {
  if (!process.argv.includes('--run')) { console.log('Prepared actual Android Git proof. Use --run after the emulator handoff.'); return; }
  assert.ok(process.env.VSTAB_ANDROID_SERIAL && process.env.VSTAB_CDP_PORT, 'An explicit owned emulator and CDP port are required.');
  assert.ok(process.env.VSTAB_CANDIDATE_SHA256, 'The frozen APK SHA-256 is required.');
  assert.ok(fixture?.container === 'vstab-demo-ssh' && /^\/home\/developer\/vstab-git-desktop-\d+\.git$/.test(fixture.repo), 'Only the prepared task-owned Git fixture is allowed.');
  fs.mkdirSync(output, {recursive: true});
  const installed = android('pm', 'path', 'com.vstab').replace(/^package:/, '');
  report.apkSha256 = android('sha256sum', installed).split(/\s+/)[0];
  assert.equal(report.apkSha256, process.env.VSTAB_CANDIDATE_SHA256);
  c = await f.CDP.connect();
  assert.equal(await c.evaluate('location.hostname'), 'localhost', 'Select the normal local Android workspace first.');
  await c.waitFor('!!document.querySelector(".monaco-workbench")');
  await c.key('Escape', 'Escape', 27);
  await trustFixture();
  report.startupProbe = JSON.parse(read('files/native-runtime/last-probe.json'));
  assert.equal(report.startupProbe.git, true); assert.equal(report.startupProbe.ssh, true);
  report.checks.nativeGitAndSshStarted = true;
  // Restage only this generated test key; a fresh test AVD need not retain Downloads.
  f.adb('push', path.join(keys, 'encrypted_ed25519'), '/sdcard/Download/' + filename);
  let configuration = read('files/home/.ssh/config');
  if (!configuration.includes('10.0.2.2')) {
    await f.command(c, 'Remote-SSH: Add New SSH Host...');
    await c.waitFor('document.querySelector(".quick-input-widget input")?.value !== ">"');
    await enter('ssh -p 22227 developer@10.0.2.2');
    await until(async () => read('files/home/.ssh/config').includes('10.0.2.2'));
  }
  if (!process.argv.includes('--resume-after-import') && !afterClone) {
  await f.command(c, 'Remote-SSH: Import SSH Private Key...');
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .monaco-list-row')].some(e=>e.innerText.includes('developer@10.0.2.2:22227'))`);
  const host = '.quick-input-widget .monaco-list-row';
  const point = await c.evaluate(`(()=>{const e=[...document.querySelectorAll(${JSON.stringify(host)})].find(e=>e.innerText.includes('developer@10.0.2.2:22227'));const r=e.getBoundingClientRect();return{x:r.x+r.width/2,y:r.y+r.height/2}})()`);
  await c.send('Input.dispatchMouseEvent', {type:'mousePressed',...point,button:'left',buttons:1,clickCount:1});
  await c.send('Input.dispatchMouseEvent', {type:'mouseReleased',...point,button:'left',buttons:0,clickCount:1});
  await selectNativeKey();
  configuration = await until(async () => { const value = read('files/home/.ssh/config'); return /IdentityFile/.test(value) ? value : null; });
  report.checks.importedKeyThroughRemoteExplorer = true;
  report.sharedSshConfiguration = configuration;
  await capture('01-imported-key-in-existing-ssh-ui.png');
  } else {
    const previous = JSON.parse(fs.readFileSync(path.join(output, 'proof.json')));
    assert.equal(previous.apkSha256, report.apkSha256);
    assert.equal(previous.checks.importedKeyThroughRemoteExplorer, true);
    fs.copyFileSync(path.join(output, 'proof.json'), path.join(output, 'proof-before-trust.json'));
    report.checks.importedKeyThroughRemoteExplorer = true;
    report.sharedSshConfiguration = configuration;
    report.screenshots.push(...previous.screenshots);
    report.driverContinuation = 'Enabled Git by trusting the controlled folder, waited for repository-source picker transitions, and accepted Android canonical folder paths.';
    if (afterClone) {
      assert.equal(previous.checks.cloneThroughGitUi, true);
      report.checks.cloneThroughGitUi = true;
      report.credentialPrompts = previous.credentialPrompts;
      if (afterPull) {
        assert.equal(previous.checks.pullThroughGitUi, true);
        report.checks.pullThroughGitUi = true;
      }
    }
  }

  const destination = '/data/user/0/com.vstab/files/workspaces/';
  const local = '/data/data/com.vstab/files/workspaces/' + path.basename(fixture.repo, '.git');
  const runtime = JSON.parse(fs.readFileSync(path.join(ROOT, '.local/git-proof/native-smoke-final.json')));
  const git = runtime.native + '/libgit.so';
  report.localRepository = local; report.fixture = fixture;
  if (!afterClone) {
  await f.command(c, 'Git: Clone');
  await c.waitFor('document.querySelector(".quick-input-widget input")?.getAttribute("aria-label") === "Provide repository URL or pick a repository source."');
  await input(fixture.url);
  await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .monaco-list-row')].some(e=>e.innerText.includes('Clone from URL')&&e.innerText.includes(${JSON.stringify(fixture.url)}))`);
  await c.clickText('Clone from URL', '.quick-input-widget .label-name');
  await c.waitFor(`document.querySelector('.quick-input-widget')?.innerText.includes('Select as Repository Destination')`);
  await input(destination); await wait(700);
  await c.clickText('Select as Repository Destination', '.quick-input-widget .monaco-button, .quick-input-widget button');
  await authenticateUntil(async () => { try { return read(local + '/README.md').trim() === 'VStab local Git desktop workflow proof'; } catch { return false; } }, '02-clone');
  report.checks.cloneThroughGitUi = true;
  assert.equal(native(git, '-C', local, 'rev-parse', 'HEAD'), fixture.initialCommit);
  await until(async () => (await view()).dialog.includes('Would you like to open'));
  await c.clickText('Open', '.monaco-dialog-box .monaco-button');
  }
  c.close();
  const selected = await until(async () => {
    const targets = await (await fetch('http://127.0.0.1:' + process.env.VSTAB_CDP_PORT + '/json/list')).json();
    return targets.find(target => { try {
      const descriptor = JSON.parse(target.description);
      return target.type === 'page' && descriptor.visible && descriptor.attached && new URL(target.url).searchParams.get('folder') === local;
    } catch { return false; } });
  }, 90000);
  c = await f.CDP.connect(selected.id);
  await c.waitFor(`new URL(location.href).searchParams.get('folder')===${JSON.stringify(local)}`, 90000);
  await c.waitFor('!!document.querySelector(".monaco-workbench")', 90000);
  await wait(1000);
  await trustFixture();
  await c.key('G', 'KeyG', 71, 10);
  await wait(500); await capture('03-cloned-repository-source-control.png');

  if (!afterPull) {
  remote(`import json,pathlib,subprocess,sys\nx=json.loads(sys.argv[1]);p=pathlib.Path(x['seed']);(p/'REMOTE_CHANGE.md').write_text('REMOTE_PULL_'+x['run']+'\\n')\ndef g(*a):return subprocess.check_output(['git','-C',str(p),*a],stderr=subprocess.STDOUT,text=True)\ng('add','.');g('commit','-m','Controlled remote change');g('push','origin','main');print(g('rev-parse','HEAD').strip())`);
  await f.command(c, 'Git: Pull');
  await authenticateUntil(async () => { try { return read(local + '/REMOTE_CHANGE.md').trim() === 'REMOTE_PULL_' + fixture.run; } catch { return false; } }, '04-pull');
  report.checks.pullThroughGitUi = true; await capture('05-pulled-remote-change.png');
  }
  // Save an actual editor change, then seed its local commit with APK-native
  // Git. Push itself is performed only by the visible Source Control command.
  const savedText = 'LOCAL_PUSH_' + fixture.run + ' saved in VStab';
  await f.openFile(c, 'README.md');
  await f.replaceFile(c, 'README.md', savedText);
  await until(async () => read(local + '/README.md') === savedText);
  report.checks.editorSavedCodeBeforePush = true;
  native(git, '-C', local, 'config', 'user.name', 'VStab Git proof');
  native(git, '-C', local, 'config', 'user.email', 'git-proof@vstab.invalid');
  native(git, '-C', local, 'add', 'README.md');
  native(git, '-C', local, 'commit', '-m', 'LOCAL_PUSH_' + fixture.run);
  const localHead = native(git, '-C', local, 'rev-parse', 'HEAD');
  await f.command(c, 'Git: Push');
  await authenticateUntil(async () => remote(`import json,subprocess,sys\nx=json.loads(sys.argv[1]);print(subprocess.check_output(['git','--git-dir='+x['repo'],'rev-parse','HEAD'],text=True).strip())`).trim() === localHead, '06-push');
  report.checks.pushThroughGitUi = true;
  const remoteText = remote(`import json,subprocess,sys\nx=json.loads(sys.argv[1]);sys.stdout.write(subprocess.check_output(['git','--git-dir='+x['repo'],'show','HEAD:README.md'],text=True))`);
  assert.equal(remoteText, savedText);
  report.checks.pushedEditorBytesVerifiedOnServer = true;
  report.checks.encryptedKeyPromptExercised = report.credentialPrompts > 0;
  const imported = [...configuration.matchAll(/IdentityFile\s+"([^"]+)"/g)].map(match => match[1]).find(value => value.startsWith('~/.ssh/keys/'));
  assert.ok(imported, 'The normal Remote Explorer importer must record its private key path.');
  const sshOptions = native(runtime.native + '/libssh.so', '-G', '10.0.2.2');
  assert.ok(sshOptions.includes('identityfile ' + imported));
  report.importedIdentityFile = imported;
  report.resolvedGitSshOptions = sshOptions.split('\n').filter(line => /^(user |port |identityfile |userknownhostsfile )/.test(line));
  report.checks.importedKeyReusedWithoutDuplication = true;
  report.finalRemoteCommit = localHead;
  assert.ok(Object.values(report.checks).every(Boolean));
  await capture('07-pushed-local-commit.png');
  report.status = 'passed';
})().catch(error => {
  report.status = 'failed'; report.error = error.message;
  console.error('Android Git proof failed: ' + error.message); process.exitCode = 1;
}).finally(() => {
  c?.close(); report.completedAt = new Date().toISOString();
  if (process.argv.includes('--run')) { fs.mkdirSync(output, {recursive:true}); fs.writeFileSync(path.join(output, 'proof.json'), JSON.stringify(report,null,2)+'\n'); }
});
