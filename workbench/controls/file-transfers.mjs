// Android file transfers stay in the main workbench frame. Code OSS continues
// to own the Explorer destination, collision decisions, undo and remote writes.
export const CHUNK_BYTES = 128 * 1024;
export const MAX_FILE_BYTES = 256 * 1024 * 1024;
const MAX_CLIPBOARD_BYTES = MAX_FILE_BYTES;

function transferError(message, code = 'Unavailable') {
  return Object.assign(new Error(message), {code});
}

export function downloadUrl(address, origin) {
  const url = new URL(address, origin + '/');
  if (url.username || url.password || url.origin !== origin || !['http:', 'blob:'].includes(url.protocol)) {
    throw transferError('Only files from this workspace can be saved to Android.', 'NoPermissions');
  }
  return url.href;
}

function base64(bytes, encode) {
  let binary = '';
  for (let start = 0; start < bytes.byteLength; start += 32768) {
    binary += String.fromCharCode(...bytes.subarray(start, start + 32768));
  }
  return encode(binary);
}

function unbase64(value, decode) {
  if (typeof value !== 'string' || value.length > Math.ceil(CHUNK_BYTES / 3) * 4 ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) {
    throw transferError('Android returned invalid file contents.');
  }
  return Uint8Array.from(decode(value), character => character.charCodeAt(0));
}

/** Stream a fetched workspace file into native staging, then ask for its SAF destination. */
export async function saveResponseToAndroid({response, request, name, encode = btoa, isClosed = () => false}) {
  if (!response.ok) throw transferError('The workspace file could not be read. Try the download again.');
  const declared = response.headers?.get('content-length');
  const size = declared && /^\d+$/.test(declared) ? Number(declared) : null;
  if (size !== null && (!Number.isSafeInteger(size) || size > MAX_FILE_BYTES)) {
    throw transferError('Android file transfers are limited to 256 MiB per file.');
  }
  if (isClosed()) throw transferError('The workspace was closed.', 'SessionChanged');
  const started = await request('files.downloadBegin', {name, type: response.headers?.get('content-type') || 'application/octet-stream', size});
  if (!started?.token) throw transferError('Android could not start this download.');
  const token = started.token;
  const chunkSize = Math.min(CHUNK_BYTES, started.chunkBytes || CHUNK_BYTES);
  if (!Number.isInteger(chunkSize) || chunkSize < 1) {
    await request('files.abort', {token}).catch(() => {});
    throw transferError('Android returned an invalid transfer size.');
  }
  let offset = 0, reader;
  try {
    const write = async bytes => {
      for (let start = 0; start < bytes.byteLength; start += chunkSize) {
        if (isClosed()) throw transferError('The workspace was closed.', 'SessionChanged');
        const chunk = bytes.subarray(start, start + chunkSize);
        if (offset + chunk.byteLength > MAX_FILE_BYTES) throw transferError('Android file transfers are limited to 256 MiB per file.');
        const result = await request('files.downloadWrite', {token, offset, dataBase64: base64(chunk, encode)});
        offset += chunk.byteLength;
        if (result?.offset !== offset) throw transferError('The download was interrupted before all bytes arrived.');
      }
    };
    if (response.body?.getReader) {
      reader = response.body.getReader();
      for (;;) {
        const {done, value} = await reader.read();
        if (done) break;
        await write(value);
      }
    } else {
      await write(new Uint8Array(await response.arrayBuffer()));
    }
    if (size !== null && offset !== size) throw transferError('The download ended before the complete file arrived.');
    if (isClosed()) throw transferError('The workspace was closed.', 'SessionChanged');
    return await request('files.downloadFinish', {token});
  } catch (error) {
    await reader?.cancel().catch(() => {});
    await request('files.abort', {token}).catch(() => {});
    throw error;
  } finally {
    reader?.releaseLock();
  }
}

/** Read only tokens granted by the current explicit Android clipboard request. */
export async function readAndroidClipboardFiles({request, FileClass = File, decode = atob, isClosed = () => false}) {
  const result = await request('files.clipboard', {});
  if (!result || !Array.isArray(result.files)) throw transferError('Android could not read the copied files.');
  const files = [], tokens = result.files.map(file => file.token).filter(token => typeof token === 'string');
  let total = 0;
  try {
    for (const item of result.files) {
      if (!item || typeof item.token !== 'string' || typeof item.name !== 'string' || !item.name ||
          /[\u0000/\\]/.test(item.name) || item.name === '.' || item.name === '..') {
        throw transferError('One copied file has an invalid name. Copy the file again.');
      }
      if (typeof item.size === 'number' && item.size > MAX_FILE_BYTES) throw transferError('Android file transfers are limited to 256 MiB per file.');
      const parts = [];
      let offset = 0;
      for (;;) {
        if (isClosed()) throw transferError('The workspace was closed.', 'SessionChanged');
        const chunk = await request('files.read', {token: item.token, offset});
        const bytes = unbase64(chunk?.dataBase64, decode);
        if (chunk.offset !== offset || chunk.nextOffset !== offset + bytes.byteLength ||
            (!chunk.eof && bytes.byteLength === 0)) throw transferError('Reading the copied file was interrupted.');
        offset += bytes.byteLength; total += bytes.byteLength;
        if (offset > MAX_FILE_BYTES || total > MAX_CLIPBOARD_BYTES) {
          throw transferError('Paste up to 256 MiB of files at a time.');
        }
        parts.push(bytes);
        if (chunk.eof) break;
      }
      if (typeof item.size === 'number' && item.size >= 0 && item.size !== offset) {
        throw transferError('A copied file changed while it was being read. Copy it again.');
      }
      files.push(new FileClass(parts, item.name, {type: item.type || 'application/octet-stream'}));
    }
    return files;
  } finally {
    // The control port allows 64 pending calls, while Android can grant 100
    // files. Release sequentially so cleanup also reaches unread files after
    // an early failure instead of overflowing the port's pending-call limit.
    for (const token of tokens) await request('files.release', {token}).catch(() => {});
  }
}

function editable(element) {
  return !!element?.closest?.('input,textarea,[contenteditable="true"],.monaco-editor,.terminal-wrapper');
}

function explorerTree(document, force) {
  const active = document.activeElement;
  if (!force && editable(active)) return null;
  let tree = active?.closest?.('.explorer-folders-view');
  if (!tree && force) tree = [...document.querySelectorAll('.explorer-folders-view')]
    .find(element => element.checkVisibility?.({checkVisibilityCSS: true}) ?? element.getBoundingClientRect().height > 0);
  if (tree && force) tree.querySelector('.monaco-list')?.focus();
  return tree;
}

function destination(tree) {
  return tree.querySelector('.monaco-list')?.getAttribute('aria-activedescendant') || '';
}

/** Install before the workbench loads; returns an explicit command path as well as disposal. */
export function installFileTransfers({hostWindow = window, request, notify = () => {}} = {}) {
  if (hostWindow.top && hostWindow.top !== hostWindow) throw transferError('File transfers require the main workspace window.', 'NoPermissions');
  if (typeof request !== 'function') throw new TypeError('File transfers require the native request channel.');
  const document = hostWindow.document;
  const origin = hostWindow.location.origin;
  let disposed = false, pasting = false, explorerCopied = false;
  let downloads = Promise.resolve();
  const replayed = new WeakSet();
  const report = error => {
    if (!disposed && error.code !== 'Cancelled' && error.code !== 'SessionChanged') {
      notify({kind: 'error', message: error.message || 'The Android file transfer failed. Try again.'});
    }
  };
  async function pasteFromAndroid({force = false, fallback} = {}) {
    if (disposed || pasting) return false;
    const tree = explorerTree(document, force);
    if (!tree) {
      if (force) notify({kind: 'error', message: 'Open a writable folder in Explorer before pasting files.'});
      return false;
    }
    const selected = destination(tree);
    pasting = true;
    try {
      const files = await readAndroidClipboardFiles({request, FileClass: hostWindow.File,
        decode: value => hostWindow.atob(value), isClosed: () => disposed});
      if (disposed) return false;
      if (!files.length) {
        if (fallback) fallback();
        else if (force) notify({kind: 'info', message: 'Copy files in Android Files, then choose Paste from Android.'});
        return false;
      }
      if (explorerTree(document, false) !== tree || destination(tree) !== selected) {
        throw transferError('The Explorer destination changed. Paste again in the folder you want.');
      }
      const data = new hostWindow.DataTransfer();
      for (const file of files) data.items.add(file);
      const event = new hostWindow.ClipboardEvent('paste', {clipboardData: data, bubbles: true, cancelable: true});
      replayed.add(event);
      // The existing Explorer window listener invokes filesExplorer.paste with
      // this FileList, preserving the ordinary overwrite/undo decisions.
      tree.dispatchEvent(event);
      return true;
    } catch (error) { report(error); return false; }
    finally { pasting = false; }
  }
  function keydown(event) {
    if (disposed || replayed.has(event) || event.defaultPrevented || event.altKey || event.shiftKey ||
        !(event.ctrlKey || event.metaKey) || !explorerTree(document, false)) return;
    const key = event.key?.toLowerCase();
    if (key === 'c' || key === 'x') { explorerCopied = true; return; }
    if (key !== 'v' || explorerCopied) return;
    event.preventDefault(); event.stopImmediatePropagation();
    const target = event.target;
    void pasteFromAndroid({fallback() {
      const copy = new hostWindow.KeyboardEvent('keydown', {key: event.key, code: event.code || 'KeyV',
        keyCode: event.keyCode || 86, which: event.which || 86, ctrlKey: event.ctrlKey, metaKey: event.metaKey,
        bubbles: true, cancelable: true});
      replayed.add(copy); target.dispatchEvent(copy);
    }});
  }
  function paste(event) {
    if (disposed || explorerCopied || replayed.has(event) || event.defaultPrevented || event.clipboardData?.files?.length || !explorerTree(document, false)) return;
    event.preventDefault(); event.stopImmediatePropagation();
    const target = event.target;
    void pasteFromAndroid({fallback() {
      const copy = new hostWindow.ClipboardEvent('paste', {clipboardData: event.clipboardData, bubbles: true, cancelable: true});
      replayed.add(copy); target.dispatchEvent(copy);
    }});
  }
  function internalCopy() { explorerCopied = true; }
  function externalFocus() { explorerCopied = false; }
  function copy(event) { explorerCopied = !!event.target?.closest?.('.explorer-folders-view') && !editable(event.target); }
  function click(event) {
    if (disposed || event.defaultPrevented) return;
    const anchor = event.target?.closest?.('a[download]');
    if (!anchor || anchor.ownerDocument !== document) return;
    let address;
    try { address = downloadUrl(anchor.href, origin); }
    catch (error) { event.preventDefault(); report(error); return; }
    event.preventDefault();
    // Begin fetch synchronously: Code OSS revokes its blob URL on the next task.
    // A multi-file Explorer Download emits several anchors before the user
    // chooses any destination. Fetch each blob immediately, but present its
    // Android save picker only after the preceding download has finished.
    const response = Promise.resolve(hostWindow.fetch(address, {credentials: 'same-origin'}))
      .then(value => ({value}), error => ({error}));
    const download = downloads.then(async () => {
      const fetched = await response;
      if (fetched.error) throw fetched.error;
      if (disposed) {
        await fetched.value.body?.cancel().catch(() => {});
        return;
      }
      return saveResponseToAndroid({response: fetched.value, request,
        name: anchor.download || 'download', encode: value => hostWindow.btoa(value), isClosed: () => disposed});
    });
    // One failed or cancelled file must not discard the other selections.
    downloads = download.catch(() => {});
    void download
      .then(result => {
        if (!disposed && result?.saved) notify({kind: 'info', message: `Saved ${result.name || anchor.download || 'file'} to Android.`});
      }).catch(report);
  }
  hostWindow.addEventListener('keydown', keydown, true);
  hostWindow.addEventListener('paste', paste, true);
  hostWindow.addEventListener('vstab-explorer-copy', internalCopy);
  hostWindow.addEventListener('blur', externalFocus);
  hostWindow.addEventListener('copy', copy, true);
  hostWindow.addEventListener('cut', copy, true);
  document.addEventListener('click', click, true);
  return {
    pasteFromAndroid,
    dispose() {
      disposed = true;
      hostWindow.removeEventListener('keydown', keydown, true);
      hostWindow.removeEventListener('paste', paste, true);
      hostWindow.removeEventListener('vstab-explorer-copy', internalCopy);
      hostWindow.removeEventListener('blur', externalFocus);
      hostWindow.removeEventListener('copy', copy, true);
      hostWindow.removeEventListener('cut', copy, true);
      document.removeEventListener('click', click, true);
    }
  };
}
