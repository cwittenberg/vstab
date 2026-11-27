#!/usr/bin/env node
// Invoke the official source packaging functions without the separate
// Marketplace-download step used by its aggregate gulp task.
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { pipeline } from 'node:stream/promises';
import { mkdir, rm, writeFile, readFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const root = resolve(import.meta.dirname, '..');
const source = resolve(process.argv[2] || join(root, '.local/upstream/vscode'));
const expectedCommit = '69047191fc427f2f92828279aae1d90d8eeda7ab';
const actualCommit = execFileSync('git', ['-C', source, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
if (actualCommit !== expectedCommit) throw new Error('The browser extension source is not the pinned Code OSS revision.');
process.chdir(source);
const { gulp } = await import(pathToFileURL(join(source, 'build/lib/gulp/facade.ts')));
const extensions = await import(pathToFileURL(join(source, 'build/lib/extensions.ts')));
const output = join(source, '.build/web');
await rm(output, { recursive: true, force: true });
await mkdir(output, { recursive: true });
await pipeline(extensions.packageAllLocalExtensionsStream(true), gulp.dest(output));
await extensions.buildExtensionMedia(false, join(output, 'extensions'));
const lockHashes = {};
for (const relative of ['pnpm-lock.yaml', 'build/pnpm-lock.yaml', 'extensions/pnpm-lock.yaml']) {
  lockHashes[relative] = createHash('sha256').update(await readFile(join(source, relative))).digest('hex');
}
await writeFile(join(output, 'vstab-build-provenance.json'), JSON.stringify({
  schema: 1, upstreamRepository: 'https://github.com/microsoft/vscode', upstreamCommit: actualCommit,
  nodeVersion: process.version, packageManager: 'pnpm@12.16.0', lockHashes,
  steps: ['packageAllLocalExtensionsStream(true)', 'buildExtensionMedia(false)'],
  marketplaceDownloadStep: false,
}, null, 2) + '\n');
console.log('Built the pinned official Code OSS browser extensions at ' + output);
