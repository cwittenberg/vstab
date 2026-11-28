#!/usr/bin/env node
'use strict';

// Installs the public extension through the actual Android Extensions view.
// It never supplies provider credentials or substitutes an extension fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {execFileSync} = require('node:child_process');
const {CDP} = require('./cdp.cjs');
const {typeKeys, command} = require('./feature-proof.cjs');

const ROOT = path.resolve(__dirname, '..');
const SERIAL = process.env.VSTAB_ANDROID_SERIAL || 'emulator-5584';
const ADB = process.env.ANDROID_HOME ? path.join(process.env.ANDROID_HOME, 'platform-tools/adb') : 'adb';
const OUTPUT = path.resolve(process.env.VSTAB_CODEX_PROOF_DIR || path.join(ROOT, 'artifacts/git-ai/codex'));
const APK = path.resolve(process.env.VSTAB_CANDIDATE_APK || path.join(ROOT, 'android/app/build/outputs/apk/debug/app-debug.apk'));
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const quote = value => "'" + String(value).replace(/'/g, "'\\''") + "'";
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const adb = (...args) => execFileSync(ADB, ['-s', SERIAL, ...args], {maxBuffer: 16 * 1024 * 1024, timeout: 30000});
const appRead = filename => adb('exec-out', 'run-as', 'app.vstab', 'cat', filename);
const appShell = script => adb('shell', 'run-as app.vstab sh -c ' + quote(script)).toString();

function installedCodex() {
  try {
    return JSON.parse(appRead('files/home/.local/share/vstab/user-data/extensions/extensions.json').toString())
      .find(value => value.identifier?.id?.toLowerCase() === 'openai.chatgpt');
  } catch { return undefined; }
}

async function galleryInstall(client) {
  const existing = installedCodex();
  if (existing) return {previouslyInstalled: true, source: existing.metadata?.source || 'Existing installed extension'};
  await client.click('a[aria-label="Extensions (Ctrl+Shift+X)"]');
  await client.waitFor('!!document.querySelector(\'textarea[aria-label="Search Extensions in Marketplace"]\')');
  await client.click('textarea[aria-label="Search Extensions in Marketplace"]');
  await client.key('a', 'KeyA', 65, 2);
  await typeKeys(client, '@id:openai.chatgpt');
  const row = '[data-extension-id="openai.chatgpt"]';
  await client.waitFor(`!!document.querySelector(${JSON.stringify(row)})`, 90000);
  if (installedCodex()) return {previouslyInstalled: true};
  const selector = row + ' .action-label.install:not(.disabled)';
  await client.waitFor(`!!document.querySelector(${JSON.stringify(selector)})`);
  await client.click(selector);
  const deadline = Date.now() + 240000;
  while (Date.now() < deadline) {
    if (installedCodex()) return {previouslyInstalled: false, source: 'Public Open VSX gallery via the normal Install action'};
    const trust = await client.evaluate(`(() => {
      const dialog = document.querySelector('.monaco-dialog-box');
      if (!dialog || !/OpenAI|openai/i.test(dialog.innerText)) return null;
      return [...dialog.querySelectorAll('.monaco-button')].map(e=>e.innerText.trim())
        .find(t=>/^Trust Publisher.*Install$/.test(t)) || null;
    })()`);
    if (trust) await client.clickText(trust, '.monaco-dialog-box .monaco-button');
    await pause(750);
  }
  throw new Error('The public Codex extension did not finish installing.');
}

async function codexView(client) {
  const result = {present: false, signInVisible: false};
  const mainFrame = (await client.frameTree()).frame.id;
  for (const context of client.contexts()) {
    if (!context.auxData?.isDefault || context.auxData.frameId === mainFrame) continue;
    try {
      const found = await client.evaluateInContext(`(() => {
        const text = document.body?.innerText || '';
        return {present: /Codex|ChatGPT/.test(text), signInVisible: /Sign in|Continue with ChatGPT/.test(text) && /ChatGPT|API key/.test(text),
          loadingError: /Codex process errored|Failed to spawn codex|app-server is not available|Error loading webview/.test(text)};
      })()`, context.id);
      if (found.present) Object.assign(result, found);
    } catch { /* An iframe can be replaced while its extension view loads. */ }
  }
  return result;
}

async function main() {
  if (!process.argv.includes('--run')) {
    console.log('Use --run with the final APK installed and an Android local workspace open.');
    return;
  }
  fs.mkdirSync(OUTPUT, {recursive: true});
  if (fs.existsSync(path.join(OUTPUT, 'proof.json'))) {
    fs.copyFileSync(path.join(OUTPUT, 'proof.json'), path.join(OUTPUT, 'prior-proof-' + Date.now() + '.json'));
  }
  const report = {schema: 1, capturedAt: new Date().toISOString(), serial: SERIAL,
    proofMethod: 'Normal public Extensions search and Install, Codex sidebar command, independent installed-package and native-process readback.',
    checks: {}, limits: ['Provider-authenticated model turns require a person to sign in; this driver never supplies account credentials.']};
  let client, failure;
  try {
    report.apkSha256 = digest(fs.readFileSync(APK));
    const installedPath = adb('shell', 'pm', 'path', 'app.vstab').toString().trim().split('\n')
      .find(line => line.endsWith('/base.apk'))?.replace(/^package:/, '');
    assert.ok(installedPath);
    report.installedApkSha256 = adb('shell', 'sha256sum ' + quote(installedPath)).toString().trim().split(/\s+/)[0];
    assert.equal(report.installedApkSha256, report.apkSha256);
    report.checks.installedApkMatchesCandidate = true;
    client = await CDP.connect();
    await client.waitFor('!!document.querySelector(".monaco-workbench")', 90000);
    report.origin = await client.evaluate('location.origin');
    assert.equal(new URL(report.origin).hostname, 'localhost', 'Select a local Android workspace before running this proof.');
    report.install = await galleryInstall(client);
    const extension = installedCodex();
    assert.ok(extension);
    report.extension = {id: extension.identifier.id, version: extension.version, targetPlatform: extension.metadata?.targetPlatform};
    const directory = extension.location?.path;
    assert.ok(directory?.includes('/extensions/openai.chatgpt-'));
    const manifest = JSON.parse(appRead(directory + '/package.json').toString());
    assert.equal(manifest.publisher, 'openai');
    assert.equal(manifest.name, 'chatgpt');
    report.extension.manifestVersion = manifest.version;
    report.checks.publicPublisherExtensionInstalled = true;
    await command(client, 'Codex: Open Codex Sidebar');
    const deadline = Date.now() + 90000;
    let view;
    while (Date.now() < deadline) {
      view = await codexView(client);
      if (view.signInVisible || view.loadingError) break;
      await pause(500);
    }
    report.view = view;
    assert.ok(view?.present && view.signInVisible && !view.loadingError, 'The normal Codex view must finish startup and show its real sign-in screen.');
    report.checks.normalCodexSignInScreen = true;
    // Kernel comm, argv[0] and ps NAME may differ after a Rust process starts.
    // Verify the executable itself, returning only matching Codex paths.
    report.processes = [];
    const processDeadline = Date.now() + 30000;
    while (!report.processes.length && Date.now() < processDeadline) {
      const processes = appShell('for p in /proc/[0-9]*/exe; do executable=$(readlink "$p" 2>/dev/null) || continue; '
        + 'case "$executable" in */libcodex.so) printf "%s %s\\n" "${p#/proc/}" "$executable";; esac; done').trim().split('\n').filter(Boolean);
      for (const line of processes) {
        const [processPath, ...parts] = line.trim().split(/\s+/);
        const pid = processPath.split('/')[0];
        const executable = parts.join(' ');
        if (/\/app\.vstab-[^/]+\/lib\/(?:x86_64|arm64)\/libcodex\.so$/.test(executable)) {
          report.processes.push({pid: Number(pid), executable});
        }
      }
      if (!report.processes.length) await pause(500);
    }
    assert.ok(report.processes.length, 'The extension must run the real Bionic engine from the installed APK.');
    await pause(3000);
    for (const item of report.processes) {
      assert.equal(appShell('readlink /proc/' + item.pid + '/exe').trim(), item.executable,
        'The managed engine must remain alive after extension startup.');
    }
    const codexLogs = appShell("find files/home/.local/share/vstab/user-data/logs -maxdepth 5 -path '*/openai.chatgpt/Codex.log'")
      .trim().split('\n').filter(Boolean).sort();
    assert.ok(codexLogs.length, 'The actual installed extension must produce its startup log.');
    const logPath = codexLogs.at(-1);
    const logText = appRead(logPath).toString();
    const start = logText.lastIndexOf('[CodexMcpConnection] Spawning codex app-server');
    assert.ok(start >= 0, 'The extension log must record its actual app-server launch.');
    const fatalError = /Codex process fatal error|Fatal error message|app-server process exited unexpectedly|Failed to spawn/.test(logText.slice(start));
    report.startup = {logPath, appServerLaunchLogged: true, fatalError};
    assert.equal(fatalError, false, 'The actual extension startup must not contain an app-server fatal error.');
    report.checks.realExtensionAppServerStartup = true;
    report.checks.apkNativeCodexProcessRunning = true;
    fs.writeFileSync(path.join(OUTPUT, 'codex-public-extension-local.png'), adb('exec-out', 'screencap', '-p'));
  } catch (error) {
    failure = error;
    report.error = error.message;
    try { fs.writeFileSync(path.join(OUTPUT, 'failure.png'), adb('exec-out', 'screencap', '-p')); } catch {}
  } finally {
    client?.close();
    report.status = failure ? 'failed' : 'passed';
    report.completedAt = new Date().toISOString();
    fs.writeFileSync(path.join(OUTPUT, 'proof.json'), JSON.stringify(report, null, 2) + '\n');
    console.log('Codex local public-extension proof: ' + report.status + ' - ' + path.join(OUTPUT, 'proof.json'));
  }
  if (failure) throw failure;
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
