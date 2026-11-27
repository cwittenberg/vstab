import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import { MessageChannel } from 'node:worker_threads';
import { createAndroidNativeTransport, createLocalWorkbenchIntegration, normalizeWorkspacePath } from './bridge.mjs';

class Emitter {
  listeners = new Set();
  event = listener => { this.listeners.add(listener); return { dispose: () => this.listeners.delete(listener) }; };
  fire(value) { for (const listener of this.listeners) listener(value); }
  dispose() { this.listeners.clear(); }
}

test('workspace paths cannot escape the app-private grant', () => {
  assert.equal(normalizeWorkspacePath('/workspace/project/main.py'), '/workspace/project/main.py');
  for (const path of ['/workspace-other/key', '/workspace/../key', '/workspace/a/./key', '/etc/passwd', '/workspace/a\\b', '/workspace/a\0b']) {
    assert.throws(() => normalizeWorkspacePath(path), error => error.code === 'NoPermissions');
  }
});

test('real provider crosses worker and native channels, preserving bytes and native errors', async () => {
  const listeners = new Set();
  const hostWindow = {
    location: { origin: 'https://appassets.androidplatform.net' },
    addEventListener(type, listener) { if (type === 'message') listeners.add(listener); },
    removeEventListener(type, listener) { if (type === 'message') listeners.delete(listener); }
  };
  const transport = createAndroidNativeTransport({ hostWindow });
  const integration = createLocalWorkbenchIntegration({
    extensionUri: 'https://appassets.androidplatform.net/local/extension/',
    requestNative: transport.request,
    channelFactory: () => new MessageChannel()
  });
  const workerPort = integration.options.messagePorts.get('vstab.local-workspace');
  const incoming = new Emitter();
  workerPort.on('message', message => incoming.fire(message));
  const commands = new Map();
  let provider;
  let fileSearch;
  let textSearch;
  const fsError = code => message => Object.assign(new Error(message), { code });
  const vscode = {
    EventEmitter: Emitter,
    Disposable: class { constructor(dispose) { this.dispose = dispose; } },
    FileChangeType: { Changed: 1, Created: 2, Deleted: 3 },
    FileSystemError: Object.fromEntries(['FileNotFound', 'FileExists', 'FileNotADirectory', 'FileIsADirectory', 'NoPermissions', 'Unavailable'].map(code => [code, fsError(code)])),
    Uri: { from: fields => ({ scheme: fields.scheme, path: fields.path, authority: '', query: '', fragment: '' }) },
    workspace: {
      registerFileSystemProvider(scheme, value) { assert.equal(scheme, 'vstab-local'); provider = value; return { dispose() {} }; },
      registerFileSearchProvider(scheme, value) { assert.equal(scheme, 'vstab-local'); fileSearch = value; return { dispose() {} }; },
      registerTextSearchProvider(scheme, value) { assert.equal(scheme, 'vstab-local'); textSearch = value; return { dispose() {} }; }
    },
    Range: class { constructor(startLine, startCharacter, endLine, endCharacter) { this.start = { line: startLine, character: startCharacter }; this.end = { line: endLine, character: endCharacter }; } },
    commands: { registerCommand(id, handler) { commands.set(id, handler); return { dispose() {} }; } },
    window: { showInformationMessage() {}, showWarningMessage() {}, showTextDocument() {} }
  };
  const context = { subscriptions: [], messagePassingProtocol: { onDidReceiveMessage: incoming.event, postMessage: message => workerPort.postMessage(message) } };
  const module = { exports: {} };
  const sandbox = { require: id => { assert.equal(id, 'vscode'); return vscode; }, module, exports: module.exports, setTimeout, clearTimeout, setInterval, clearInterval, queueMicrotask, Uint8Array, TextDecoder, TextEncoder, btoa, atob, performance, URL, navigator: { userAgent: 'Mozilla/5.0 Android', language: 'en' } };
  try {
    vm.runInNewContext(await readFile(new URL('./extension/extension.js', import.meta.url), 'utf8'), sandbox);
    module.exports.activate(context);
  } catch (error) {
    integration.dispose(); transport.dispose();
    throw error;
  }

  const native = new MessageChannel();
  const files = new Map([['/workspace/main.py', new TextEncoder().encode('print("Android 🌱")\n')]]);
  let nativeCalls = 0;
  native.port2.on('message', text => {
    const request = JSON.parse(text);
    nativeCalls++;
    let result;
    let error;
    if (request.method === 'fs.readFile') {
      const bytes = files.get(request.params.path);
      if (bytes) result = { dataBase64: Buffer.from(bytes).toString('base64') };
      else error = { code: 'FileNotFound', message: 'No such local file.' };
    } else if (request.method === 'fs.writeFile') {
      files.set(request.params.path, new Uint8Array(Buffer.from(request.params.dataBase64, 'base64')));
      result = null;
    } else if (request.method === 'fs.stat') {
      const value = files.get(request.params.path);
      if (value) result = { type: 1, ctime: 0, mtime: 1, size: value.length };
      else error = { code: 'FileNotFound', message: 'No such local file.' };
    } else if (request.method === 'fs.readDirectory') {
      const prefix = request.params.path + '/';
      const children = new Map();
      for (const path of files.keys()) if (path.startsWith(prefix)) {
        const rest = path.slice(prefix.length), slash = rest.indexOf('/');
        children.set(slash < 0 ? rest : rest.slice(0, slash), slash < 0 ? 1 : 2);
      }
      result = [...children];
    } else error = { code: 'Unavailable', message: 'Not needed by this boundary test.' };
    native.port2.postMessage(JSON.stringify({ v: 1, id: request.id, ok: !error, result, error }));
  });

  try {
    // Queue a real provider request before Android supplies the native port.
    const uri = vscode.Uri.from({ scheme: 'vstab-local', path: '/workspace/main.py' });
    const reading = provider.readFile(uri);
    for (const listener of listeners) listener({ data: 'vstab-native-ready', ports: [native.port1], source: null, origin: '' });
    assert.equal(new TextDecoder().decode(await reading), 'print("Android 🌱")\n');
    const replacement = new TextEncoder().encode('print("saved from Code OSS")\n');
    await provider.writeFile(uri, replacement, { create: true, overwrite: true });
    assert.deepEqual(files.get('/workspace/main.py'), replacement);
    await assert.rejects(provider.readFile(vscode.Uri.from({ scheme: 'vstab-local', path: '/workspace/missing.py' })), error => error.code === 'FileNotFound');
    const before = nativeCalls;
    await assert.rejects(transport.request('ssh.exec', { path: '/workspace/main.py' }), error => error.code === 'NoPermissions');
    assert.equal(nativeCalls, before, 'privileged operations must be rejected before native messaging');
    assert.ok(commands.has('vstab.local.importFile'));
    assert.ok(commands.has('vstab.local.exportFile'));

    const encode = value => new TextEncoder().encode(value);
    files.set('/workspace/.gitignore', encode('node_modules/\nsrc/ignored.js\n'));
    files.set('/workspace/src/hello.js', encode('const hello = "world";\nHELLO helloWorld\n'));
    files.set('/workspace/src/ignored.js', encode('hello should be ignored\n'));
    files.set('/workspace/node_modules/vendor.js', encode('hello dependency\n'));
    const options = { folder: vscode.Uri.from({ scheme: 'vstab-local', path: '/workspace' }), includes: ['**/*.{js,py}'], excludes: [], useIgnoreFiles: true, maxResults: 20 };
    const token = { isCancellationRequested: false };
    const found = await fileSearch.provideFileSearchResults({ pattern: 'hlo' }, options, token);
    assert.deepEqual(Array.from(found, item => item.path), ['/workspace/src/hello.js'], 'Quick Open must honor fuzzy paths, globs and workspace gitignore rules');
    const matches = [];
    const completed = await textSearch.provideTextSearchResults({ pattern: 'hello', isCaseSensitive: false, isWordMatch: true }, options, { report: item => matches.push(item) }, token);
    assert.equal(matches.length, 2, 'Find in Files must honor whole words, case options and ignore rules');
    assert.equal(completed.limitHit, false);
    assert.equal(matches[0].uri.path, '/workspace/src/hello.js');
    assert.deepEqual(matches[0].ranges.start, { line: 0, character: 6 });
    const limited = [];
    const limitedCompletion = await textSearch.provideTextSearchResults({ pattern: 'hello', isRegExp: true }, { ...options, maxResults: 1 }, { report: item => limited.push(item) }, token);
    assert.equal(limited.length, 1);
    assert.equal(limitedCompletion.limitHit, true);
    const cancelled = await fileSearch.provideFileSearchResults({ pattern: '' }, options, { isCancellationRequested: true });
    assert.equal(cancelled.length, 0);
  } finally {
    for (const disposable of context.subscriptions) disposable.dispose();
    integration.dispose(); transport.dispose();
    native.port1.close(); native.port2.close();
  }
});

test('native filesystem channel is unavailable to a remote workbench origin', () => {
  assert.throws(() => createAndroidNativeTransport({ hostWindow: { location: { origin: 'https://remote.example' } } }), /bundled local workbench/);
});
