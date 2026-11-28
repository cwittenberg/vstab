'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {install} = require('./codex-extension-runtime.cjs');

function harness(options = {}) {
  const calls = [];
  const directories = [];
  const child = {spawn(...args) { calls.push({receiver: this, args}); return 'child'; }};
  const enabled = install({platform: 'android',
    environment: {VSTAB_CODEX_EXECUTABLE: '/apk/lib/libcodex.so'},
    child, exists: () => true, mkdir: (...args) => directories.push(args), ...options});
  return {child, calls, directories, enabled};
}

test('public Codex extensions retain their app-server arguments and process options', () => {
  const {child, calls, enabled} = harness();
  assert.equal(enabled, true);
  const args = ['app-server', '--analytics-default-enabled'];
  const options = {env: {HOME: '/private/home'}, cwd: '/private/project', stdio: ['pipe', 'pipe', 'pipe']};
  for (const architecture of ['x86_64', 'aarch64']) {
    const file = `/private/extensions/openai.chatgpt-26.1120.50957-linux-${architecture}/bin/linux-${architecture}/codex`;
    assert.equal(child.spawn(file, args, options), 'child');
    const call = calls.at(-1);
    assert.equal(call.receiver, child);
    assert.equal(call.args[0], '/apk/lib/libcodex.so');
    assert.equal(call.args[1], args);
    assert.equal(call.args[2], options);
  }
});

test('custom CLI paths and unrelated executables keep their original behavior', () => {
  const {child, calls} = harness();
  for (const file of ['codex', '/private/custom/codex',
    '/private/extensions/another.publisher-1/bin/linux-aarch64/codex',
    '/private/extensions/openai.chatgpt-26/bin/linux-aarch64/other',
    '/private/extensions/openai.chatgpt-26/bin/linux-aarch64/codex-helper']) {
    child.spawn(file, ['--version']);
    assert.equal(calls.at(-1).args[0], file);
  }
});

test('a missing Android engine and desktop platforms never install a redirect', () => {
  for (const options of [{platform: 'linux'}, {environment: {}},
    {environment: {VSTAB_CODEX_EXECUTABLE: 'libcodex.so'}}, {exists: () => false}]) {
    const {child, calls, enabled} = harness(options);
    assert.equal(enabled, false);
    const file = '/private/extensions/openai.chatgpt-26/bin/linux-x86_64/codex';
    child.spawn(file, ['--version']);
    assert.equal(calls[0].args[0], file);
  }
});

test('reloading the preload does not wrap the same process twice', () => {
  const {child} = harness();
  const first = child.spawn;
  assert.equal(install({platform: 'android', environment: {VSTAB_CODEX_EXECUTABLE: '/different/codex'},
    child, exists: () => true}), false);
  assert.equal(child.spawn, first);
});

test('first managed extension launch creates only its selected default Codex home', () => {
  const {child, directories} = harness();
  const options = {env: {HOME: '/private/home', CODEX_HOME: '/private/home/.codex'}};
  child.spawn('/extensions/openai.chatgpt-26/bin/linux-x86_64/codex', ['app-server'], options);
  assert.deepEqual(directories, [['/private/home/.codex', {recursive: true, mode: 0o700}]]);
});

test('custom Codex homes and unrelated executable launches do not create directories', () => {
  const {child, directories} = harness();
  const managed = '/extensions/openai.chatgpt-26/bin/linux-x86_64/codex';
  for (const codexHome of ['/private/custom-codex', '/private/home/.codex-other', '.codex']) {
    child.spawn(managed, ['app-server'], {env: {HOME: '/private/home', CODEX_HOME: codexHome}});
  }
  child.spawn('/custom/codex', ['app-server'], {env: {HOME: '/private/home', CODEX_HOME: '/private/home/.codex'}});
  assert.deepEqual(directories, []);
});
