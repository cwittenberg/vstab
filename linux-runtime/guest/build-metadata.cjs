'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
function sha(filename) { return crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex'); }
function metadata(root, output, archive) {
  const components = {
    serverArchiveSha256: sha(archive), kernelSha256: sha(path.join(output, 'kernel')),
    kernelConfigSha256: sha(path.join(output, 'kernel.config')),
    debianPackageInventorySha256: sha(path.join(output, 'packages.tsv')), integrationFiles: {},
  };
  for (const filename of ['/sbin/vstab-init', '/usr/local/bin/xdg-open', '/usr/local/lib/vstab/supervisor.cjs', '/usr/local/lib/vstab/open-uri.cjs', '/usr/local/lib/vstab/disk-growth.cjs', '/usr/local/lib/vstab/dns-relay.cjs', '/usr/local/bin/vstab-android-files']) {
    const actual = path.join(root, filename);
    if (fs.existsSync(actual)) components.integrationFiles[filename] = sha(actual);
  }
  const identity = {metadataSchema: 1, platformApiVersion: 1, dataSchemaVersion: 1,
    userDataMode: 'whole-root', upgradePolicy: 'preserve-installed-system', logicalDiskBytes: 8 * 1024 ** 3,
    serverVersion: '4.146.0', codeVersion: '1.146.0', components};
  const guestBuildId = crypto.createHash('sha256').update(JSON.stringify(identity)).digest('hex');
  return {schema: 1, guestBuildId, ...identity,
    kernelVersion: fs.readFileSync(path.join(output, 'kernel-version.txt'), 'utf8').trim(),
    nodeVersion: fs.readFileSync(path.join(output, 'node-version.txt'), 'utf8').trim(),
    capabilities: {gracefulStopRequest: true, separateHomeDisk: false, diskGrowth: true, dnsRelay: true}};
}
module.exports = {metadata};
if (require.main === module) {
  if (process.argv.length !== 5) throw Error('Expected rootfs, build output and pinned server archive');
  const result = metadata(process.argv[2], process.argv[3], process.argv[4]);
  const filename = path.join(process.argv[2], '/usr/local/share/vstab/guest-build.json');
  fs.writeFileSync(filename, JSON.stringify(result, null, 2) + '\n', {mode: 0o644});
}
