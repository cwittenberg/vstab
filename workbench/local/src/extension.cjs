'use strict';

const vscode = require('vscode');
const { createSearchProviders } = require('./search.js');
const SCHEME = 'vstab-local';
const MAX_FILE_BYTES = 16 * 1024 * 1024;

function pathFor(uri) {
  const value = uri.path;
  if (uri.scheme !== SCHEME || uri.authority || uri.query || uri.fragment ||
      typeof value !== 'string' || !(/^\/workspace(?:\/|$)/).test(value) ||
      value.includes('\\') || value.includes('\0') ||
      value.split('/').some(part => part === '.' || part === '..')) {
    throw vscode.FileSystemError.NoPermissions('Path is outside the Android workspace.');
  }
  return value.length > 10 ? value.replace(/\/+$/, '') : value;
}

function encodeBytes(data) {
  if (data.byteLength > MAX_FILE_BYTES) throw vscode.FileSystemError.Unavailable('Local files are limited to 16 MiB.');
  let binary = '';
  for (let i = 0; i < data.length; i += 8192) binary += String.fromCharCode(...data.subarray(i, i + 8192));
  return btoa(binary);
}

function decodeBytes(value) {
  if (typeof value !== 'string' || value.length > Math.ceil(MAX_FILE_BYTES / 3) * 4) {
    throw vscode.FileSystemError.Unavailable('Invalid or oversized Android file response.');
  }
  const binary = atob(value);
  const result = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) result[i] = binary.charCodeAt(i);
  return result;
}

function nativeError(error) {
  const code = error && error.code;
  const message = error && typeof error.message === 'string' ? error.message : 'Android file operation failed.';
  const factories = {
    FileNotFound: vscode.FileSystemError.FileNotFound,
    FileExists: vscode.FileSystemError.FileExists,
    FileNotADirectory: vscode.FileSystemError.FileNotADirectory,
    FileIsADirectory: vscode.FileSystemError.FileIsADirectory,
    NoPermissions: vscode.FileSystemError.NoPermissions,
    Unavailable: vscode.FileSystemError.Unavailable
  };
  return (factories[code] || vscode.FileSystemError.Unavailable)(message);
}

function activate(context) {
  const protocol = context.messagePassingProtocol;
  if (!protocol) throw new Error('The vstab local workspace channel is unavailable. Reopen the local workspace.');
  let sequence = 0;
  const pending = new Map();
  const changed = new vscode.EventEmitter();

  function request(method, params) {
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        pending.delete(id);
        reject(vscode.FileSystemError.Unavailable('Android did not finish the file operation.'));
      }, method.startsWith('local.') ? 120000 : 45000);
      pending.set(id, { resolve, reject, timer });
      protocol.postMessage({ v: 1, id, method, params });
    });
  }

  const listener = protocol.onDidReceiveMessage(message => {
    if (!message || message.v !== 1) return;
    if (message.event === 'filesChanged' && Array.isArray(message.changes)) {
      const events = [];
      for (const item of message.changes) {
        try {
          const uri = vscode.Uri.from({ scheme: SCHEME, path: item.path });
          pathFor(uri);
          if ([1, 2, 3].includes(item.type)) events.push({ uri, type: item.type });
        } catch (_) { /* Ignore changes outside the granted workspace. */ }
      }
      if (events.length) changed.fire(events);
      return;
    }
    const item = pending.get(message.id);
    if (!item) return;
    clearTimeout(item.timer);
    pending.delete(message.id);
    if (message.ok === true) item.resolve(message.result);
    else item.reject(nativeError(message.error));
  });

  const provider = {
    onDidChangeFile: changed.event,
    watch() { return new vscode.Disposable(() => {}); },
    stat(uri) { return request('fs.stat', { path: pathFor(uri) }); },
    readDirectory(uri) { return request('fs.readDirectory', { path: pathFor(uri) }); },
    async readFile(uri) {
      const result = await request('fs.readFile', { path: pathFor(uri) });
      return decodeBytes(result.dataBase64);
    },
    async writeFile(uri, content, options) {
      const path = pathFor(uri);
      await request('fs.writeFile', { path, dataBase64: encodeBytes(content), create: options.create, overwrite: options.overwrite });
      changed.fire([{ uri, type: vscode.FileChangeType.Changed }]);
    },
    async createDirectory(uri) {
      await request('fs.createDirectory', { path: pathFor(uri) });
      changed.fire([{ uri, type: vscode.FileChangeType.Created }]);
    },
    async delete(uri, options) {
      await request('fs.delete', { path: pathFor(uri), recursive: options.recursive });
      changed.fire([{ uri, type: vscode.FileChangeType.Deleted }]);
    },
    async rename(oldUri, newUri, options) {
      await request('fs.rename', { path: pathFor(oldUri), newPath: pathFor(newUri), overwrite: options.overwrite });
      changed.fire([{ uri: oldUri, type: vscode.FileChangeType.Deleted }, { uri: newUri, type: vscode.FileChangeType.Created }]);
    }
  };

  const search = createSearchProviders(vscode, provider, pathFor);

  context.subscriptions.push(listener, changed,
    vscode.workspace.registerFileSystemProvider(SCHEME, provider, { isCaseSensitive: true }),
    vscode.workspace.registerFileSearchProvider(SCHEME, search.file),
    vscode.workspace.registerTextSearchProvider(SCHEME, search.text),
    changed.event(() => search.invalidate()),
    vscode.commands.registerCommand('vstab.local.importFile', async () => {
      const result = await request('local.import', {});
      if (!result || result.cancelled || !result.path) return;
      const uri = vscode.Uri.from({ scheme: SCHEME, path: result.path });
      pathFor(uri);
      changed.fire([{ uri, type: vscode.FileChangeType.Created }]);
      await vscode.window.showTextDocument(uri);
    }),
    vscode.commands.registerCommand('vstab.local.exportFile', async () => {
      const document = vscode.window.activeTextEditor && vscode.window.activeTextEditor.document;
      if (!document || document.uri.scheme !== SCHEME) {
        await vscode.window.showInformationMessage('Open a local workspace file to export it.');
        return;
      }
      if (!(await document.save())) throw new Error('Save the file before exporting.');
      return request('local.export', { path: pathFor(document.uri) });
    }),
    new vscode.Disposable(() => {
      for (const item of pending.values()) {
        clearTimeout(item.timer);
        item.reject(vscode.FileSystemError.Unavailable('The local workspace was closed.'));
      }
      pending.clear();
    })
  );
  return { provider };
}

exports.activate = activate;
exports.deactivate = function deactivate() {};
