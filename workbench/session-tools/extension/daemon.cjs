'use strict';
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const {SessionManager} = require('./session-core.cjs');
const MAX_LINE = 128 * 1024;
const MAX_PENDING_BYTES = 1024 * 1024;

function startDaemon({directory, workspace, ptyModule, shell = process.env.SHELL || '/bin/bash', idleMs = 300000}) {
  const token = fs.readFileSync(path.join(directory, 'token'), 'utf8');
  const manager = new SessionManager({pty: require(ptyModule), shell, cwd: workspace, env: process.env});
  const clients = new Set();
  const socketPath = path.join(directory, 'session.sock');
  let idle;
  function scheduleIdle() {
    clearTimeout(idle);
    if (!manager.sessions.size) { idle = setTimeout(() => server.close(() => process.exit(0)), idleMs); idle.unref(); }
  }
  function write(client, value) {
    if (client.socket.destroyed) return;
    const data = JSON.stringify(value) + '\n';
    // A stalled editor cannot grow the daemon's memory without bound.
    if (client.socket.writableLength + Buffer.byteLength(data) > MAX_PENDING_BYTES) client.socket.destroy();
    else client.socket.write(data);
  }
  manager.on('data', event => { for (const c of clients) if (c.authenticated && c.attached.has(event.sessionId)) write(c, {event: 'data', ...event}); });
  manager.on('exit', event => { for (const c of clients) if (c.authenticated && c.attached.has(event.sessionId)) write(c, {event: 'exit', ...event}); });
  manager.on('stopped', event => { for (const c of clients) if (c.authenticated) write(c, {event: 'stopped', ...event}); });
  manager.on('changed', () => {
    scheduleIdle();
    for (const c of clients) if (c.authenticated) write(c, {event: 'changed'});
  });
  const server = net.createServer(socket => {
    const client = {socket, authenticated: false, attached: new Set(), buffer: '', usedIds: new Set()};
    clients.add(client); socket.setEncoding('utf8'); socket.setTimeout(10000);
    socket.on('timeout', () => { if (!client.authenticated) socket.destroy(); });
    socket.on('error', () => {});
    socket.on('close', () => clients.delete(client));
    socket.on('data', text => {
      client.buffer += text;
      if (Buffer.byteLength(client.buffer) > MAX_LINE) { socket.destroy(); return; }
      for (;;) {
        const end = client.buffer.indexOf('\n'); if (end < 0) break;
        const line = client.buffer.slice(0, end); client.buffer = client.buffer.slice(end + 1);
        let request;
        try {
          request = JSON.parse(line);
          if (!Number.isSafeInteger(request.id) || request.id < 1 || client.usedIds.has(request.id)) throw new Error('Invalid request identity.');
          client.usedIds.add(request.id);
          if (client.usedIds.size > 4096) client.usedIds.delete(client.usedIds.values().next().value);
          if (!client.authenticated) {
            if (request.method !== 'auth' || typeof request.token !== 'string' ||
                request.token.length !== token.length || !require('node:crypto').timingSafeEqual(Buffer.from(request.token), Buffer.from(token))) {
              socket.destroy(); return;
            }
            client.authenticated = true; socket.setTimeout(0); write(client, {id: request.id, result: {version: 1}}); continue;
          }
          const params = request.params || {};
          let result;
          switch (request.method) {
            case 'list': result = manager.list(); break;
            case 'create': result = manager.create(params); break;
            case 'input': result = manager.input(params); break;
            case 'resize': result = manager.resize(params); break;
            case 'rename': result = manager.rename(params); break;
            case 'stop': result = manager.stop(params); break;
            case 'attach': {
              const session = manager.get(params.sessionId); client.attached.add(session.id);
              write(client, {event: 'data', sessionId: session.id, data: session.replay, replay: true, sequence: session.sequence});
              if (session.exited) write(client, {event: 'exit', sessionId: session.id, exitCode: session.exitCode});
              result = manager.metadata(session); break;
            }
            case 'detach': client.attached.delete(params.sessionId); result = {}; break;
            default: throw new Error('Unsupported terminal operation.');
          }
          write(client, {id: request.id, result});
        } catch (error) { write(client, {id: request?.id, error: String(error.message).slice(0, 400)}); }
      }
    });
  });
  server.on('error', error => { if (require.main === module) process.exitCode = 1; manager.dispose(); });
  server.listen(socketPath, () => { fs.chmodSync(socketPath, 0o600); scheduleIdle(); });
  function stop() { clearTimeout(idle); for (const c of clients) c.socket.destroy(); manager.dispose(); server.close(); }
  return {server, manager, stop};
}
if (require.main === module) {
  const [, , directory, workspace, ptyModule] = process.argv;
  if (!directory || !workspace || !ptyModule) process.exit(2);
  process.on('SIGHUP', () => {});
  const instance = startDaemon({directory, workspace, ptyModule});
  process.on('SIGTERM', () => { instance.stop(); process.exit(0); });
  process.on('SIGINT', () => { instance.stop(); process.exit(0); });
}
module.exports = {startDaemon};
