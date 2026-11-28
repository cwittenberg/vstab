'use strict';
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const LOOPBACK = new Set(['localhost', '127.0.0.1', '[::1]', '::1']);
const MAX_LISTENERS = 32;

function processInfo(pid, {procRoot = '/proc'} = {}) {
  if (!Number.isSafeInteger(pid) || pid < 1) return null;
  try {
    const stat = fs.readFileSync(path.join(procRoot, String(pid), 'stat'), 'utf8');
    const end = stat.lastIndexOf(')');
    const fields = stat.slice(end + 2).trim().split(/\s+/);
    const status = fs.readFileSync(path.join(procRoot, String(pid), 'status'), 'utf8');
    const uids = /^Uid:\s+(\d+)\s+(\d+)\s+(\d+)\s+(\d+)/m.exec(status);
    if (end < 0 || fields.length < 20 || !uids || !/^\d+$/.test(fields[19])) return null;
    return {pid, ppid: Number(fields[1]), session: Number(fields[3]), startTime: fields[19], uid: Number(uids[2])};
  } catch (error) { if (['ENOENT', 'ESRCH', 'EACCES', 'EPERM'].includes(error.code)) return null; throw error; }
}
function processTable(options = {}) {
  const result = new Map();
  for (const name of fs.readdirSync(options.procRoot || '/proc')) if (/^[1-9]\d*$/.test(name)) {
    const info = processInfo(Number(name), options); if (info) result.set(info.pid, info);
    if (result.size > 16384) throw Error('The Linux process inventory exceeds its bound.');
  }
  return result;
}
function descendant(pid, ancestor, processes) {
  const seen = new Set();
  while (pid > 0 && !seen.has(pid)) { if (pid === ancestor) return true; seen.add(pid); pid = processes.get(pid)?.ppid || 0; }
  return false;
}
function loopbackAddress(encoded, ipv6) {
  if (!new RegExp(ipv6 ? '^[0-9A-Fa-f]{32}$' : '^[0-9A-Fa-f]{8}$').test(encoded)) return null;
  const bytes = Buffer.from(encoded, 'hex');
  for (let i = 0; i < bytes.length; i += 4) bytes.subarray(i, i + 4).reverse();
  if (!ipv6) return bytes.every(n => n === 0) || bytes[0] === 127 ? '127.0.0.1' : null;
  if (bytes.every(n => n === 0) || bytes.subarray(0, 15).every(n => n === 0) && bytes[15] === 1) return '::1';
  if (bytes.subarray(0, 10).every(n => n === 0) && bytes[10] === 255 && bytes[11] === 255 && bytes[12] === 127) return '127.0.0.1';
  return null;
}
function networkListeners(uid, {procRoot = '/proc'} = {}) {
  const result = new Map();
  for (const family of ['tcp', 'tcp6']) {
    let text; try { text = fs.readFileSync(path.join(procRoot, 'net', family), 'utf8'); }
    catch (error) { if (error.code === 'ENOENT' && family === 'tcp6') continue; throw error; }
    for (const line of text.split('\n').slice(1)) {
      const fields = line.trim().split(/\s+/); if (fields.length < 10 || fields[3] !== '0A' || Number(fields[7]) !== uid) continue;
      const [address, encodedPort] = fields[1].split(':'); const host = loopbackAddress(address, family === 'tcp6'), port = parseInt(encodedPort, 16);
      if (!host || port < 1024 || port > 65535 || !/^\d+$/.test(fields[9])) continue;
      result.set(fields[9], {port, host, inode: fields[9]});
    }
  }
  return result;
}
function ownedListeners(processes, candidates, uid, options = {}) {
  const network = networkListeners(uid, options), result = [];
  for (const pid of candidates) {
    const info = processes.get(pid); if (!info || info.uid !== uid) continue;
    let descriptors; try { descriptors = fs.readdirSync(path.join(options.procRoot || '/proc', String(pid), 'fd')); }
    catch (error) { if (['ENOENT', 'ESRCH', 'EACCES', 'EPERM'].includes(error.code)) continue; throw error; }
    if (descriptors.length > 16384) throw Error('The callback process descriptor inventory exceeds its bound.');
    for (const fd of descriptors) {
      let target; try { target = fs.readlinkSync(path.join(options.procRoot || '/proc', String(pid), 'fd', fd)); }
      catch (error) { if (['ENOENT', 'ESRCH', 'EACCES', 'EPERM'].includes(error.code)) continue; throw error; }
      const inode = /^socket:\[(\d+)\]$/.exec(target)?.[1], listener = network.get(inode);
      if (!listener || options.ports?.length && !options.ports.includes(listener.port) || result.some(item => item.pid === pid && item.inode === inode)) continue;
      result.push({...listener, pid, startTime: info.startTime, uid});
      if (result.length > MAX_LISTENERS) throw Error('Too many browser callback listeners.');
    }
  }
  return result;
}
function selectPorts(listeners, ports) { return ports.length ? listeners.filter(listener => ports.includes(listener.port)) : listeners; }
function sessionListeners(backendPid, ports = [], options = {}) {
  const uid = options.uid ?? 1000, processes = processTable(options), scope = processes.get(backendPid);
  if (!scope || scope.uid !== uid) throw Error('The Linux backend process is no longer available.');
  const candidates = [...processes.values()].filter(info => info.uid === uid && descendant(info.pid, backendPid, processes)).map(info => info.pid);
  return {scope: {pid: scope.pid, startTime: scope.startTime, uid}, listeners: selectPorts(ownedListeners(processes, candidates, uid, {...options, ports}), ports)};
}
function openerListeners(openerPid, ports = [], options = {}) {
  const uid = options.uid ?? 1000, processes = processTable(options), opener = processes.get(openerPid);
  if (!opener || opener.uid !== uid) throw Error('The browser opener process is no longer available.');
  let pid = opener.ppid; const seen = new Set();
  while (pid > 1 && !seen.has(pid)) {
    seen.add(pid); const info = processes.get(pid);
    if (!info || info.uid !== uid || info.session !== opener.session) break;
    // A detached engine can itself lead the session. Inspect its own sockets,
    // then stop: an interactive session's sibling jobs are unrelated callers.
    const boundary = pid === opener.session;
    const candidates = ports.length && !boundary ? [...processes.values()].filter(item => item.uid === uid && descendant(item.pid, pid, processes)).map(item => item.pid) : [pid];
    const listeners = selectPorts(ownedListeners(processes, candidates, uid, {...options, ports}), ports);
    if (listeners.length) return {scope: {pid, startTime: info.startTime, uid}, listeners};
    if (boundary) break;
    pid = info.ppid;
  }
  return {scope: {pid: opener.pid, startTime: opener.startTime, uid}, listeners: []};
}
function verifyListeners(value, options = {}) {
  const uid = options.uid ?? 1000;
  if (!value?.scope || !Array.isArray(value.listeners) || value.listeners.length > MAX_LISTENERS || value.scope.uid !== uid) throw Error('Invalid listener ownership record.');
  const processes = processTable(options), scope = processes.get(value.scope.pid);
  if (!scope || scope.uid !== uid || scope.startTime !== String(value.scope.startTime)) throw Error('The listener process identity changed.');
  const owners = new Set();
  for (const listener of value.listeners) {
    const process = processes.get(listener.pid);
    if (!process || process.uid !== uid || process.startTime !== String(listener.startTime) || !descendant(listener.pid, scope.pid, processes)) throw Error('The callback listener is outside its owning process tree.');
    owners.add(listener.pid);
  }
  const live = ownedListeners(processes, owners, uid, {...options, ports: value.listeners.map(listener => listener.port)});
  for (const listener of value.listeners) if (!live.some(item => item.pid === listener.pid && item.startTime === String(listener.startTime)
      && item.inode === String(listener.inode) && item.port === listener.port && item.host === listener.host)) throw Error('The callback listener closed or changed.');
  return {verified: true, scope: value.scope, listeners: value.listeners};
}
function requiredPorts(address) {
  const url = address instanceof URL ? address : new URL(address), result = new Set();
  const add = value => { const parsed = value instanceof URL ? value : new URL(value);
    if (['http:', 'https:'].includes(parsed.protocol) && LOOPBACK.has(parsed.hostname)) {
      const port = Number(parsed.port || (parsed.protocol === 'https:' ? 443 : 80));
      if (port < 1024 || port > 65535) throw Error('The callback requires a privileged loopback port.'); result.add(port);
    }
  };
  add(url);
  const redirects = url.searchParams.getAll('redirect_uri'); if (redirects.length > 1) throw Error('The browser address has ambiguous redirect parameters.');
  if (redirects.length) { try { add(redirects[0]); } catch (error) { if (error instanceof TypeError) return [...result]; throw error; } }
  return [...result];
}
async function postBrowser(configuration, message) {
  const body = Buffer.from(JSON.stringify(message)); if (body.length > 64 * 1024) throw Error('The browser request is too large.');
  return new Promise((resolve, reject) => {
    const request = http.request({host: configuration.host, port: configuration.port, method: 'POST', path: '/browser',
      headers: {'Content-Type': 'application/json', 'Content-Length': body.length, Authorization: 'Bearer ' + configuration.token}}, response => {
      const parts = []; let length = 0;
      response.on('data', data => { length += data.length; if (length > 65536) response.destroy(Error('The browser acknowledgment is too large.')); else parts.push(data); });
      response.on('error', reject);
      response.on('end', () => {
        if (response.statusCode !== 200) { reject(Error('The Android browser bridge rejected the request (' + response.statusCode + ').')); return; }
        let result; try { result = JSON.parse(Buffer.concat(parts).toString('utf8')); } catch { reject(Error('The browser acknowledgment is invalid.')); return; }
        if (result.accepted !== true) { reject(Error('The Android browser did not accept this request.')); return; }
        const expected = new Set([...(message.listeners || []), ...(message.requiredPorts || [])]);
        if ([...expected].some(port => !Array.isArray(result.forwardedPorts) || !result.forwardedPorts.includes(port))) { reject(Error('The browser callback ports were not acknowledged.')); return; }
        resolve(result);
      });
    });
    request.setTimeout(30000, () => request.destroy(Error('The Android browser bridge did not acknowledge the request.')));
    request.on('error', reject); request.end(body);
  });
}
function portsArgument(value) {
  if (!value) return []; const ports = value.split(',').map(Number);
  if (ports.length > MAX_LISTENERS || ports.some(port => !Number.isInteger(port) || port < 1024 || port > 65535)) throw Error('Invalid callback port filter.'); return [...new Set(ports)];
}
async function main() {
  if (['--list-session-listeners', '--discover-listeners'].includes(process.argv[2])) {
    if (process.argv.length < 4 || process.argv.length > 5 || !/^[1-9]\d*$/.test(process.argv[3])) throw Error('Invalid listener inventory request.');
    const discover = process.argv[2] === '--list-session-listeners' ? sessionListeners : openerListeners;
    console.log(JSON.stringify(discover(Number(process.argv[3]), portsArgument(process.argv[4])))); return;
  }
  if (process.argv[2] === '--verify-listeners') {
    if (process.argv.length !== 4 || process.argv[3].length > 65536 || !/^[A-Za-z0-9+/]+={0,2}$/.test(process.argv[3])) throw Error('Invalid listener verification request.');
    console.log(JSON.stringify(verifyListeners(JSON.parse(Buffer.from(process.argv[3], 'base64').toString('utf8'))))); return;
  }
  const address = process.argv[2];
  if (!address || process.argv.length !== 3) throw Error('Usage: xdg-open <http-or-https-url>');
  const url = new URL(address);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw Error('The Android browser bridge accepts HTTP and HTTPS addresses without embedded credentials.');
  const filename = path.join(process.env.HOME, '.config/vstab/control.json'), stat = fs.lstatSync(filename);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.uid !== process.getuid() || (stat.mode & 0o077)) throw Error('The browser bridge configuration must be private.');
  const configuration = JSON.parse(fs.readFileSync(filename, 'utf8'));
  if (!['10.0.2.2', '127.0.0.1'].includes(configuration.host) || !Number.isInteger(configuration.port) || configuration.port < 1 || configuration.port > 65535
      || !/^[a-f0-9]{64}$/.test(configuration.token)) throw Error('The Android browser bridge is not configured.');
  const opener = processInfo(process.pid), ports = requiredPorts(url), discovered = openerListeners(process.pid, ports, {uid: process.getuid()});
  await postBrowser(configuration, {url: url.href, listeners: [...new Set(discovered.listeners.map(listener => listener.port))],
    listenerOwners: discovered.listeners, scope: discovered.scope, opener, requiredPorts: ports});
}
module.exports = {processInfo, processTable, loopbackAddress, networkListeners, ownedListeners, descendant,
  sessionListeners, openerListeners, verifyListeners, requiredPorts, postBrowser};
if (require.main === module) main().catch(error => { console.error('xdg-open: ' + error.message); process.exitCode = 1; });
