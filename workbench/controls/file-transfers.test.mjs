import assert from 'node:assert/strict';
import test from 'node:test';
import {CHUNK_BYTES, downloadUrl, installFileTransfers, readAndroidClipboardFiles, saveResponseToAndroid} from './file-transfers.mjs';

function downloadHarness({finish = {saved: true, name: 'résumé.bin'}, badOffset = false} = {}) {
  const calls = [], writes = [];
  let offset = 0;
  return {calls, writes, async request(method, params) {
    calls.push({method, params});
    if (method === 'files.downloadBegin') return {token: 'download-token', chunkBytes: CHUNK_BYTES};
    if (method === 'files.downloadWrite') {
      assert.equal(params.offset, offset);
      const bytes = Buffer.from(params.dataBase64, 'base64');
      assert(bytes.length <= CHUNK_BYTES);
      writes.push(bytes); offset += bytes.length;
      return {offset: offset + (badOffset ? 1 : 0)};
    }
    if (method === 'files.downloadFinish') return finish;
    if (method === 'files.abort') return {};
    throw Error(method);
  }};
}

test('binary download streams bounded chunks, exact offsets and Unicode filename to the Android save result', async () => {
  const bytes = Uint8Array.from({length: 393223}, (_, index) => index % 256);
  const h = downloadHarness();
  const result = await saveResponseToAndroid({response: new Response(bytes, {headers: {'content-length': String(bytes.length)}}), request: h.request, name: 'résumé.bin'});
  assert.deepEqual(Buffer.concat(h.writes), Buffer.from(bytes));
  assert.equal(h.calls[0].params.name, 'résumé.bin');
  assert.equal(h.calls[0].params.size, bytes.length);
  assert.equal(h.calls.filter(call => call.method === 'files.downloadWrite').length, 4);
  assert.equal(h.calls.at(-1).method, 'files.downloadFinish');
  assert(result.saved);
});

test('cancelling Android save returns cancellation and never claims success', async () => {
  const h = downloadHarness({finish: {cancelled: true}});
  assert.deepEqual(await saveResponseToAndroid({response: new Response('kept inside workspace'), request: h.request, name: 'keep.txt'}), {cancelled: true});
  assert(!h.calls.some(call => call.method === 'files.abort'));
});

test('native chunk loss aborts staging before displaying the save picker', async () => {
  const h = downloadHarness({badOffset: true});
  await assert.rejects(saveResponseToAndroid({response: new Response('binary'), request: h.request, name: 'one.bin'}), /interrupted/);
  assert.equal(h.calls.at(-1).method, 'files.abort');
  assert(!h.calls.some(call => call.method === 'files.downloadFinish'));
});

test('truncated HTTP file and closed workspace abort rather than deliver partial bytes', async () => {
  const h = downloadHarness();
  await assert.rejects(saveResponseToAndroid({response: new Response('short', {headers: {'content-length': '99'}}), request: h.request, name: 'one.txt'}), /complete file/);
  assert.equal(h.calls.at(-1).method, 'files.abort');
  const closed = downloadHarness();
  await assert.rejects(saveResponseToAndroid({response: new Response('one'), request: closed.request, name: 'one.txt', isClosed: () => true}), /closed/);
  assert.equal(closed.calls.length, 0);
});

test('read failure aborts the staged download; an HTTP error does not start staging', async () => {
  const h = downloadHarness();
  const body = new ReadableStream({start(controller) { controller.error(new Error('connection dropped')); }});
  await assert.rejects(saveResponseToAndroid({response: new Response(body), request: h.request, name: 'one.bin'}), /connection dropped/);
  assert.equal(h.calls.at(-1).method, 'files.abort');
  const failed = downloadHarness();
  await assert.rejects(saveResponseToAndroid({response: new Response('', {status: 403}), request: failed.request, name: 'one.txt'}), /could not be read/);
  assert.equal(failed.calls.length, 0);
});

test('declared files larger than the native transfer limit never open staging', async () => {
  const h = downloadHarness();
  await assert.rejects(saveResponseToAndroid({response: new Response('', {headers: {'content-length': '268435457'}}), request: h.request, name: 'too-big'}), /256 MiB/);
  assert.equal(h.calls.length, 0);
});

function clipboardHarness(items, {badOffset = false} = {}) {
  const releases = [], reads = [];
  return {releases, reads, async request(method, params) {
    if (method === 'files.clipboard') return {files: items.map(({bytes, ...metadata}) => metadata)};
    if (method === 'files.release') { releases.push(params.token); return {}; }
    if (method === 'files.read') {
      reads.push(params);
      const item = items.find(item => item.token === params.token);
      const chunk = item.bytes.subarray(params.offset, params.offset + CHUNK_BYTES);
      return {dataBase64: Buffer.from(chunk).toString('base64'), offset: params.offset,
        nextOffset: params.offset + chunk.length + (badOffset ? 1 : 0), eof: params.offset + chunk.length === item.bytes.length};
    }
    throw Error(method);
  }};
}

test('Android clipboard creates exact binary Files and releases all native URI tokens', async () => {
  const bytes = Uint8Array.from({length: CHUNK_BYTES + 19}, (_, index) => index % 256);
  const h = clipboardHarness([{token: 'one', name: '照片.bin', size: bytes.length, type: 'application/octet-stream', bytes},
    {token: 'empty', name: 'empty.txt', size: 0, type: 'text/plain', bytes: new Uint8Array()}]);
  const files = await readAndroidClipboardFiles({request: h.request});
  assert.equal(files[0].name, '照片.bin');
  assert.deepEqual(new Uint8Array(await files[0].arrayBuffer()), bytes);
  assert.equal(files[1].size, 0);
  assert.deepEqual(h.releases.sort(), ['empty', 'one']);
  assert.deepEqual(h.reads.filter(read => read.token === 'one').map(read => read.offset), [0, CHUNK_BYTES]);
});

test('clipboard failure releases even unread tokens and never returns partial files', async () => {
  const h = clipboardHarness([{token: 'one', name: 'one.bin', size: 1, bytes: new Uint8Array([1])},
    {token: 'two', name: 'two.bin', size: 1, bytes: new Uint8Array([2])}], {badOffset: true});
  await assert.rejects(readAndroidClipboardFiles({request: h.request}), /interrupted/);
  assert.deepEqual(h.releases.sort(), ['one', 'two']);
});

test('an early clipboard failure releases all 100 URI tokens without exceeding the 64-call control limit', async () => {
  const tokens = Array.from({length: 100}, (_, index) => 'file-' + index);
  const releases = [];
  let active = 0, maximum = 0;
  async function request(method, params) {
    if (method === 'files.clipboard') return {files: tokens.map(token => ({token, name: token + '.bin', size: 1}))};
    if (method === 'files.read') throw new Error('provider access revoked');
    if (method !== 'files.release') throw new Error(method);
    if (active >= 64) throw new Error('too many pending requests');
    active++; maximum = Math.max(maximum, active);
    await new Promise(resolve => setImmediate(resolve));
    releases.push(params.token); active--;
    return {};
  }
  await assert.rejects(readAndroidClipboardFiles({request}), /access revoked/);
  assert.deepEqual(releases, tokens);
  assert.equal(maximum, 1);
});

test('clipboard rejects unsafe filenames and changed source size and releases the grant', async () => {
  const unsafe = clipboardHarness([{token: 'one', name: '../escape', size: 0, bytes: new Uint8Array()}]);
  await assert.rejects(readAndroidClipboardFiles({request: unsafe.request}), /invalid name/);
  assert.deepEqual(unsafe.releases, ['one']);
  const changed = clipboardHarness([{token: 'two', name: 'ok', size: 2, bytes: new Uint8Array([1])}]);
  await assert.rejects(readAndroidClipboardFiles({request: changed.request}), /changed while/);
  assert.deepEqual(changed.releases, ['two']);
});

test('only workspace HTTP and workspace-owned blob download URLs are accepted', () => {
  const origin = 'http://localhost:35721';
  assert.equal(downloadUrl('/file?a=b', origin), origin + '/file?a=b');
  assert.equal(downloadUrl('blob:' + origin + '/one', origin), 'blob:' + origin + '/one');
  for (const address of ['https://outside.test/x', 'http://localhost:999/x', 'blob:http://127.0.0.1:35721/x', 'file:///sdcard/one', 'data:text/plain,x', 'http://user:pass@localhost:35721/x']) {
    assert.throws(() => downloadUrl(address, origin), /Only files from this workspace/);
  }
});

function windowHarness({inExplorer = true, clipboardFiles = []} = {}) {
  const listeners = new Map(), emitted = [], notices = [], calls = [];
  const tree = {querySelector: () => ({getAttribute: () => 'selected-folder', focus() {}}), dispatchEvent(event) { emitted.push(event); }};
  const active = {closest(selector) { if (selector.includes('input')) return inExplorer ? null : this; return inExplorer ? tree : null; },
    dispatchEvent(event) { emitted.push(event); for (const listener of listeners.get(event.type) || []) listener(event); }};
  const document = {activeElement: active, addEventListener(type, listener) { listeners.set('document:' + type, [listener]); },
    removeEventListener(type) { listeners.delete('document:' + type); }, querySelectorAll() { return []; }};
  class ClipboardEvent extends Event { constructor(type, options) { super(type, options); this.clipboardData = options.clipboardData; } }
  class KeyboardEvent extends Event { constructor(type, options) { super(type, options); for (const key of ['key', 'code', 'keyCode', 'which', 'ctrlKey', 'metaKey']) this[key] = options[key]; } }
  class DataTransfer { constructor() { this.files = []; this.items = {add: file => this.files.push(file)}; } }
  const hostWindow = {document, location: {origin: 'http://localhost:35721'}, File, DataTransfer, ClipboardEvent, KeyboardEvent, atob, btoa,
    addEventListener(type, listener) { listeners.set(type, [listener]); }, removeEventListener(type) { listeners.delete(type); }};
  const integration = installFileTransfers({hostWindow, request: async (method, params) => {
    calls.push({method, params});
    if (method === 'files.clipboard') return {files: clipboardFiles};
    if (method === 'files.read') return {dataBase64: 'AAH/', offset: 0, nextOffset: 3, eof: true};
    return {};
  }, notify: notice => notices.push(notice)});
  function press(key = 'v') {
    const event = {key, ctrlKey: true, target: active, preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; }};
    for (const listener of listeners.get('keydown')) listener(event);
    return event;
  }
  return {integration, calls, notices, emitted, press, listeners};
}

test('file paste stays scoped to Explorer; editor text paste never reads the Android clipboard', async () => {
  const h = windowHarness({inExplorer: false});
  try {
    assert(!h.press().defaultPrevented);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.calls.length, 0);
  } finally { h.integration.dispose(); }
});

test('Explorer Ctrl+V delivers a FileList through the existing paste event route', async () => {
  const h = windowHarness({clipboardFiles: [{token: 'one', name: 'one.bin', size: 3}]});
  try {
    assert(h.press().defaultPrevented);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.emitted.length, 1);
    assert.equal(h.emitted[0].type, 'paste');
    assert.equal(h.emitted[0].clipboardData.files[0].name, 'one.bin');
    assert.deepEqual(new Uint8Array(await h.emitted[0].clipboardData.files[0].arrayBuffer()), new Uint8Array([0, 1, 255]));
    assert.equal(h.notices.length, 0);
  } finally { h.integration.dispose(); }
});

test('an internal Explorer file copy keeps its ordinary keybinding when Android has no file URIs', async () => {
  const h = windowHarness();
  try {
    h.press();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.calls.length, 1);
    assert.equal(h.emitted.length, 1);
    assert.equal(h.emitted[0].type, 'keydown');
    assert.equal(h.notices.length, 0);
  } finally { h.integration.dispose(); }
});

test('an internal Explorer copy takes priority over an older Android URI clipboard', async () => {
  const h = windowHarness({clipboardFiles: [{token: 'old', name: 'older.bin', size: 3}]});
  try {
    h.press('c');
    assert(!h.press().defaultPrevented);
    assert.equal(h.calls.length, 0);
    for (const listener of h.listeners.get('blur')) listener({});
    assert(h.press().defaultPrevented);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(h.emitted[0].clipboardData.files[0].name, 'older.bin');
  } finally { h.integration.dispose(); }
});

test('the core context-menu copy signal preserves internal paste without reading stale Android files', () => {
  const h = windowHarness({clipboardFiles: [{token: 'old', name: 'older.bin', size: 3}]});
  try {
    for (const listener of h.listeners.get('vstab-explorer-copy')) listener({});
    assert(!h.press().defaultPrevented);
    assert.equal(h.calls.length, 0);
  } finally { h.integration.dispose(); }
});

function selectedDownloadsHarness({firstFetchFails = false} = {}) {
  const origin = 'http://localhost:35721';
  const listeners = new Map(), fetched = [], calls = [], notices = [], received = new Map();
  let firstFinish;
  const document = {addEventListener(type, callback) { listeners.set(type, callback); }, removeEventListener() {}};
  const hostWindow = {document, location: {origin}, btoa,
    addEventListener() {}, removeEventListener() {},
    fetch(address) {
      fetched.push(address);
      if (firstFetchFails && address.endsWith('/first')) return Promise.reject(new Error('first file disappeared'));
      return Promise.resolve(new Response(address.endsWith('/first') ? new Uint8Array([0, 255, 1]) : new Uint8Array([2, 0, 254])));
    }};
  const integration = installFileTransfers({hostWindow, notify: notice => notices.push(notice), request: async (method, params) => {
    calls.push({method, params});
    if (method === 'files.downloadBegin') { received.set(params.name, []); return {token: params.name, chunkBytes: CHUNK_BYTES}; }
    if (method === 'files.downloadWrite') {
      received.get(params.token).push(...Buffer.from(params.dataBase64, 'base64'));
      return {offset: received.get(params.token).length};
    }
    if (method === 'files.downloadFinish') {
      if (params.token === 'first.bin') return new Promise(resolve => { firstFinish = resolve; });
      return {saved: true, name: params.token};
    }
    throw new Error(method);
  }});
  function click(name) {
    const anchor = {ownerDocument: document, href: 'blob:' + origin + '/' + name, download: name + '.bin'};
    const event = {target: {closest() { return anchor; }}, preventDefault() { this.defaultPrevented = true; }};
    listeners.get('click')(event);
    assert(event.defaultPrevented);
  }
  return {integration, fetched, calls, notices, received, click, finishFirst: result => firstFinish(result)};
}

for (const cancelled of [false, true]) {
  test(`multiple selected downloads fetch immediately and serialize save pickers after ${cancelled ? 'cancellation' : 'completion'}`, async () => {
    const h = selectedDownloadsHarness();
    try {
      h.click('first'); h.click('second');
      assert.equal(h.fetched.length, 2, 'Both blob URLs must be fetched before upstream revokes them');
      await new Promise(resolve => setImmediate(resolve));
      assert.deepEqual(h.calls.filter(call => call.method === 'files.downloadBegin').map(call => call.params.name), ['first.bin']);
      assert.deepEqual(h.received.get('first.bin'), [0, 255, 1]);
      h.finishFirst(cancelled ? {cancelled: true} : {saved: true, name: 'first.bin'});
      await new Promise(resolve => setImmediate(resolve));
      assert.deepEqual(h.calls.filter(call => call.method === 'files.downloadFinish').map(call => call.params.token), ['first.bin', 'second.bin']);
      assert.deepEqual(h.received.get('second.bin'), [2, 0, 254]);
      assert.equal(h.notices.filter(notice => notice.kind === 'info').length, cancelled ? 1 : 2);
      assert.equal(h.notices.filter(notice => notice.kind === 'error').length, 0);
    } finally { h.integration.dispose(); }
  });
}

test('a failed selected download reports its error and still saves the next selected file', async () => {
  const h = selectedDownloadsHarness({firstFetchFails: true});
  try {
    h.click('first'); h.click('second');
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(h.calls.filter(call => call.method === 'files.downloadBegin').map(call => call.params.name), ['second.bin']);
    assert.deepEqual(h.received.get('second.bin'), [2, 0, 254]);
    assert.equal(h.notices.filter(notice => notice.kind === 'error').length, 1);
    assert.equal(h.notices.filter(notice => notice.kind === 'info').length, 1);
  } finally { h.integration.dispose(); }
});
