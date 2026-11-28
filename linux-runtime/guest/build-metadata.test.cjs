'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {metadata} = require('./build-metadata.cjs');
test('installed build identity is stable across user-data writes and changes with integration or kernel', () => {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'vstab-build-id-'));
  const root = path.join(temporary, 'root'), output = path.join(temporary, 'out'), archive = path.join(temporary, 'editor.tar.gz');
  fs.mkdirSync(path.join(root, 'sbin'), {recursive: true}); fs.mkdirSync(output);
  fs.writeFileSync(path.join(root, 'sbin/vstab-init'), 'initial init'); fs.writeFileSync(archive, 'paired archive');
  for (const name of ['kernel', 'kernel.config', 'packages.tsv', 'kernel-version.txt', 'node-version.txt']) fs.writeFileSync(path.join(output, name), name);
  try {
    const initial = metadata(root, output, archive);
    assert.match(initial.guestBuildId, /^[a-f0-9]{64}$/);
    assert.equal(initial.upgradePolicy, 'preserve-installed-system');
    fs.mkdirSync(path.join(root, 'home')); fs.writeFileSync(path.join(root, 'home/project.txt'), 'user project');
    assert.equal(metadata(root, output, archive).guestBuildId, initial.guestBuildId);
    fs.writeFileSync(path.join(root, 'sbin/vstab-init'), 'updated init');
    const updated = metadata(root, output, archive); assert.notEqual(updated.guestBuildId, initial.guestBuildId);
    fs.writeFileSync(path.join(output, 'kernel'), 'updated kernel');
    assert.notEqual(metadata(root, output, archive).guestBuildId, updated.guestBuildId);
  } finally { fs.rmSync(temporary, {recursive: true, force: true}); }
});
