'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const {ActivationLogParser, BoundedLogReader, classify, redact, fingerprint, MAX_RECORD} = require('../extension/diagnostics');
const {createDiagnostics} = require('../extension/extension');

const stamp = (level, text) => `2026-11-27 21:05:03.123 [${level}] ${text}\n`;
const failure = (id, details) => stamp('error', `Activating extension ${id} failed due to an error:`) +
  stamp('error', details) + '    at activate (/home/test/extensions/' + id + '/extension.js:10:3)\n';

test('reads actual Code OSS multiline failures without classifying inactive or unrelated errors', () => {
  const parser = new ActivationLogParser();
  const records = parser.push(stamp('info', 'ExtensionService#_doActivateExtension publisher.id') +
    stamp('error', 'Cannot find module something — no extension failure marker') +
    failure('Native.Helper', 'Error: Unsupported platform: android') +
    stamp('info', 'Eager extensions activated'));
  assert.equal(records.length, 1);
  assert.equal(records[0].extensionId, 'native.helper');
  assert.match(records[0].details, /Unsupported platform: android/);
  assert.match(records[0].details, /at activate/);
  assert.deepEqual(parser.flush(), []);
});

test('preserves a failure header split across reads and waits for the multiline detail', () => {
  const parser = new ActivationLogParser();
  const text = failure('p.failed', 'Error: dlopen failed: cannot locate symbol "test"');
  assert.deepEqual(parser.push(text.slice(0, 40)), []);
  assert.deepEqual(parser.flush(), []);
  assert.deepEqual(parser.push(text.slice(40, 98)), []);
  assert.deepEqual(parser.push(text.slice(98)), []);
  const records = parser.flush();
  assert.equal(records.length, 1);
  assert.equal(classify(records[0].details, true).category, 'native-platform');
});

test('quoted single-line failures and multiple sequential extension failures remain separate', () => {
  const parser = new ActivationLogParser();
  const records = parser.push(stamp('error', "Activating extension 'p.one' failed: Cannot find module test.") +
    failure('p.two', 'Error: spawn EACCES'));
  records.push(...parser.flush());
  assert.deepEqual(records.map(item => item.extensionId), ['p.one', 'p.two']);
  assert.equal(classify(records[0].details).category, 'missing-dependency');
  assert.equal(classify(records[1].details, true).category, 'permission');
  assert.doesNotMatch(records[0].details, /EACCES/);
});

test('malformed or oversized lines do not manufacture a failure or grow parser state unboundedly', () => {
  const parser = new ActivationLogParser();
  assert.deepEqual(parser.push('x'.repeat(40 * 1024)), []);
  assert.equal(parser.partial, '');
  const records = parser.push('Activating extension p.fake failed: bogus\n' +
    failure('p.real', 'Error: ' + 'x'.repeat(100 * 1024)) + stamp('info', 'done'));
  assert.equal(records.length, 1);
  assert.equal(records[0].extensionId, 'p.real');
  assert.ok(records[0].details.length <= MAX_RECORD);
  parser.reset();
  assert.deepEqual(parser.flush(), []);
});

test('classifies native ABI, Android execution restrictions, API, dependency and network errors distinctly', () => {
  for (const message of [
    'Unsupported platform: android', 'libstdc++.so.6: cannot open shared object file',
    'Module was compiled against a different Node.js version using NODE_MODULE_VERSION 115',
    'dlopen failed: cannot locate symbol epoll_pwait2', 'Exec format error'
  ]) {
    const reason = classify(message, true);
    assert.equal(reason.category, 'native-platform', message);
    assert.equal(reason.suggestRemote, true);
  }
  assert.equal(classify('spawn helper EACCES', true).suggestRemote, true);
  assert.equal(classify('spawn helper EACCES', false).suggestRemote, false);
  assert.equal(classify("Cannot find module 'dependency'", true).suggestRemote, false);
  assert.equal(classify('This extension requires API proposal chatSessionsProvider').category, 'editor-api');
  assert.equal(classify('Error: fetch failed ECONNREFUSED').category, 'network');
  assert.equal(classify('ordinary activation exception').category, 'activation');
});

test('redacts URLs, auth headers, environment/JSON tokens, API keys, JWTs and PEM private keys', () => {
  const text = [
    'Error at https://alice:secret@example.test/callback?access_token=urlsecret#fragmentsecret',
    'Authorization: Bearer headersecret', 'OPENAI_API_KEY=envsecret PASSWORD="quoted secret"',
    '{"access_token":"jsonsecret", "client_secret": "clientsecret"}',
    'sk-proj-1234567890123456 ghp_1234567890123456',
    'eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjEwMH0.signature',
    '-----BEGIN OPENSSH PRIVATE KEY-----\nprivatecontents\n-----END OPENSSH PRIVATE KEY-----',
    '/home/test/extensions/p.x/extension.js /home/test/project'
  ].join('\n');
  const safe = redact(text, {home: '/home/test', extensionPath: '/home/test/extensions/p.x'});
  for (const secret of ['alice', 'secret@', 'urlsecret', 'fragmentsecret', 'headersecret', 'envsecret',
    'quoted secret', 'jsonsecret', 'clientsecret', '1234567890123456', 'eyJhbGci', 'privatecontents', '/home/test']) {
    assert.ok(!safe.includes(secret), secret + ' must not be in report');
  }
  assert.match(safe, /example.test\/callback/);
  assert.match(safe, /\{extension\}\/extension.js/);
});

test('file reader preserves split UTF-8, bounds initial and follow-up reads, and skips a partial first line', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vstab-log-reader-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const filename = path.join(root, 'exthost.log');
  const text = failure('p.unicode', 'Error: native helper 🌱 cannot start');
  await fs.writeFile(filename, text);
  const reader = new BoundedLogReader(filename, {maxReadBytes: 3});
  let combined = '';
  let batch;
  do { batch = await reader.read(); combined += batch.text; assert.ok(batch.bytesRead <= 3); } while (!batch.caughtUp);
  assert.equal(combined, text);
  assert.equal((await reader.read()).bytesRead, 0);
  await fs.writeFile(filename, 'x'.repeat(300) + '\n' + text);
  const tailReader = new BoundedLogReader(filename, {maxInitialBytes: Buffer.byteLength(text) + 12, maxReadBytes: 13});
  combined = '';
  do { batch = await tailReader.read(); combined += batch.text; } while (!batch.caughtUp);
  assert.equal(combined, text);
});

test('file reader detects replacement and truncation, including initially absent logs', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vstab-log-rotation-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const filename = path.join(root, 'exthost.log');
  const reader = new BoundedLogReader(filename);
  assert.equal((await reader.read()).missing, true);
  await fs.writeFile(filename, 'old long content\n');
  assert.equal((await reader.read()).rotated, false);
  await fs.writeFile(filename, 'new\n');
  assert.equal((await reader.read()).rotated, true);
  await fs.rename(filename, filename + '.1');
  await fs.writeFile(filename, 'replacement\n');
  const replaced = await reader.read();
  assert.equal(replaced.rotated, true);
  assert.equal(replaced.text, 'replacement\n');
  await fs.writeFile(filename, 'same-inode truncate followed by a larger write\n');
  const regrown = await reader.read();
  assert.equal(regrown.rotated, true);
  assert.equal(regrown.text, 'same-inode truncate followed by a larger write\n');
});

function vscodeHarness(extensions) {
  const warnings = [], channels = [], executed = [], commands = new Map();
  const status = {visible: false, show() { this.visible = true; }, hide() { this.visible = false; }, dispose() {}};
  const vscode = {
    StatusBarAlignment: {Left: 1},
    extensions: {getExtension(id) { return extensions[id]; }},
    commands: {
      registerCommand(id, handler) { commands.set(id, handler); return {dispose() { commands.delete(id); }}; },
      async executeCommand(id) { executed.push(id); }
    },
    window: {
      createOutputChannel() {
        const channel = {lines: [], clear() { this.lines = []; }, appendLine(line) { this.lines.push(line); }, show() { this.shown = true; }, dispose() {}};
        channels.push(channel); return channel;
      },
      createStatusBarItem() { return status; },
      async showWarningMessage(message, ...actions) { warnings.push({message, actions}); return vscode.nextAction; }
    }
  };
  return {vscode, warnings, channels, executed, commands, status};
}

function contextHarness(root, global = new Map()) {
  return {subscriptions: [], extension: {id: 'vstab.extension-compatibility'}, logUri: {fsPath: path.join(root, 'vstab.extension-compatibility')},
    globalState: {get(key, fallback) { return global.get(key) || fallback; }, async update(key, value) { global.set(key, value); }}};
}

test('real filesystem tail emits named warning, routes SSH action, keeps safe report and deduplicates across reloads', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vstab-diagnostics-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const filename = path.join(root, 'remoteexthost.log');
  const installed = {'p.native': {packageJSON: {displayName: 'Native Test', version: '1.0.0'}, extensionPath: '/home/test/extensions/p.native'},
    'p.inactive': {packageJSON: {displayName: 'Inactive Test', version: '1.0.0'}, isActive: false}};
  const h = vscodeHarness(installed);
  const state = new Map();
  const monitor = createDiagnostics(h.vscode, contextHarness(root, state), {android: true, pollMs: 3600000});
  t.after(() => monitor.dispose());
  await monitor.ready;
  assert.equal(h.warnings.length, 0);
  await fs.writeFile(filename, failure('p.not-installed', 'Unsupported platform: android') +
    failure('p.native', 'Error: Unsupported platform: android token=secret-value'));
  h.vscode.nextAction = 'Connect to SSH Host…';
  await monitor.scan();
  await monitor.scan();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.warnings.length, 1);
  assert.match(h.warnings[0].message, /Native Test \(p.native\).*android device/i);
  assert.deepEqual(h.executed, ['vstab.ssh.connect']);
  assert.equal(h.status.visible, true);
  await h.commands.get('vstab.compatibility.showReport')();
  const output = h.channels[0].lines.join('\n');
  assert.match(output, /Native Test \(p.native\)/);
  assert.ok(!output.includes('secret-value'));
  assert.ok(!output.includes('p.inactive'));
  assert.ok(!output.includes('p.not-installed'));
  await fs.appendFile(filename, failure('p.native', 'Error: Unsupported platform: android token=secret-value'));
  await monitor.scan(); await monitor.scan();
  assert.equal(h.warnings.length, 1);
  monitor.dispose();
  const reloaded = createDiagnostics(h.vscode, contextHarness(root, state), {android: true, pollMs: 3600000});
  t.after(() => reloaded.dispose());
  await reloaded.ready; await reloaded.scan();
  assert.equal(h.warnings.length, 1);
  assert.equal(reloaded.failures.length, 1);
  delete installed['p.native'];
  await reloaded.scan();
  assert.equal(reloaded.failures.length, 0);
  assert.equal(h.status.visible, false);
});

test('rotation cannot attach an old error to a new extension or display a false failure', async t => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'vstab-diagnostics-rotation-'));
  t.after(() => fs.rm(root, {recursive: true, force: true}));
  const filename = path.join(root, 'remoteexthost.log');
  await fs.writeFile(filename, stamp('error', 'Activating extension p.first failed due to an error:'));
  const h = vscodeHarness({'p.first': {packageJSON: {version: '1'}}, 'p.second': {packageJSON: {version: '1'}}});
  const monitor = createDiagnostics(h.vscode, contextHarness(root), {android: true, pollMs: 3600000});
  t.after(() => monitor.dispose());
  await monitor.ready;
  await fs.rename(filename, filename + '.1');
  await fs.writeFile(filename, stamp('error', 'An unrelated error after rotation'));
  await monitor.scan(); await monitor.scan();
  assert.equal(h.warnings.length, 0);
  await fs.appendFile(filename, failure('p.second', 'Error: Cannot find module dep'));
  await monitor.scan(); await monitor.scan();
  assert.deepEqual(monitor.failures.map(record => record.extensionId), ['p.second']);
  assert.deepEqual(h.warnings[0].actions, ['Show details']);
});

test('fingerprints distinguish versions and failure categories without storing raw log text', () => {
  const base = {extensionId: 'p.test', version: '1', details: 'Error: EACCES', reason: classify('EACCES', true)};
  assert.equal(fingerprint(base).length, 64);
  assert.notEqual(fingerprint(base), fingerprint({...base, version: '2'}));
});
