#!/usr/bin/env python3
"""Execute Java-exported journal SQL on REAL SQLite via Python sqlite3.
NOT a JDBC/LeDatPlatform/native Minecraft test. Includes process exit before/after one SQL COMMIT.
Needs JDK 21 + a previously compiled core (scripts/test-core.sh); no third-party Python dependency.
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

ROOT = Path(__file__).resolve().parents[1]
CLASSES = ROOT / 'core/build/offline/main'

def exported() -> dict:
    if not (CLASSES / 'vn/ledat/itemupgrader/demo/JournalSqlContract.class').is_file():
        raise RuntimeError('Compile first: ./scripts/test-core.sh')
    output = subprocess.check_output(['java', '-cp', str(CLASSES), 'vn.ledat.itemupgrader.demo.JournalSqlContract'], text=True)
    return json.loads(output)

def connect(path: Path) -> sqlite3.Connection:
    c = sqlite3.connect(path, timeout=5, isolation_level=None)
    c.row_factory = sqlite3.Row
    c.execute('PRAGMA busy_timeout=5000')
    c.execute('PRAGMA synchronous=FULL')
    return c

def initialize(c: sqlite3.Connection, contract: dict) -> None:
    c.execute('PRAGMA journal_mode=WAL')
    for ddl in contract['sqlite-ddl'] + contract['indexes']:
        c.execute(ddl)
    c.execute(contract['queries']['insert-version'], ('transactions-v1', 1, 0))

def values(row: dict) -> tuple:
    payload = base64.b64decode(row['payload'], validate=True)
    return (row['id'], row['key'], row['player'], row['planDigest'], base64.b64decode(row['planPayload'], validate=True), row['state'], int(row['version']),
            int(row['created']), int(row['updated']), int(row['sample']) if row['sample'] else None, payload, row['payloadDigest'])

def event(c: sqlite3.Connection, sql: dict, row: dict) -> None:
    c.execute(sql['insert-event'], (row['id'], int(row['version']), row['state'], int(row['updated']), row['payloadDigest']))

def claim_statements(c: sqlite3.Connection, sql: dict, row: dict) -> None:
    c.execute(sql['insert-attempt'], values(row))
    c.execute(sql['insert-lock'], (row['player'], row['id']))
    event(c, sql, row)

def claim(c: sqlite3.Connection, sql: dict, row: dict) -> None:
    c.execute('BEGIN')
    try:
        claim_statements(c, sql, row)
        c.execute('COMMIT')
    except BaseException:
        c.execute('ROLLBACK')
        raise

def cas_statements(c: sqlite3.Connection, sql: dict, old: dict, new: dict) -> bool:
    payload = base64.b64decode(new['payload'], validate=True)
    affected = c.execute(sql['update'], (new['state'], int(new['version']), int(new['updated']),
                 int(new['sample']) if new['sample'] else None, payload, new['payloadDigest'], new['id'],
                 int(old['version']), old['payloadDigest'], old['planDigest'])).rowcount
    if not affected:
        return False
    if affected != 1:
        raise RuntimeError('affected rows mismatch')
    owner = c.execute(sql['active'], (new['player'],)).fetchone()
    if owner is None or owner['tx_id'] != new['id']:
        raise RuntimeError('missing owner')
    event(c, sql, new)
    if new['state'] in ('COMPLETED', 'ABORTED'):
        if c.execute(sql['unlock'], (new['player'], new['id'])).rowcount != 1:
            raise RuntimeError('unlock mismatch')
    return True

def cas(c: sqlite3.Connection, sql: dict, old: dict, new: dict) -> bool:
    c.execute('BEGIN')
    try:
        result = cas_statements(c, sql, old, new)
        c.execute('COMMIT')
        return result
    except BaseException:
        c.execute('ROLLBACK')
        raise

def crash_child(database: str, fixture: str, after_commit: str) -> None:
    contract = json.loads(Path(fixture).read_text())
    c = connect(Path(database))
    rows = contract['records']
    stop = next(i for i, row in enumerate(rows) if row['state'] == 'OUTCOME_COMMITTED')
    for i in range(1, stop):
        assert cas(c, contract['queries'], rows[i-1], rows[i])
    c.execute('BEGIN')
    assert cas_statements(c, contract['queries'], rows[stop-1], rows[stop])
    if after_commit == 'yes':
        c.execute('COMMIT')
    os._exit(73)  # Deliberately bypass normal connection close/finally, not a simulated exception.

if len(sys.argv) > 1 and sys.argv[1] == '--crash-child':
    crash_child(*sys.argv[2:5])
    raise AssertionError('os._exit must not return')

CONTRACT = exported()
SQL = CONTRACT['queries']
ROWS = CONTRACT['records']
FIRST, LAST = ROWS[0], ROWS[-1]

class JournalSqlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='iup-sql-')
        self.root = Path(self.temp.name)
        self.path = self.root / 'journal.db'
        self.c = connect(self.path)
        initialize(self.c, CONTRACT)

    def tearDown(self):
        self.c.close()
        self.temp.cleanup()

    def test_schema_indexes_and_wal(self):
        indexes = self.c.execute("PRAGMA index_list('iup_attempts')").fetchall()
        names = {r['name'] for r in indexes}
        self.assertIn('iup_attempts_player_time', names)
        self.assertIn('iup_attempts_state_time', names)
        self.assertEqual('wal', self.c.execute('PRAGMA journal_mode').fetchone()[0])
        self.assertEqual(2, self.c.execute('PRAGMA synchronous').fetchone()[0])
        self.assertEqual(1, self.c.execute(SQL['schema-version'], ('transactions-v1',)).fetchone()[0])

    def test_claim_atomically_inserts_attempt_lock_event(self):
        claim(self.c, SQL, FIRST)
        for table in ('iup_attempts', 'iup_player_locks', 'iup_tx_events'):
            self.assertEqual(1, self.c.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0])
        self.assertEqual(FIRST['id'], self.c.execute(SQL['active'], (FIRST['player'],)).fetchone()[0])

    def test_duplicate_idempotency_no_duplicate_event(self):
        claim(self.c, SQL, FIRST)
        with self.assertRaises(sqlite3.IntegrityError):
            claim(self.c, SQL, FIRST)
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_tx_events').fetchone()[0])
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_player_locks').fetchone()[0])

    def test_different_attempt_same_player_rolls_back_new_row(self):
        claim(self.c, SQL, FIRST)
        with self.assertRaises(sqlite3.IntegrityError):
            claim(self.c, SQL, CONTRACT['competitor'])
        self.assertIsNone(self.c.execute(SQL['find'], (CONTRACT['competitor']['id'],)).fetchone())
        self.assertEqual(FIRST['id'], self.c.execute(SQL['active'], (FIRST['player'],)).fetchone()[0])

    def test_event_failure_rolls_back_claim(self):
        event(self.c, SQL, FIRST)  # Corrupt/orphan pre-existing event makes INSERT event fail.
        with self.assertRaises(sqlite3.IntegrityError):
            claim(self.c, SQL, FIRST)
        self.assertEqual(0, self.c.execute('SELECT COUNT(*) FROM iup_attempts').fetchone()[0])
        self.assertEqual(0, self.c.execute('SELECT COUNT(*) FROM iup_player_locks').fetchone()[0])

    def test_cas_is_version_and_digest_guarded(self):
        claim(self.c, SQL, FIRST)
        self.assertTrue(cas(self.c, SQL, FIRST, ROWS[1]))
        self.assertFalse(cas(self.c, SQL, FIRST, ROWS[1]))
        forged = dict(ROWS[1], payloadDigest='0' * 64)
        self.assertFalse(cas(self.c, SQL, forged, ROWS[2]))
        self.assertEqual(int(ROWS[1]['version']), self.c.execute(SQL['find'], (FIRST['id'],)).fetchone()['version'])

    def test_event_failure_rolls_back_cas_and_preserves_owner(self):
        claim(self.c, SQL, FIRST)
        event(self.c, SQL, ROWS[1])
        with self.assertRaises(sqlite3.IntegrityError):
            cas(self.c, SQL, FIRST, ROWS[1])
        self.assertEqual(0, self.c.execute(SQL['find'], (FIRST['id'],)).fetchone()['version'])
        self.assertIsNotNone(self.c.execute(SQL['active'], (FIRST['player'],)).fetchone())

    def test_terminal_event_state_and_unlock_commit_together(self):
        claim(self.c, SQL, FIRST)
        for old, new in zip(ROWS[:-2], ROWS[1:-1]):
            self.assertTrue(cas(self.c, SQL, old, new))
        self.c.execute('BEGIN')
        self.assertTrue(cas_statements(self.c, SQL, ROWS[-2], LAST))
        self.c.execute('ROLLBACK')
        self.assertEqual(ROWS[-2]['state'], self.c.execute(SQL['find'], (FIRST['id'],)).fetchone()['state'])
        self.assertIsNotNone(self.c.execute(SQL['active'], (FIRST['player'],)).fetchone())
        self.assertTrue(cas(self.c, SQL, ROWS[-2], LAST))
        self.assertIsNone(self.c.execute(SQL['active'], (FIRST['player'],)).fetchone())
        claim(self.c, SQL, CONTRACT['competitor'])
        self.assertEqual(CONTRACT['competitor']['id'], self.c.execute(SQL['active'], (FIRST['player'],)).fetchone()[0])
        self.assertIsNotNone(self.c.execute(SQL['find-by-key'], (FIRST['key'],)).fetchone())

    def test_recovery_page_excludes_terminal_but_not_pending(self):
        claim(self.c, SQL, FIRST)
        self.assertEqual(1, len(self.c.execute(SQL['unfinished'], ('', 4)).fetchall()))
        self.assertEqual(0, len(self.c.execute(SQL['unfinished'], (FIRST['id'], 4)).fetchall()))
        for old, new in zip(ROWS, ROWS[1:]):
            cas(self.c, SQL, old, new)
        self.assertEqual(0, len(self.c.execute(SQL['unfinished'], ('', 4)).fetchall()))

    def test_original_java_payload_survives_database_round_trip(self):
        claim(self.c, SQL, FIRST)
        row = self.c.execute(SQL['find'], (FIRST['id'],)).fetchone()
        self.assertEqual(FIRST['payloadDigest'], hashlib.sha256(row['payload']).hexdigest())
        payload_file = self.root / 'payload.bin'
        payload_file.write_bytes(row['payload'])
        plan_file = self.root / 'plan.bin'
        plan_file.write_bytes(row['plan_payload'])
        output = subprocess.check_output(['java', '-cp', str(CLASSES), 'vn.ledat.itemupgrader.demo.JournalPayloadInspect', str(payload_file), str(plan_file)], text=True)
        self.assertIn('state=PREPARED', output)

    def test_close_reopen_keeps_committed_outcome_bytes(self):
        claim(self.c, SQL, FIRST)
        for old, new in zip(ROWS, ROWS[1:]):
            cas(self.c, SQL, old, new)
            if new['state'] == 'OUTCOME_COMMITTED':
                break
        self.c.close()
        self.c = connect(self.path)
        saved = self.c.execute(SQL['find'], (FIRST['id'],)).fetchone()
        self.assertEqual('OUTCOME_COMMITTED', saved['state'])
        self.assertEqual(12_345_678, saved['sample'])
        self.assertEqual(new['payloadDigest'], hashlib.sha256(saved['payload']).hexdigest())

    def test_two_connections_compete_for_same_idempotency_key(self):
        self._compete([FIRST, FIRST])

    def test_two_connections_compete_for_same_player(self):
        self._compete([FIRST, CONTRACT['competitor']])

    def _compete(self, rows):
        barrier = threading.Barrier(2)
        def task(row):
            c = connect(self.path)
            try:
                barrier.wait(timeout=10)
                try:
                    claim(c, SQL, row)
                    return 'created'
                except sqlite3.IntegrityError:
                    return 'conflict'
            finally:
                c.close()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(task, rows))
        self.assertEqual(['conflict', 'created'], sorted(results))
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_attempts').fetchone()[0])
        self.assertEqual(1, self.c.execute('SELECT COUNT(*) FROM iup_player_locks').fetchone()[0])

    def _exit_during_outcome_commit(self, after_commit: bool):
        claim(self.c, SQL, FIRST)
        fixture_file = self.root / 'contract.json'
        fixture_file.write_text(json.dumps(CONTRACT))
        process = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--crash-child', str(self.path), str(fixture_file), 'yes' if after_commit else 'no'], timeout=30)
        self.assertEqual(73, process.returncode)
        self.c.close()
        self.c = connect(self.path)
        row = self.c.execute(SQL['find'], (FIRST['id'],)).fetchone()
        self.assertEqual('OUTCOME_COMMITTED' if after_commit else 'DRAW_INTENT', row['state'])
        self.assertEqual(12_345_678 if after_commit else None, row['sample'])
        self.assertEqual(row['version'] + 1, self.c.execute('SELECT COUNT(*) FROM iup_tx_events').fetchone()[0])
        self.assertIsNotNone(self.c.execute(SQL['active'], (FIRST['player'],)).fetchone())
        payload_file = self.root / 'recovered.bin'
        payload_file.write_bytes(row['payload'])
        plan_file = self.root / 'plan.bin'
        plan_file.write_bytes(row['plan_payload'])
        output = subprocess.check_output(['java', '-cp', str(CLASSES), 'vn.ledat.itemupgrader.demo.JournalPayloadInspect', str(payload_file), str(plan_file), 'simulate-resume'], text=True)
        self.assertIn('additional-draws=0', output)
        self.assertIn('result=COMPLETED' if after_commit else 'result=RECONCILIATION_REQUIRED', output)

    def test_process_exit_before_commit_does_not_redraw(self):
        self._exit_during_outcome_commit(False)

    def test_process_exit_after_commit_preserves_roll(self):
        self._exit_during_outcome_commit(True)

if __name__ == '__main__':
    print('SQLite library:', sqlite3.sqlite_version)
    print('SQL strings exported from Java JournalSql; JDBC/native plugin integration NOT tested.')
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(JournalSqlTests)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    print(f'RESULT sqlite_tests={result.testsRun} failures={len(result.failures)} errors={len(result.errors)}')
    sys.exit(0 if result.wasSuccessful() else 1)
