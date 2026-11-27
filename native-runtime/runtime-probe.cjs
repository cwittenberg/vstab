'use strict';
// Executed by Android's own app process, using the APK's readonly Node binary.
// This exercises real Bionic bindings rather than claiming a host build proves Android.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {fork} = require('node:child_process');
if (process.argv[2] === '--child') {
  process.send({platform: process.platform, executable: process.execPath});
  process.exit(0);
}

async function main() {
  if (process.platform !== 'android') throw new Error('This probe must run on Android Bionic');
  const base = __dirname;
  const results = {platform: process.platform, architecture: process.arch,
    node: process.version, executable: process.execPath};
  results.fork = await new Promise((resolve, reject) => {
    const child = fork(__filename, ['--child'], {silent: true});
    const timer = setTimeout(() => {child.kill(); reject(new Error('native Node fork timeout'));}, 10000);
    child.once('message', message => {results.child = message;});
    child.once('error', reject);
    child.once('exit', code => {clearTimeout(timer); code === 0 && results.child
      ? resolve(true) : reject(new Error('native Node child failed: '+code));});
  });
  const pty = require(path.join(base, 'lib/vscode/node_modules/node-pty'));
  results.pty = await new Promise((resolve, reject) => {
    const terminal = pty.spawn(process.env.SHELL || '/system/bin/sh',
      ['--noprofile','--norc','-c','printf VSTAB_NATIVE_PTY_OK'],
      {name: 'xterm-256color', cols: 80, rows: 24, cwd: process.env.HOME, env: process.env});
    let data = '';
    const timer = setTimeout(() => {terminal.kill(); reject(new Error('Bionic PTY timeout'));}, 10000);
    terminal.onData(chunk => {data += chunk;});
    terminal.onExit(({exitCode}) => {clearTimeout(timer); exitCode === 0 && data.includes('VSTAB_NATIVE_PTY_OK')
      ? resolve(true) : reject(new Error('Bionic PTY failed: '+exitCode+' '+data));});
  });
  const sqlite = require(path.join(base, 'lib/vscode/node_modules/@vscode/sqlite3'));
  results.sqlite = await new Promise((resolve, reject) => {
    const db = new sqlite.Database(':memory:');
    db.get('select 6 * 7 as answer', (error, row) => {
      db.close(); error ? reject(error) : row.answer === 42 ? resolve(true) : reject(new Error('SQLite wrong result'));
    });
  });
  const argon2 = require(path.join(base, 'node_modules/argon2'));
  const password = 'generated-public-runtime-probe-value';
  const hash = await argon2.hash(password, {memoryCost: 1024, timeCost: 1, parallelism: 1});
  results.argon2 = await argon2.verify(hash, password);
  const watcher = require(path.join(base, 'lib/vscode/node_modules/@parcel/watcher'));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-native-probe-'));
  try {
    results.watcher = await new Promise(async (resolve, reject) => {
      let subscription;
      const timer = setTimeout(() => reject(new Error('Bionic inotify timeout')), 10000);
      try {
        subscription = await watcher.subscribe(directory, (error, events) => {
          if (error) {clearTimeout(timer); reject(error); return;}
          if (events.some(event => event.path.endsWith('/probe.txt'))) {
            clearTimeout(timer);
            subscription.unsubscribe().then(() => resolve(true), reject);
          }
        });
        fs.writeFileSync(path.join(directory, 'probe.txt'), 'native inotify');
      } catch (error) {clearTimeout(timer); reject(error);}
    });
  } finally {fs.rmSync(directory, {recursive: true, force: true});}
  console.log(JSON.stringify({status:'ready', ...results}));
}
main().catch(error => {console.error(error.stack); process.exitCode = 1;});
