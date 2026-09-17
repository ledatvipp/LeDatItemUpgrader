#!/usr/bin/env python3
"""Real SQLite SQL tests using EXACT Java OutputSql/OutputCodec exports.
These run Python's sqlite3 driver, not JdbcOutputRepository or a Minecraft server.
Crash cases terminate a child process around COMMIT; no native item/currency effects.
"""
from __future__ import annotations
import base64
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ROOT / 'core/build/offline/main'

def java(main: str, *args: str) -> str:
    return subprocess.check_output(['java', '-cp', str(CLASSES), 'vn.ledat.itemupgrader.demo.'+main, *args], text=True, cwd=ROOT)

def connection(path: Path) -> sqlite3.Connection:
    c = sqlite3.connect(path, isolation_level=None, timeout=10)
    c.row_factory = sqlite3.Row
    c.execute('PRAGMA journal_mode=WAL')
    c.execute('PRAGMA synchronous=FULL')
    c.execute('PRAGMA busy_timeout=10000')
    return c

def initialize(c: sqlite3.Connection, contract: dict) -> None:
    for statement in contract['sqlite-ddl']:
        c.execute(statement)
    c.execute(contract['queries']['insert-version'], ('outputs-v1', 1))

def insert(c: sqlite3.Connection, sql: dict, f: dict) -> None:
    c.execute(sql['insert'], (f['attempt'], f['player'], f['planDigest'], int(f['created'])))

def finish_statements(c: sqlite3.Connection, sql: dict, f: dict, *, state: str = 'READY', reason: str = '', identity: str | None = None) -> int:
    blob = base64.b64decode(f['payload'], validate=True) if state == 'READY' else None
    digest = f['payloadDigest'] if state == 'READY' else None
    changed = c.execute(sql['finish'], (state, blob, digest, reason, f['attempt'], f['player'], f['planDigest'], int(f['created']))).rowcount
    if changed and state == 'READY':
        c.execute(sql['identity'], (identity or f['identity'], f['attempt'], 'SUCCESS'))
    return changed

def finish(c: sqlite3.Connection, sql: dict, f: dict, **kwargs) -> int:
    c.execute('BEGIN')
    try:
        changed = finish_statements(c, sql, f, **kwargs)
        c.execute('COMMIT')
        return changed
    except BaseException:
        c.execute('ROLLBACK')
        raise

def crash_child(path: str, fixture_path: str, after_commit: str) -> None:
    fixture = json.loads(Path(fixture_path).read_text())
    c = connection(Path(path))
    c.execute('BEGIN')
    assert finish_statements(c, fixture['queries'], fixture['fixture']) == 1
    if after_commit == 'yes':
        c.execute('COMMIT')
    os._exit(75)

if len(sys.argv) > 1 and sys.argv[1] == '--crash-child':
    crash_child(*sys.argv[2:5])
    raise AssertionError('must not return from deliberate process exit')

if not (CLASSES / 'vn/ledat/itemupgrader/demo/OutputSqlContract.class').exists():
    raise SystemExit('Run scripts/test-core.sh with JDK 21 before this SQL test.')
CONTRACT = json.loads(java('OutputSqlContract'))
SQL, FIXTURE = CONTRACT['queries'], CONTRACT['fixture']

class OutputSqlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='iup-output-sql-')
        self.root = Path(self.temp.name)
        self.path = self.root / 'outputs.db'
        self.c = connection(self.path)
        initialize(self.c, CONTRACT)

    def tearDown(self):
        self.c.close()
        self.temp.cleanup()

    def row(self, attempt: str | None = None):
        return self.c.execute(SQL['find'], (attempt or FIXTURE['attempt'],)).fetchone()

    def test_schema_has_primary_keys_and_wal(self):
        self.assertEqual('wal', self.c.execute('PRAGMA journal_mode').fetchone()[0])
        self.assertEqual(2, self.c.execute('PRAGMA synchronous').fetchone()[0])
        self.assertEqual(1, self.c.execute(SQL['version'], ('outputs-v1',)).fetchone()[0])
        for table in ('iup_outputs', 'iup_output_ids'):
            self.assertTrue(any(row['pk'] for row in self.c.execute(f'PRAGMA table_info({table})')))

    def test_claim_stores_preparing_not_entitlement(self):
        insert(self.c, SQL, FIXTURE)
        self.assertEqual('PREPARING', self.row()['state'])
        self.assertIsNone(self.row()['payload'])
        self.assertEqual(0, self.c.execute('SELECT COUNT(*) FROM iup_output_ids').fetchone()[0])

    def test_duplicate_claim_does_not_overwrite_ready(self):
        insert(self.c, SQL, FIXTURE)
        finish(self.c, SQL, FIXTURE)
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.c, SQL, FIXTURE)
        self.assertEqual('READY', self.row()['state'])
        self.assertEqual(FIXTURE['payloadDigest'], self.row()['payload_digest'])

    def test_ready_and_identity_commit_together(self):
        insert(self.c, SQL, FIXTURE)
        self.c.execute('BEGIN')
        self.assertEqual(1, finish_statements(self.c, SQL, FIXTURE))
        other = connection(self.path)
        try:
            self.assertEqual('PREPARING', other.execute(SQL['find'], (FIXTURE['attempt'],)).fetchone()['state'])
            self.assertEqual(0, other.execute('SELECT COUNT(*) FROM iup_output_ids').fetchone()[0])
        finally:
            other.close()
        self.c.execute('COMMIT')
        self.assertEqual('READY', self.row()['state'])
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_output_ids').fetchone()[0])

    def test_identity_collision_rolls_back_ready(self):
        insert(self.c, SQL, FIXTURE)
        self.c.execute(SQL['identity'], (FIXTURE['identity'], str(uuid.uuid4()), 'SUCCESS'))
        with self.assertRaises(sqlite3.IntegrityError):
            finish(self.c, SQL, FIXTURE)
        self.assertEqual('PREPARING', self.row()['state'])
        self.assertIsNone(self.row()['payload'])
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_output_ids').fetchone()[0])

    def test_cas_binds_player_digest_and_created_time(self):
        insert(self.c, SQL, FIXTURE)
        for changes in ({'player': str(uuid.uuid4())}, {'planDigest': '0'*64}, {'created': str(int(FIXTURE['created'])+1)}, {'attempt': str(uuid.uuid4())}):
            self.assertEqual(0, finish(self.c, SQL, dict(FIXTURE, **changes)))
        self.assertEqual('PREPARING', self.row()['state'])
        self.assertEqual(0, self.c.execute('SELECT COUNT(*) FROM iup_output_ids').fetchone()[0])

    def test_terminal_states_cannot_be_replaced(self):
        insert(self.c, SQL, FIXTURE)
        self.assertEqual(1, finish(self.c, SQL, FIXTURE, state='REJECTED', reason='RECONFIRM_REQUIRED'))
        self.assertEqual(0, finish(self.c, SQL, FIXTURE))
        self.assertEqual('REJECTED', self.row()['state'])
        self.assertIsNone(self.row()['payload'])

    def test_ambiguous_tombstone_is_not_expiring_lease(self):
        insert(self.c, SQL, FIXTURE)
        finish(self.c, SQL, FIXTURE, state='AMBIGUOUS', reason='CREATION_UNCERTAIN')
        self.assertEqual(0, finish(self.c, SQL, FIXTURE))
        self.assertEqual('AMBIGUOUS', self.row()['state'])
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.c, SQL, dict(FIXTURE, created=str(int(FIXTURE['created'])+99999999)))

    def test_payload_java_round_trip_after_database(self):
        insert(self.c, SQL, FIXTURE)
        finish(self.c, SQL, FIXTURE)
        self.check_payload_with_java(self.row())

    def check_payload_with_java(self, row):
        self.assertEqual(row['payload_digest'], hashlib.sha256(row['payload']).hexdigest())
        payload_file, plan_file = self.root / 'output.bin', self.root / 'plan.bin'
        payload_file.write_bytes(row['payload'])
        plan_file.write_bytes(base64.b64decode(FIXTURE['planPayload'], validate=True))
        result = java('OutputPayloadInspect', str(payload_file), str(plan_file))
        self.assertIn('failureDamage=90', result)
        self.assertIn('outputDigest='+FIXTURE['payloadDigest'], result)

    def test_close_reopen_preserves_candidates_and_identity(self):
        insert(self.c, SQL, FIXTURE)
        finish(self.c, SQL, FIXTURE)
        self.c.close()
        self.c = connection(self.path)
        self.check_payload_with_java(self.row())
        self.assertEqual(FIXTURE['attempt'], self.c.execute('SELECT attempt_id FROM iup_output_ids').fetchone()[0])

    def test_two_connections_only_one_claim(self):
        barrier = threading.Barrier(2)
        def compete(_):
            c = connection(self.path)
            try:
                barrier.wait(timeout=10)
                try:
                    insert(c, SQL, FIXTURE)
                    return 'created'
                except sqlite3.IntegrityError:
                    return 'duplicate'
            finally:
                c.close()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            self.assertEqual(['created', 'duplicate'], sorted(executor.map(compete, range(2))))
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_outputs').fetchone()[0])

    def test_two_connections_only_one_finish(self):
        insert(self.c, SQL, FIXTURE)
        barrier = threading.Barrier(2)
        def compete(_):
            c = connection(self.path)
            try:
                barrier.wait(timeout=10)
                return finish(c, SQL, FIXTURE)
            finally:
                c.close()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            self.assertEqual([0, 1], sorted(executor.map(compete, range(2))))
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_output_ids').fetchone()[0])

    def crash_check(self, after_commit: bool):
        insert(self.c, SQL, FIXTURE)
        fixture = self.root / 'contract.json'
        fixture.write_text(json.dumps(CONTRACT))
        child = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--crash-child', str(self.path), str(fixture), 'yes' if after_commit else 'no'], timeout=20, check=False)
        self.assertEqual(75, child.returncode)
        self.c.close()
        self.c = connection(self.path)
        saved = self.row()
        self.assertEqual('READY' if after_commit else 'PREPARING', saved['state'])
        self.assertEqual(int(after_commit), self.c.execute('SELECT COUNT(*) FROM iup_output_ids').fetchone()[0])
        if after_commit:
            self.check_payload_with_java(saved)
        else:
            self.assertIsNone(saved['payload'])
        # No deletion/reclaim or factory is invoked by recovery of PREPARING.
        with self.assertRaises(sqlite3.IntegrityError):
            insert(self.c, SQL, FIXTURE)

    def test_process_exit_before_ready_commit_preserves_pending(self):
        self.crash_check(False)

    def test_process_exit_after_ready_commit_preserves_exact_output(self):
        self.crash_check(True)

if __name__ == '__main__':
    unittest.main(verbosity=2)
