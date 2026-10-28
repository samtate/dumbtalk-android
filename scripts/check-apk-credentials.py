#!/usr/bin/env python3
"""Reject APKs containing build-time Telegram fields or locally configured credentials."""
import argparse
import os
from pathlib import Path
import re
import sys
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apks', type=Path, nargs='+')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    local = root / 'local.properties'
    values = []
    if local.exists():
        values += re.findall(r'(?m)^\s*telegram\.api_(?:id|hash)\s*[=:]\s*(\S+)', local.read_text())
    values += [os.environ.get(name, '') for name in ('TELEGRAM_API_ID', 'TELEGRAM_API_HASH')]
    needles = {value.encode() for value in values if len(value) >= 5 and value != '0'}
    forbidden = (b'TELEGRAM_API_ID', b'TELEGRAM_API_HASH')
    failed = False
    for apk in args.apks:
        matches = []
        with zipfile.ZipFile(apk) as archive:
            for name in archive.namelist():
                if name.endswith('/'):
                    continue
                data = archive.read(name)
                if any(needle in data for needle in needles) or (name.endswith('.dex') and any(field in data for field in forbidden)):
                    matches.append(name)
        if matches:
            # Never print the credential values, even on failure.
            print(f'FAIL {apk.name}: credential data or build-time fields in {", ".join(matches)}', file=sys.stderr)
            failed = True
        else:
            print(f'PASS {apk.name}: no build-time Telegram fields or configured credential values')
    return int(failed)


if __name__ == '__main__':
    sys.exit(main())
