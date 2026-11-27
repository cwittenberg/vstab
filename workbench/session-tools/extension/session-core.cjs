'use strict';
const {EventEmitter} = require('node:events');
const crypto = require('node:crypto');
const MAX_SESSIONS = 12;
const MAX_REPLAY = 256 * 1024;
const MAX_INPUT = 16 * 1024;

function validName(name) {
  if (typeof name !== 'string' || !name.trim() || name.length > 80 || /[\x00-\x1f\x7f]/.test(name)) {
    throw new Error('Use a terminal name of 1–80 characters without control characters.');
  }
  return name.trim();
}
function dimensions(cols = 100, rows = 30) {
  if (!Number.isInteger(cols) || !Number.isInteger(rows) || cols < 2 || cols > 500 || rows < 1 || rows > 200) {
    throw new Error('Terminal dimensions must be 2–500 columns and 1–200 rows.');
  }
  return {cols, rows};
}
function validId(value) { return typeof value === 'string' && /^[0-9a-f-]{36}$/.test(value); }
function namespace(workspace, home) {
  return crypto.createHash('sha256').update(home + '\0' + workspace).digest('hex').slice(0, 24);
}

/** Owns PTYs independently of editor/extension connections. Never reruns commands. */
class SessionManager extends EventEmitter {
  constructor({pty, shell, cwd, env, maxSessions = MAX_SESSIONS, replayLimit = MAX_REPLAY}) {
    super();
    this.pty = pty; this.shell = shell; this.cwd = cwd; this.env = env;
    this.sessions = new Map(); this.maxSessions = maxSessions; this.replayLimit = replayLimit;
  }
  metadata(session) {
    return {id: session.id, name: session.name, pid: session.pty.pid, cwd: this.cwd,
      createdAt: session.createdAt, cols: session.cols, rows: session.rows,
      exited: session.exited, exitCode: session.exitCode, sequence: session.sequence};
  }
  list() { return [...this.sessions.values()].map(s => this.metadata(s)); }
  get(id) {
    const session = this.sessions.get(id);
    if (!session) throw new Error('This terminal no longer exists. Create a new session to run a new shell.');
    return session;
  }
  create({id = crypto.randomUUID(), name, cols, rows}) {
    if (!validId(id)) throw new Error('Invalid terminal identity.');
    // A lost create acknowledgement must not cause a second shell.
    if (this.sessions.has(id)) return this.metadata(this.sessions.get(id));
    if (this.sessions.size >= this.maxSessions) throw new Error('Close an existing session before creating another persistent terminal.');
    name = validName(name);
    const size = dimensions(cols, rows);
    const child = this.pty.spawn(this.shell, ['-l'], {name: 'xterm-256color', cwd: this.cwd,
      env: {...this.env, TERM: 'xterm-256color', VSTAB_SESSION_ID: id}, ...size});
    const session = {id, name, pty: child, ...size, createdAt: Date.now(), replay: '', sequence: 0,
      inputs: new Set(), exited: false, exitCode: null};
    this.sessions.set(id, session);
    child.onData(data => {
      session.sequence++;
      session.replay = (session.replay + data).slice(-this.replayLimit);
      // Avoid splitting a UTF-16 surrogate pair at the replay boundary.
      if (/^[\uDC00-\uDFFF]/.test(session.replay)) session.replay = session.replay.slice(1);
      this.emit('data', {sessionId: id, data, sequence: session.sequence});
    });
    child.onExit(({exitCode}) => {
      session.exited = true; session.exitCode = exitCode;
      this.emit('exit', {sessionId: id, exitCode}); this.emit('changed');
    });
    this.emit('changed');
    return this.metadata(session);
  }
  input({sessionId, inputId, data}) {
    const session = this.get(sessionId);
    if (session.exited) throw new Error('This terminal has exited.');
    if (!validId(inputId) || typeof data !== 'string' || Buffer.byteLength(data) > MAX_INPUT) throw new Error('Invalid terminal input.');
    if (session.inputs.has(inputId)) return {delivered: false};
    session.inputs.add(inputId);
    if (session.inputs.size > 2048) session.inputs.delete(session.inputs.values().next().value);
    session.pty.write(data);
    return {delivered: true};
  }
  resize({sessionId, cols, rows}) {
    const session = this.get(sessionId), size = dimensions(cols, rows);
    if (!session.exited) session.pty.resize(size.cols, size.rows);
    Object.assign(session, size); return this.metadata(session);
  }
  rename({sessionId, name}) {
    const session = this.get(sessionId); session.name = validName(name);
    this.emit('changed'); return this.metadata(session);
  }
  stop({sessionId}) {
    const session = this.sessions.get(sessionId);
    if (!session) return {stopped: true};
    this.sessions.delete(sessionId);
    if (!session.exited) session.pty.kill();
    this.emit('stopped', {sessionId}); this.emit('changed'); return {stopped: true};
  }
  dispose() { for (const id of [...this.sessions.keys()]) this.stop({sessionId: id}); }
}
module.exports = {SessionManager, namespace, validName, validId, dimensions, MAX_INPUT};
