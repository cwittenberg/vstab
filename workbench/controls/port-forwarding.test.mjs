import assert from 'node:assert/strict';
import test from 'node:test';
import {createPortForwarding} from './port-forwarding.mjs';

const uri = (address, path = '/app', query = 'q=1', fragment = 'test') => ({
  scheme: 'http', authority: address, path, query, fragment,
  with(changes) { return {...this, ...changes}; }
});
function harness(options = {}) {
  const calls = [];
  const integration = createPortForwarding({
    workbenchAuthority: '127.0.0.1:35721',
    request: async (method, params) => {
      calls.push({method, params});
      if (method === 'tunnels.close') return {closed: true};
      return {id: 'one', connectionId: 'session', remoteHost: params.remoteHost, remotePort: params.remotePort,
        localHost: '127.0.0.1', localPort: 41235, localAddress: '127.0.0.1:41235'};
    }, ...options
  });
  return {integration, calls};
}

test('Ports factory returns the allocated native endpoint, and the URI resolver does not proxy or forward it again', async () => {
  const {integration, calls} = harness();
  const tunnel = await integration.tunnelProvider.tunnelFactory({remoteAddress: {host: 'localhost', port: 3000}, localAddressPort: 3001});
  assert.equal(tunnel.localAddress, '127.0.0.1:41235');
  assert.equal(tunnel.localPort, 41235);
  assert.equal(tunnel.privacy, 'private');
  assert.deepEqual(calls, [{method: 'tunnels.forward', params: {remoteHost: '127.0.0.1', remotePort: 3000, localPort: 3001}}]);
  const address = uri(tunnel.localAddress);
  assert.equal(await integration.resolveExternalUri(address), address);
  assert.equal(calls.length, 1);
});

test('localhost links from remote extensions resolve to a native listener and keep path, query and fragment', async () => {
  const {integration, calls} = harness();
  const resolved = await integration.resolveExternalUri(uri('localhost:3000'));
  assert.equal(resolved.authority, '127.0.0.1:41235');
  assert.equal(resolved.path, '/app');
  assert.equal(resolved.query, 'q=1');
  assert.equal(resolved.fragment, 'test');
  const tunnel = await integration.tunnelProvider.tunnelFactory({remoteAddress: {host: '0.0.0.0', port: 3000}});
  assert.equal(tunnel.localAddress, resolved.authority);
  assert.equal(calls.length, 1);
});

test('Stop Forwarding waits for native close, fires disposal once and does not close a later session', async () => {
  let finish;
  const closed = new Promise(resolve => { finish = resolve; });
  const calls = [];
  const {integration} = harness({request: async (method, params) => {
    calls.push({method, params});
    return method === 'tunnels.close' ? closed : {id: 'one', connectionId: 'old-session', localAddress: '127.0.0.1:3000', localPort: 3000};
  }});
  const tunnel = await integration.tunnelProvider.tunnelFactory({remoteAddress: {host: 'localhost', port: 3000}});
  let disposed = 0;
  tunnel.onDidDispose(() => disposed++);
  const stopping = tunnel.dispose();
  assert.equal(tunnel.dispose(), stopping);
  assert.equal(disposed, 0);
  assert.deepEqual(calls[1], {method: 'tunnels.close', params: {id: 'one', connectionId: 'old-session'}});
  finish({closed: true}); await stopping;
  assert.equal(disposed, 1);
  await tunnel.dispose();
  assert.equal(calls.length, 2);
});

test('disconnection clears forwarding state and marks panel entries disposed without stale close RPCs', async () => {
  const {integration, calls} = harness();
  const options = {remoteAddress: {host: 'localhost', port: 3000}};
  const tunnel = await integration.tunnelProvider.tunnelFactory(options);
  let disposed = 0;
  tunnel.onDidDispose(() => disposed++);
  integration.updateState({connection: {phase: 'error'}});
  assert.equal(disposed, 1);
  await tunnel.dispose();
  await integration.tunnelProvider.tunnelFactory(options);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(call => call.method === 'tunnels.forward'));
});

test('failed native forwards are retryable and preserve the reported connection error', async () => {
  let count = 0;
  const {integration} = harness({request: async () => { count++; throw new Error('SSH disconnected'); }});
  const options = {remoteAddress: {host: 'localhost', port: 3000}};
  await assert.rejects(integration.tunnelProvider.tunnelFactory(options), /SSH disconnected/);
  await assert.rejects(integration.tunnelProvider.tunnelFactory(options), /SSH disconnected/);
  assert.equal(count, 2);
});

test('a late response from a disconnected session cannot revive its tunnel or evict the new session request', async () => {
  const operations = [];
  const {integration} = harness({request: () => new Promise(resolve => operations.push(resolve))});
  const options = {remoteAddress: {host: 'localhost', port: 3000}};
  const old = integration.tunnelProvider.tunnelFactory(options);
  const rejected = assert.rejects(old, {code: 'SessionChanged'});
  integration.updateState({connection: {phase: 'error'}});
  const current = integration.tunnelProvider.tunnelFactory(options);
  operations[0]({id: 'old', connectionId: 'old-session', localAddress: '127.0.0.11:3000', localPort: 3000});
  await rejected;
  const same = integration.tunnelProvider.tunnelFactory(options);
  assert.equal(operations.length, 2);
  operations[1]({id: 'new', connectionId: 'new-session', localAddress: '127.0.0.11:4000', localPort: 4000});
  assert.equal((await current).localAddress, '127.0.0.11:4000');
  assert.equal((await same).localAddress, '127.0.0.11:4000');
  const native = uri('127.0.0.11:4000');
  assert.equal(await integration.resolveExternalUri(native), native);
});

test('on-device workspaces open their existing service directly without an SSH request', async () => {
  const {integration, calls} = harness({local: true, workbenchAuthority: 'localhost:35721'});
  const tunnel = await integration.tunnelProvider.tunnelFactory({remoteAddress: {host: 'localhost', port: 3000}});
  assert.equal(tunnel.localAddress, 'localhost:3000');
  assert.equal((await integration.resolveExternalUri(uri('127.0.0.1:3000'))).authority, 'localhost:3000');
  await tunnel.dispose();
  assert.equal(calls.length, 0);
});

test('external and workbench URLs pass through, invalid ports and non-loopback targets never reach native', async () => {
  const {integration, calls} = harness();
  for (const address of ['example.com:3000', '127.0.0.1:35721']) {
    const original = uri(address);
    assert.equal(await integration.resolveExternalUri(original), original);
  }
  for (const address of [{host: '192.168.1.1', port: 3000}, {host: 'localhost', port: 65536}, {host: 'localhost', port: 2.5}]) {
    await assert.rejects(integration.tunnelProvider.tunnelFactory({remoteAddress: address}));
  }
  assert.equal(calls.length, 0);
});

test('IPv6 wildcard discovery forwards to remote IPv6 loopback', async () => {
  const {integration, calls} = harness();
  await integration.resolveExternalUri(uri('[::]:3000'));
  assert.equal(calls[0].params.remoteHost, '::1');
});
