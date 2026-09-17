#!/usr/bin/env python3
"""TEST ONLY: executes SQL emitted by actual Java repository code on sqlite3.
The Java endpoint is a deliberately tiny JDBC-interface proxy, NOT a supported JDBC driver.
No Minecraft APIs, provider calls, or production connections are involved.
Line protocol encodes every field; no SQL or filesystem path comes from a player.
"""
from __future__ import annotations
import base64
import sqlite3
import sys

MAX_LINE = 24 * 1024 * 1024

def encode(value):
    if value is None:
        return 'n'
    if isinstance(value, bytes):
        return 'b' + base64.b64encode(value).decode('ascii')
    if isinstance(value, int):
        return 'i' + str(value)
    return 's' + base64.b64encode(str(value).encode('utf-8')).decode('ascii')

def decode(value):
    if value == 'n':
        return None
    if value.startswith('b'):
        return base64.b64decode(value[1:], validate=True)
    if value.startswith('i'):
        return int(value[1:])
    if value.startswith('s'):
        return base64.b64decode(value[1:], validate=True).decode('utf-8')
    raise ValueError('invalid field type')

def emit(*fields):
    sys.stdout.write('\t'.join(fields) + '\n')
    sys.stdout.flush()

def run(path):
    c = sqlite3.connect(path, isolation_level=None, timeout=5)
    c.execute('PRAGMA busy_timeout=5000')
    c.execute('PRAGMA journal_mode=WAL')
    c.execute('PRAGMA synchronous=FULL')
    auto = True
    try:
        for raw in sys.stdin:
            if len(raw) > MAX_LINE:
                raise ValueError('bridge line bound exceeded')
            fields = raw.rstrip('\n').split('\t')
            operation = fields[0]
            try:
                if operation == 'CLOSE':
                    if c.in_transaction:
                        c.rollback()
                    emit('OK')
                    return
                if operation == 'AUTO':
                    next_auto = fields[1] == 'true'
                    if next_auto and c.in_transaction:
                        c.commit()
                    auto = next_auto
                    emit('OK')
                    continue
                if operation in ('COMMIT', 'ROLLBACK'):
                    if c.in_transaction:
                        c.commit() if operation == 'COMMIT' else c.rollback()
                    emit('OK')
                    continue
                if operation not in ('Q', 'U'):
                    raise ValueError('unsupported bridge operation')
                if not auto and not c.in_transaction:
                    c.execute('BEGIN')
                sql = decode(fields[1])
                args = tuple(decode(v) for v in fields[2:])
                cur = c.execute(sql, args)
                if operation == 'U':
                    emit('U', str(max(0, cur.rowcount)))
                else:
                    names = [v[0] for v in cur.description]
                    emit('R', *(encode(name) for name in names))
                    for index, row in enumerate(cur):
                        if index > 10000:
                            raise ValueError('bridge result row bound exceeded')
                        emit('ROW', *(encode(v) for v in row))
                    emit('END')
            except (sqlite3.Error, ValueError) as error:
                code = getattr(error, 'sqlite_errorcode', 0)
                state = '23505' if code in (1555, 2067) else 'HY000'
                emit('E', state, str(code), encode(str(error)))
    finally:
        if c.in_transaction:
            c.rollback()
        c.close()

if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: sqlite_bridge.py test-database-path')
    run(sys.argv[1])
