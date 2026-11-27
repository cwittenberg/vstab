'use strict';
// Standalone proof of the actual PTY/session protocol; no VS Code mock involved.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');
const {SessionClient} = require('./extension/client.cjs');
const ptyModule = process.env.VSTAB_TEST_PTY_MODULE || path.resolve(__dirname, '../../.local/android-runtime/addon-sources/node-pty');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function wait(predicate) { const end = Date.now() + 10000; while (Date.now() < end) { if (predicate()) return; await sleep(30); } throw Error('Real PTY proof timed out.'); }
(async () => {
  const workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-persistent-proof-'));
  let one, two, daemonPid;
  try {
    one = new SessionClient({workspace, ptyModule, temporary: workspace});
    const id = crypto.randomUUID();
    const session = await one.request('create', {id, name: 'Persistent build proof', cols: 100, rows: 30});
    daemonPid = Number(fs.readFileSync('/proc/' + session.pid + '/stat', 'utf8').split(' ')[3]);
    await one.request('attach', {sessionId: id});
    const inputId = crypto.randomUUID();
    const input = {sessionId: id, inputId, data: 'printf "executed-once Ω🌱\\n" >> proof.txt; sleep .2; printf "completed-while-detached\\n"\r'};
    await one.request('input', input); await one.request('input', input); one.close();
    await sleep(400);
    two = new SessionClient({workspace, ptyModule, temporary: workspace});
    let output = '';
    two.on('data', event => { output += event.data; });
    const restored = (await two.request('list')).find(s => s.id === id);
    assert.equal(restored.pid, session.pid);
    await two.request('attach', {sessionId: id});
    await two.request('resize', {sessionId: id, cols: 132, rows: 44});
    await two.request('input', {sessionId: id, inputId: crypto.randomUUID(), data: 'stty size; printf "utf8 Ω🌱\\n"\r'});
    await wait(() => output.includes('44 132') && output.includes('completed-while-detached'));
    const file = fs.readFileSync(path.join(workspace, 'proof.txt'), 'utf8');
    assert.equal(file, 'executed-once Ω🌱\n');
    const report = {environment: 'Linux host; real pinned node-pty native binding, separate detached daemon',
      samePtyPid: session.pid === restored.pid, shellPid: session.pid, independentDaemonPid: daemonPid,
      outputWhileDetachedRecovered: output.includes('completed-while-detached'), duplicateInputExecutedOnce: true,
      unicodePreserved: file.includes('Ω🌱'), resizeObserved: output.includes('44 132'),
      markerFileBytes: Buffer.byteLength(file), capturedAt: new Date().toISOString()};
    await two.request('stop', {sessionId: id});
    report.explicitStopRemovedSession = (await two.request('list')).length === 0;
    const target = path.resolve(__dirname, '../../artifacts/features/persistent-terminals-linux.json');
    fs.mkdirSync(path.dirname(target), {recursive: true}); fs.writeFileSync(target, JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report, null, 2));
  } finally {
    one?.close(); two?.close();
    if (daemonPid) { try { process.kill(daemonPid, 'SIGTERM'); } catch {} }
    fs.rmSync(workspace, {recursive: true, force: true});
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
