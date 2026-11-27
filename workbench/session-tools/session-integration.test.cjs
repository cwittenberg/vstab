'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const crypto = require('node:crypto');
const {spawn} = require('node:child_process');
const {SessionClient, privateDirectory} = require('./extension/client.cjs');
const {startDaemon} = require('./extension/daemon.cjs');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, timeout = 10000) {
  const end = Date.now() + timeout;
  while (Date.now() < end) { if (await predicate()) return; await sleep(30); }
  throw new Error('Timed out waiting for real PTY evidence.');
}
const ptyModule = process.env.VSTAB_TEST_PTY_MODULE || path.resolve(__dirname, '../../.local/android-runtime/addon-sources/node-pty');
const hasPty = fs.existsSync(path.join(ptyModule, 'package.json'));

test('private state refuses redirected token files', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-session-private-'));
  try {
    const directory = privateDirectory(root, root);
    const tokenPath = path.join(directory, 'token');
    fs.unlinkSync(tokenPath); fs.symlinkSync(path.join(root, 'elsewhere'), tokenPath);
    assert.throws(() => privateDirectory(root, root), /not private/);
  } finally { fs.rmSync(root, {recursive: true, force: true}); }
});

test('real PTY survives client teardown; replay, resize, UTF-8 and command-once are preserved', {skip: !hasPty, timeout: 30000}, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-session-real-'));
  const directory = privateDirectory(root, root);
  const daemon = startDaemon({directory, workspace: root, ptyModule, shell: '/bin/bash', idleMs: 60000});
  t.after(() => { daemon.stop(); fs.rmSync(root, {recursive: true, force: true}); });
  await until(() => fs.existsSync(path.join(directory, 'session.sock')));
  const one = new SessionClient({workspace: root, ptyModule, temporary: root});
  t.after(() => one.close());
  let output = '';
  one.on('data', event => { output += event.data; });
  const id = crypto.randomUUID();
  const session = await one.request('create', {id, name: 'Build 🌱', cols: 90, rows: 27});
  await one.request('attach', {sessionId: id});
  const inputId = crypto.randomUUID();
  const data = 'printf "ONCE Ω🌱\\n" >> once.txt; sleep .2; printf "DETACHED_OUTPUT\\n"\r';
  await one.request('input', {sessionId: id, inputId, data});
  await one.request('input', {sessionId: id, inputId, data});
  one.close();
  // The shell keeps running while no editor client exists.
  await until(() => fs.existsSync(path.join(root, 'once.txt')));
  await sleep(300);
  const two = new SessionClient({workspace: root, ptyModule, temporary: root});
  t.after(() => two.close());
  let restored = '';
  two.on('data', event => { restored += event.data; });
  const list = await two.request('list');
  assert.equal(list.length, 1); assert.equal(list[0].pid, session.pid);
  await two.request('attach', {sessionId: id});
  await until(() => restored.includes('DETACHED_OUTPUT'));
  await two.request('resize', {sessionId: id, cols: 132, rows: 44});
  await two.request('input', {sessionId: id, inputId: crypto.randomUUID(), data: 'stty size; printf "UTF8_Ω🌱\\n"\r'});
  await until(() => restored.includes('44 132') && restored.includes('UTF8_Ω🌱'));
  assert.equal(fs.readFileSync(path.join(root, 'once.txt'), 'utf8'), 'ONCE Ω🌱\n');
  await two.request('rename', {sessionId: id, name: 'Renamed build'});
  assert.equal((await two.request('list'))[0].name, 'Renamed build');
  assert.equal((await two.request('list'))[0].pid, session.pid);
  await two.request('stop', {sessionId: id});
  assert.deepEqual(await two.request('list'), []);
});

test('daemon authenticates each socket and input is rejected while disconnected', {skip: !hasPty, timeout: 10000}, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-session-auth-'));
  const directory = privateDirectory(root, root);
  const daemon = startDaemon({directory, workspace: root, ptyModule, idleMs: 60000});
  t.after(() => { daemon.stop(); fs.rmSync(root, {recursive: true, force: true}); });
  await until(() => fs.existsSync(path.join(directory, 'session.sock')));
  const socket = net.createConnection(path.join(directory, 'session.sock'));
  await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
  socket.write(JSON.stringify({id: 1, method: 'list'}) + '\n');
  await new Promise(resolve => socket.once('close', resolve));
  const client = new SessionClient({workspace: root, ptyModule, temporary: root});
  await assert.rejects(client.request('input', {sessionId: crypto.randomUUID(), inputId: crypto.randomUUID(), data: 'dangerous\r'}), /not queued or replayed/);
  assert.deepEqual(await client.request('list'), []); client.close();
});

test('detached daemon remains alive between independent client processes', {skip: !hasPty, timeout: 30000}, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-session-daemon-'));
  const directory = privateDirectory(root, root);
  const child = spawn(process.execPath, [path.join(__dirname, 'extension/daemon.cjs'), directory, root, ptyModule], {detached: true, stdio: 'ignore'});
  child.unref();
  t.after(() => { try { process.kill(child.pid, 'SIGTERM'); } catch {} fs.rmSync(root, {recursive: true, force: true}); });
  await until(() => fs.existsSync(path.join(directory, 'session.sock')));
  const client = new SessionClient({workspace: root, ptyModule, temporary: root});
  const session = await client.request('create', {name: 'Detached process', cols: 80, rows: 24}); client.close();
  const next = new SessionClient({workspace: root, ptyModule, temporary: root});
  t.after(() => next.close());
  assert.equal((await next.request('list'))[0].pid, session.pid);
  await next.request('stop', {sessionId: session.id});
});
