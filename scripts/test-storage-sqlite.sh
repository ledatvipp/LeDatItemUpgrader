#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
[ -f core/build/offline/test/vn/ledat/itemupgrader/test/Phase09SqliteRepositoryTest.class ] || { echo 'Run ./scripts/test-core.sh first' >&2; exit 1; }
CP=core/build/offline/main:core/build/offline/test
if [ -n "${JDBC_TEST_CLASSPATH:-}" ]; then
    # User-supplied real driver + its dependencies; nothing downloaded or bundled.
    java -Diup.test.jdbc=true -cp "$CP:$JDBC_TEST_CLASSPATH" vn.ledat.itemupgrader.test.Phase09SqliteRepositoryTest \
        scripts/testing/sqlite_bridge.py core/build/reports/phase09-sqlite-driver.xml
else
    command -v python3 >/dev/null 2>&1 || { echo 'Python 3 required for the TEST-ONLY SQLite transport' >&2; exit 1; }
    java -cp "$CP" vn.ledat.itemupgrader.test.Phase09SqliteRepositoryTest \
        scripts/testing/sqlite_bridge.py core/build/reports/phase09-sqlite-bridge.xml
fi
