#!/usr/bin/env python3
"""Prepare genuine, matching SYMBOL_TABLE files for AGP's native-symbol artifact.

Pre-stripped vendor ELF files cannot supply their missing static symbol tables.
They are recorded as unavailable, never renamed or copied into the symbol set.
The runtime JNI payload is read-only throughout this operation.
"""
import argparse
import hashlib
import json
import mmap
import os
from pathlib import Path
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / '.local/android-runtime'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def elf_info(path):
    """Read ELF64 section identities and real defined function symbols."""
    with path.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        if data[:6] != b'\x7fELF\x02\x01':
            raise ValueError('Expected a little-endian ELF64 file: ' + str(path))
        machine = struct.unpack_from('<H', data, 18)[0]
        offset = struct.unpack_from('<Q', data, 40)[0]
        entry_size, count, names_index = struct.unpack_from('<HHH', data, 58)
        if not count or entry_size != 64 or names_index >= count:
            raise ValueError('ELF section table is missing or unsupported: ' + str(path))
        sections = [struct.unpack_from('<IIQQQQIIQQ', data, offset + i * entry_size)
                    for i in range(count)]
        names = sections[names_index]
        section_names = data[names[4]:names[4] + names[5]]
        allocated = []
        functions = []
        symbol_count = 0
        debug_info = False
        build_id = None
        for section in sections:
            name_offset, kind, flags, address, start, size, link, _, _, width = section
            name = section_names[name_offset:].split(b'\0', 1)[0].decode('utf-8', 'replace')
            if flags & 2:
                allocated.append({'name': name, 'address': address, 'bytes': size,
                                  'sha256': None if kind == 8 else
                                  hashlib.sha256(data[start:start + size]).hexdigest()})
            if name == '.debug_info':
                debug_info = True
            if name == '.note.gnu.build-id':
                end = start + size
                while start + 12 <= end:
                    name_size, desc_size, note_type = struct.unpack_from('<III', data, start)
                    owner = data[start + 12:start + 12 + name_size].rstrip(b'\0')
                    desc_start = start + 12 + ((name_size + 3) & ~3)
                    if owner == b'GNU' and note_type == 3:
                        build_id = data[desc_start:desc_start + desc_size].hex()
                    start = desc_start + ((desc_size + 3) & ~3)
            if kind == 2:  # SHT_SYMTAB, not the vendor's remaining SHT_DYNSYM.
                if width != 24 or link >= len(sections):
                    raise ValueError('Unsupported ELF static symbol table: ' + str(path))
                strings_section = sections[link]
                strings = data[strings_section[4]:strings_section[4] + strings_section[5]]
                symbol_count += size // width
                for index in range(size // width):
                    name_at, info, _, section_index, value, length = struct.unpack_from(
                        '<IBBHQQ', data, section[4] + index * width)
                    if info & 15 == 2 and section_index != 0:
                        symbol_name = strings[name_at:].split(b'\0', 1)[0].decode('utf-8', 'replace')
                        if symbol_name:
                            functions.append({'name': symbol_name, 'address': value, 'bytes': length})
        return {'machine': machine, 'buildId': build_id, 'allocatedSections': allocated,
                'staticSymbolCount': symbol_count, 'definedFunctionCount': len(functions),
                'functionExamples': functions[:8], 'hasDebugInfo': debug_info}


def matches_payload(symbols, payload):
    return (symbols['machine'] == payload['machine'] and
            symbols['buildId'] == payload['buildId'] and
            symbols['allocatedSections'] == payload['allocatedSections'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME'))
    parser.add_argument('--jni-root', type=Path, default=RUNTIME / 'jniLibs')
    parser.add_argument('--unstripped-root', type=Path, default=RUNTIME / 'unstripped')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.ndk is None:
        parser.error('Pass --ndk or set ANDROID_NDK_HOME')
    objcopy = args.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy'
    if not objcopy.is_file():
        parser.error('The Android NDK llvm-objcopy executable is missing')
    jni_root = args.jni_root.resolve()
    output = args.output.resolve()
    if output == jni_root or output in jni_root.parents or jni_root in output.parents:
        parser.error('Native symbol output must be separate from the runtime JNI payload')
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    records = {}
    prepared = 0
    for payload_path in sorted(jni_root.glob('*/*.so')):
        relative = payload_path.relative_to(jni_root)
        payload_hash = sha(payload_path)
        payload = elf_info(payload_path)
        record = {'payloadSha256': payload_hash, 'buildId': payload['buildId'],
                  'prepared': False, 'rejectedUnstrippedCandidates': []}
        candidates = [payload_path, args.unstripped_root / relative]
        for candidate in candidates:
            if not candidate.is_file():
                continue
            info = payload if candidate == payload_path else elf_info(candidate)
            if not info['staticSymbolCount'] or not info['definedFunctionCount']:
                continue
            if not matches_payload(info, payload):
                record['rejectedUnstrippedCandidates'].append(str(candidate))
                continue
            symbol_path = output / relative.parent / (relative.name + '.sym')
            symbol_path.parent.mkdir(parents=True, exist_ok=True)
            subprocess.run([str(objcopy), '--strip-debug', str(candidate), str(symbol_path)], check=True)
            symbols = elf_info(symbol_path)
            if not symbols['staticSymbolCount'] or not symbols['definedFunctionCount'] or not matches_payload(symbols, payload):
                raise RuntimeError('Generated native symbols do not match the runtime ELF: ' + str(relative))
            if sha(payload_path) != payload_hash:
                raise RuntimeError('Runtime JNI payload changed during symbol preparation: ' + str(relative))
            record.update({'prepared': True, 'symbolsSha256': sha(symbol_path),
                           'symbolSource': str(candidate), 'symbolFile': str(relative) + '.sym',
                           'staticSymbolCount': symbols['staticSymbolCount'],
                           'definedFunctionCount': symbols['definedFunctionCount'],
                           'functionExamples': symbols['functionExamples'],
                           'matchingAllocatedSections': True,
                           'hasGnuBuildId': symbols['buildId'] is not None})
            prepared += 1
            break
        if not record['prepared']:
            record['reason'] = 'Static symbol table was stripped and no matching unstripped build is available'
        records[str(relative)] = record
    if not records or not prepared:
        raise RuntimeError('No genuine matching native function symbol tables are available')
    report = {'schema': 1, 'debugSymbolLevel': 'SYMBOL_TABLE',
              'nativeFiles': len(records), 'preparedSymbolFiles': prepared,
              'unavailableSymbolFiles': len(records) - prepared,
              'runtimePayloadModified': False,
              'coverageNote': 'Only real defined function symbols are included. Pre-stripped vendor libraries remain uncovered.',
              'files': records}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items() if key != 'files'}))


if __name__ == '__main__':
    main()
