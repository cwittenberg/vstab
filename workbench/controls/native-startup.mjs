import {installFileTransfers} from './file-transfers.mjs';

const EXTENSION_ID = 'vstab.android-controls';
const MAX_BYTES = 512 * 1024;
const MAX_PENDING = 64;
const METHODS = new Set([
  'ssh.getState', 'ssh.readConfig', 'ssh.writeConfig', 'ssh.addHost', 'ssh.removeHost',
  'ssh.connect', 'ssh.disconnect', 'ssh.reconnect', 'ssh.recordWorkspace',
  'ssh.importKey', 'ssh.respondPrompt', 'workspace.local', 'app.about', 'window.new',
  'documents.getState', 'documents.pickFolder', 'documents.pickFile', 'documents.openApproved', 'documents.sync', 'documents.resolve',
  'files.paste'
]);
const TRANSFER_METHODS = new Set(['files.clipboard', 'files.read', 'files.release',
  'files.downloadBegin', 'files.downloadWrite', 'files.downloadFinish', 'files.abort']);
const FIRST_MAIN_ID = 2 ** 52;
const encodedSize = value => new TextEncoder().encode(value).byteLength;

/** Connect one first-party browser extension worker to Android's scoped port. */
export function createNativeControlIntegration({
  hostWindow = window,
  expectedOrigin = hostWindow.location.origin,
  channelFactory = () => new MessageChannel(),
  requestTimeoutMs = 120000,
  pasteFromAndroid = () => Promise.reject(new Error('Android file paste is not ready.'))
} = {}) {
  if (hostWindow.location.origin !== expectedOrigin) throw new Error('The SSH control origin changed.');
  const url = new URL(expectedOrigin);
  if (url.protocol !== 'http:' || !['localhost', '127.0.0.1', '127.0.0.2', '127.0.0.3', '127.0.0.4'].includes(url.hostname) || !url.port) {
    throw new Error('SSH controls require the bundled loopback workbench.');
  }
  const channel = channelFactory();
  const pending = new Map();
  let nativePort;
  let disposed = false;
  let mainSequence = FIRST_MAIN_ID;

  function reply(message) { if (!disposed) channel.port2.postMessage(message); }
  function fail(id, code, message) {
    const item = pending.get(id);
    if (item) { clearTimeout(item.timer); pending.delete(id); }
    if (item?.reject) item.reject(Object.assign(new Error(message), {code}));
    else reply({v: 1, id, ok: false, error: {code, message}});
  }
  function nativeMessage(event) {
    if (disposed || typeof event.data !== 'string' || encodedSize(event.data) > MAX_BYTES) return;
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (!message || message.v !== 1) return;
    if (message.event === 'stateChanged' || message.event === 'configChanged' || message.event === 'sshPrompt') {
      if (!Object.hasOwn(message, 'id')) reply(message);
      return;
    }
    const item = pending.get(message.id);
    if (!item || typeof message.ok !== 'boolean') return;
    clearTimeout(item.timer); pending.delete(message.id);
    if (item.resolve) {
      if (message.ok) item.resolve(message.result);
      else item.reject(Object.assign(new Error(message.error?.message || 'The Android file transfer failed.'),
        {code: message.error?.code || 'Unavailable'}));
    } else reply(message);
  }
  function handshake(event) {
    if (disposed || event.data !== 'vstab-ssh-ready' || event.ports?.length !== 1 ||
        (event.source !== null && event.source !== hostWindow) ||
        (event.origin && event.origin !== 'null' && event.origin !== expectedOrigin)) return;
    // A cached local workbench receives a new native endpoint when resumed.
    if (nativePort) {
      nativePort.close();
      for (const id of [...pending.keys()]) fail(id, 'SessionChanged', 'The native SSH control session changed. Retry the request.');
    }
    nativePort = event.ports[0];
    const endpoint = nativePort;
    nativePort.onmessage = message => { if (nativePort === endpoint) nativeMessage(message); };
    nativePort.start?.();
    for (const item of pending.values()) {
      if (item.body !== undefined) nativePort.postMessage(item.body);
      item.body = undefined;
    }
  }
  hostWindow.addEventListener('message', handshake);
  channel.port2.onmessage = event => {
    const message = event.data;
    if (disposed || !message || message.v !== 1 || !Number.isSafeInteger(message.id) || message.id < 1 || message.id >= FIRST_MAIN_ID) return;
    if (!METHODS.has(message.method) || !message.params || typeof message.params !== 'object' || Array.isArray(message.params)) {
      fail(message.id, 'NoPermissions', 'Unsupported SSH control request.'); return;
    }
    if (message.method === 'ssh.respondPrompt' && Object.hasOwn(message.params, 'value') &&
        (typeof message.params.value !== 'string' || encodedSize(message.params.value) > 16 * 1024)) {
      fail(message.id, 'NoPermissions', 'Invalid SSH prompt response.'); return;
    }
    if (pending.has(message.id)) {
      // Do not dispatch an operation twice or overwrite its first correlation.
      return;
    }
    if (pending.size >= MAX_PENDING) {
      fail(message.id, 'Unavailable', 'Too many SSH control requests.'); return;
    }
    let body;
    try { body = JSON.stringify(message); }
    catch { fail(message.id, 'NoPermissions', 'SSH control request is not serializable.'); return; }
    if (encodedSize(body) > MAX_BYTES) {
      fail(message.id, 'Unavailable', 'SSH control request exceeds the message limit.'); return;
    }
    const item = {body, timer: setTimeout(() => fail(message.id, 'Unavailable',
      nativePort ? 'The native SSH operation timed out.' : 'The native SSH control channel did not become ready.'), requestTimeoutMs)};
    pending.set(message.id, item);
    if (message.method === 'files.paste') {
      // The first-party UI command asks the main frame to perform one explicit
      // paste. File bytes and native transfer tokens never enter its worker.
      item.body = undefined;
      Promise.resolve().then(pasteFromAndroid).then(result => {
        if (!pending.has(message.id)) return;
        clearTimeout(item.timer); pending.delete(message.id);
        reply({v: 1, id: message.id, ok: true, result});
      }, error => { if (pending.has(message.id)) fail(message.id, error.code || 'Unavailable', error.message || 'Android file paste failed.'); });
      return;
    }
    if (nativePort) {
      try { nativePort.postMessage(body); item.body = undefined; }
      catch { fail(message.id, 'SessionChanged', 'The native SSH control channel is closed.'); }
    }
  };
  channel.port2.start?.();
  return {
    options: {messagePorts: new Map([[EXTENSION_ID, channel.port1]])},
    request(method, params = {}) {
      if (disposed) return Promise.reject(new Error('The workbench was closed.'));
      if (!TRANSFER_METHODS.has(method)) return Promise.reject(new Error('Unsupported Android file request.'));
      if (pending.size >= MAX_PENDING) return Promise.reject(new Error('Too many Android file requests.'));
      const id = ++mainSequence;
      const body = JSON.stringify({v: 1, id, method, params});
      if (encodedSize(body) > MAX_BYTES) return Promise.reject(new Error('The Android file request is too large.'));
      return new Promise((resolve, reject) => {
        const timeout = method === 'files.downloadFinish' ? Math.max(requestTimeoutMs, 30 * 60 * 1000) : requestTimeoutMs;
        const item = {body, resolve, reject, timer: setTimeout(() => fail(id, 'Unavailable',
          'The Android file operation timed out.'), timeout)};
        pending.set(id, item);
        if (nativePort) {
          try { nativePort.postMessage(body); item.body = undefined; }
          catch { fail(id, 'SessionChanged', 'The Android file channel is closed.'); }
        }
      });
    },
    notify({kind, message}) {
      reply({v: 1, event: 'filesNotice', kind, message});
    },
    dispose() {
      if (disposed) return;
      for (const id of [...pending.keys()]) fail(id, 'Unavailable', 'The SSH control workbench was closed.');
      disposed = true;
      hostWindow.removeEventListener('message', handshake);
      nativePort?.close(); channel.port1.close(); channel.port2.close();
    }
  };
}

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  let transfers;
  const integration = createNativeControlIntegration({pasteFromAndroid: () => transfers.pasteFromAndroid({force: true})});
  transfers = installFileTransfers({hostWindow: window, request: (method, params) => integration.request(method, params),
    notify: notice => integration.notify(notice)});
  globalThis.__vstabControlOptions = integration.options;
  globalThis.addEventListener('pagehide', () => { transfers.dispose(); integration.dispose(); }, {once: true});
  const baseValue = document.getElementById('vscode-workbench-web-base-url')?.getAttribute('data-settings');
  if (!baseValue) throw new Error('The bundled Code OSS base URL is missing.');
  const base = new URL(baseValue.replace(/\/$/, '') + '/', location.href);
  if (base.origin !== location.origin) throw new Error('The bundled editor must use its native app origin.');
  await import(new URL('out/vs/code/browser/workbench/workbench.js', base).href);
}
