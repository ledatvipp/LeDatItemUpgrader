#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ ! -f "$ROOT/core/build/offline/main/vn/ledat/itemupgrader/demo/TransactionDemo.class" ]; then
    sh "$ROOT/scripts/test-core.sh"
fi
java -cp "$ROOT/core/build/offline/main" vn.ledat.itemupgrader.demo.TransactionDemo
