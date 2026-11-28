'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const net = require('node:net');
const dgram = require('node:dgram');
const {Resolver} = require('node:dns/promises');
const {startDnsRelay} = require('./dns-relay.cjs');
const token = 'a'.repeat(64);
function query(id = 123) {
  const name = Buffer.concat(['store', 'invalid'].map(label => Buffer.concat([Buffer.from([label.length]), Buffer.from(label)])));
  const header = Buffer.alloc(12); header.writeUInt16BE(id); header.writeUInt16BE(0x100, 2); header.writeUInt16BE(1, 4);
  return Buffer.concat([header, name, Buffer.from([0, 0, 1, 0, 1])]);
}
function answer(wire) {
  const reply = Buffer.from(wire); reply.writeUInt16BE(0x8180, 2); reply.writeUInt16BE(1, 6);
  return Buffer.concat([reply, Buffer.from([0xc0, 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203, 0, 113, 7])]);
}
async function fixture(t, handler) {
  const server = http.createServer(async (req, res) => {
    assert.equal(req.url, '/dns'); assert.equal(req.headers.authorization, 'Bearer ' + token);
    const parts = []; for await (const chunk of req) parts.push(chunk);
    const wire = Buffer.from(JSON.parse(Buffer.concat(parts)).message, 'base64');
    handler(wire, res);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => { server.closeAllConnections(); server.close(resolve); }));
  return {host: '127.0.0.1', port: server.address().port, token};
}
async function relay(t, options) {
  const instance = await startDnsRelay({listenHost: '127.0.0.1', port: 0, ...options});
  t.after(() => instance.close()); return instance.address().port;
}
function udp(port, wire) {
  return new Promise((resolve, reject) => {
    const socket = dgram.createSocket('udp4'); const timer = setTimeout(() => { socket.close(); reject(Error('DNS timeout')); }, 1500);
    socket.once('message', response => { clearTimeout(timer); socket.close(); resolve(response); });
    socket.send(wire, port, '127.0.0.1');
  });
}
function tcp(port, wire, partial = false) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(port, '127.0.0.1'); const parts = [];
    const timer = setTimeout(() => { socket.destroy(); reject(Error('TCP EOF hang')); }, 1500);
    socket.on('error', reject); socket.on('data', chunk => parts.push(chunk));
    socket.on('end', () => { clearTimeout(timer); resolve(Buffer.concat(parts)); });
    socket.on('connect', () => { const frame = Buffer.alloc(2); frame.writeUInt16BE(wire.length); socket.end(Buffer.concat([frame, partial ? wire.subarray(0, 3) : wire])); });
  });
}
test('stock Node DNS resolver delegates normal UDP A lookup through authenticated bridge', async t => {
  const control = await fixture(t, (wire, res) => res.end(JSON.stringify({message: answer(wire).toString('base64')})));
  const port = await relay(t, {control}); const resolver = new Resolver(); resolver.setServers(['127.0.0.1:' + port]);
  assert.deepEqual(await resolver.resolve4('store.invalid'), ['203.0.113.7']);
});
test('TCP framed query completes after client half-close and truncated EOF closes promptly', async t => {
  const control = await fixture(t, (wire, res) => res.end(JSON.stringify({message: answer(wire).toString('base64')})));
  const port = await relay(t, {control}); const wire = query(); const response = await tcp(port, wire);
  assert.equal(response.readUInt16BE(0), response.length - 2); assert.deepEqual(response.subarray(2), answer(wire));
  assert.equal((await tcp(port, wire, true)).length, 0);
});
test('missing control and offline upstream return bounded SERVFAIL without preventing service start', async t => {
  const port = await relay(t, {}); const response = await udp(port, query(321));
  assert.equal(response.readUInt16BE(0), 321); assert.equal(response.readUInt16BE(2) & 15, 2);
  const control = await fixture(t, () => {}); const offlinePort = await relay(t, {control, timeoutMs: 50});
  const started = Date.now(); const timeout = await udp(offlinePort, query(456));
  assert.equal(timeout.readUInt16BE(0), 456); assert.equal(timeout.readUInt16BE(2) & 15, 2); assert.ok(Date.now() - started < 1000);
});
test('malformed and mismatched upstream responses return SERVFAIL preserving caller ID', async t => {
  for (const invalid of ['not json', JSON.stringify({message: answer(query(999)).toString('base64')})]) {
    const control = await fixture(t, (_wire, res) => res.end(invalid)); const port = await relay(t, {control});
    const response = await udp(port, query(123)); assert.equal(response.readUInt16BE(0), 123); assert.equal(response.readUInt16BE(2) & 15, 2);
  }
});
