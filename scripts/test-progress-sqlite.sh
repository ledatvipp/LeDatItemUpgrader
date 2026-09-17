#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
[ -f core/build/offline/test/vn/ledat/itemupgrader/test/Phase08SqliteRepositoryTest.class ] || { echo 'Run ./scripts/test-core.sh first' >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo 'Python 3 stdlib sqlite3 required for test bridge' >&2; exit 1; }
java -cp core/build/offline/main:core/build/offline/test vn.ledat.itemupgrader.test.Phase08SqliteRepositoryTest \
  scripts/testing/sqlite_bridge.py core/build/reports/phase08-sqlite-bridge.xml
