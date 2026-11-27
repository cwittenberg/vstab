import assert from 'node:assert/strict';
import {once} from 'node:events';
import test from 'node:test';
import {MessageChannel} from 'node:worker_threads';
import {createNativeControlIntegration} from './native-startup.mjs';

function harness(origin = 'http://localhost:35721') {
  const listeners = new Set();
  const hostWindow = {location: {origin},
    addEventListener(type, listener) { if (type === 'message') listeners.add(listener); },
    removeEventListener(type, listener) { if (type === 'message') listeners.delete(listener); }};
  const integration = createNativeControlIntegration({hostWindow, channelFactory: () => new MessageChannel()});
  const worker = integration.options.messagePorts.get('vstab.android-controls');
  const natives = [];
  function attach(overrides = {}) {
    const channel = new MessageChannel(); natives.push(channel);
    for (const listener of listeners) listener({data: 'vstab-ssh-ready', source: null, origin, ports: [channel.port1], ...overrides});
    return channel.port2;
  }
  return {worker, attach, close() { integration.dispose(); worker.close(); for (const c of natives) { c.port1.close(); c.port2.close(); } }};
}
const request = (id, method = 'ssh.getState', params = {}) => ({v: 1, id, method, params});

test('real worker/native ports queue handshake, preserve Unicode config and typed errors/events', async () => {
  const h = harness('http://127.0.0.1:41234');
  try {
    h.worker.postMessage(request(1, 'ssh.writeConfig', {content: 'Host 🌱\n  HostName example.test\n', expectedRevision: 'abc'}));
    await new Promise(resolve => setTimeout(resolve, 10));
    const native = h.attach();
    const [text] = await once(native, 'message');
    assert.deepEqual(JSON.parse(text), request(1, 'ssh.writeConfig', {content: 'Host 🌱\n  HostName example.test\n', expectedRevision: 'abc'}));
    const response = once(h.worker, 'message');
    native.postMessage(JSON.stringify({v: 1, id: 1, ok: false, error: {code: 'Conflict', message: 'Configuration changed.'}}));
    assert.equal((await response)[0].error.code, 'Conflict');
    const event = once(h.worker, 'message');
    native.postMessage(JSON.stringify({v: 1, event: 'configChanged', revision: 'new', mtime: 4, size: 50}));
    assert.deepEqual((await event)[0], {v: 1, event: 'configChanged', revision: 'new', mtime: 4, size: 50});
  } finally { h.close(); }
});

test('cached view accepts a new native endpoint and rejects stale pending calls', async () => {
  const h = harness();
  try {
    const oldNative = h.attach();
    const oldReceived = once(oldNative, 'message');
    h.worker.postMessage(request(2)); await oldReceived;
    const rejected = once(h.worker, 'message');
    const newNative = h.attach();
    assert.equal((await rejected)[0].error.code, 'SessionChanged');
    const received = once(newNative, 'message');
    h.worker.postMessage(request(3));
    assert.equal(JSON.parse((await received)[0]).id, 3);
    const response = once(h.worker, 'message');
    newNative.postMessage(JSON.stringify({v: 1, id: 3, ok: true, result: {schema: 1}}));
    assert.equal((await response)[0].result.schema, 1);
  } finally { h.close(); }
});

test('foreign-frame handshake, unsupported execution and oversized UTF-8 requests are rejected', async () => {
  const h = harness();
  try {
    const foreign = h.attach({source: {foreignFrame: true}});
    let foreignCalls = 0;
    foreign.on('message', () => foreignCalls++);
    h.worker.postMessage(request(7));
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(foreignCalls, 0);
    let response = once(h.worker, 'message');
    h.worker.postMessage(request(4, 'native.exec', {command: 'ignored'}));
    assert.equal((await response)[0].error.code, 'NoPermissions');
    response = once(h.worker, 'message');
    h.worker.postMessage(request(5, 'ssh.writeConfig', {content: '🌱'.repeat(140000), expectedRevision: 'old'}));
    assert.equal((await response)[0].error.code, 'Unavailable');
    const native = h.attach();
    const received = once(native, 'message');
    assert.equal(JSON.parse((await received)[0]).id, 7);
  } finally { h.close(); }
});

test('credential replies are bounded and native prompts remain on the dedicated worker channel', async () => {
  const h = harness();
  try {
    const native = h.attach();
    let response = once(h.worker, 'message');
    h.worker.postMessage(request(8, 'ssh.respondPrompt', {promptId: 'one-use-test-id', value: '🌱'.repeat(5000)}));
    assert.equal((await response)[0].error.code, 'NoPermissions');
    const received = once(native, 'message');
    h.worker.postMessage(request(9, 'ssh.respondPrompt', {promptId: 'one-use-test-id', value: 'public-test-value'}));
    assert.equal(JSON.parse((await received)[0]).params.value, 'public-test-value');
    response = once(h.worker, 'message');
    native.postMessage(JSON.stringify({v: 1, event: 'sshPrompt', prompt: {id: 'next', kind: 'host-key', fingerprint: 'test-fingerprint'}}));
    assert.equal((await response)[0].prompt.kind, 'host-key');
  } finally { h.close(); }
});

test('outstanding calls are bounded before the native handshake', async () => {
  const h = harness();
  try {
    const overflow = once(h.worker, 'message');
    for (let id = 1; id <= 65; id++) h.worker.postMessage(request(id));
    const [message] = await overflow;
    assert.equal(message.id, 65);
    assert.equal(message.error.code, 'Unavailable');
  } finally { h.close(); }
});
