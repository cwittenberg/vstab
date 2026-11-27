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
    assert.match(path.basename(directory), /^vstab-pty-v2-/);
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
  assert.equal(two.engineVersion, 2); assert.equal(two.daemonPid, process.pid);
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

test('explicit Stop force-terminates a real HUP/TERM-ignoring shell and its own background job only', {skip: !hasPty, timeout: 15000}, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-session-stop-'));
  const directory = privateDirectory(root, root);
  const daemon = startDaemon({directory, workspace: root, ptyModule, shell: '/bin/bash', idleMs: 60000});
  const unrelated = spawn('/bin/sleep', ['30'], {stdio: 'ignore'});
  let shellPid, jobPid;
  const running = pid => {
    try {
      const stat = fs.readFileSync('/proc/' + pid + '/stat', 'utf8');
      return stat.slice(stat.lastIndexOf(')') + 2).split(' ')[0] !== 'Z';
    } catch { return false; }
  };
  t.after(() => {
    daemon.stop(); unrelated.kill('SIGKILL');
    for (const pid of [shellPid, jobPid]) if (pid && running(pid)) { try { process.kill(pid, 'SIGKILL'); } catch {} }
    fs.rmSync(root, {recursive: true, force: true});
  });
  await until(() => fs.existsSync(path.join(directory, 'session.sock')));
  const client = new SessionClient({workspace: root, ptyModule, temporary: root});
  t.after(() => client.close());
  const session = await client.request('create', {name: 'Ignores hangup', cols: 100, rows: 30});
  shellPid = session.pid;
  await client.request('attach', {sessionId: session.id});
  await client.request('input', {sessionId: session.id, inputId: crypto.randomUUID(),
    data: 'trap "" HUP TERM; sleep 1000 & echo $! > ignored-child.pid; echo READY_FOR_STOP\r'});
  await until(() => fs.existsSync(path.join(root, 'ignored-child.pid')) && /^\d+\s*$/.test(fs.readFileSync(path.join(root, 'ignored-child.pid'), 'utf8')));
  jobPid = Number(fs.readFileSync(path.join(root, 'ignored-child.pid'), 'utf8').trim());
  process.kill(shellPid, 'SIGHUP'); await sleep(50);
  assert.ok(running(shellPid), 'The regression shell must really ignore the old SIGHUP stop.');
  assert.ok(running(jobPid)); assert.ok(running(unrelated.pid));
  const result = await client.request('stop', {sessionId: session.id});
  assert.deepEqual(result, {stopped: true});
  assert.equal(running(shellPid), false, 'A successful Stop reply must follow actual native shell exit.');
  await until(() => !running(jobPid), 1500);
  assert.ok(running(unrelated.pid), 'Unrelated processes of the same user must not be signalled.');
  assert.deepEqual(await client.request('list'), []);
});


test('version-2 namespace leaves legacy socket state untouched and rejects an incompatible engine', {timeout: 10000}, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-engine-version-'));
  const client = new SessionClient({workspace: root, ptyModule, temporary: root});
  const legacy = client.directory.replace('vstab-pty-v2-', 'vstab-pty-');
  fs.mkdirSync(legacy, {mode: 0o700}); fs.writeFileSync(path.join(legacy, 'legacy-job'), 'do not touch');
  const server = net.createServer(socket => {
    socket.setEncoding('utf8'); let buffer = '';
    socket.on('error', () => {});
    socket.on('data', text => { buffer += text; if (buffer.includes('\n')) {
      const request = JSON.parse(buffer.split('\n')[0]);
      socket.write(JSON.stringify({id: request.id, result: {version: 1}}) + '\n');
    }});
  });
  await new Promise(resolve => server.listen(client.socketPath, resolve));
  t.after(async () => { client.close(); await new Promise(resolve => server.close(resolve)); fs.rmSync(root, {recursive: true, force: true}); });
  await assert.rejects(client.request('list'), /engine is incompatible/);
  assert.equal(fs.readFileSync(path.join(legacy, 'legacy-job'), 'utf8'), 'do not touch');
  assert.notEqual(client.directory, legacy);
});
