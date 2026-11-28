const LOOPBACK = new Set(['localhost', '127.0.0.1', '0.0.0.0', '[::1]', '::1', '[::]', '::']);

function remoteHost(host) {
  if (!LOOPBACK.has(host)) throw new Error('Forward a port on the remote localhost. Other destination hosts are not supported.');
  return ['::1', '[::1]', '::', '[::]'].includes(host) ? '::1' : '127.0.0.1';
}

function validPort(port) {
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Enter a port between 1 and 65535.');
  return port;
}

/** Use native SSH listeners behind the ordinary Code OSS Ports panel. */
export function createPortForwarding({request, local = false, workbenchAuthority}) {
  const forwards = new Map();
  const localAddresses = new Set();
  const tunnels = new Set();
  let generation = 0;

  function requireGeneration(expected) {
    if (expected !== generation) throw Object.assign(new Error('The SSH connection changed. Retry forwarding the port.'), {code: 'SessionChanged'});
  }

  async function forward(address, localPort) {
    const host = remoteHost(address.host);
    const port = validPort(address.port);
    if (localPort !== undefined && localPort !== 0) validPort(localPort);
    if (local) return {localAddress: `localhost:${port}`, localPort: port};
    const key = `${host}:${port}:${localPort ?? ''}`;
    if (!forwards.has(key)) {
      const expectedGeneration = generation;
      const operation = request('tunnels.forward', {
        remoteHost: host, remotePort: port, ...(localPort === undefined ? {} : {localPort})
      }).then(result => {
        requireGeneration(expectedGeneration);
        localAddresses.add(result.localAddress);
        return result;
      }).catch(error => { if (forwards.get(key) === operation) forwards.delete(key); throw error; });
      forwards.set(key, operation);
    }
    return forwards.get(key);
  }

  function forget(result) {
    localAddresses.delete(result.localAddress);
    for (const [key, operation] of forwards) {
      operation.then(value => { if (value.id === result.id && forwards.get(key) === operation) forwards.delete(key); }, () => {});
    }
  }

  const tunnelProvider = {
    features: {elevation: false, public: false, privacyOptions: [], protocol: true},
    async tunnelFactory(options) {
      const expectedGeneration = generation;
      const result = await forward(options.remoteAddress, options.localAddressPort);
      requireGeneration(expectedGeneration);
      const listeners = new Set();
      let closing;
      let closed = false;
      const didClose = () => {
        if (closed) return;
        closed = true;
        forget(result);
        tunnels.delete(didClose);
        for (const listener of listeners) listener();
        listeners.clear();
      };
      tunnels.add(didClose);
      return {
        remoteAddress: options.remoteAddress,
        localAddress: result.localAddress,
        localPort: result.localPort,
        privacy: 'private',
        protocol: options.protocol ?? 'http',
        onDidDispose(listener, thisArg, disposables) {
          const callback = () => listener.call(thisArg);
          listeners.add(callback);
          const subscription = {dispose: () => listeners.delete(callback)};
          disposables?.push(subscription);
          return subscription;
        },
        dispose() {
          if (closed) return Promise.resolve();
          if (!closing) closing = (local ? Promise.resolve() : request('tunnels.close', {
            id: result.id, connectionId: result.connectionId
          })).then(didClose).catch(error => { closing = undefined; throw error; });
          return closing;
        }
      };
    }
  };

  return {
    tunnelProvider,
    async resolveExternalUri(uri) {
      if (uri.scheme !== 'http' && uri.scheme !== 'https') return uri;
      if (uri.authority === workbenchAuthority || localAddresses.has(uri.authority)) return uri;
      const url = new URL(`${uri.scheme}://${uri.authority}`);
      if (!LOOPBACK.has(url.hostname)) return uri;
      const result = await forward({host: url.hostname, port: Number(url.port || (uri.scheme === 'https' ? 443 : 80))});
      return uri.with({authority: result.localAddress});
    },
    updateState(state) {
      if (['disconnected', 'disconnecting', 'error'].includes(state.connection?.phase)) {
        generation++;
        for (const close of [...tunnels]) close();
        forwards.clear(); localAddresses.clear();
      }
    }
  };
}
