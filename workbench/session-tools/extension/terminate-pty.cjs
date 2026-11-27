'use strict';
const fs = require('node:fs');

function ownedSession(pid, ownerUid = process.getuid?.()) {
  if (!Number.isSafeInteger(pid) || pid < 2 || ownerUid === undefined) return undefined;
  try {
    if (fs.statSync('/proc/' + pid).uid !== ownerUid) return undefined;
    const stat = fs.readFileSync('/proc/' + pid + '/stat', 'utf8');
    const fields = stat.slice(stat.lastIndexOf(')') + 2).trim().split(/\s+/);
    return {pid, state: fields[0], parent: Number(fields[1]), group: Number(fields[2]), session: Number(fields[3])};
  } catch { return undefined; }
}

/** Kill only processes in this PTY's own OS session; never signal the editor. */
function terminatePty(pty) {
  const shellPid = pty.pid;
  const shell = ownedSession(shellPid);
  // node-pty creates a fresh session for its shell. On Android, /proc may be
  // restricted; the native PTY handle can still terminate its own direct child.
  try {
    if (shell?.session === shellPid) {
      let entries = [];
      try { entries = fs.readdirSync('/proc'); } catch {}
      for (const entry of entries) {
        if (!/^\d+$/.test(entry)) continue;
        const pid = Number(entry);
        if (pid === shellPid) continue;
        const member = ownedSession(pid);
        if (member?.session !== shellPid) continue;
        // Check membership immediately before signalling, including ownership;
        // unrelated app/host processes never qualify for this session cleanup.
        if (ownedSession(pid)?.session === shellPid) {
          try { process.kill(pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
        }
      }
    }
  } finally {
    // Explicit Stop must work even when a shell/job ignores HUP or TERM.
    // Closing an editor tab never calls this path; it only detaches.
    pty.kill('SIGKILL');
  }
}
module.exports = {terminatePty, ownedSession};
