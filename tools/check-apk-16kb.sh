#!/usr/bin/env bash
# ELF segment check only: this does not establish runtime/translator page-size compatibility.
set -euo pipefail
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 path/to.apk" >&2
    exit 2
fi
python3 - "$1" <<'PY'
import struct
import sys
import zipfile

passed = 0
failed = []
with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        if not (name.startswith('lib/arm64-v8a/') and name.endswith('.so')):
            continue
        data = apk.read(name)
        try:
            if data[:6] != b'\x7fELF\x02\x01':
                raise ValueError('expected little-endian ELF64')
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            if size < 56:
                raise ValueError('invalid program header size')
            loads = []
            for i in range(count):
                header = offset + i * size
                if struct.unpack_from('<I', data, header)[0] == 1:
                    loads.append(struct.unpack_from('<Q', data, header + 48)[0])
            if not loads or any(align < 16384 for align in loads):
                raise ValueError(f'LOAD alignments {loads}')
            passed += 1
        except (ValueError, struct.error) as error:
            failed.append(name)
            print(f'FAIL {name}: {error}')
print(f'{passed}/{passed + len(failed)} ARM64 native libraries have 16 KB ELF LOAD alignment.')
print('Separate checks are required for APK packaging, runtime execution and translation.')
if failed or passed == 0:
    sys.exit(1)
PY
