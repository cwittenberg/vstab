import assert from 'node:assert/strict';
import {once} from 'node:events';
import test from 'node:test';
import {MessageChannel} from 'node:worker_threads';
import {createNativeControlIntegration} from './native-startup.mjs';

function integrationAt(origin) {
  const listeners = new Set();
  const hostWindow = {location: {origin}, addEventListener(_, handler) { listeners.add(handler); },
    removeEventListener(_, handler) { listeners.delete(handler); }};
  const integration = createNativeControlIntegration({hostWindow, channelFactory: () => new MessageChannel()});
  const worker = integration.options.messagePorts.get('vstab.android-controls');
  const nativeChannels = [];
  return {worker,
    attach(eventOrigin = origin) {
      const channel = new MessageChannel(); nativeChannels.push(channel);
      for (const handler of listeners) handler({data: 'vstab-ssh-ready', source: null, origin: eventOrigin, ports: [channel.port1]});
      return channel.port2;
    },
    dispose() {
      integration.dispose(); worker.close();
      for (const channel of nativeChannels) { channel.port1.close(); channel.port2.close(); }
    }};
}

test('all four workspace aliases carry approved UI requests while keeping their native endpoints independent', async t => {
  const windows = [1, 2, 3, 4].map(slot => integrationAt(`http://127.0.0.${slot}:35000`));
  t.after(() => windows.forEach(window => window.dispose()));
  for (const [index, window] of windows.entries()) {
    const endpoint = window.attach();
    const received = once(endpoint, 'message');
    window.worker.postMessage({v: 1, id: 1, method: 'window.new', params: {}});
    assert.deepEqual(JSON.parse((await received)[0]), {v: 1, id: 1, method: 'window.new', params: {}});
    const response = once(window.worker, 'message');
    endpoint.postMessage(JSON.stringify({v: 1, id: 1, ok: true, result: {window: index}}));
    assert.equal((await response)[0].result.window, index);
  }
});

test('another window alias cannot replace the current native endpoint', async t => {
  const window = integrationAt('http://127.0.0.2:35000');
  t.after(() => window.dispose());
  const correct = window.attach();
  const received = once(correct, 'message');
  const foreign = window.attach('http://127.0.0.3:35000');
  let foreignRequests = 0;
  foreign.on('message', () => foreignRequests++);
  window.worker.postMessage({v: 1, id: 2, method: 'documents.openApproved', params: {}});
  assert.equal(JSON.parse((await received)[0]).method, 'documents.openApproved');
  assert.equal(foreignRequests, 0);
});

test('window/document UI additions do not broaden the native endpoint to arbitrary loopback or public sites', () => {
  for (const origin of ['http://127.0.0.5:35000', 'http://127.0.1.1:35000', 'http://127.0.0.1.evil.test:35000',
    'http://192.168.1.2:35000', 'https://localhost:35000', 'http://localhost']) {
    assert.throws(() => integrationAt(origin), /bundled loopback workbench/);
  }
});
