'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {SessionManager, namespace, validName, dimensions} = require('./extension/session-core.cjs');
function fixture(options = {}) {
  const spawned = [];
  const pty = {spawn(shell, args, config) {
    const child = {pid: 431 + spawned.length, writes: [], sizes: [], killed: false,
      onData(fn) { this.data = fn; }, onExit(fn) { this.exit = fn; },
      write(data) { this.writes.push(data); }, resize(cols, rows) { this.sizes.push([cols, rows]); }, kill() { this.killed = true; }};
    spawned.push({shell, args, config, child}); return child;
  }};
  return {spawned, manager: new SessionManager({pty, shell: '/bin/bash', cwd: '/projects/α', env: {HOME: '/home/test'}, ...options})};
}

test('create/input identities prevent duplicate shells and duplicate command delivery', () => {
  const {manager, spawned} = fixture(), id = crypto.randomUUID(), inputId = crypto.randomUUID();
  manager.create({id, name: 'Build 🌱', cols: 90, rows: 27});
  manager.create({id, name: 'Repeated acknowledgement', cols: 90, rows: 27});
  assert.equal(spawned.length, 1);
  assert.equal(spawned[0].config.cwd, '/projects/α');
  assert.equal(manager.input({sessionId: id, inputId, data: 'make\r'}).delivered, true);
  assert.equal(manager.input({sessionId: id, inputId, data: 'make\r'}).delivered, false);
  assert.deepEqual(spawned[0].child.writes, ['make\r']);
});

test('rename and resize retain process; exit retains transcript; only explicit stop kills', () => {
  const {manager, spawned} = fixture();
  const session = manager.create({name: 'Shell', cols: 80, rows: 24});
  const child = spawned[0].child;
  child.data('hello Ω\r\n'); manager.rename({sessionId: session.id, name: 'Tests'});
  manager.resize({sessionId: session.id, cols: 132, rows: 44});
  assert.equal(manager.list()[0].name, 'Tests'); assert.equal(manager.list()[0].pid, session.pid);
  assert.deepEqual(child.sizes, [[132, 44]]); assert.equal(child.killed, false);
  child.exit({exitCode: 2});
  assert.equal(manager.get(session.id).replay, 'hello Ω\r\n');
  assert.equal(manager.list()[0].exitCode, 2);
  assert.throws(() => manager.input({sessionId: session.id, inputId: crypto.randomUUID(), data: 'x'}), /exited/);
  manager.stop({sessionId: session.id}); assert.deepEqual(manager.list(), []);
});

test('resource bounds, Unicode replay boundaries and invalid requests', () => {
  const {manager, spawned} = fixture({maxSessions: 1, replayLimit: 5});
  const session = manager.create({name: 'Shell', cols: 80, rows: 24});
  spawned[0].child.data('hello🌱abcd');
  assert.equal(manager.get(session.id).replay, 'abcd');
  assert.throws(() => manager.create({name: 'Second'}), /existing session/);
  assert.throws(() => manager.input({sessionId: session.id, inputId: crypto.randomUUID(), data: 'x'.repeat(17000)}), /Invalid terminal input/);
  for (const name of ['', '\nunsafe', 'x'.repeat(81)]) assert.throws(() => validName(name));
  for (const size of [[1, 20], [80, 0], [501, 10], [80.5, 10]]) assert.throws(() => dimensions(...size));
  manager.stop({sessionId: session.id}); assert.equal(spawned[0].child.killed, true);
  assert.notEqual(namespace('/project/a', '/home/x'), namespace('/project/b', '/home/x'));
  assert.notEqual(namespace('/project/a', '/home/x'), namespace('/project/a', '/home/y'));
});
