#!/usr/bin/env python3
"""Bounded setup-policy tests; these do not claim Android or guest boot proof."""
import base64
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest

SCRIPT = Path(__file__).with_name('termux-launch.sh').read_text()


class PackageConsent(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='vstab-termux-host-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'bin').mkdir()
        (self.root / 'home').mkdir()
        apt = self.root / 'bin/apt-get'
        apt.write_text('''#!/usr/bin/python3
import os,sys
from pathlib import Path
args=sys.argv[1:]
if args == ['update']:
 Path(os.environ['VSTAB_TEST_INSTALL_LOG']+'.metadata').write_text('update')
 print('Updated signed package indexes.')
elif '--simulate' in args:
 print('Remv existing' if os.environ.get('VSTAB_TEST_REMOVE') else 'Inst existing [1.0] (2.0 official)' if os.environ.get('VSTAB_TEST_UPGRADE') else 'Inst generic-qemu (1.0 official)')
 print('0 upgraded, 1 newly installed, 0 to remove and 0 not upgraded.')
elif '--print-uris' in args:
 print('Need to get 1234 B of archives.')
 print("'https://official.example/qemu.deb' qemu.deb 1234 SHA256:abc" + os.environ.get('VSTAB_TEST_CHANGED',''))
else:
 Path(os.environ['VSTAB_TEST_INSTALL_LOG']).write_text(' '.join(args))
 print('Installed the approved package transaction.')
''')
        apt.chmod(0o755)
        qemu = self.root / 'bin/qemu-system-x86_64'
        qemu.write_text('''#!/bin/bash
[[ "${VSTAB_TEST_QEMU:-}" == 1 ]] || exit 1
case "$1" in
 --version) echo 'QEMU emulator version 11.1.3' ;;
 -device) echo 'name "virtio-9p-pci", bus PCI' ;;
 -netdev) printf 'Available netdev backend types:\nuser\n' ;;
 *) exit 1 ;;
esac
''')
        qemu.chmod(0o755)
        self.environment = dict(os.environ,
            PATH=str(self.root / 'bin') + os.pathsep + '/usr/bin:/bin',
            HOME=str(self.root / 'home'),
            VSTAB_TEST_INSTALL_LOG=str(self.root / 'installed'))

    def invoke(self, mode, approved='', expected_exit=0, **changes):
        values = {'VSTAB_MODE_B64': mode, 'VSTAB_ARCH_B64': 'x86_64',
                  'VSTAB_ENVIRONMENT_ID_B64': 'fixture-environment',
                  'VSTAB_APPROVED_TRANSACTION_B64': approved}
        prefix = ''.join(name + "='" + base64.b64encode(value.encode()).decode() + "'\n"
                         for name, value in values.items())
        completed = subprocess.run(['bash', '-s'], input=prefix + SCRIPT,
            text=True, capture_output=True, timeout=45 if mode == 'stop' else 10, env=dict(self.environment, **changes))
        self.assertEqual(completed.returncode, expected_exit, completed.stderr)
        line = next(line for line in reversed(completed.stdout.splitlines())
                    if line.startswith('VSTAB_TERMUX_RESULT\t'))
        fields = line.split('\t')
        self.assertEqual(len(fields), 5)
        return fields[1], base64.b64decode(fields[2]).decode(), \
            base64.b64decode(fields[3]).decode(), fields[4]

    def test_preview_reports_size_and_never_installs(self):
        status, message, details, digest = self.invoke('preview')
        self.assertEqual(status, 'needs-package')
        self.assertIn('1234 B', details)
        self.assertRegex(digest, '^[a-f0-9]{64}$')
        self.assertFalse((self.root / 'installed').exists())

    def test_preflight_reuses_compatible_qemu_without_install(self):
        status, message, details, digest = self.invoke('check', VSTAB_TEST_QEMU='1')
        self.assertEqual(status, 'available')
        self.assertIn('11.1.3', details)
        self.assertEqual(digest, '')
        self.assertFalse((self.root / 'installed').exists())

    def test_preflight_offers_preview_when_qemu_cannot_run(self):
        status, message, details, digest = self.invoke('check')
        self.assertEqual(status, 'needs-package')
        self.assertIn('1234 B', details)
        self.assertRegex(digest, '^[a-f0-9]{64}$')
        self.assertFalse((self.root / 'installed').exists())

    def test_explicit_metadata_refresh_does_not_install_packages(self):
        self.assertEqual(self.invoke('refresh')[0], 'refreshed')
        self.assertEqual((self.root / 'installed.metadata').read_text(), 'update')
        self.assertFalse((self.root / 'installed').exists())

    def test_only_exact_approved_transaction_installs(self):
        digest = self.invoke('preview')[3]
        self.assertEqual(self.invoke('install', digest)[0], 'installed')
        arguments = (self.root / 'installed').read_text().split()
        self.assertIn('--no-upgrade', arguments)
        self.assertIn('--no-install-recommends', arguments)
        self.assertIn('--no-remove', arguments)
        self.assertIn('qemu-system-x86-64-headless', arguments)
        self.assertIn('xz-utils', arguments)
        self.assertNotIn('--allow-unauthenticated', arguments)

    def test_changed_transaction_requires_new_approval(self):
        digest = self.invoke('preview')[3]
        status, message, details, current = self.invoke('install', digest, VSTAB_TEST_CHANGED='changed')
        self.assertEqual(status, 'needs-package')
        self.assertNotEqual(digest, current)
        self.assertFalse((self.root / 'installed').exists())

    def test_required_dependency_upgrade_is_disclosed_and_needs_approval(self):
        status, message, details, digest = self.invoke('preview', VSTAB_TEST_UPGRADE='1')
        self.assertEqual(status, 'needs-package')
        self.assertIn('dependency changes', message)
        self.assertIn('existing [1.0] (2.0 official)', details)
        self.assertRegex(digest, '^[a-f0-9]{64}$')
        self.assertFalse((self.root / 'installed').exists())
        self.assertEqual(self.invoke('install', digest, VSTAB_TEST_UPGRADE='1')[0], 'installed')

    def test_package_removals_are_rejected(self):
        status, message, details, digest = self.invoke('preview', VSTAB_TEST_REMOVE='1')
        self.assertEqual(status, 'needs-package')
        self.assertIn('remove installed packages', message)
        self.assertEqual(digest, '')
        self.assertFalse((self.root / 'installed').exists())

    def guest_directory(self):
        directory = self.root / 'home/.local/share/vstab/linux/fixture-environment'
        (directory / 'boot').mkdir(parents=True)
        (directory / 'data.ext4').write_bytes(b'persisted-project-bytes')
        return directory

    def controlled_process(self, directory, respond=True):
        # A disposable host process exercises ownership/wait policy only. It is
        # deliberately not reported as an Android QEMU or Linux guest proof.
        program = '''import pathlib,time,sys
root=pathlib.Path(sys.argv[1])
while True:
 request=root/'boot/stop-request'
 if sys.argv[2]=='yes' and request.exists() and request.read_text()=='fixture-environment':
  (root/'clean-shutdown').write_text('yes')
  break
 time.sleep(.03)
'''
        child = subprocess.Popen([sys.executable, '-c', program, str(directory), 'yes' if respond else 'no',
            '-pidfile', str(directory / 'qemu.pid'), '-drive', 'file=' + str(directory / 'data.ext4') + ',format=raw'],
            stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        (directory / 'qemu.pid').write_text(str(child.pid) + '\n')
        threading.Thread(target=child.wait, daemon=True).start()
        def cleanup():
            if child.poll() is None:
                child.terminate(); child.wait(timeout=5)
        self.addCleanup(cleanup)
        return child

    def test_stop_absent_environment_does_not_create_or_replace_a_disk(self):
        self.assertEqual(self.invoke('stop')[0], 'stopped')
        self.assertFalse((self.root / 'home/.local/share/vstab/linux').exists())

    def test_stop_waits_for_owned_process_clean_exit_and_preserves_disk(self):
        directory = self.guest_directory()
        child = self.controlled_process(directory)
        self.assertEqual(self.invoke('stop')[0], 'stopped')
        self.assertIsNotNone(child.poll())
        self.assertTrue((directory / 'clean-shutdown').is_file())
        self.assertEqual((directory / 'data.ext4').read_bytes(), b'persisted-project-bytes')

    def test_unrelated_reused_pid_is_not_stopped(self):
        directory = self.guest_directory()
        (directory / 'qemu.pid').write_text(str(os.getpid()) + '\n')
        status, message, details, digest = self.invoke('stop', expected_exit=1)
        self.assertEqual(status, 'error')
        self.assertIn('identity does not match', message)
        self.assertFalse((directory / 'boot/stop-request').exists())
        self.assertEqual((directory / 'data.ext4').read_bytes(), b'persisted-project-bytes')

    def test_pid_start_time_mismatch_is_not_adopted(self):
        directory = self.guest_directory()
        child = self.controlled_process(directory)
        (directory / 'qemu-owner').write_text(str(child.pid) + ' 1\n')
        self.assertEqual(self.invoke('stop', expected_exit=1)[0], 'error')
        self.assertIsNone(child.poll())
        self.assertFalse((directory / 'boot/stop-request').exists())

    def test_unresponsive_owned_guest_is_not_force_killed_or_replaced(self):
        directory = self.guest_directory()
        child = self.controlled_process(directory, respond=False)
        status, message, details, digest = self.invoke('stop', expected_exit=1)
        self.assertEqual(status, 'error')
        self.assertIn('did not shut down cleanly', message)
        self.assertIsNone(child.poll())
        self.assertEqual((directory / 'data.ext4').read_bytes(), b'persisted-project-bytes')


class InstalledGuestPinning(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='vstab-termux-pinning-test-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / 'kernel').write_bytes(b'original paired kernel')
        (self.root / 'initrd').write_bytes(b'original paired initrd')
        (self.root / 'data.ext4').write_bytes(b'user modified disk with projects')
        self.base = hashlib.sha256(b'original base image').hexdigest()
        (self.root / 'data-base.sha256').write_text(self.base + '\n')
        self.installed = {'SCHEMA': '1', 'CODE_VERSION': '1.146.0', 'SERVER_VERSION': '4.146.0',
            'KERNEL_SHA256': hashlib.sha256((self.root / 'kernel').read_bytes()).hexdigest(),
            'INITRD_SHA256': hashlib.sha256((self.root / 'initrd').read_bytes()).hexdigest(),
            'DISK_UNPACKED_SHA256': self.base,
            'KERNEL_ARGS_B64': base64.b64encode(b'root=/dev/vda console=ttyS0 paired-old-args').decode()}
        self.write_spec(self.root / 'installed-guest', self.installed)

    @staticmethod
    def write_spec(path, values):
        path.write_text(''.join(key + '=' + base64.b64encode(value.encode()).decode() + '\n'
                                for key, value in values.items()))

    def select(self, **changes):
        offered = dict(self.installed, KERNEL_SHA256=hashlib.sha256(b'new kernel').hexdigest(),
            DISK_UNPACKED_SHA256=hashlib.sha256(b'new base image').hexdigest(),
            KERNEL_ARGS_B64=base64.b64encode(b'new unpaired kernel args').decode(), **changes)
        self.write_spec(self.root / 'offered', offered)
        library = SCRIPT.split('\nvstab_mode=', 1)[0]
        script = library + '\nvstab_root=' + repr(str(self.root)) + '\n' + '''
declare -A vstab_spec
read_guest_spec "$vstab_root/offered" vstab_spec
pin_installed_guest
printf 'PINNED\\t%s\\t%s\\t%s\\t%s\\t%s\\n' "${vstab_spec[KERNEL_SHA256]}" "${vstab_spec[DISK_UNPACKED_SHA256]}" "${vstab_spec[KERNEL_ARGS_B64]}" "${vstab_spec[DISK_GROWTH]:-false}" "${vstab_spec[LOGICAL_DISK_BYTES]:-0}"
'''
        before = {name: (self.root / name).read_bytes() for name in ['kernel', 'initrd', 'data.ext4'] if (self.root / name).exists()}
        completed = subprocess.run(['bash', '-s'], input=script, text=True, capture_output=True, timeout=10)
        for name, data in before.items(): self.assertEqual((self.root / name).read_bytes(), data)
        return completed

    def test_same_editor_keeps_paired_old_kernel_args_and_modified_disk(self):
        completed = self.select()
        self.assertEqual(completed.returncode, 0, completed.stderr)
        fields = completed.stdout.strip().split('\t')
        self.assertEqual(fields[0], 'PINNED')
        self.assertEqual(fields[1], self.installed['KERNEL_SHA256'])
        self.assertEqual(fields[2], self.base)
        self.assertEqual(fields[3], self.installed['KERNEL_ARGS_B64'])

    def test_other_editor_requires_migration_without_touching_runtime(self):
        completed = self.select(CODE_VERSION='1.147.0')
        self.assertEqual(completed.returncode, 1)
        self.assertIn('VSTAB_TERMUX_RESULT\terror\t', completed.stdout)

    def test_missing_pairing_record_is_preserved_without_guessing(self):
        (self.root / 'installed-guest').unlink()
        self.assertEqual(self.select().returncode, 1)

    def test_changed_old_kernel_is_not_replaced_by_new_asset(self):
        (self.root / 'kernel').write_bytes(b'corrupted old kernel')
        self.assertEqual(self.select().returncode, 1)

    def test_new_apk_cannot_grant_growth_to_an_older_installed_runtime(self):
        completed = self.select(DISK_GROWTH='true', LOGICAL_DISK_BYTES='8589934592')
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout.strip().split('\t')[4:], ['false', '0'])

    def test_installed_growth_capability_and_limit_remain_paired(self):
        self.installed.update(DISK_GROWTH='true', LOGICAL_DISK_BYTES='8589934592')
        self.write_spec(self.root / 'installed-guest', self.installed)
        completed = self.select(DISK_GROWTH='true', LOGICAL_DISK_BYTES='17179869184')
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(completed.stdout.strip().split('\t')[4:], ['true', '8589934592'])


class SparseDiskGrowth(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='vstab-termux-disk-growth-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.disk = self.root / 'data.ext4'
        self.disk.write_bytes(b'persisted user project bytes')
        with self.disk.open('r+b') as file: file.truncate(2 * 1024**3)
        (self.root / 'data-base.sha256').write_text('original seed identity')

    def grow(self, capability='true', low_space=False):
        environment = dict(os.environ)
        if low_space:
            binary = self.root / 'bin'; binary.mkdir()
            stat = binary / 'stat'
            stat.write_text('#!/bin/bash\nif [[ "$1" == -f ]]; then echo "1 4096"; else exec /usr/bin/stat "$@"; fi\n')
            stat.chmod(0o755)
            environment['PATH'] = str(binary) + os.pathsep + environment['PATH']
        library = SCRIPT.split('\nvstab_mode=', 1)[0]
        script = library + '\nvstab_root=' + repr(str(self.root)) + '\n' + '''
declare -A vstab_spec
vstab_spec[DISK_GROWTH]=''' + repr(capability) + '''
vstab_spec[LOGICAL_DISK_BYTES]=8589934592
grow_guest_disk
'''
        return subprocess.run(['bash', '-s'], input=script, text=True, capture_output=True, timeout=10, env=environment)

    def test_sparse_growth_keeps_original_bytes_seed_marker_and_physical_allocation(self):
        before = self.disk.stat().st_blocks
        result = self.grow()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.disk.stat().st_size, 8 * 1024**3)
        self.assertLessEqual((self.disk.stat().st_blocks - before) * 512, 1024 * 1024)
        with self.disk.open('rb') as file: self.assertEqual(file.read(28), b'persisted user project bytes')
        self.assertEqual((self.root / 'data-base.sha256').read_text(), 'original seed identity')

    def test_missing_capability_never_grows_an_old_runtime(self):
        self.assertEqual(self.grow(capability='false').returncode, 0)
        self.assertEqual(self.disk.stat().st_size, 2 * 1024**3)

    def test_larger_disk_is_not_shrunk(self):
        with self.disk.open('r+b') as file: file.truncate(10 * 1024**3)
        self.assertEqual(self.grow(low_space=True).returncode, 0)
        self.assertEqual(self.disk.stat().st_size, 10 * 1024**3)

    def test_low_physical_space_preserves_existing_disk(self):
        result = self.grow(low_space=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.disk.stat().st_size, 2 * 1024**3)



if __name__ == '__main__':
    unittest.main()
