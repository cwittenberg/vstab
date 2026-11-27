#!/usr/bin/env node
'use strict';
/** Real Android named-terminal reload proof; run only after emulator handoff. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const f = require('./feature-proof.cjs');
const ROOT = path.resolve(__dirname, '..');
const OUTPUT = path.join(ROOT, 'artifacts/features');
const runId = Date.now().toString();
const name = 'Final APK local proof ' + runId;
async function until(fn, timeout = 30000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { const result = await fn(); if (result) return result; await f.delay(150); }
  throw new Error('Local persistent terminal proof timed out.');
}
function read(filename) {
  try { return f.adb('exec-out', 'run-as', 'app.vstab', 'cat', filename).toString().trim(); }
  catch { return ''; }
}
async function selectNamed(c, sessionName) {
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
async function trustControlled(c) {
  const dialogButton = await c.evaluate(`(() => {
    const box = document.querySelector('.monaco-dialog-box');
    return box?.offsetHeight ? [...box.querySelectorAll('.monaco-button')].map(e=>e.textContent.trim()).find(t=>/Yes, I trust/.test(t)) : null;
  })()`);
  if (dialogButton) await c.clickText(dialogButton, '.monaco-dialog-box .monaco-button');
  const restricted = '[aria-label="Restricted Mode: Some features are disabled because this folder is not trusted."]';
  if (await c.evaluate(`!!document.querySelector(${JSON.stringify(restricted)})`)) {
    await c.click(restricted);
    await c.waitFor('[...document.querySelectorAll("a.monaco-button")].some(e=>e.textContent.trim()==="Trust")');
    await c.clickText('Trust'); await c.key('Escape', 'Escape', 27);
  }
}
function processRow(pid) {
  const rows = f.adb('shell', 'ps', '-A', '-o', 'PID,PPID,NAME').toString().split('\n').map(l=>l.trim().split(/\s+/));
  const row = rows.find(r=>r[0]===String(pid)); return row ? {pid:row[0], parentPid:row[1], name:row.slice(2).join(' ')} : null;
}
async function screenshot(name) {
  const ime = f.adb('shell', 'dumpsys', 'input_method').toString();
  if (/\bmInputShown=true\b/.test(ime)) f.adb('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await f.delay(700); return path.relative(ROOT, f.screenshot(name));
}
(async () => {
  if (!process.argv.includes('--run')) { console.log('Prepared local Android session proof; use --run after emulator handoff.'); return; }
  fs.mkdirSync(OUTPUT, {recursive: true});
  const apkPath = path.resolve(process.env.VSTAB_CANDIDATE_APK || path.join(ROOT, 'android/app/build/outputs/apk/debug/app-debug.apk'));
  const apkSha256 = crypto.createHash('sha256').update(fs.readFileSync(apkPath)).digest('hex');
  const report = {schema: 1, capturedAt: new Date().toISOString(), environment: 'Actual Android API36 x86_64 tablet emulator app.vstab',
    apkSha256, sessionName: name, checks: {}, screenshots: [], limits: ['Device reboot and Android force-stop can end on-device shells.']};
  let c, failure;
  try {
    const installedPath = f.adb('shell', 'pm', 'path', 'app.vstab').toString().trim().split('\n')[0].replace(/^package:/, '');
    const installedSha = f.adb('exec-out', 'sha256sum', installedPath).toString().match(/^[a-f0-9]{64}/)?.[0];
    report.installedApkSha256 = installedSha;
    assert.equal(installedSha, apkSha256, 'The installed APK must match the candidate APK being reported.');
    c = await f.CDP.connect();
    assert.equal(await c.evaluate('location.hostname'), 'localhost', 'Select a controlled local workspace first.');
    const workspace = await c.evaluate('new URL(location.href).searchParams.get("folder")');
    assert.ok(workspace?.startsWith('/data/') && !workspace.includes('\0'), 'The UI URL must identify an app-private local workspace.');
    report.workspace = workspace;
    await trustControlled(c);
    const beforePath = workspace + '/.vstab-local-before-' + runId + '.pid';
    const afterPath = workspace + '/.vstab-local-after-' + runId + '.pid';
    const countPath = workspace + '/.vstab-local-once-' + runId + '.txt';
    await f.command(c, 'vstab: New Persistent Terminal...');
    await c.waitFor('document.querySelector(".quick-input-widget")?.offsetHeight > 0 && document.querySelector(".quick-input-widget input")?.value === "Project shell"');
    await f.delay(250); await c.click('.quick-input-widget input');
    await c.key('a', 'KeyA', 65, 2); await f.delay(100); await f.typeKeys(c, name);
    assert.equal(await c.evaluate('document.querySelector(".quick-input-widget input").value'), name);
    await c.key('Enter', 'Enter', 13);
    await c.waitFor('!document.querySelector(".quick-input-widget")?.offsetHeight');
    await c.waitFor(`document.body.innerText.includes(${JSON.stringify(name)})`);
    await c.waitFor('!!document.querySelector(".terminal-wrapper.active .xterm-screen")');
    await selectNamed(c, name);
    await f.terminal(c, `trap "" HUP TERM; echo $$ > .vstab-local-before-${runId}.pid; echo ONCE >> .vstab-local-once-${runId}.txt; (sleep 3; echo OUTPUT_DURING_RELOAD_${runId}) &`);
    const pid = await until(() => { const value = read(beforePath); return /^\d+$/.test(value) ? value : ''; });
    assert.match(pid, /^\d+$/); report.pidBefore = pid;
    console.log('Named local PTY created; independent PID ' + pid + '.');
    await c.send('Page.enable');
    let navigations = 0; c.on('Page.frameNavigated', event => { if (!event.frame.parentId) navigations++; });
    const beforeNavigation = navigations;
    await f.command(c, 'Developer: Reload Window');
    await until(() => navigations > beforeNavigation, 30000);
    await f.delay(700);
    console.log('Actual Reload Window navigation observed; waiting for named session restoration.');
    await c.waitFor(`document.body.innerText.includes(${JSON.stringify(name)})`, 90000);
    await selectNamed(c, name);
    await c.waitFor(`document.body.innerText.includes(${JSON.stringify('OUTPUT_DURING_RELOAD_' + runId)})`, 90000);
    await f.terminal(c, `echo $$ > .vstab-local-after-${runId}.pid; echo SAME_PTY_AFTER_RELOAD=$$`);
    console.log('Post-reload command entered into the explicitly selected named terminal.');
    const after = await until(() => { const value = read(afterPath); return /^\d+$/.test(value) ? value : ''; }); report.pidAfter = after;
    report.checks.reloadNavigationObserved = navigations > beforeNavigation;
    report.checks.samePtyPid = pid === after;
    report.checks.namedTabAutomaticallyRestored = await c.evaluate(`document.body.innerText.includes(${JSON.stringify(name)})`);
    report.checks.outputProducedDuringReloadRecovered = await c.evaluate(`document.body.innerText.includes(${JSON.stringify('OUTPUT_DURING_RELOAD_' + runId)})`);
    report.checks.commandNotReplayed = read(countPath) === 'ONCE';
    assert.ok(Object.values(report.checks).every(Boolean));
    report.visibleTerminalText = (await c.evaluate('document.body.innerText')).slice(-12000);
    report.screenshots.push(await screenshot('02-persistent-terminal-restored-final-apk.png'));
    report.shellBeforeStop = processRow(pid);
    assert.ok(report.shellBeforeStop, 'Independent Android ps must observe the actual live PTY shell.');
    const daemonCommand = f.adb('exec-out', 'run-as', 'app.vstab', 'cat', '/proc/' + report.shellBeforeStop.parentPid + '/cmdline').toString().replaceAll('\0',' ');
    report.daemonCommand = daemonCommand; report.checks.version2EngineObserved = daemonCommand.includes('vstab-pty-v2-');
    assert.ok(report.checks.version2EngineObserved, 'The actual PTY parent must run the new version-2 engine.');
    await f.command(c, 'vstab: Stop Persistent Terminal...');
    await c.waitFor(`[...document.querySelectorAll('.quick-input-widget .label-name')].some(e=>e.textContent.trim()===${JSON.stringify(name)})`);
    await c.clickText(name, '.quick-input-widget .label-name');
    await c.waitFor(`[...document.querySelectorAll('.monaco-dialog-box .monaco-button')].some(e=>e.textContent.trim()==='Stop Session')`);
    await c.clickText('Stop Session', '.monaco-dialog-box .monaco-button');
    await until(()=>!processRow(pid), 10000);
    report.checks.explicitStopConfirmedActualShellDeath = true;
    report.checks.ignoredHupTermFixture = true;
    report.screenshots.push(await screenshot('12-local-v2-session-stopped.png'));
    console.log('Local version-2 Stop confirmed actual shell death.');
  } catch (error) {
    failure = error; report.error = error.message;
    try { report.screenshots.push(await screenshot('local-session-final-apk-failure.png')); } catch {}
  } finally {
    c?.close(); report.result = failure ? 'failed' : 'passed';
    fs.writeFileSync(path.join(OUTPUT, process.env.VSTAB_LOCAL_PROOF_REPORT || 'persistent-terminals-android-final-apk.json'), JSON.stringify(report, null, 2) + '\n');
    console.log('Final APK local persistent terminal proof: ' + report.result);
  }
  if (failure) throw failure;
})().catch(error => { console.error(error.message); process.exitCode = 1; });
