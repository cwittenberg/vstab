const KEYS = ['scheme', 'authority', 'path', 'query', 'fragment'];

/** A callback capability belongs to one editor document, including across reloads. */
export function createAuthCallbackProvider({callbackUrl, uri, hostWindow = window, now = Date.now,
  randomId = () => [...crypto.getRandomValues(new Uint8Array(24))].map(n => n.toString(16).padStart(2, '0')).join('')}) {
  const base = new URL(callbackUrl);
  if (base.protocol !== 'http:' || base.hostname !== '127.0.0.1' || !base.port || base.search || base.hash || base.username || base.password) {
    throw new Error('Sign-in callbacks require the native loopback receiver.');
  }
  const pending = new Map();
  const listeners = new Set();
  let disposed = false;
  const prune = () => { for (const [id, item] of pending) if (now() - item.created > 15 * 60 * 1000) pending.delete(id); };
  function receive(event) {
    if (disposed || typeof event.detail !== 'string' || event.detail.length > 65536) return;
    let result;
    try { result = new URL(event.detail); } catch { return; }
    if (result.origin !== base.origin || result.pathname !== base.pathname || result.username || result.password) return;
    const params = result.searchParams;
    const id = params.get('vscode-reqid');
    prune();
    const item = pending.get(id);
    if (!item || params.getAll('vscode-reqid').length !== 1) return;
    for (const key of KEYS) {
      const values = params.getAll('vscode-' + key);
      if (values.length > 1 || (values[0] || '') !== (item.options[key] || '')) return;
    }
    params.delete('vscode-reqid');
    for (const key of KEYS) params.delete('vscode-' + key);
    for (const [key, value] of new URLSearchParams(item.options.query || '')) params.set(key, value);
    const components = {...item.options, query: params.toString()};
    pending.delete(id);
    for (const listener of listeners) listener(uri.from(components));
  }
  hostWindow.addEventListener('vstab-auth-callback', receive);
  return {
    onCallback(listener, thisArg, disposables) {
      const callback = value => listener.call(thisArg, value);
      listeners.add(callback);
      const subscription = {dispose: () => listeners.delete(callback)};
      disposables?.push(subscription);
      return subscription;
    },
    create(options = {}) {
      if (disposed) throw new Error('The editor sign-in session was closed.');
      prune();
      if (pending.size >= 32) throw new Error('Too many pending sign-in requests. Reload the editor and retry.');
      const id = randomId();
      const address = new URL(base);
      address.searchParams.set('vscode-reqid', id);
      const saved = {};
      for (const key of KEYS) if (options[key]) {
        saved[key] = options[key];
        address.searchParams.set('vscode-' + key, options[key]);
      }
      pending.set(id, {created: now(), options: saved});
      return uri.parse(address.href);
    },
    dispose() {
      disposed = true;
      pending.clear(); listeners.clear();
      hostWindow.removeEventListener('vstab-auth-callback', receive);
    }
  };
}
