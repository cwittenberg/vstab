import assert from 'node:assert/strict';
import {once} from 'node:events';
import test from 'node:test';
import {MessageChannel} from 'node:worker_threads';
import {createNativeControlIntegration} from './native-startup.mjs';

function harness(origin = 'http://localhost:35721', options = {}) {
  const listeners = new Set();
  const hostWindow = {location: {origin},
    addEventListener(type, listener) { if (type === 'message') listeners.add(listener); },
    removeEventListener(type, listener) { if (type === 'message') listeners.delete(listener); }};
  const integration = createNativeControlIntegration({hostWindow, channelFactory: () => new MessageChannel(), ...options});
  const worker = integration.options.messagePorts.get('vstab.android-controls');
  const natives = [];
  function attach(overrides = {}) {
    const channel = new MessageChannel(); natives.push(channel);
    for (const listener of listeners) listener({data: 'vstab-ssh-ready', source: null, origin, ports: [channel.port1], ...overrides});
    return channel.port2;
  }
  return {integration, worker, attach, close() { integration.dispose(); worker.close(); for (const c of natives) { c.port1.close(); c.port2.close(); } }};
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

test('main-frame file calls use independent IDs and never expose response bytes to worker', async () => {
  const h = harness();
  try {
    const native = h.attach();
    let workerReplies = 0;
    h.worker.on('message', () => workerReplies++);
    const received = once(native, 'message');
    const result = h.integration.request('files.read', {token: 'granted-document', offset: 0});
    const call = JSON.parse((await received)[0]);
    assert.ok(call.id > 2 ** 52);
    assert.equal(call.method, 'files.read');
    native.postMessage(JSON.stringify({v: 1, id: call.id, ok: true, result: {dataBase64: 'AAEC/w=='}}));
    assert.deepEqual(await result, {dataBase64: 'AAEC/w=='});
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(workerReplies, 0);
    await assert.rejects(h.integration.request('ssh.writeConfig', {}), /Unsupported/);
    const blocked = once(h.worker, 'message');
    h.worker.postMessage(request(1, 'files.read', {token: 'not-authorized'}));
    assert.equal((await blocked)[0].error.code, 'NoPermissions');
  } finally { h.close(); }
});

test('main-frame file calls reject on native session replacement', async () => {
  const h = harness();
  try {
    const native = h.attach();
    const received = once(native, 'message');
    const operation = h.integration.request('files.downloadBegin', {name: 'data.bin', size: 4});
    await received;
    const rejected = assert.rejects(operation, {code: 'SessionChanged'});
    h.attach();
    await rejected;
  } finally { h.close(); }
});

test('Explorer paste command stays in the main frame and shares no native token', async () => {
  let pastes = 0;
  const h = harness('http://localhost:35721', {pasteFromAndroid: async () => { pastes++; return {pasted: 2}; }});
  try {
    const native = h.attach();
    let nativeCalls = 0;
    native.on('message', () => nativeCalls++);
    const reply = once(h.worker, 'message');
    h.worker.postMessage(request(2, 'files.paste'));
    assert.deepEqual((await reply)[0].result, {pasted: 2});
    assert.equal(pastes, 1);
    assert.equal(nativeCalls, 0);
    const notice = once(h.worker, 'message');
    h.integration.notify({kind: 'error', message: 'The file could not be saved.'});
    assert.equal((await notice)[0].event, 'filesNotice');
  } finally { h.close(); }
});

test('host controls reject custom directory browsing and only synchronize standard folder metadata', async () => {
  const h = harness();
  try {
    const native = h.attach();
    let response = once(h.worker, 'message');
    h.worker.postMessage(request(10, 'ssh.listDirectory', {profileId: 'host', connectionId: 'session', path: '/'}));
    assert.equal((await response)[0].error.code, 'NoPermissions');
    response = once(h.worker, 'message');
    h.worker.postMessage(request(11, 'ssh.openFolder', {profileId: 'host', connectionId: 'session', path: '/project', navigate: false}));
    assert.equal((await response)[0].error.code, 'NoPermissions');
    const received = once(native, 'message');
    h.worker.postMessage(request(12, 'ssh.recordWorkspace', {profileId: 'host', connectionId: 'session', path: '/project'}));
    const metadata = JSON.parse((await received)[0]);
    assert.equal(metadata.method, 'ssh.recordWorkspace');
    assert.equal(metadata.params.path, '/project');
    assert.equal(Object.hasOwn(metadata.params, 'navigate'), false);
    const emptyWindow = once(native, 'message');
    h.worker.postMessage(request(13, 'ssh.recordWorkspace', {profileId: 'host', connectionId: 'session', path: ''}));
    assert.equal(JSON.parse((await emptyWindow)[0]).params.path, '');
  } finally { h.close(); }
});
