import test from 'node:test';
import assert from 'node:assert/strict';
import {createAuthCallbackProvider} from './auth-callbacks.mjs';

function setup() {
  const hostWindow = new EventTarget();
  let sequence = 0;
  let time = 0;
  const provider = createAuthCallbackProvider({callbackUrl: 'http://127.0.0.1:12345/secret/callback',
    hostWindow, uri: {parse: value => new URL(value), from: value => value},
    randomId: () => 'random-' + ++sequence, now: () => time});
  const received = [];
  provider.onCallback(value => received.push(value));
  return {provider, received, expire: () => time += 16 * 60 * 1000,
    receive(url) { hostWindow.dispatchEvent(new CustomEvent('vstab-auth-callback', {detail: String(url)})); }};
}

test('browser returns arrive once and preserve encoded OAuth values', () => {
  const h = setup();
  const address = h.provider.create({scheme: 'code-oss', authority: 'publisher.extension', path: '/sign-in', query: 'state=original%25state'});
  address.searchParams.set('code', 'literal%2F+&code');
  h.receive(address);
  assert.deepEqual(h.received, [{scheme: 'code-oss', authority: 'publisher.extension', path: '/sign-in',
    query: 'code=literal%252F%2B%26code&state=original%25state'}]);
  h.receive(address);
  assert.equal(h.received.length, 1);
  h.provider.dispose();
});

test('a callback cannot cross document identities, receivers, or extension identities', () => {
  const h = setup();
  const address = h.provider.create({scheme: 'code-oss', authority: 'one.extension'});
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
  const expired = h.provider.create({scheme: 'code-oss', authority: 'one.extension'});
  h.expire(); h.receive(expired);
  const closed = h.provider.create({scheme: 'code-oss', authority: 'one.extension'});
  h.provider.dispose(); h.receive(closed);
  assert.deepEqual(h.received, []);
  assert.throws(() => h.provider.create(), /closed/);
});
