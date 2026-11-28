# Android QEMU source provenance

The binary package hashes and authenticated Termux indexes are pinned in
[`release/android-qemu.lock.json`](../../release/android-qemu.lock.json).
[`release/android-qemu.sources.json`](../../release/android-qemu.sources.json)
records the matching official recipe, source archive and verification scope.
These source records do not change the frozen executable or archive inputs.

The signed packages identify version `1:11.1.3`, without a package revision.
The matching [Termux recipe commit](https://github.com/termux/termux-packages/commit/7251e3f37e348a6b9bb30951d7649b83f84d4c14)
pins [QEMU 11.1.3 source](https://download.qemu.org/qemu-11.1.3.tar.xz), whose
141,819,712 downloaded bytes were verified against the recipe's SHA-256:
`5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22`.
Use the [whole pinned recipe directory](https://github.com/termux/termux-packages/tree/7251e3f37e348a6b9bb30951d7649b83f84d4c14/packages/qemu-system-x86-64-headless),
including its Android patches, architecture helpers and subpackage recipes,
with the Termux build infrastructure at that repository commit.

The package metadata does not attest the Git commit used to build its binaries.
This is a version-matched source recipe, without a byte-for-byte rebuild claim.
The packager preserves the license/copyright notices available in the downloaded
packages and license package inside the host archives. Exact source/build
history for every runtime library, firmware file and build dependency has not
been collected here. This record does not claim a complete corresponding-source
bundle or a written source offer.
