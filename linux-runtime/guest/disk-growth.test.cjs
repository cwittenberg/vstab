'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {ext4Geometry, rootDeviceMatches} = require('./disk-growth.cjs');
test('ext4 geometry includes 64-bit block counts and rejects an unrelated filesystem', () => {
  const block = Buffer.alloc(1024); block.writeUInt16LE(0xef53, 0x38); block.writeUInt32LE(2, 0x18);
  block.writeUInt32LE(524288, 4);
  assert.equal(ext4Geometry(block).bytes, 2n * 1024n ** 3n);
  block.writeUInt32LE(0x80, 0x60); block.writeUInt32LE(1, 0x150);
  assert.equal(ext4Geometry(block).blocks, (1n << 32n) + 524288n);
  block.writeUInt16LE(0, 0x38); assert.throws(() => ext4Geometry(block), /not_ext4/);
});
test('grow eligibility requires the mounted writable root ext4 device identity', () => {
  const line = '20 1 254:0 / / rw,relatime shared:1 - ext4 /dev/root rw';
  assert.equal(rootDeviceMatches(line, '254:0'), true);
  assert.equal(rootDeviceMatches(line, '254:16'), false);
  assert.equal(rootDeviceMatches(line.replace('ext4', 'xfs'), '254:0'), false);
  assert.equal(rootDeviceMatches(line.replace('rw,relatime', 'ro,relatime'), '254:0'), false);
});
