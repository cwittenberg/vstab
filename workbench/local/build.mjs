import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { readFile, writeFile } from 'node:fs/promises';

const root = fileURLToPath(new URL('../../', import.meta.url));
const local = fileURLToPath(new URL('./', import.meta.url));
const upstream = process.env.VSTAB_UPSTREAM_SOURCE || root + '.local/upstream/vscode';
const upstreamGlob = upstream + '/src/vs/base/common/glob.ts';
const license = await readFile(upstream + '/LICENSE.txt', 'utf8');

await build({
  entryPoints: [local + 'src/extension.cjs'],
  outfile: local + 'extension/extension.js',
  bundle: true,
  platform: 'browser',
  format: 'cjs',
  target: ['chrome133'],
  external: ['vscode'],
  alias: { 'vstab-upstream-glob': upstreamGlob },
  legalComments: 'inline',
  banner: { js: '/* vstab local workspace; includes the MIT-licensed Microsoft Code OSS glob matcher. */' },
  logLevel: 'info'
});
await writeFile(local + 'extension/UPSTREAM-LICENSE.txt', license);
await writeFile(local + 'extension/IGNORE-LICENSE.txt', await readFile(local + 'node_modules/ignore/LICENSE-MIT', 'utf8'));
