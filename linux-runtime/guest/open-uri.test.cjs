'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const {spawn, execFileSync} = require('node:child_process');
const {loopbackAddress, requiredPorts, sessionListeners, verifyListeners, postBrowser} = require('./open-uri.cjs');
const listen = server => new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', () => resolve(server.address().port)); });
const close = server => new Promise(resolve => server.close(resolve));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

test('Linux proc addresses and standard redirect_uri select only loopback ports', () => {
  assert.equal(loopbackAddress('0100007F', false), '127.0.0.1');
  assert.equal(loopbackAddress('00000000', false), '127.0.0.1');
  assert.equal(loopbackAddress('0100000A', false), null);
  assert.equal(loopbackAddress('00000000000000000000000001000000', true), '::1');
  assert.equal(loopbackAddress('0000000000000000FFFF00000100007F', true), '127.0.0.1');
  assert.deepEqual(requiredPorts('https://identity.invalid/authorize?redirect_uri=' + encodeURIComponent('http://localhost:14555/callback?a=%26')), [14555]);
  assert.deepEqual(requiredPorts('http://[::1]:16000/callback'), [16000]);
  assert.deepEqual(requiredPorts('https://identity.invalid/?redirect_uri=https%3A%2F%2Freturn.invalid%2F'), []);
  assert.throws(() => requiredPorts('https://identity.invalid/?redirect_uri=http://localhost:80/callback'), /privileged/);
  assert.throws(() => requiredPorts('https://identity.invalid/?redirect_uri=a&redirect_uri=b'), /ambiguous/);
});

test('live socket verification rejects changed process identity and stale socket inode', async () => {
  const server = http.createServer((request, response) => response.end('fixture'));
  const port = await listen(server);
  try {
    const record = sessionListeners(process.pid, [port], {uid: process.getuid()});
    assert.equal(record.listeners.length, 1); assert.equal(record.listeners[0].port, port);
    assert.equal(verifyListeners(record, {uid: process.getuid()}).verified, true);
    assert.throws(() => verifyListeners({...record, scope: {...record.scope, startTime: record.scope.startTime + '9'}}, {uid: process.getuid()}), /identity/);
    assert.throws(() => verifyListeners({...record, listeners: [{...record.listeners[0], inode: record.listeners[0].inode + '9'}]}, {uid: process.getuid()}), /closed or changed/);
    await close(server);
    assert.throws(() => verifyListeners(record, {uid: process.getuid()}), /closed or changed/);
  } finally { if (server.listening) await close(server); }
});

test('browser success waits for the host to acknowledge every required callback port', async () => {
  const server = http.createServer((request, response) => { request.resume(); request.on('end', () => response.end(JSON.stringify({accepted: true, forwardedPorts: []}))); });
  const port = await listen(server);
  try {
    await assert.rejects(postBrowser({host: '127.0.0.1', port, token: 'a'.repeat(64)}, {url: 'https://identity.invalid/', requiredPorts: [14555], listeners: []}), /not acknowledged/);
  } finally { await close(server); }
});

test('a real native OAuth process discovers its own listener and excludes unrelated servers before the readiness acknowledgment', {timeout: 25000}, async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-native-browser-'));
  const native = path.join(directory, 'native-oauth-fixture');
  execFileSync('cc', ['-std=c11', '-Wall', '-Wextra', '-Werror', path.join(__dirname, 'tests/native-oauth-fixture.c'), '-o', native]);
  const unrelated = http.createServer((request, response) => response.end('unrelated'));
  const unrelatedPort = await listen(unrelated);
  let engine, received, controllerError;
  const controller = http.createServer(async (request, response) => {
    try {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8')); received = body;
      assert.equal(request.headers.authorization, 'Bearer ' + 'a'.repeat(64));
      assert.equal(body.listenerOwners.length, 1); assert.equal(body.listenerOwners[0].pid, engine.pid);
      assert.equal(body.scope.pid, engine.pid); assert.notEqual(body.opener.pid, engine.pid);
      assert.deepEqual(body.listeners, body.requiredPorts); assert.ok(!body.listeners.includes(unrelatedPort));
      assert.equal(verifyListeners({scope: body.scope, listeners: body.listenerOwners}, {uid: process.getuid()}).verified, true);
      assert.throws(() => verifyListeners({scope: body.opener, listeners: body.listenerOwners}, {uid: process.getuid()}), /outside/);
      await delay(100); assert.equal(engine.exitCode, null);
      response.end(JSON.stringify({accepted: true, forwardedPorts: body.listeners}));
      // Stand-in Android browser, after the host's listener readiness barrier.
      const callback = http.get({host: '127.0.0.1', port: body.requiredPorts[0], path: '/callback?fixture=returned'}, callbackResponse => callbackResponse.resume());
      callback.on('error', error => { controllerError = error; });
    } catch (error) { controllerError = error; response.statusCode = 409; response.end('rejected'); }
  });
  const controlPort = await listen(controller);
  try {
    const configDirectory = path.join(directory, '.config/vstab'); fs.mkdirSync(configDirectory, {recursive: true, mode: 0o700});
    fs.writeFileSync(path.join(configDirectory, 'control.json'), JSON.stringify({host: '127.0.0.1', port: controlPort, token: 'a'.repeat(64)}), {mode: 0o600});
    engine = spawn(native, [process.execPath, path.join(__dirname, 'open-uri.cjs')], {detached: true, env: {...process.env, HOME: directory}, stdio: ['ignore', 'pipe', 'pipe']});
    let output = '', stderr = ''; engine.stdout.on('data', data => output += data); engine.stderr.on('data', data => stderr += data);
    const result = await new Promise((resolve, reject) => { engine.once('error', reject); engine.once('exit', (code, signal) => resolve({code, signal})); });
    if (controllerError) throw controllerError;
    assert.equal(result.code, 0, stderr); assert.equal(result.signal, null); assert.ok(received); assert.match(output, /^PASS: native loopback callback/);
    assert.throws(() => verifyListeners({scope: received.scope, listeners: received.listenerOwners}, {uid: process.getuid()}), /identity/);
  } finally {
    if (engine && engine.exitCode === null) engine.kill('SIGKILL');
    await close(controller); await close(unrelated); fs.rmSync(directory, {recursive: true, force: true});
  }
});
