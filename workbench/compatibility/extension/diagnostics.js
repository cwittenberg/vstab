'use strict';

const fs = require('node:fs/promises');
const {StringDecoder} = require('node:string_decoder');
const crypto = require('node:crypto');

const MAX_LINE = 16 * 1024;
const MAX_RECORD = 32 * 1024;
const LOG_PREFIX = /^\d{4}-\d\d-\d\d[T ]\d\d:\d\d:\d\d(?:[.,]\d+)?(?:Z|[+-]\d\d:\d\d)?\s+\[(trace|debug|info|warning|warn|error|critical)\]\s*/i;
const FAILURE = /\bActivating extension\s+['"]?([a-z0-9][a-z0-9_-]*\.[a-z0-9][a-z0-9_.-]{0,127})['"]?\s+failed(?:\s+due to an error)?\s*:\s*(.*)/i;

/** Remove credentials before any error text reaches a notification or report. */
function redact(input, {home = '', extensionPath = ''} = {}) {
  let text = String(input || '').slice(0, MAX_RECORD)
    .replace(/-----BEGIN [^-\r\n]*PRIVATE KEY-----[\s\S]*?(?:-----END [^-\r\n]*PRIVATE KEY-----|$)/g, '[private key redacted]')
    .replace(/\b(?:https?|ssh):\/\/[^\s<>"']+/gi, value => {
      try {
        const url = new URL(value);
        return url.protocol + '//' + url.host + url.pathname + (url.search || url.hash ? '?[redacted]' : '');
      } catch { return '[URL redacted]'; }
    })
    .replace(/\b(Bearer|Basic)\s+[a-z0-9+/_.=~-]+/gi, '$1 [redacted]')
    .replace(/((?:[a-z0-9_]*(?:password|passphrase|token|secret|api[_-]?key|access[_-]?key)[a-z0-9_]*|authorization)["']?\s*[:=]\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;\r\n]+)/gi, '$1[redacted]')
    .replace(/\b(?:sk-(?:proj-|ant-)?[a-z0-9_-]{12,}|gh[pousr]_[a-z0-9]{16,}|github_pat_[a-z0-9_]{16,})\b/gi, '[credential redacted]')
    .replace(/\beyJ[a-z0-9_-]+\.[a-z0-9_-]+\.[a-z0-9_-]+\b/gi, '[JWT redacted]')
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, '');
  if (extensionPath) text = text.split(extensionPath).join('{extension}');
  if (home) text = text.split(home).join('~');
  return text;
}

function classify(details, android = false) {
  const text = String(details || '');
  if (/NODE_MODULE_VERSION|compiled against a different Node|Module did not self-register|not a valid Win32 application|wrong ELF class|ELFCLASS|invalid ELF|Exec format error|dlopen failed|GLIBC_[\d.]|libstdc\+\+\.so|libc\.so\.6|cannot locate symbol|not supported.*\bandroid\b|unsupported (?:platform|operating system).*\bandroid\b|\bandroid\b.*(?:not supported|unsupported)/i.test(text)) {
    return {category: 'native-platform', summary: android
      ? 'A bundled native component cannot run on this Android device.'
      : 'A bundled native component does not match this host or Node runtime.', suggestRemote: android};
  }
  if (/EACCES|EPERM|Permission denied|bad interpreter/i.test(text)) {
    return {category: 'permission', summary: android
      ? 'Android blocked a file or helper program the extension needs.'
      : 'The extension could not access a required file or start its helper.', suggestRemote: android};
  }
  if (/Cannot find module|ERR_MODULE_NOT_FOUND|MODULE_NOT_FOUND|ENOENT|No such file or directory/i.test(text)) {
    return {category: 'missing-dependency', summary: 'A required module, file or helper program is missing.', suggestRemote: false};
  }
  if (/proposed (?:API|apis)|API proposal|not a function|Cannot read properties of undefined|requires.*(?:vscode|VS Code)|incompatible.*(?:vscode|VS Code)/i.test(text)) {
    return {category: 'editor-api', summary: 'The extension may require an editor API this build does not provide.', suggestRemote: false};
  }
  if (/certificate|CERT_|self.signed|ENOTFOUND|ECONNREFUSED|ETIMEDOUT|fetch failed/i.test(text)) {
    return {category: 'network', summary: 'A required network connection or certificate check failed.', suggestRemote: false};
  }
  return {category: 'activation', summary: 'The extension reported an error while starting.', suggestRemote: false};
}

/**
 * Parse the exact Code OSS extHostExtensionActivator log records. A failure
 * marker is required; inactive extensions and unrelated [error] lines do not
 * establish a failure. Continuations may span file reads and UTF-8 chunks.
 */
class ActivationLogParser {
  constructor() { this.reset(); }
  reset() { this.partial = ''; this.pending = null; this.discarding = false; }
  push(text) {
    const records = [];
    const lines = (this.partial + text).split('\n');
    this.partial = lines.pop();
    for (let line of lines) {
      if (this.discarding) { this.discarding = false; continue; }
      line = line.replace(/\r$/, '').slice(0, MAX_LINE);
      const prefix = line.match(LOG_PREFIX);
      const message = prefix ? line.slice(prefix[0].length) : line;
      const failure = message.match(FAILURE);
      if (failure) {
        this.finish(records);
        this.pending = {extensionId: failure[1].toLowerCase(), details: failure[2]};
      } else if (this.pending) {
        if (prefix && !['error', 'critical'].includes(prefix[1].toLowerCase())) {
          this.finish(records);
        } else {
          this.pending.details = (this.pending.details + '\n' + message).slice(0, MAX_RECORD);
        }
      }
    }
    if (this.discarding || this.partial.length > MAX_LINE) {
      this.partial = '';
      this.discarding = true;
    }
    return records;
  }
  finish(records) {
    if (!this.pending) return;
    records.push({extensionId: this.pending.extensionId,
      details: this.pending.details.trim() || 'Activation failed without further error details.'});
    this.pending = null;
  }
  /** Call only after an idle read, so a header is not separated from its error. */
  flush() {
    const records = [];
    if (!this.partial) this.finish(records);
    return records;
  }
}

/** Incremental bounded file reads, with replacement/truncation and UTF-8 safety. */
class BoundedLogReader {
  constructor(filename, {maxInitialBytes = 512 * 1024, maxReadBytes = 128 * 1024} = {}) {
    this.filename = filename;
    this.maxInitialBytes = maxInitialBytes;
    this.maxReadBytes = maxReadBytes;
    this.identity = null;
    this.offset = 0;
    this.decoder = new StringDecoder('utf8');
    this.skipFirstLine = false;
    this.anchor = Buffer.alloc(0);
    this.anchorPosition = 0;
  }
  async read() {
    let handle;
    try {
      handle = await fs.open(this.filename, 'r');
      const stat = await handle.stat();
      if (!stat.isFile()) return {text: '', missing: true, caughtUp: true};
      const identity = stat.dev + ':' + stat.ino;
      let rotated = this.identity !== null && (identity !== this.identity || stat.size < this.offset);
      // A log can be truncated and regrow past our old offset between polls.
      // Check the last bytes read, not only the inode and current file length.
      if (!rotated && this.identity !== null && this.anchor.length) {
        const anchor = Buffer.alloc(this.anchor.length);
        const result = await handle.read(anchor, 0, anchor.length, this.anchorPosition);
        rotated = result.bytesRead !== anchor.length || !anchor.equals(this.anchor);
      }
      let skippedBytes = 0;
      if (this.identity === null || rotated) {
        this.identity = identity;
        this.offset = Math.max(0, stat.size - this.maxInitialBytes);
        skippedBytes = this.offset;
        this.decoder = new StringDecoder('utf8');
        this.skipFirstLine = this.offset > 0;
        this.anchor = Buffer.alloc(0);
      }
      const length = Math.min(Math.max(0, stat.size - this.offset), this.maxReadBytes);
      const buffer = Buffer.alloc(length);
      const {bytesRead} = length ? await handle.read(buffer, 0, length, this.offset) : {bytesRead: 0};
      this.offset += bytesRead;
      if (bytesRead) {
        this.anchor = Buffer.from(buffer.subarray(Math.max(0, bytesRead - 64), bytesRead));
        this.anchorPosition = this.offset - this.anchor.length;
      }
      let text = this.decoder.write(buffer.subarray(0, bytesRead));
      if (this.skipFirstLine) {
        const newline = text.indexOf('\n');
        this.skipFirstLine = newline < 0;
        text = newline < 0 ? '' : text.slice(newline + 1);
      }
      return {text, rotated, skippedBytes, bytesRead, caughtUp: this.offset >= stat.size};
    } catch (error) {
      if (error.code === 'ENOENT') return {text: '', missing: true, caughtUp: true};
      throw error;
    } finally { await handle?.close(); }
  }
}

function fingerprint(record) {
  return crypto.createHash('sha256').update([
    record.extensionId, record.version, record.reason.category, record.details
      .replace(LOG_PREFIX, '').replace(/:\d+:\d+/g, ':line:column')
  ].join('\0')).digest('hex');
}

module.exports = {redact, classify, ActivationLogParser, BoundedLogReader, fingerprint, MAX_RECORD};
