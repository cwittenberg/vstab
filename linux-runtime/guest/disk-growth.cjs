'use strict';
const fs = require('node:fs');
const {spawnSync} = require('node:child_process');
const DEVICE = '/dev/vda';
function ext4Geometry(superblock) {
  if (superblock.length < 1024 || superblock.readUInt16LE(0x38) !== 0xef53) throw Error('root_disk_not_ext4');
  const shift = superblock.readUInt32LE(0x18);
  if (shift > 6) throw Error('root_disk_block_size_invalid');
  const high = superblock.readUInt32LE(0x60) & 0x80 ? BigInt(superblock.readUInt32LE(0x150)) : 0n;
  const blocks = BigInt(superblock.readUInt32LE(4)) + (high << 32n);
  if (!blocks) throw Error('root_disk_block_count_invalid');
  const blockSize = 1024n << BigInt(shift);
  return {blocks, blockSize, bytes: blocks * blockSize};
}
function rootDeviceMatches(mountInfo, expectedDevice) {
  const root = mountInfo.split('\n').map(line => line.split(' ')).find(fields => fields[4] === '/');
  if (!root) return false;
  const separator = root.indexOf('-');
  return separator > 5 && root[2] === expectedDevice && root[separator + 1] === 'ext4' && root[5].split(',').includes('rw');
}
function geometry() {
  const descriptor = fs.openSync(DEVICE, 'r');
  try {
    const superblock = Buffer.alloc(1024);
    if (fs.readSync(descriptor, superblock, 0, superblock.length, 1024) !== superblock.length) throw Error('root_disk_superblock_short');
    return ext4Geometry(superblock);
  } finally { fs.closeSync(descriptor); }
}
function growRootDisk() {
  if (!fs.lstatSync(DEVICE).isBlockDevice()) throw Error('root_disk_not_block_device');
  const deviceIdentity = fs.readFileSync('/sys/class/block/vda/dev', 'utf8').trim();
  if (!/^\d+:\d+$/.test(deviceIdentity) || !rootDeviceMatches(fs.readFileSync('/proc/self/mountinfo', 'utf8'), deviceIdentity)) throw Error('root_disk_device_mismatch');
  const sectors = fs.readFileSync('/sys/class/block/vda/size', 'utf8').trim();
  if (!/^[1-9]\d*$/.test(sectors)) throw Error('root_disk_capacity_invalid');
  const capacity = BigInt(sectors) * 512n;
  const before = geometry();
  if (capacity < before.bytes || capacity > 16n * 1024n ** 4n) throw Error('root_disk_capacity_invalid');
  let after = before;
  if (capacity / before.blockSize > before.blocks) {
    const resize = spawnSync('/usr/sbin/resize2fs', [DEVICE], {encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe']});
    if (resize.status !== 0) {
      console.error('VSTAB_LINUX_DISK_ERROR ' + JSON.stringify({schema: 1, code: 'root_disk_resize_failed', message: String(resize.stderr || resize.error?.message || '').slice(0, 2048)}));
      throw Error('root_disk_resize_failed');
    }
    if (spawnSync('/bin/sync', [], {stdio: 'ignore'}).status !== 0) throw Error('root_disk_sync_failed');
    after = geometry();
    if (after.blocks !== capacity / after.blockSize) throw Error('root_disk_resize_incomplete');
  }
  return {device: DEVICE, filesystem: 'ext4', capacityBytes: Number(capacity), filesystemBytes: Number(after.bytes), grown: after.bytes > before.bytes};
}
module.exports = {ext4Geometry, rootDeviceMatches, growRootDisk};
