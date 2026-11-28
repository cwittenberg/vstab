import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {createAuthCallbackProvider} from './auth-callbacks.mjs';

// Use the same URI implementation and revision as the bundled workbench.
// A WHATWG URL mock does not reproduce URI.parse's query percent-decoding.
const upstream = fileURLToPath(new URL('../../.local/upstream/vscode/', import.meta.url));
const spec = JSON.parse(readFileSync(new URL('../bundle-spec.json', import.meta.url)));
assert.equal(execFileSync('git', ['-C', upstream, 'rev-parse', 'HEAD'], {encoding: 'utf8'}).trim(), spec.upstreamCommit);
const require = createRequire(new URL('../local/package.json', import.meta.url));
const compiled = require('esbuild').buildSync({
  entryPoints: [fileURLToPath(new URL('../../.local/upstream/vscode/src/vs/base/common/uri.ts', import.meta.url))],
  bundle: true, platform: 'node', format: 'esm', write: false
});
const {URI} = await import('data:text/javascript;base64,' + Buffer.from(compiled.outputFiles[0].contents).toString('base64'));
const browserAddress = uri => new URL(uri.toString(true));

function setup() {
  const hostWindow = new EventTarget();
  let sequence = 0;
  let time = 0;
  const provider = createAuthCallbackProvider({callbackUrl: 'http://127.0.0.1:12345/secret/callback',
    hostWindow, uri: URI,
    randomId: () => 'random-' + ++sequence, now: () => time});
  const received = [];
  provider.onCallback(value => received.push(value));
  return {provider, received, expire: () => time += 16 * 60 * 1000,
    receive(url) { hostWindow.dispatchEvent(new CustomEvent('vstab-auth-callback', {detail: String(url)})); }};
}

test('real VS Code URI browser roundtrip preserves nested query, percent, ampersand and fragment values', () => {
  const h = setup();
  const options = {scheme: 'code-oss', authority: 'publisher.extension', path: '/sign-in',
    query: 'state=original%25state&second=one%26two%2Fthree', fragment: 'a&b%'};
  const address = browserAddress(h.provider.create(options));
  assert.equal(address.searchParams.get('vscode-query'), options.query);
  assert.equal(address.searchParams.get('vscode-fragment'), options.fragment);
  address.searchParams.set('code', 'literal%2F+&code');
  h.receive(address);
  assert.equal(h.received.length, 1);
  assert.ok(h.received[0] instanceof URI);
  assert.deepEqual(h.received[0].toJSON(), {$mid: 1, ...options,
    query: 'code=literal%252F%2B%26code&state=original%25state&second=one%26two%2Fthree'});
  h.receive(address);
  assert.equal(h.received.length, 1);
  h.provider.dispose();
});

test('a callback cannot cross document identities, receivers, or extension identities', () => {
  const h = setup();
  const address = browserAddress(h.provider.create({scheme: 'code-oss', authority: 'one.extension'}));
  for (const change of [u => u.port = '12346', u => u.pathname = '/other/callback',
    u => u.searchParams.set('vscode-reqid', 'unknown'), u => u.searchParams.set('vscode-authority', 'two.extension'),
    u => u.searchParams.append('vscode-authority', 'one.extension')]) {
    const changed = new URL(address);
    change(changed); h.receive(changed);
  }
  assert.deepEqual(h.received, []);
  h.receive(address);
  assert.equal(h.received.length, 1);
  h.provider.dispose();
});

test('expired and disposed sign-ins cannot be delivered', () => {
  const h = setup();
  const expired = browserAddress(h.provider.create({scheme: 'code-oss', authority: 'one.extension'}));
  h.expire(); h.receive(expired);
  const closed = browserAddress(h.provider.create({scheme: 'code-oss', authority: 'one.extension'}));
  h.provider.dispose(); h.receive(closed);
  assert.deepEqual(h.received, []);
  assert.throws(() => h.provider.create(), /closed/);
});
