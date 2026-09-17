#!/usr/bin/env python3
"""Compare plan/record/state hashes from actual Phase07 source compiled before the v3 extension.
Synthetic item/provider fixtures; NOT server migration, Minecraft NBT, or xerial JDBC validation.
"""
from pathlib import Path
import subprocess
ROOT = Path(__file__).resolve().parents[1]
old = (ROOT/'verification/phase07-fixtures/output-v2-hashes.txt').read_text().splitlines()
new = subprocess.check_output(['java','-cp',str(ROOT/'core/build/offline/main'),
                               'vn.ledat.itemupgrader.demo.LegacyOutputContract'], text=True).splitlines()
if old != new:
    for index, (a,b) in enumerate(zip(old,new)):
        if a != b:
            raise SystemExit(f'v2 mismatch at row {index}: {a.split()[0]}')
    raise SystemExit('v2 fixture count mismatch')
plans = sum('/plan ' in row for row in old)
print(f'PASS legacy v2 hashes: {plans} pinned plans + {len(old)-plans} record/state pairs match actual Phase07 baseline')
print('Fixture bytes/hashes only, not native migration or provider metadata validation.')
