const EXTENSION_ID = 'vstab.local-workspace';
const SCHEME = 'vstab-local';
const MAX_JSON_BYTES = 24 * 1024 * 1024;
const ALLOWED_METHODS = new Set([
  'fs.stat', 'fs.readDirectory', 'fs.readFile', 'fs.writeFile',
  'fs.createDirectory', 'fs.delete', 'fs.rename',
  'local.import', 'local.export', 'ssh.showConnections'
]);

export function normalizeWorkspacePath(value) {
  if (typeof value !== 'string' || !(/^\/workspace(?:\/|$)/).test(value) ||
      value.includes('\\') || value.includes('\0') ||
      value.split('/').some(part => part === '.' || part === '..')) {
    throw Object.assign(new Error('Path is outside the Android workspace.'), { code: 'NoPermissions' });
  }
  return value.length > 10 ? value.replace(/\/+$/, '') : value;
}

function validateRequest(method, params) {
  if (!ALLOWED_METHODS.has(method) || !params || typeof params !== 'object' || Array.isArray(params)) {
    throw Object.assign(new Error('Unsupported vstab operation.'), { code: 'NoPermissions' });
  }
  if (method.startsWith('fs.') || method === 'local.export') normalizeWorkspacePath(params.path);
  if (method === 'fs.rename') normalizeWorkspacePath(params.newPath);
  if (method === 'fs.writeFile' && (typeof params.dataBase64 !== 'string' || params.dataBase64.length > 22369624)) {
    throw Object.assign(new Error('Invalid or oversized file write.'), { code: 'Unavailable' });
  }
}

/** Native Android transfers a dedicated MessagePort to the local main frame. */
export function createAndroidNativeTransport({ hostWindow = window, expectedOrigin = 'https://appassets.androidplatform.net' } = {}) {
  if (hostWindow.location.origin !== expectedOrigin) throw new Error('Native files are available only in the bundled local workbench.');
  let port;
  let closed = false;
  let sequence = 0;
  const pending = new Map();
  const queue = [];
  const fileListeners = new Set();

  const fail = (item, message) => {
    clearTimeout(item.timer);
    item.reject(Object.assign(new Error(message), { code: 'Unavailable' }));
  };

  function receive(event) {
    if (typeof event.data !== 'string' || event.data.length > MAX_JSON_BYTES) return;
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (!message || message.v !== 1) return;
    if (message.event === 'filesChanged' && Array.isArray(message.changes)) {
      for (const listener of fileListeners) listener(message.changes);
      return;
    }
    const item = pending.get(message.id);
    if (!item) return;
    pending.delete(message.id);
    clearTimeout(item.timer);
    if (message.ok === true) item.resolve(message.result);
    else item.reject(Object.assign(new Error(message.error?.message || 'Android operation failed.'), { code: message.error?.code || 'Unavailable' }));
  }

  function handshake(event) {
    // A subframe must never replace the native channel. Android-originated
    // WebMessages have no Window source; same-window messages are also safe.
    if (closed || port || event.data !== 'vstab-native-ready' || event.ports?.length !== 1 ||
        (event.source !== null && event.source !== hostWindow)) return;
    if (event.origin && event.origin !== 'null' && event.origin !== expectedOrigin) return;
    port = event.ports[0];
    port.onmessage = receive;
    port.start?.();
    for (const item of queue.splice(0)) if (pending.has(item.id)) port.postMessage(item.body);
  }
  hostWindow.addEventListener('message', handshake);

  function request(method, params = {}) {
    try { validateRequest(method, params); } catch (error) { return Promise.reject(error); }
    if (closed) return Promise.reject(new Error('The Android connection is closed.'));
    const id = ++sequence;
    const body = JSON.stringify({ v: 1, id, method, params });
    if (body.length > MAX_JSON_BYTES) return Promise.reject(new Error('Android request is too large.'));
    return new Promise((resolve, reject) => {
      const item = { id, body, resolve, reject };
      item.timer = setTimeout(() => {
        pending.delete(id);
        const index = queue.indexOf(item);
        if (index >= 0) queue.splice(index, 1);
        fail(item, port ? 'Android did not finish the operation.' : 'Android local workspace channel did not become ready.');
      }, method.startsWith('local.') ? 120000 : 45000);
      pending.set(id, item);
      if (port) port.postMessage(body);
      else queue.push(item);
    });
  }

  return {
    request,
    onFilesChanged(listener) { fileListeners.add(listener); return () => fileListeners.delete(listener); },
    dispose() {
      closed = true;
      hostWindow.removeEventListener('message', handshake);
      port?.close();
      for (const item of pending.values()) fail(item, 'The Android workspace was closed.');
      pending.clear(); queue.length = 0; fileListeners.clear();
    }
  };
}

function uriComponents(value) {
  const url = value instanceof URL ? value : new URL(value);
  return { scheme: url.protocol.slice(0, -1), authority: url.host, path: decodeURIComponent(url.pathname), query: url.search.slice(1), fragment: url.hash.slice(1) };
}

/** Returns real Code OSS IWorkbenchConstructionOptions plus channel lifecycle. */
export function createLocalWorkbenchIntegration({
  extensionUri,
  requestNative,
  workspacePath = '/workspace',
  openWorkspace,
  channelFactory = () => new MessageChannel()
}) {
  if (typeof requestNative !== 'function') throw new Error('An Android request function is required.');
  workspacePath = normalizeWorkspacePath(workspacePath);
  const channel = channelFactory();
  let closed = false;
  channel.port2.onmessage = async event => {
    const message = event.data;
    if (closed || !message || message.v !== 1 || !Number.isSafeInteger(message.id)) return;
    try {
      validateRequest(message.method, message.params);
      const result = await requestNative(message.method, message.params);
      if (!closed) channel.port2.postMessage({ v: 1, id: message.id, ok: true, result });
    } catch (error) {
      if (!closed) channel.port2.postMessage({ v: 1, id: message.id, ok: false, error: { code: error?.code || 'Unavailable', message: error?.message || 'Android operation failed.' } });
    }
  };
  channel.port2.start?.();

  return {
    options: {
      // An explicit undefined prevents the server bootstrap assigning location.host.
      remoteAuthority: undefined,
      remoteStorageEnabled: false,
      isEnabledFileDownloads: false,
      isEnabledFileUploads: false,
      isEnabledFileCache: false,
      isEnabledCoderGettingStarted: false,
      additionalBuiltinExtensions: [uriComponents(extensionUri)],
      enabledExtensionProposedApi: [EXTENSION_ID],
      messagePorts: new Map([[EXTENSION_ID, channel.port1]]),
      workspaceProvider: {
        workspace: { folderUri: { scheme: SCHEME, path: workspacePath } },
        trusted: undefined,
        async open(workspace, options) {
          const uri = workspace?.folderUri;
          if (!uri || uri.scheme !== SCHEME) return false;
          normalizeWorkspacePath(uri.path);
          if (openWorkspace) return Boolean(await openWorkspace(workspace, options));
          return uri.path === workspacePath;
        }
      },
      windowIndicator: { label: '$(device-mobile) Android', tooltip: 'Local Android workspace · Connect to an SSH host', command: 'vstab.ssh.connect' },
      enableWorkspaceTrust: true,
      configurationDefaults: {
        'workbench.startupEditor': 'none',
        'workbench.editor.enablePreview': false,
        'terminal.integrated.gpuAcceleration': 'off',
        'security.workspace.trust.startupPrompt': 'always',
        'telemetry.telemetryLevel': 'off'
      }
    },
    filesChanged(changes) {
      if (!closed) channel.port2.postMessage({ v: 1, event: 'filesChanged', changes });
    },
    dispose() { closed = true; channel.port1.close(); channel.port2.close(); }
  };
}
