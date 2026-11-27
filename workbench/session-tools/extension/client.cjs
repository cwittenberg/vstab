'use strict';
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const net = require('node:net');
const {spawn} = require('node:child_process');
const {EventEmitter} = require('node:events');
const {namespace} = require('./session-core.cjs');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

function privateDirectory(workspace, temporary = os.tmpdir()) {
  const directory = path.join(temporary, 'vstab-pty-' + (process.getuid?.() || 0) + '-' + namespace(workspace, os.homedir()));
  fs.mkdirSync(directory, {mode: 0o700, recursive: true});
  const info = fs.lstatSync(directory);
  if (!info.isDirectory() || info.isSymbolicLink() || (process.getuid && info.uid !== process.getuid())) throw new Error('The terminal state directory is not private.');
  fs.chmodSync(directory, 0o700);
  const tokenPath = path.join(directory, 'token');
  try { fs.writeFileSync(tokenPath, crypto.randomBytes(32).toString('hex'), {flag: 'wx', mode: 0o600}); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  const tokenInfo = fs.lstatSync(tokenPath);
  if (!tokenInfo.isFile() || tokenInfo.isSymbolicLink() || (tokenInfo.mode & 0o077) || (process.getuid && tokenInfo.uid !== process.getuid())) throw new Error('The terminal token is not private.');
  return directory;
}

class SessionClient extends EventEmitter {
  constructor({workspace, ptyModule, node = process.env.VSTAB_NODE || process.execPath,
    temporary, daemon = path.join(__dirname, 'daemon.cjs'), timeoutMs = 10000}) {
    super(); this.workspace = fs.realpathSync(workspace); this.ptyModule = ptyModule;
    this.directory = privateDirectory(this.workspace, temporary); this.node = node; this.daemon = daemon;
    this.timeoutMs = timeoutMs; this.sequence = 0; this.pending = new Map(); this.closed = false;
  }
  get socketPath() { return path.join(this.directory, 'session.sock'); }
  async connect() {
    if (this.closed) throw new Error('The terminal connection is closed.');
    if (this.socket && !this.socket.destroyed && this.authenticated) return;
    if (this.connecting) return this.connecting;
    this.connecting = this._ensure().finally(() => { this.connecting = undefined; });
    return this.connecting;
  }
  async _open() {
    if (this.closed) throw new Error('The terminal connection is closed.');
    const socket = net.createConnection(this.socketPath);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => { socket.destroy(); reject(new Error('Terminal connection timed out.')); }, 2000);
      const fail = error => { clearTimeout(timer); reject(error); };
      socket.once('error', fail);
      socket.once('connect', () => { clearTimeout(timer); socket.removeListener('error', fail); resolve(); });
    });
    this.socket = socket; this.authenticated = false; let buffer = '';
    socket.setEncoding('utf8');
    socket.on('error', () => {});
    socket.on('close', () => {
      if (this.socket === socket) { this.authenticated = false; this.socket = undefined; }
      for (const [id, pending] of this.pending) if (pending.socket === socket) {
        clearTimeout(pending.timer); this.pending.delete(id);
        pending.reject(new Error('Terminal connection was lost. Input was not replayed; inspect the session before retrying.'));
      }
      if (!this.closed) this.emit('disconnected');
    });
    socket.on('data', text => {
      buffer += text;
      if (Buffer.byteLength(buffer) > 2 * 1024 * 1024) { socket.destroy(); return; }
      for (;;) {
        const end = buffer.indexOf('\n'); if (end < 0) break;
        const line = buffer.slice(0, end); buffer = buffer.slice(end + 1);
        let message;
        try { message = JSON.parse(line); } catch { socket.destroy(); return; }
        if (message.event) this.emit(message.event, message);
        else {
          const pending = this.pending.get(message.id); if (!pending || pending.socket !== socket) continue;
          clearTimeout(pending.timer); this.pending.delete(message.id);
          if (message.error) pending.reject(new Error(message.error)); else pending.resolve(message.result);
        }
      }
    });
    try {
      await this._request('auth', undefined, {token: fs.readFileSync(path.join(this.directory, 'token'), 'utf8')});
      this.authenticated = true;
    } catch (error) { socket.destroy(); throw error; }
  }
  async _ensure() {
    try { await this._open(); return; } catch (error) {
      if (!['ENOENT', 'ECONNREFUSED'].includes(error.code)) throw error;
    }
    const lock = path.join(this.directory, 'starting');
    const until = Date.now() + 15000;
    let locked = false;
    while (Date.now() < until) {
      try {
        fs.mkdirSync(lock, {mode: 0o700}); locked = true;
        fs.writeFileSync(path.join(lock, 'pid'), String(process.pid), {mode: 0o600}); break;
      } catch (error) {
        if (error.code !== 'EEXIST') throw error;
        try {
          const pid = Number(fs.readFileSync(path.join(lock, 'pid'), 'utf8'));
          try { if (pid > 0) process.kill(pid, 0); }
          catch (probe) { if (probe.code === 'ESRCH') fs.rmSync(lock, {recursive: true, force: true}); }
          if (Date.now() - fs.statSync(lock).mtimeMs > 30000) fs.rmSync(lock, {recursive: true, force: true});
        } catch {}
        await pause(100);
        try { await this._open(); return; } catch (connectError) {
          if (!['ENOENT', 'ECONNREFUSED'].includes(connectError.code)) throw connectError;
        }
      }
    }
    if (!locked) throw new Error('A persistent terminal is taking too long to start. Try again.');
    try {
      try { await this._open(); return; } catch (error) {
        if (!['ENOENT', 'ECONNREFUSED'].includes(error.code)) throw error;
      }
      try {
        const info = fs.lstatSync(this.socketPath);
        if (!info.isSocket() || (process.getuid && info.uid !== process.getuid())) throw new Error('An unexpected file occupies the terminal socket.');
        fs.unlinkSync(this.socketPath);
      } catch (error) { if (error.code !== 'ENOENT') throw error; }
      const child = spawn(this.node, [this.daemon, this.directory, this.workspace, this.ptyModule], {
        detached: true, stdio: 'ignore', env: process.env, cwd: this.workspace
      });
      let spawnError;
      child.on('error', error => { spawnError = error; }); child.unref();
      for (let attempt = 0; attempt < 60; attempt++) {
        if (spawnError) throw spawnError;
        await pause(100);
        try { await this._open(); return; } catch (error) {
          if (!['ENOENT', 'ECONNREFUSED'].includes(error.code)) throw error;
        }
      }
      throw new Error('The persistent terminal could not start. This backend needs a compatible node-pty binding.');
    } finally { fs.rmSync(lock, {recursive: true, force: true}); }
  }
  _request(method, params, extra = {}) {
    const socket = this.socket;
    if (!socket || socket.destroyed) return Promise.reject(new Error('The terminal is disconnected.'));
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error('The terminal operation timed out. Inspect the session before retrying input.')); }, this.timeoutMs);
      this.pending.set(id, {socket, resolve, reject, timer});
      socket.write(JSON.stringify({id, method, params, ...extra}) + '\n');
    });
  }
  request(method, params = {}) {
    if (method === 'input') {
      if (!this.authenticated || !this.socket || this.socket.destroyed) return Promise.reject(new Error('The terminal is disconnected. Input was not queued or replayed.'));
      return this._request(method, params);
    }
    return this.connect().then(() => this._request(method, params));
  }
  close() { this.closed = true; this.socket?.destroy(); }
}
module.exports = {SessionClient, privateDirectory};
