'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const plain = value => JSON.parse(JSON.stringify(value));
const tick = () => new Promise(resolve => setImmediate(resolve));

function harness(initial = {}, {folder = '/tablet/project', beforeNavigate = async () => true,
    scheme = 'vscode-remote', authority = 'remote', hasWorkspace = true} = {}) {
  let state = {local: true, folderPath: folder, name: 'Project', message: 'Synchronized', conflicts: [], ...initial};
  const commands = new Map(), requests = [], executed = [], shown = [], warnings = [], information = [], errors = [];
  let pickResult, commandFailure, quickPick, decision, poll, cancelledTimer = false;
  const status = {visible: false, show() { this.visible = true; }, hide() { this.visible = false; }, dispose() {}};
  const context = {subscriptions: []};
  const native = {async request(method, params = {}) {
    requests.push({method, params: plain(params)});
    if (commandFailure && method !== 'documents.getState') throw commandFailure;
    if (method === 'documents.getState') return state;
    if (method === 'documents.pickFile' || method === 'documents.pickFolder') return pickResult || state;
    return {accepted: true};
  }};
  const vscode = {
    Uri: {from(value) { return {...value}; }}, StatusBarAlignment: {Left: 1},
    workspace: {workspaceFolders: hasWorkspace ? [{uri: {scheme, authority, path: folder}}] : undefined},
    commands: {
      registerCommand(id, callback) { commands.set(id, callback); return {dispose() { commands.delete(id); }}; },
      async executeCommand(id, ...args) { executed.push({id, args: plain(args)}); }
    },
    window: {
      createStatusBarItem() { return status; },
      async showTextDocument(uri) { shown.push(plain(uri)); },
      async showWarningMessage(message, ...actions) { warnings.push({message, actions}); return decision; },
      async showInformationMessage(message) { information.push(message); },
      async showErrorMessage(message) { errors.push(message); },
      async showQuickPick(choices) { return quickPick === undefined ? choices[0] : quickPick; }
    }
  };
  const module = {exports: {}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'extension/documents-ui.js'), 'utf8'), {
    module, exports: module.exports,
    setInterval(callback) { poll = callback; return 1; },
    clearInterval() { cancelledTimer = true; }
  }, {filename: 'documents-ui.js'});
  module.exports.registerDocumentCommands(vscode, context, native, beforeNavigate);
  return {
    commands, requests, executed, shown, warnings, information, errors, status, vscode,
    setState(next) { state = {...state, ...next}; },
    setPick(next) { pickResult = next; },
    setFailure(next) { commandFailure = next; },
    setDecision(next) { decision = next; },
    setQuickPick(next) { quickPick = next; },
    async poll() { await poll(); await tick(); },
    dispose() { for (const item of context.subscriptions) item.dispose(); return cancelledTimer; }
  };
}

test('registers commands and shows only a matching local shared project, not another project or SSH workspace', async t => {
  const h = harness(); t.after(() => h.dispose()); await tick();
  assert.ok(h.commands.has('vstab.documents.openFolder'));
  assert.ok(h.commands.has('vstab.documents.openFile'));
  assert.ok(h.commands.has('vstab.window.new'));
  assert.equal(h.status.visible, true);
  assert.match(h.status.text, /Shared: Project/);
  h.setState({folderPath: '/tablet/another'}); await h.poll();
  assert.equal(h.status.visible, false);
  h.setState({folderPath: '/tablet/project', local: false, openedFile: '/tablet/project/code.py'}); await h.poll();
  assert.equal(h.status.visible, false);
  assert.equal(h.shown.length, 0);
});

test('opens imported files using normal editor URIs once, without repeatedly stealing editor focus', async t => {
  const h = harness({filePath: '/tablet/project/日本語.py'}); t.after(() => h.dispose()); await tick();
  assert.deepEqual(h.shown, [{scheme: 'vscode-remote', authority: 'remote', path: '/tablet/project/日本語.py'}]);
  await h.poll(); await h.poll();
  assert.equal(h.shown.length, 1);
  h.setPick({local: true, folderPath: '/tablet/project', filePath: '/tablet/project/another.py'});
  await h.commands.get('vstab.documents.openFile')();
  assert.equal(h.shown.at(-1).path, '/tablet/project/another.py');
});

test('Open with and file-picker editor URIs match the resolved workspace file-watcher authority', async t => {
  const file = '/tablet/project/app.js';
  const h = harness({filePath: file}, {authority: 'localhost:35721'});
  t.after(() => h.dispose()); await tick();
  // Actual Android regression: vscode-remote://remote/... read and saved the
  // same bytes, but events for vscode-remote://localhost:35721/... missed it.
  const watcherUri = {scheme: 'vscode-remote', authority: 'localhost:35721', path: file};
  assert.deepEqual(h.shown[0], watcherUri);
  h.setPick({local: true, folderPath: '/tablet/project', filePath: '/tablet/project/other.js'});
  await h.commands.get('vstab.documents.openFile')();
  assert.deepEqual(h.shown.at(-1), {...watcherUri, path: '/tablet/project/other.js'});
});

test('shared-folder navigation and both conflict versions retain the active filesystem authority', async t => {
  const h = harness({conflicts: [{path: 'app.js', recoveryPath: 'app.js.device-conflict-a1b2'}]}, {authority: '127.0.0.2:35721'});
  t.after(() => h.dispose()); await tick();
  h.setPick({local: true, folderPath: '/tablet/shared/工程 with spaces'});
  await h.commands.get('vstab.documents.openFolder')();
  assert.deepEqual(h.executed.at(-1), {id: 'vscode.openFolder', args: [
    {scheme: 'vscode-remote', authority: '127.0.0.2:35721', path: '/tablet/shared/工程 with spaces'}, {forceNewWindow: false}
  ]});
  await h.commands.get('vstab.documents.review')();
  const diff = h.executed.find(call => call.id === 'vscode.diff');
  assert.deepEqual(diff.args.slice(0, 2), [
    {scheme: 'vscode-remote', authority: '127.0.0.2:35721', path: '/tablet/project/app.js'},
    {scheme: 'vscode-remote', authority: '127.0.0.2:35721', path: '/tablet/project/app.js.device-conflict-a1b2'}
  ]);
});

test('an ordinary file workspace keeps its file provider when opening approved documents', async t => {
  const h = harness({filePath: '/tablet/project/plain file.py'}, {scheme: 'file', authority: ''});
  t.after(() => h.dispose()); await tick();
  assert.deepEqual(h.shown, [{scheme: 'file', authority: '', path: '/tablet/project/plain file.py'}]);
  h.setPick({local: true, folderPath: '/tablet/shared project'});
  await h.commands.get('vstab.documents.openFolder')();
  assert.deepEqual(h.executed.at(-1).args[0], {scheme: 'file', authority: '', path: '/tablet/shared project'});
});

test('no-folder and virtual-root navigation use only the configured bootstrap backend fallback', async t => {
  for (const options of [{hasWorkspace: false}, {scheme: 'memfs', authority: 'unrelated-provider'}]) {
    const h = harness({}, options); t.after(() => h.dispose()); await tick();
    h.setPick({local: true, folderPath: '/tablet/approved project'});
    await h.commands.get('vstab.documents.openFolder')();
    assert.deepEqual(h.executed.at(-1).args[0], {scheme: 'vscode-remote', authority: 'remote', path: '/tablet/approved project'});
    assert.equal(h.requests.filter(call => call.method === 'documents.pickFolder').length, 1);
    assert.ok(!h.requests.some(call => call.method === 'documents.openApproved'));
  }
});

test('local folder picker opens the approved project via Code OSS without asking native code to execute a path', async t => {
  const h = harness(); t.after(() => h.dispose()); await tick();
  h.setPick({local: true, folderPath: '/tablet/shared/工程 with spaces'});
  await h.commands.get('vstab.documents.openFolder')();
  assert.deepEqual(h.executed.at(-1), {id: 'vscode.openFolder', args: [
    {scheme: 'vscode-remote', authority: 'remote', path: '/tablet/shared/工程 with spaces'}, {forceNewWindow: false}
  ]});
  assert.ok(h.requests.some(call => call.method === 'documents.pickFolder'));
  assert.ok(!h.requests.some(call => call.method === 'documents.openApproved'));
});

test('remote picker switches through the approved native local workspace and never sends tablet paths to SSH', async t => {
  let guarded = 0;
  const h = harness({local: false}, {beforeNavigate: async () => { guarded++; return true; }});
  t.after(() => h.dispose()); await tick();
  h.setPick({local: false, folderPath: '/data/user/0/app.vstab/files/shared/project', filePath: '/data/user/0/app.vstab/files/shared/project/code.py'});
  await h.commands.get('vstab.documents.openFile')();
  assert.equal(guarded, 1);
  assert.ok(h.requests.some(call => call.method === 'documents.openApproved'));
  assert.deepEqual(h.executed, []);
  assert.deepEqual(h.shown, []);
});

test('cancelled unsaved-work guard retains the SSH workspace after Android picker approval', async t => {
  const h = harness({local: false}, {beforeNavigate: async () => false});
  t.after(() => h.dispose()); await tick();
  h.setPick({local: false, folderPath: '/tablet/imported'});
  await h.commands.get('vstab.documents.openFolder')();
  assert.ok(!h.requests.some(call => call.method === 'documents.openApproved'));
  assert.deepEqual(h.executed, []);
});

test('picker cancellation is quiet but real provider errors remain visible', async t => {
  const h = harness(); t.after(() => h.dispose()); await tick();
  h.setFailure(Object.assign(new Error('Picker cancelled'), {code: 'Cancelled'}));
  await h.commands.get('vstab.documents.openFile')();
  assert.deepEqual(h.errors, []);
  h.setFailure(Object.assign(new Error('Folder permission revoked'), {code: 'NoPermissions'}));
  await h.commands.get('vstab.documents.openFolder')();
  assert.deepEqual(h.errors, ['Folder permission revoked']);
});

test('conflict review compares app/device versions and sends only an explicit decision to the native session', async t => {
  const h = harness({conflicts: [{path: 'src/code.py', recoveryPath: 'src/code.py.device-conflict-a1b2'}]});
  t.after(() => h.dispose()); await tick();
  h.setDecision('Keep device version');
  await h.commands.get('vstab.documents.review')();
  assert.deepEqual(h.executed.at(-1), {id: 'vscode.diff', args: [
    {scheme: 'vscode-remote', authority: 'remote', path: '/tablet/project/src/code.py'},
    {scheme: 'vscode-remote', authority: 'remote', path: '/tablet/project/src/code.py.device-conflict-a1b2'},
    'src/code.py — app / device'
  ]});
  assert.deepEqual(h.requests.at(-1), {method: 'documents.resolve', params: {path: 'src/code.py', keepLocal: false}});
  h.setDecision(undefined);
  const before = h.requests.filter(call => call.method === 'documents.resolve').length;
  await h.commands.get('vstab.documents.review')();
  assert.equal(h.requests.filter(call => call.method === 'documents.resolve').length, before);
});

test('delete conflicts remain resolvable without attempting to diff an invented null recovery path', async t => {
  const h = harness({conflicts: [{path: 'deleted.py', recoveryPath: null}]});
  t.after(() => h.dispose()); await tick();
  h.setDecision('Keep app version');
  await h.commands.get('vstab.documents.review')();
  assert.equal(h.executed.filter(call => call.id === 'vscode.diff').length, 0);
  assert.match(h.information.at(-1), /deleted/);
  assert.deepEqual(h.requests.at(-1), {method: 'documents.resolve', params: {path: 'deleted.py', keepLocal: true}});
});

test('provider warning is deduplicated during repeated polls and timer is disposed', async () => {
  const h = harness({error: 'The device folder could not be read.'}); await tick();
  await h.poll(); await h.poll();
  assert.equal(h.warnings.length, 1);
  assert.match(h.status.text, /Shared save pending/);
  assert.equal(h.dispose(), true);
});

test('shared conflict review cannot diff tablet paths against a remote SSH backend', async t => {
  const h = harness({local: false, conflicts: [{path: 'code.py', recoveryPath: 'code.py.device-conflict-test'}]});
  t.after(() => h.dispose()); await tick();
  await h.commands.get('vstab.documents.review')();
  assert.equal(h.executed.filter(call => call.id === 'vscode.diff').length, 0,
    'A remote workspace must switch to the approved Android folder or explain the restriction before comparing tablet paths.');
});
