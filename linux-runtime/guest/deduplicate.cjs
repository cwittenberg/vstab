'use strict';
// Share immutable editor files only when their bytes and permission identity are
// identical. Every original pathname and byte sequence remains available.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const root = process.argv[2];
if (!root || process.argv.length !== 3) throw Error('Expected one editor root');
const groups = new Map();
let files = 0, saved = 0;
function visit(directory) {
  for (const name of fs.readdirSync(directory)) {
    const filename = path.join(directory, name), stat = fs.lstatSync(filename);
    if (stat.isDirectory()) { visit(filename); continue; }
    if (!stat.isFile() || stat.size < 1024) continue;
    const identity = [stat.size, stat.mode, stat.uid, stat.gid].join(':');
    let candidates = groups.get(identity); if (!candidates) groups.set(identity, candidates = new Map());
    const bytes = fs.readFileSync(filename), digest = crypto.createHash('sha256').update(bytes).digest('hex');
    const original = candidates.get(digest);
    if (!original) { candidates.set(digest, {filename, ino: stat.ino}); continue; }
    if (original.ino === stat.ino) continue;
    if (!bytes.equals(fs.readFileSync(original.filename))) throw Error('Unexpected digest collision');
    fs.unlinkSync(filename); fs.linkSync(original.filename, filename); files++; saved += stat.size;
  }
}
visit(root);
console.log(JSON.stringify({schema: 1, identicalFilesHardlinked: files, rawBytesShared: saved}));
