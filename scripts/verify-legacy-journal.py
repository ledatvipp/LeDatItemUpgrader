#!/usr/bin/env python3
"""Compare actual Phase-4 exported plan/state bytes with current v1 codecs.
This is fixture compatibility, not a running-server migration test.
"""
from pathlib import Path
import json
import subprocess
ROOT=Path(__file__).resolve().parents[1]
old=json.loads((ROOT/'verification/phase04-fixtures/journal-contract-v1.json').read_text())
new=json.loads(subprocess.check_output(['java','-cp',str(ROOT/'core/build/offline/main'),'vn.ledat.itemupgrader.demo.JournalSqlContract'],text=True))
if len(old['records'])!=len(new['records']):
    raise SystemExit('Legacy history record count changed')
for a,b in zip(old['records'],new['records'],strict=True):
    for key in ('planPayload','planDigest','payload','payloadDigest'):
        if a[key]!=b[key]:
            raise SystemExit(f'Legacy compatibility mismatch at {a["state"]}: {key}')
for key in ('planPayload','planDigest','payload','payloadDigest'):
    if old['competitor'][key]!=new['competitor'][key]:
        raise SystemExit(f'Legacy competitor mismatch: {key}')
print(f'PASS legacy v1 exact bytes/digests: {len(old["records"])} history records + 1 competitor fixture')
print('Plan/state byte compatibility only; no automatic native reward/recovery migration.')
