'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const http = require('node:http');
const net = require('node:net');
const {growRootDisk} = require('./disk-growth.cjs');
const {startDnsRelay} = require('./dns-relay.cjs');
const {spawn, spawnSync} = require('node:child_process');
const HOME = '/home/developer';
const NODE = '/opt/code-server/lib/node';
const children = new Set();
let stopping = false;
let dnsRelay;
function fail(code) { console.error('VSTAB_LINUX_ERROR ' + JSON.stringify({schema: 1, code})); }
function run(command, args, input) {
  const result = spawnSync(command, args, {input, encoding: 'utf8', stdio: [input === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe']});
  if (result.status !== 0) throw Error('guest_setup_failed:' + path.basename(command));
  return result.stdout;
}
function write(filename, value, mode = 0o600, owner = false) {
  fs.mkdirSync(path.dirname(filename), {recursive: true, mode: 0o700});
  fs.writeFileSync(filename, value, {mode}); fs.chmodSync(filename, mode);
  if (owner) fs.chownSync(filename, 1000, 1000);
}
function decode(value, required = false) {
  if (!value) { if (required) throw Error('bootstrap_value_missing'); return ''; }
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(value) || value.length > 2 * 1024 * 1024) throw Error('bootstrap_value_invalid');
  const decoded = Buffer.from(value, 'base64').toString('utf8');
  if (decoded.includes('\0')) throw Error('bootstrap_value_invalid');
  return decoded;
}
function number(value) {
  if (!/^\d+$/.test(value || '')) return 0;
  const result = Number(value); return result > 0 && result < 65536 ? result : 0;
}
function launch(command, args, options = {}) {
  const child = spawn(command, args, options); children.add(child);
  child.on('error', () => { if (!stopping) fail('service_start_failed:' + path.basename(command)); });
  child.on('exit', () => { children.delete(child); if (!stopping) fail('service_stopped:' + path.basename(command)); });
  return child;
}
function healthy(password) {
  return new Promise(resolve => {
    const body = 'password=' + encodeURIComponent(password);
    const request = http.request({host: '127.0.0.1', port: 3000, path: '/login', method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded', 'Content-Length': Buffer.byteLength(body)}}, response => {
      response.resume(); response.on('end', () => {
        if (response.statusCode !== 302 || !response.headers['set-cookie']?.length) return resolve(false);
        const cookie = response.headers['set-cookie'].map(value => value.split(';', 1)[0]).join('; ');
        resolve(workbenchHealthy(cookie));
      });
    });
    request.setTimeout(2000, () => request.destroy()); request.on('error', () => resolve(false)); request.end(body);
  });
}
function workbenchHealthy(cookie, target = '/', redirects = 0) {
  return new Promise(resolve => {
    const workbench = http.get({host: '127.0.0.1', port: 3000, path: target, headers: {Cookie: cookie}}, page => {
      if (page.statusCode === 302 && page.headers.location && redirects < 4) {
        page.resume();
        const next = new URL(page.headers.location, 'http://127.0.0.1:3000' + target);
        if (next.origin !== 'http://127.0.0.1:3000') return resolve(false);
        return resolve(workbenchHealthy(cookie, next.pathname + next.search, redirects + 1));
      }
      let html = '';
      page.on('data', chunk => { html += chunk; if (html.length > 2 * 1024 * 1024) workbench.destroy(); });
      page.on('end', () => resolve(page.statusCode === 200 && html.includes('vscode-workbench-web-configuration')));
    });
    workbench.setTimeout(10000, () => workbench.destroy()); workbench.on('error', () => resolve(false));
  });
}
function sshHealthy() {
  return new Promise(resolve => {
    const socket = net.createConnection({host: '127.0.0.1', port: 22});
    let received = '';
    const finish = value => { socket.destroy(); resolve(value); };
    socket.setTimeout(2000, () => finish(false));
    socket.on('error', () => finish(false));
    socket.on('data', chunk => {
      received += chunk.toString('ascii');
      if (received.includes('\n')) finish(/^SSH-2\.0-OpenSSH_/.test(received));
      else if (received.length > 1024) finish(false);
    });
  });
}
function watchStopRequest(environmentId, stopGuest = stop, options = {}) {
  const filename = options.filename || '/run/vstab-bootstrap/stop-request';
  const timer = setInterval(() => {
    if (stopping) return;
    try {
      const stat = fs.lstatSync(filename);
      if (!stat.isFile() || stat.size !== Buffer.byteLength(environmentId)) return;
      if (fs.readFileSync(filename, 'utf8') === environmentId) void stopGuest();
    } catch (error) {
      if (!['ENOENT', 'ESTALE', 'EIO'].includes(error.code)) fail('stop_request_read_failed');
    }
  }, options.intervalMs || 1000);
  timer.unref();
  return timer;
}
async function main() {
  const source = fs.readFileSync('/run/vstab-bootstrap/bootstrap.env', 'utf8');
  if (Buffer.byteLength(source) > 2 * 1024 * 1024) throw Error('bootstrap_too_large');
  const config = Object.create(null);
  for (const line of source.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const index = line.indexOf('='); if (index < 1) throw Error('bootstrap_line_invalid');
    const key = line.slice(0, index), value = line.slice(index + 1);
    if (!/^VSTAB_[A-Z0-9_]+$/.test(key) || Object.hasOwn(config, key)) throw Error('bootstrap_key_invalid');
    config[key] = value;
  }
  const environmentId = decode(config.VSTAB_ENVIRONMENT_ID_B64, true);
  watchStopRequest(environmentId);
  const publicKey = decode(config.VSTAB_AUTHORIZED_KEY_B64, true).trim();
  if (!/^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(?:256|384|521)) [A-Za-z0-9+/=]+(?: [^\r\n]+)?$/.test(publicKey)) throw Error('bootstrap_key_invalid');
  const password = decode(config.VSTAB_EDITOR_PASSWORD_B64, true);
  if (password.length < 16 || password.length > 4096 || /[\r\n]/.test(password)) throw Error('bootstrap_password_invalid');
  const rootDisk = growRootDisk();
  // Inbound machine authentication is separate from the user's shared SSH data.
  fs.chownSync(HOME, 1000, 1000);
  write('/run/vstab/machine-authorized-keys', publicKey + '\n', 0o644);
  // Unlock the account using an unknown random password; SSH password and
  // keyboard-interactive authentication remain disabled independently.
  run('/usr/sbin/chpasswd', [], 'developer:' + crypto.randomBytes(48).toString('base64') + '\n');
  run('/usr/bin/ssh-keygen', ['-A']);
  write('/etc/ssh/sshd_config', ['Port 22', 'ListenAddress 0.0.0.0', 'ListenAddress ::',
    'HostKey /etc/ssh/ssh_host_ed25519_key', 'PermitRootLogin no', 'PasswordAuthentication no',
    'KbdInteractiveAuthentication no', 'PubkeyAuthentication yes', 'UsePAM no', 'PermitEmptyPasswords no',
    'AllowUsers developer', 'AuthorizedKeysFile /run/vstab/machine-authorized-keys', 'AllowTcpForwarding yes',
    'GatewayPorts no', 'Subsystem sftp internal-sftp', 'PrintMotd no', 'LogLevel ERROR', ''].join('\n'));
  const control = {host: config.VSTAB_CONTROL_HOST || '10.0.2.2', port: number(config.VSTAB_CONTROL_PORT), token: decode(config.VSTAB_CONTROL_TOKEN_B64)};
  fs.mkdirSync(HOME + '/.config/vstab', {recursive: true, mode: 0o700});
  fs.chownSync(HOME + '/.config', 1000, 1000); fs.chownSync(HOME + '/.config/vstab', 1000, 1000);
  write(HOME + '/.config/vstab/control.json', JSON.stringify(control), 0o600, true);
  dnsRelay = await startDnsRelay({control});
  write('/etc/resolv.conf', 'nameserver 127.0.0.53\noptions timeout:15 attempts:1\n', 0o644);
  fs.mkdirSync(HOME + '/workspaces/default', {recursive: true});
  run('/bin/chown', ['-R', '1000:1000', HOME + '/workspaces']);
  if (!fs.existsSync(HOME + '/workspaces/default/README.md')) write(HOME + '/workspaces/default/README.md', '# VStab Linux workspace\n\nThis project runs in the local Linux guest.\n', 0o644, true);
  if (config.VSTAB_FILES_CONFIG_B64) {
    const filesConfig = decode(config.VSTAB_FILES_CONFIG_B64, true);
    JSON.parse(filesConfig); write('/run/vstab/files.json', filesConfig);
    if (!fs.existsSync('/usr/local/bin/vstab-android-files')) throw Error('filesystem_client_missing');
    const files = launch('/usr/local/bin/vstab-android-files', ['--config', '/run/vstab/files.json', '/mnt/android', '-f', '-o', 'allow_other,default_permissions'], {stdio: ['ignore', 'ignore', 'inherit']});
    let mounted = false;
    for (let attempt = 0; attempt < 100; attempt++) {
      if (files.exitCode !== null) throw Error('filesystem_mount_failed');
      if (fs.readFileSync('/proc/self/mountinfo', 'utf8').split('\n').some(line => line.includes(' /mnt/android ') && line.includes(' - fuse'))) { mounted = true; break; }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    if (!mounted) throw Error('filesystem_mount_not_ready');
    const sharedSsh = '/mnt/android/app/ssh';
    if (fs.existsSync(sharedSsh)) {
      let current;
      try { current = fs.lstatSync(HOME + '/.ssh'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
      if (current && !current.isSymbolicLink()) {
        // Preserve any earlier guest-owned identities rather than overwriting them.
        fs.renameSync(HOME + '/.ssh', HOME + '/.ssh-guest-before-android-' + Date.now());
      } else if (current && fs.readlinkSync(HOME + '/.ssh') !== sharedSsh) {
        throw Error('ssh_directory_link_conflict');
      }
      if (!current || !current.isSymbolicLink()) fs.symlinkSync(sharedSsh, HOME + '/.ssh');
    }
  }
  // With no Android SSH grant, a fresh guest still has an ordinary private
  // directory. Preserve a revoked shared link without replacing the user's data.
  let sshDirectory;
  try { sshDirectory = fs.lstatSync(HOME + '/.ssh'); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (!sshDirectory) {
    fs.mkdirSync(HOME + '/.ssh', {mode: 0o700}); fs.chownSync(HOME + '/.ssh', 1000, 1000);
  }
  const log = fs.openSync('/var/log/vstab-code-server.log', 'a', 0o600);
  write('/run/vstab/code-server.yaml', 'bind-addr: 0.0.0.0:3000\nauth: password\npassword: ' + JSON.stringify(password) + '\ncert: false\ndisable-telemetry: true\ndisable-update-check: true\n', 0o600, true);
  fs.chmodSync('/run/vstab', 0o755);
  const server = launch('/opt/code-server/bin/code-server', ['--config', '/run/vstab/code-server.yaml',
    '--user-data-dir', HOME + '/.local/share/vstab/user-data', '--extensions-dir', HOME + '/.local/share/vstab/extensions', HOME + '/workspaces/default'],
    {uid: 1000, gid: 1000, cwd: HOME, env: {HOME, USER: 'developer', LOGNAME: 'developer', SHELL: '/bin/bash',
      PATH: '/usr/local/bin:/usr/bin:/bin', LANG: 'C.UTF-8', TERM: 'xterm-256color', BROWSER: '/usr/local/bin/xdg-open'}, stdio: ['ignore', log, log]});
  const sshd = launch('/usr/sbin/sshd', ['-D', '-e'], {stdio: ['ignore', 'ignore', 'inherit']});
  let ready = false;
  for (let attempt = 0; attempt < 120; attempt++) {
    if (server.exitCode !== null || sshd.exitCode !== null) throw Error('service_start_failed');
    if (await healthy(password) && await sshHealthy()) { ready = true; break; }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  if (!ready) throw Error('editor_not_ready');
  const [type, key] = fs.readFileSync('/etc/ssh/ssh_host_ed25519_key.pub', 'utf8').trim().split(/\s+/);
  const manifest = JSON.parse(fs.readFileSync('/usr/local/share/vstab/guest-build.json', 'utf8'));
  console.log('VSTAB_LINUX_READY ' + JSON.stringify({schema: 1, environmentId, sshPort: 22, editorPort: 3000,
    sshHostKey: {type, key}, backendPid: server.pid, codeVersion: manifest.codeVersion, serverVersion: manifest.serverVersion,
    guestBuildId: manifest.guestBuildId, platformApiVersion: manifest.platformApiVersion, dataSchemaVersion: manifest.dataSchemaVersion,
    userDataMode: manifest.userDataMode || 'whole-root', disk: rootDisk,
    capabilities: {gracefulStopRequest: true, separateHomeDisk: false, diskGrowth: true, dnsRelay: true}}));
}
async function stop() {
  if (stopping) return; stopping = true;
  for (const child of children) child.kill('SIGTERM');
  if (dnsRelay) await dnsRelay.close();
  await new Promise(resolve => setTimeout(resolve, 1500));
  for (const child of children) child.kill('SIGKILL');
  run('/bin/sync', []);
  spawnSync('/bin/busybox', ['poweroff', '-f']);
}
module.exports = {watchStopRequest};
if (require.main === module) {
  for (const signal of ['SIGTERM', 'SIGINT', 'SIGPWR']) process.on(signal, () => { void stop(); });
  main().catch(error => {
    fail(error.message.replace(/[^a-zA-Z0-9_:.-]/g, '_'));
    // PID1 must remain available for an orderly host stop after setup failure.
    setInterval(() => {}, 60000);
  });
}
