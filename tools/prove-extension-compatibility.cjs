#!/usr/bin/env node
'use strict';

/**
 * Negative activation proof in the actual debug Android app.
 *
 * Seed a real, test-only extension and its install record, then use the normal
 * Code OSS Reload Window / details / report commands. No log or notification is
 * injected. The fixture really throws from activate() in Android's Node host.
 * Its registry entry and files are removed even when a check fails.
 *
 * Usage: source .local/env.sh
 *        node tools/prove-extension-compatibility.cjs --run
 * Select an Android workspace before running; this does not change projects.
 */

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {CDP} = require('./cdp.cjs');
const {typeKeys} = require('./feature-proof.cjs');
const {ActivationLogParser, redact} = require('../workbench/compatibility/extension/diagnostics');

const ROOT = path.resolve(__dirname, '..');
const APP = 'com.vstab';
const SERIAL = process.env.VSTAB_ANDROID_SERIAL || 'emulator-5580';
const ADB = process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, 'platform-tools/adb') : 'adb';
const OUTPUT = path.resolve(process.env.VSTAB_COMPATIBILITY_PROOF_DIR || path.join(ROOT, 'artifacts/features/extension-compatibility'));
const FIXTURE = path.join(ROOT, 'workbench/compatibility/test/fixtures/native-failure');
const CANDIDATE = path.resolve(process.env.VSTAB_CANDIDATE_APK || process.env.VSTAB_COMPATIBILITY_APK || path.join(ROOT, 'android/app/build/outputs/apk/debug/app-debug.apk'));
const EXPECTED_APK_SHA = process.env.VSTAB_EXPECTED_APK_SHA256;
const runId = Date.now().toString();
const fixtureId = 'vstab-proof.native-failure-' + runId;
const fixtureFolder = fixtureId + '-1.0.0';
const extensionsRoot = 'files/home/.local/share/vstab/user-data/extensions';
const registryPath = extensionsRoot + '/extensions.json';
const fixturePath = extensionsRoot + '/' + fixtureFolder;
const fixtureStage = 'files/compatibility-proof-stage-' + runId;
const quote = value => "'" + String(value).replace(/'/g, "'\\''") + "'";
const digest = buffer => crypto.createHash('sha256').update(buffer).digest('hex');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

function adb(args, options = {}) {
  return execFileSync(ADB, ['-s', SERIAL, ...args], {timeout: 30000, maxBuffer: 16 * 1024 * 1024, ...options});
}
function appShell(command) {
  try { return adb(['shell', 'run-as ' + APP + ' sh -c ' + quote(command)]); }
  catch (error) { throw Object.assign(new Error('Android fixture operation failed (exit ' + error.status + ').'), {status: error.status}); }
}
function appRead(filename) { return adb(['exec-out', 'run-as', APP, 'cat', filename]); }
function appWrite(filename, contents, expectedSha) {
  const temporary = filename + '.compatibility-proof-' + runId;
  const body = Buffer.isBuffer(contents) ? contents : Buffer.from(contents);
  let command = '';
  if (expectedSha) command += '[ "$(sha256sum ' + quote(filename) + " | cut -d ' ' -f 1)\" = " + quote(expectedSha) + ' ] || exit 71; ';
  command += 'printf %s ' + quote(body.toString('base64')) + ' | base64 -d > ' + quote(temporary) +
    ' && chmod 600 ' + quote(temporary) + ' && mv ' + quote(temporary) + ' ' + quote(filename);
  appShell(command);
}
function readRegistry() {
  const bytes = appRead(registryPath);
  const values = JSON.parse(bytes.toString());
  assert.ok(Array.isArray(values), 'The existing extension registry must be valid; no registry is replaced on a parse failure.');
  return {bytes, values};
}
function removeFixture() {
  appShell('rm -rf -- ' + quote(fixturePath));
  appShell('rm -rf -- ' + quote(fixtureStage));
  for (let attempt = 0; attempt < 4; attempt++) {
    const current = readRegistry();
    const remaining = current.values.filter(value => value.identifier?.id !== fixtureId);
    if (remaining.length === current.values.length) return;
    try { appWrite(registryPath, JSON.stringify(remaining), digest(current.bytes)); return; }
    catch (error) { if (error.status !== 71 || attempt === 3) throw error; }
  }
}
function hideKeyboard() {
  // mInputShown can remain true after the IME disappeared. A Back based on that
  // stale field can finish the editor. Require the actual IME Window surface.
  const state = adb(['shell', 'dumpsys', 'window', 'windows']).toString();
  const windows = state.split(/(?=^  Window #\d+ )/m);
  const visible = windows.some(block => /mIsImWindow=true\b/.test(block) &&
    /mViewVisibility=0x0\b/.test(block) && /mHasSurface=true\b/.test(block) &&
    /\bisOnScreen=true\b/.test(block) && /\bisVisible=true\b/.test(block));
  if (visible) adb(['shell', 'input', 'keyevent', 'KEYCODE_BACK']);
}
async function screenshot(name) {
  hideKeyboard();
  // Android's IME animation and WebView resize finish after the Back command.
  await pause(1000);
  hideKeyboard();
  await pause(400);
  const filename = path.join(OUTPUT, name);
  const bytes = adb(['exec-out', 'screencap', '-p']);
  fs.writeFileSync(filename, bytes);
  return {file: path.relative(ROOT, filename), bytes: bytes.length, sha256: digest(bytes)};
}

async function palette(client, label) {
  await client.waitFor('!!document.querySelector(".monaco-workbench")', 90000);
  await client.key('Escape', 'Escape', 27);
  await client.key('P', 'KeyP', 80, 10);
  await client.waitFor('document.querySelector(".quick-input-widget input")?.value === ">" && document.querySelector(".quick-input-widget").offsetHeight > 0');
  await pause(200);
  await client.click('.quick-input-widget input');
  await client.waitFor('document.activeElement === document.querySelector(".quick-input-widget input")');
  await typeKeys(client, label);
  await client.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e => e.textContent.trim() === ${JSON.stringify(label)})`);
  await client.clickText(label, '.quick-input-widget .label-name');
}

async function reloadWorkbench(client) {
  await client.send('Page.enable');
  let loaded = false;
  client.on('Page.loadEventFired', () => { loaded = true; });
  await palette(client, 'Developer: Reload Window');
  const deadline = Date.now() + 30000;
  while (!loaded && Date.now() < deadline) await pause(100);
  assert.ok(loaded, 'Reload Window must finish navigation before the next palette action.');
  await pause(1000);
  await client.waitFor('document.readyState === "complete" && !!document.querySelector(".monaco-workbench")');
}

function observerScript() {
  // Passive DOM observation only: it does not create, alter or trigger a toast.
  return `(() => {
    const seen = new WeakSet();
    const check = () => {
      for (const element of document.querySelectorAll('.notification-list-item')) {
        if (seen.has(element) || !element.textContent.includes(${JSON.stringify(fixtureId)}) || !element.textContent.includes('could not start')) continue;
        const actions = [...element.querySelectorAll('button,a.monaco-button,.monaco-button')].map(e => e.textContent.trim()).filter(Boolean);
        if (!actions.includes('Show details') || !actions.some(a => a.includes('Connect to SSH Host'))) continue;
        seen.add(element);
        globalThis.vstabCompatibilityProofNotice(JSON.stringify({text: element.innerText, actions}));
      }
    };
    new MutationObserver(check).observe(document, {childList: true, subtree: true, characterData: true});
    check();
  })();`;
}

async function currentReport(client) {
  await palette(client, 'vstab: Show Extension Compatibility Report');
  await client.waitFor('document.querySelector(".output-view .view-lines")?.textContent.includes("Unsupported")');
  if (await client.evaluate(`!!document.querySelector(${JSON.stringify('[aria-label="Maximize Panel"]')})`)) {
    await client.click('[aria-label="Maximize Panel"]');
  }
  hideKeyboard();
  await pause(1000);
  if (await client.evaluate(`!!document.querySelector(${JSON.stringify('[aria-label="Turn Auto Scrolling Off"]')})`)) {
    await client.click('[aria-label="Turn Auto Scrolling Off"]');
  }
  const point = await client.evaluate('(() => { const r = document.querySelector(".output-view").getBoundingClientRect(); return {x: r.x + r.width / 2, y: r.y + r.height / 2}; })()');
  await client.send('Input.dispatchMouseEvent', {type: 'mouseWheel', ...point, deltaX: 0, deltaY: -100000});
  await client.waitFor(`(() => { const text = document.body.innerText.replace(/\u00a0/g, ' '); return text.includes(${JSON.stringify(fixtureId)}) && text.includes('Details (credentials removed)'); })()`);
  return client.evaluate(`(() => {
    const text = [...document.querySelectorAll('.output .view-lines, .panel .output-view .view-lines, .output-view .view-lines')]
      .map(e => e.innerText.replace(/\u00a0/g, ' ')).join(String.fromCharCode(10));
    return text || document.body.innerText.replace(/\u00a0/g, ' ');
  })()`);
}

async function visibleFixtureNotifications(client) {
  return client.evaluate(`[...document.querySelectorAll('.notification-list-item')]
    .filter(e => e.textContent.includes(${JSON.stringify(fixtureId)}) && e.textContent.includes('could not start'))
    .map(e => ({text: e.innerText, actions: [...e.querySelectorAll('.monaco-button')].map(b => b.textContent.trim())}))`);
}

async function clickFixtureAction(client, label) {
  const point = await client.evaluate(`(() => {
    const notification = [...document.querySelectorAll('.notification-list-item')].find(e => e.textContent.includes(${JSON.stringify(fixtureId)}) && e.textContent.includes('could not start'));
    const button = [...(notification?.querySelectorAll('.monaco-button') || [])].find(e => e.textContent.trim() === ${JSON.stringify(label)});
    if (!button) throw new Error('The fixture notification action is not visible.');
    const r = button.getBoundingClientRect(); return {x: r.x + r.width / 2, y: r.y + r.height / 2};
  })()`);
  await client.send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
  await client.send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
}

async function main() {
  if (!process.argv.includes('--run')) {
    console.log('Prepared Android proof. Run with --run after the emulator is available.');
    return;
  }
  fs.mkdirSync(OUTPUT, {recursive: true});
  if (fs.existsSync(path.join(OUTPUT, 'proof.json'))) {
    const old = JSON.parse(fs.readFileSync(path.join(OUTPUT, 'proof.json'), 'utf8'));
    const prior = old.result === 'failed' ? path.join(ROOT, '.local/compatibility-failed-attempt-' + runId) : path.join(OUTPUT, 'prior-capture');
    fs.mkdirSync(prior, {recursive: true});
    for (const name of ['proof.json', '01-extension-failure-warning.png', '02-extension-compatibility-report.png', 'visible-warning.json', 'visible-report.txt']) {
      if (fs.existsSync(path.join(OUTPUT, name))) fs.copyFileSync(path.join(OUTPUT, name), path.join(prior, name));
    }
  }
  const report = {schema: 1, capturedAt: new Date().toISOString(), environment: 'Actual Android emulator WebView inside com.vstab',
    fixture: {id: fixtureId, source: path.relative(ROOT, FIXTURE),
      activation: 'The original onStartupFinished test extension deliberately throws Unsupported platform: android from activate(). No production extension is altered.'},
    proofMethod: 'Install the original controlled real extension; ordinary IDE Reload Window, warning details and report/check commands; passive UI observation; read actual extension-host logs.',
    screenshots: [], checks: {}, limits: ['Covers activation failures in the Node extension host.',
      'Does not establish detection of arbitrary helper errors hidden after successful activation or failures in the separate browser-worker host.']};
  let client, seeded = false, registered = false, observerId;
  const notices = [];
  let failure;
  try {
    report.apkPath = path.relative(ROOT, CANDIDATE);
    report.apkSha256 = digest(fs.readFileSync(CANDIDATE));
    if (EXPECTED_APK_SHA) assert.equal(report.apkSha256, EXPECTED_APK_SHA, 'The frozen candidate must match the expected APK hash.');
    const expectedSha = EXPECTED_APK_SHA || report.apkSha256;
    report.expectedApkSha256Source = EXPECTED_APK_SHA ? 'Explicit expected hash' : 'Independently hashed frozen candidate file';
    const installedPath = adb(['shell', 'pm', 'path', APP]).toString().trim().split('\n').find(line => line.endsWith('/base.apk'))?.replace(/^package:/, '');
    assert.ok(installedPath, 'The installed app must expose one base APK.');
    report.installedApkSha256 = adb(['shell', 'sha256sum ' + quote(installedPath)]).toString().trim().split(/\s+/)[0];
    assert.equal(report.installedApkSha256, expectedSha, 'The actual installed APK must be the frozen final candidate.');
    report.checks.installedApkMatchesFrozenCandidate = true;
    client = await CDP.connect();
    const origin = await client.evaluate('location.origin');
    assert.equal(new URL(origin).hostname, 'localhost', 'Select a local Android workspace before running this proof.');
    report.origin = origin;
    await client.send('Runtime.addBinding', {name: 'vstabCompatibilityProofNotice'});
    client.on('Runtime.bindingCalled', event => {
      if (event.name === 'vstabCompatibilityProofNotice') {
        try { notices.push({...JSON.parse(event.payload), observedAt: new Date().toISOString()}); } catch {}
      }
    });
    observerId = (await client.send('Page.addScriptToEvaluateOnNewDocument', {source: observerScript()})).identifier;
    // Extension profile changes can activate a package immediately, before any
    // reload. Observe both this document and subsequent reloads before install.
    await client.evaluate(observerScript());
    const appDirectory = appShell('pwd').toString().trim();
    const initialRegistry = readRegistry();
    assert.ok(!initialRegistry.values.some(value => value.identifier?.id === fixtureId));
    appShell('mkdir -p ' + quote(fixtureStage));
    seeded = true;
    const packageJSON = JSON.parse(fs.readFileSync(path.join(FIXTURE, 'package.json'), 'utf8'));
    packageJSON.name = 'native-failure-' + runId;
    appWrite(fixtureStage + '/package.json', JSON.stringify(packageJSON, null, 2));
    appWrite(fixtureStage + '/extension.js', fs.readFileSync(path.join(FIXTURE, 'extension.js')));
    appShell('mv ' + quote(fixtureStage) + ' ' + quote(fixturePath));
    const record = {identifier: {id: fixtureId}, version: packageJSON.version,
      location: {scheme: 'file', path: appDirectory + '/' + fixturePath}, relativeLocation: fixtureFolder,
      metadata: {installedTimestamp: Date.now(), targetPlatform: 'undefined'}};
    // The backend watches new extension directories and can add its own install
    // record before this write. Preserve that legitimate concurrent update.
    for (let attempt = 0; attempt < 4; attempt++) {
      const current = readRegistry();
      if (current.values.some(value => value.identifier?.id === fixtureId)) break;
      try { appWrite(registryPath, JSON.stringify([...current.values, record]), digest(current.bytes)); break; }
      catch (error) { if (error.status !== 71 || attempt === 3) throw error; }
    }
    assert.ok(readRegistry().values.some(value => value.identifier?.id === fixtureId));
    registered = true;
    report.checks.realExtensionInstalled = true;
    report.fixture.javascriptSha256 = digest(fs.readFileSync(path.join(FIXTURE, 'extension.js')));

    await pause(2000);
    if (!notices.length) await reloadWorkbench(client);
    const deadline = Date.now() + 90000;
    while (!notices.length && Date.now() < deadline) await pause(300);
    assert.ok(notices.length > 0, 'A visible failure notification must name the real failed extension.');
    report.warning = notices[0];
    report.checks.namedWarningVisible = notices[0].text.includes(fixtureId);
    report.checks.androidNativeReasonVisible = notices[0].text.includes('bundled native component cannot run on this Android device');
    report.checks.sshActionVisible = notices[0].actions.some(action => action.includes('Connect to SSH Host'));
    if (!(await visibleFixtureNotifications(client)).length) await palette(client, 'Notifications: Show Notifications');
    await client.waitFor(`document.querySelector('.notification-list-item') && [...document.querySelectorAll('.notification-list-item')].some(e => e.textContent.includes(${JSON.stringify(fixtureId)}))`);
    report.visibleWarnings = await visibleFixtureNotifications(client);
    assert.equal(report.visibleWarnings.length, 1, 'The notification center must hold one matching failure.');
    report.screenshots.push(await screenshot('01-extension-failure-warning.png'));

    await clickFixtureAction(client, 'Show details');
    const text = await currentReport(client);
    assert.ok(text.includes(fixtureId) && text.includes('Unsupported platform: android'), 'The actual report must contain the observed activation error.');
    report.reportText = redact(text).slice(0, 12000);
    report.checks.actualActivationErrorInReport = true;
    report.screenshots.push(await screenshot('02-extension-compatibility-report.png'));
    await palette(client, 'vstab: Check Extension Failures Now');
    await palette(client, 'vstab: Check Extension Failures Now');
    await pause(2000);
    assert.equal((await visibleFixtureNotifications(client)).length, 0, 'After Show details dismisses the warning, repeated checks must not create another.');
    report.checks.repeatedChecksDeduplicated = true;

    const logs = appShell('find files/home/.local/share/vstab/user-data/logs -name remoteexthost.log').toString().trim().split('\n').filter(Boolean);
    const errors = [];
    for (const log of logs) {
      const parser = new ActivationLogParser();
      const text = appShell('tail -c 1048576 ' + quote(log)).toString();
      for (const item of [...parser.push(text), ...parser.flush()]) if (item.extensionId === fixtureId) {
        errors.push({extensionId: item.extensionId, details: redact(item.details).slice(0, 1200)});
      }
    }
    assert.ok(errors.length > 0, 'The real Node extension host must independently log its activation failure.');
    report.actualExtensionHostErrors = errors;
    report.checks.realNodeActivationFailureLogged = true;

    // Re-reading a fresh host log after an ordinary reload must preserve warning
    // deduplication while the report continues to explain the failed extension.
    await reloadWorkbench(client);
    await pause(2500);
    const reloadedText = await currentReport(client);
    await pause(1500);
    assert.ok(reloadedText.includes(fixtureId));
    assert.equal((await visibleFixtureNotifications(client)).length, 0, 'The same failure must remain deduplicated across a workbench reload.');
    report.checks.reloadDeduplicated = true;
    report.screenshots.push(await screenshot('03-report-after-reload.png'));
  } catch (error) {
    failure = error;
    report.error = redact(error.message);
    try { report.screenshots.push(await screenshot('failure-state.png')); } catch {}
  } finally {
    if (seeded) {
      try {
        removeFixture();
        report.checks.fixtureRemoved = !readRegistry().values.some(value => value.identifier?.id === fixtureId);
      } catch (error) { report.cleanupError = redact(error.message); failure ||= error; }
    }
    if (client) {
      try { if (observerId) await client.send('Page.removeScriptToEvaluateOnNewDocument', {identifier: observerId}); } catch {}
      if (registered && report.checks.fixtureRemoved) {
        try { await reloadWorkbench(client); report.checks.cleanedWorkspaceReloaded = true; }
        catch (error) { report.cleanupReloadError = redact(error.message); failure ||= error; }
      }
      client.close();
    }
    report.result = failure ? 'failed' : 'passed';
    const destination = path.join(OUTPUT, 'proof.json');
    fs.writeFileSync(destination, JSON.stringify(report, null, 2) + '\n');
    console.log('Android extension compatibility proof: ' + report.result + ' — ' + path.relative(ROOT, destination));
  }
  if (failure) throw failure;
}

main().catch(error => { console.error(redact(error.message)); process.exitCode = 1; });
