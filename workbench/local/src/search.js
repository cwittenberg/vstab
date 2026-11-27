import { match as matchGlob } from 'vstab-upstream-glob';
import ignore from 'ignore';

const MAX_ENTRIES = 20000;
const MAX_FILE_BYTES = 16 * 1024 * 1024;
const MAX_TOTAL_BYTES = 64 * 1024 * 1024;
const isCancelled = token => Boolean(token?.isCancellationRequested);
const anyGlob = (patterns, path) => (patterns || []).some(pattern => matchGlob(pattern, path));
const warning = text => ({ text, type: 2 });

function fuzzyContains(pattern, value) {
  pattern = pattern.toLowerCase().replaceAll('\\', '/');
  value = value.toLowerCase();
  let index = 0;
  for (const character of value) if (character === pattern[index]) index++;
  return index === pattern.length;
}

function decode(bytes, encoding) {
  const aliases = { utf8: 'utf-8', utf8bom: 'utf-8', utf16le: 'utf-16le', utf16be: 'utf-16be', windows1252: 'windows-1252' };
  return new TextDecoder(aliases[encoding] || encoding || 'utf-8', { fatal: false }).decode(bytes);
}

function lineStarts(text) {
  const result = [0];
  for (let index = 0; index < text.length; index++) if (text.charCodeAt(index) === 10) result.push(index + 1);
  return result;
}

function position(starts, offset) {
  let low = 0, high = starts.length;
  while (low + 1 < high) {
    const middle = (low + high) >>> 1;
    if (starts[middle] <= offset) low = middle; else high = middle;
  }
  return { line: low, character: offset - starts[low] };
}

function preview(vscode, lines, start, end, options = {}) {
  const count = Math.max(1, options.matchLines || 100);
  const maxChars = Math.max(1, options.charsPerLine || 1000);
  const firstOffset = Math.max(0, start.character - Math.floor(maxChars / 3));
  const shown = lines.slice(start.line, Math.min(end.line + 1, start.line + count)).map((line, index) => line.slice(index ? 0 : firstOffset, (index ? 0 : firstOffset) + maxChars));
  const lastLine = Math.min(end.line - start.line, shown.length - 1);
  const lastCharacter = end.line - start.line > lastLine ? shown[lastLine].length : Math.min(shown[lastLine].length, Math.max(0, end.character - (lastLine === 0 ? firstOffset : 0)));
  return { text: shown.join('\n'), matches: new vscode.Range(0, start.character - firstOffset, lastLine, lastCharacter) };
}

export function createSearchProviders(vscode, fs, pathFor) {
  let generation = 0;
  let cache;
  let lastFileWarning = 0;
  const childUri = (root, relative) => vscode.Uri.from({ scheme: root.scheme, path: pathFor(root) + '/' + relative });

  async function collect(options, token) {
    const root = options.folder;
    const key = JSON.stringify([pathFor(root), options.includes, options.excludes, options.useIgnoreFiles, generation]);
    if (cache?.key === key && Date.now() - cache.time < 3000) return cache.value;
    const value = { files: [], limitHit: false, skipped: 0 };
    const stack = [{ relative: '', ignores: [] }];
    let entries = 0;
    while (stack.length && !isCancelled(token)) {
      const current = stack.pop();
      const uri = current.relative ? childUri(root, current.relative) : root;
      let children;
      try { children = await fs.readDirectory(uri); } catch (_) { value.skipped++; continue; }
      if (isCancelled(token)) break;
      let ignores = current.ignores;
      if (options.useIgnoreFiles && children.some(([name, type]) => name === '.gitignore' && (type & 1))) {
        try {
          const bytes = await fs.readFile(childUri(root, current.relative ? current.relative + '/.gitignore' : '.gitignore'));
          if (bytes.length <= 256 * 1024) ignores = [...ignores, { base: current.relative, matcher: ignore().add(decode(bytes)) }];
        } catch (_) { /* A missing/unreadable ignore file does not hide files. */ }
      }
      for (const [name, type] of children.sort((a, b) => a[0].localeCompare(b[0]))) {
        if (isCancelled(token)) break;
        if (++entries > MAX_ENTRIES) { value.limitHit = true; break; }
        if (!name || name === '.' || name === '..' || name.includes('/') || name.includes('\\')) continue;
        const relative = current.relative ? current.relative + '/' + name : name;
        const directory = Boolean(type & 2);
        if (type & 64) continue; // Native local workspaces do not grant symlink traversal.
        if (anyGlob(options.excludes, relative) || (directory && anyGlob(options.excludes, relative + '/'))) continue;
        let ignored = false;
        for (const rule of ignores) {
          const path = rule.base ? relative.slice(rule.base.length + 1) : relative;
          const state = rule.matcher.test(path + (directory ? '/' : ''));
          if (state.ignored) ignored = true;
          else if (state.unignored) ignored = false;
        }
        if (ignored) continue;
        if (directory) stack.push({ relative, ignores });
        else if ((type & 1) && (!(options.includes?.length) || anyGlob(options.includes, relative))) value.files.push({ uri: childUri(root, relative), relative });
      }
      if (value.limitHit) break;
    }
    if (!isCancelled(token)) cache = { key, time: Date.now(), value };
    return value;
  }

  return {
    invalidate() { generation++; cache = undefined; },
    file: {
      async provideFileSearchResults(query, options, token) {
        const collected = await collect(options, token);
        const matches = [];
        const maximum = options.maxResults ?? Number.MAX_SAFE_INTEGER;
        for (const entry of collected.files) {
          if (isCancelled(token) || matches.length >= maximum) break;
          if (!query.pattern || fuzzyContains(query.pattern, entry.relative)) matches.push(entry.uri);
        }
        if (collected.limitHit && Date.now() - lastFileWarning > 30000) {
          lastFileWarning = Date.now();
          vscode.window.showWarningMessage('Local file search reached its 20,000-entry limit. Narrow the folder or exclusions.');
        }
        return matches;
      }
    },
    text: {
      async provideTextSearchResults(query, options, progress, token) {
        if (!query.pattern || isCancelled(token)) return { limitHit: false };
        const source = query.isRegExp ? query.pattern : query.pattern.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        const expression = new RegExp(source, 'gu' + (query.isCaseSensitive ? '' : 'i') + (query.isMultiline ? 'm' : ''));
        const word = /[\p{L}\p{N}_]/u;
        const collected = await collect(options, token);
        const maxResults = options.maxResults ?? Number.MAX_SAFE_INTEGER;
        const maxFileSize = Math.min(options.maxFileSize ?? MAX_FILE_BYTES, MAX_FILE_BYTES);
        let results = 0, bytesRead = 0, skippedSize = 0, skippedError = collected.skipped;
        let limitHit = collected.limitHit;
        outer: for (const entry of collected.files) {
          if (isCancelled(token)) break;
          let bytes;
          try {
            const stat = await fs.stat(entry.uri);
            if (stat.size > maxFileSize) { skippedSize++; continue; }
            if (bytesRead + stat.size > MAX_TOTAL_BYTES) { limitHit = true; break; }
            bytes = await fs.readFile(entry.uri);
          } catch (_) { skippedError++; continue; }
          bytesRead += bytes.byteLength;
          if (isCancelled(token)) break;
          if ((!options.encoding || /^utf8/.test(options.encoding)) && bytes.subarray(0, 8192).includes(0)) continue;
          let text;
          try { text = decode(bytes, options.encoding); } catch (_) { skippedError++; continue; }
          const starts = lineStarts(text);
          const lines = text.split('\n').map(line => line.endsWith('\r') ? line.slice(0, -1) : line);
          const contextReported = new Set();
          expression.lastIndex = 0;
          let match;
          while ((match = expression.exec(text))) {
            if (isCancelled(token)) break outer;
            if (match[0].length === 0) { expression.lastIndex += text.codePointAt(expression.lastIndex) > 0xffff ? 2 : 1; continue; }
            const last = match.index + match[0].length;
            if (query.isWordMatch && ((match.index && word.test(text[match.index - 1])) || (last < text.length && word.test(text[last])))) continue;
            if (results >= maxResults) { limitHit = true; break outer; }
            const start = position(starts, match.index), end = position(starts, last);
            if (!query.isMultiline && start.line !== end.line) continue;
            for (let line = Math.max(0, start.line - (options.beforeContext || 0)); line < start.line; line++) {
              if (!contextReported.has(line)) { progress.report({ uri: entry.uri, text: lines[line], lineNumber: line }); contextReported.add(line); }
            }
            progress.report({ uri: entry.uri, ranges: new vscode.Range(start.line, start.character, end.line, end.character), preview: preview(vscode, lines, start, end, options.previewOptions) });
            results++;
            for (let line = end.line + 1; line <= Math.min(lines.length - 1, end.line + (options.afterContext || 0)); line++) {
              if (!contextReported.has(line)) { progress.report({ uri: entry.uri, text: lines[line], lineNumber: line }); contextReported.add(line); }
            }
          }
        }
        const messages = [];
        if (collected.limitHit) messages.push(warning('Local search reached its 20,000-entry limit. Narrow the folder or exclusions.'));
        if (bytesRead >= MAX_TOTAL_BYTES || (limitHit && results < maxResults && !collected.limitHit)) messages.push(warning('Local search reached its 64 MiB read budget. Narrow the folder or exclusions.'));
        if (skippedSize) messages.push(warning(`${skippedSize} files exceeded the local search file-size limit.`));
        if (skippedError) messages.push(warning(`${skippedError} files or folders could not be searched.`));
        return { limitHit, message: messages.length ? messages : undefined };
      }
    }
  };
}
