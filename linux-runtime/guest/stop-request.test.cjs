'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {watchStopRequest} = require('./supervisor.cjs');
const wait = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
test('only an exact regular-file request stops its owning environment', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-stop-'));
  const filename = path.join(root, 'stop-request'), target = path.join(root, 'target');
  let stopped = 0;
  const timer = watchStopRequest('environment-A', () => { stopped++; clearInterval(timer); }, {filename, intervalMs: 5});
  try {
    await wait(15); assert.equal(stopped, 0);
    fs.writeFileSync(filename, 'environment-B'); await wait(15); assert.equal(stopped, 0);
    fs.writeFileSync(filename, 'environment-A\n'); await wait(15); assert.equal(stopped, 0);
    fs.unlinkSync(filename); fs.writeFileSync(target, 'environment-A'); fs.symlinkSync(target, filename);
    await wait(15); assert.equal(stopped, 0); fs.unlinkSync(filename);
    fs.writeFileSync(filename, 'environment-A'); await wait(30); assert.equal(stopped, 1);
  } finally { clearInterval(timer); fs.rmSync(root, {recursive: true, force: true}); }
});
