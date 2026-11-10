"""Verify an APK's signature, optional hardware and native 16 KiB alignment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def native_alignment(apk):
    results = []
    with zipfile.ZipFile(apk) as archive:
        for item in archive.infolist():
            if not item.filename.endswith('.so') or not any('/' + abi + '/' in item.filename for abi in ('arm64-v8a', 'x86_64')):
                continue
            data = archive.read(item)
            if data[:4] != b'\x7fELF' or data[4] not in (1, 2) or data[5] not in (1, 2):
                raise ValueError('Invalid ELF library: ' + item.filename)
            endian = '<' if data[5] == 1 else '>'
            if data[4] == 2:
                offset = struct.unpack_from(endian + 'Q', data, 32)[0]
                size, count = struct.unpack_from(endian + 'HH', data, 54)
                alignment_offset, alignment_format = 48, 'Q'
            else:
                offset = struct.unpack_from(endian + 'I', data, 28)[0]
                size, count = struct.unpack_from(endian + 'HH', data, 42)
                alignment_offset, alignment_format = 28, 'I'
            alignments = [struct.unpack_from(endian + alignment_format, data, offset + i * size + alignment_offset)[0]
                for i in range(count) if struct.unpack_from(endian + 'I', data, offset + i * size)[0] == 1]
            if not alignments or min(alignments) < 16384:
                raise ValueError('Native library lacks 16 KiB load alignment: ' + item.filename)
            results.append({'library': item.filename, 'load_alignments': alignments})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'android/app/build/outputs/apk/debug/app-debug.apk')
    parser.add_argument('--output', type=Path, default=ROOT / 'output/android-artifact-check.json')
    args = parser.parse_args()
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        parser.error('Set ANDROID_HOME to the Android SDK')
    tools = Path(sdk) / 'build-tools/35.0.0'
    suffix = '.exe' if os.name == 'nt' else ''
    apk = args.apk.resolve(strict=True)
    badging = subprocess.check_output([str(tools / ('aapt2' + suffix)), 'dump', 'badging', str(apk)], text=True)
    required = [line for line in badging.splitlines() if line.startswith('uses-feature:')]
    if required:
        raise ValueError('Optional-hardware policy violated: ' + ', '.join(required))
    subprocess.run([str(tools / ('zipalign' + suffix)), '-c', '-P', '16', '4', str(apk)], check=True)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java' + suffix)) if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([java, '-jar', str(tools / 'lib/apksigner.jar'), 'verify', str(apk)], check=True)
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = {'apk': str(apk), 'sha256': digest, 'signature_verified': True,
        'required_hardware_features': required, 'zip_alignment_16kb': True,
        'native_libraries': native_alignment(apk), 'runtime_on_16kb_device_tested': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(f'PASS: signed APK; optional hardware; ZIP and {len(report["native_libraries"])} native libraries aligned to 16 KiB')


if __name__ == '__main__':
    main()
