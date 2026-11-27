'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const tick = () => new Promise(resolve => setImmediate(resolve));
function harness(backend = new Map(), state = new Map()) {
  const commands = new Map(), terminals = [], closed = new EventEmitter();
  const calls = [], prompts = [];
  class VSCodeEvent {
    constructor() { this.emitter = new EventEmitter(); this.event = fn => { this.emitter.on('event', fn); return {dispose: () => this.emitter.off('event', fn)}; }; }
    fire(value) { this.emitter.emit('event', value); }
    dispose() { this.emitter.removeAllListeners(); }
  }
  class Client extends EventEmitter {
    constructor() { super(); this.socketPath = '/does/not/exist'; }
    async connect() {}
    async request(method, params = {}) {
      calls.push({method, params});
      switch (method) {
        case 'list': return [...backend.values()];
        case 'create': { const s = {...params, pid: 7421, cwd: '/project'}; backend.set(s.id, s); return s; }
        case 'rename': backend.get(params.sessionId).name = params.name; return backend.get(params.sessionId);
        case 'attach': return backend.get(params.sessionId);
        case 'input': return {delivered: true};
        case 'resize': case 'detach': return {};
        case 'stop': backend.delete(params.sessionId); this.emit('stopped', params); return {};
        default: throw new Error(method);
      }
    }
    close() {}
  }
  const vscode = {
    workspace: {workspaceFolders: [{uri: {fsPath: '/project'}}], isTrusted: true},
    env: {appRoot: '/editor'}, EventEmitter: VSCodeEvent,
    ThemeIcon: class {constructor(name) { this.name = name; }}, TreeItem: class {constructor(label) { this.label = label; }}, TreeItemCollapsibleState: {None: 0},
    TerminalExitReason: {Unknown: 0, Shutdown: 1, Process: 2, User: 3, Extension: 4},
    commands: {registerCommand(id, fn) { commands.set(id, fn); return {dispose() {}}; }},
    window: {
      createOutputChannel() { return {appendLine() {}, show() {}, dispose() {}}; },
      async showErrorMessage(message) { throw new Error(message); },
      async showInformationMessage() {}, async showWarningMessage() { return 'Stop Session'; },
      async showInputBox() { return prompts.shift() || 'Build 🌱'; },
      async showQuickPick(items) { return items[0]; },
      registerTreeDataProvider() { return {dispose() {}}; },
      onDidCloseTerminal(fn) { closed.on('close', fn); return {dispose() { closed.off('close', fn); }}; },
      createTerminal(options) {
        const terminal = {name: options.name, options, show() {},
          close(reason) { this.exitStatus = {reason}; options.pty.close(); closed.emit('close', this); },
          dispose() { this.close(4); }};
        options.pty.onDidChangeName?.(name => { terminal.name = name; });
        terminals.push(terminal); queueMicrotask(() => void options.pty.open({columns: 100, rows: 30})); return terminal;
      }
    }
  };
  const context = {subscriptions: [], workspaceState: {get: (key, fallback) => state.get(key) || fallback, async update(key, value) { state.set(key, JSON.parse(JSON.stringify(value))); }}};
  const exports = {};
  const source = fs.readFileSync(path.join(__dirname, 'extension/extension.cjs'), 'utf8');
  const fakeRequire = id => {
    if (id === 'vscode') return vscode;
    if (id === './client.cjs') return {SessionClient: Client};
    if (id === './session-core.cjs') return require('./extension/session-core.cjs');
    return require(id);
  };
  vm.runInNewContext(source, {require: fakeRequire, exports, __dirname: path.join(__dirname, 'extension'),
    console, setTimeout, clearTimeout, queueMicrotask, process});
  exports.activate(context);
  return {commands, terminals, state, backend, calls, prompts, exports, context};
}

test('window shutdown preserves restoration identity and next activation reattaches same shell', async () => {
  const first = harness();
  const id = await first.commands.get('vstab.sessions.create')(); await tick();
  assert.deepEqual(first.state.get('attachedSessionIds'), [id]);
  first.terminals[0].close(1); first.exports.deactivate();
  assert.deepEqual(first.state.get('attachedSessionIds'), [id]);
  const next = harness(first.backend, first.state); await tick(); await tick();
  assert.equal(next.terminals.length, 1); assert.equal(next.terminals[0].name, 'Build 🌱');
  assert.equal(next.calls.filter(c => c.method === 'create').length, 0);
  assert.equal(next.calls.find(c => c.method === 'attach').params.sessionId, id);
  next.exports.deactivate();
});

test('intentional close detaches without killing shell and does not restore that tab on reload', async () => {
  const first = harness();
  const id = await first.commands.get('vstab.sessions.create')(); await tick();
  first.terminals[0].close(3); await tick();
  assert.equal(first.backend.get(id).pid, 7421);
  assert.deepEqual(first.state.get('attachedSessionIds'), []);
  assert.equal(first.calls.some(c => c.method === 'stop'), false);
  first.exports.deactivate();
  const next = harness(first.backend, first.state); await tick();
  assert.equal(next.terminals.length, 0); next.exports.deactivate();
});

test('renaming retains its tab, shell and restoration record; Stop is explicit', async () => {
  const first = harness();
  const id = await first.commands.get('vstab.sessions.create')(); await tick();
  first.prompts.push('Tests'); await first.commands.get('vstab.sessions.rename')({id}); await tick();
  assert.equal(first.backend.get(id).pid, 7421);
  assert.equal(first.terminals.length, 1); assert.equal(first.terminals[0].name, 'Tests');
  assert.deepEqual(first.state.get('attachedSessionIds'), [id]);
  await first.commands.get('vstab.sessions.stop')({id}); await tick();
  assert.equal(first.backend.has(id), false); assert.deepEqual(first.state.get('attachedSessionIds'), []);
  first.exports.deactivate();
});


test('restored terminal forwards ordinary text, Unicode and Enter through the same input path', async () => {
  const first = harness();
  const id = await first.commands.get('vstab.sessions.create')(); await tick();
  first.terminals[0].close(1); first.exports.deactivate();
  const next = harness(first.backend, first.state); await tick(); await tick();
  const text = 'printf "RESTORED_INPUT_Ω🌱"';
  next.terminals[0].options.pty.handleInput(text);
  next.terminals[0].options.pty.handleInput('\r'); await tick();
  const input = next.calls.filter(c => c.method === 'input');
  assert.equal(input.length, 2); assert.equal(input[0].params.data, text);
  assert.equal(input[1].params.data, '\r');
  assert.ok(input.every(c => c.params.sessionId === id));
  assert.notEqual(input[0].params.inputId, input[1].params.inputId);
  next.exports.deactivate();
});

test('large pasted Unicode text is chunked without splitting a surrogate pair', async () => {
  const h = harness(); await h.commands.get('vstab.sessions.create')(); await tick();
  const text = 'x'.repeat(4095) + '🌱'.repeat(5000) + 'Ω';
  h.terminals[0].options.pty.handleInput(text); await tick();
  const chunks = h.calls.filter(c => c.method === 'input').map(c => c.params.data);
  assert.equal(chunks.join(''), text); assert.ok(chunks.length > 1);
  assert.ok(chunks.every(c => Buffer.byteLength(c) <= 16384));
  assert.ok(chunks.every(c => !/^[\uDC00-\uDFFF]/.test(c) && !/[\uD800-\uDBFF]$/.test(c)));
  h.exports.deactivate();
});
