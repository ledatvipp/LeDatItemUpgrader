#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
if [ ! -f core/build/offline/main/vn/ledat/itemupgrader/demo/QuoteDemo.class ]; then
    ./scripts/test-core.sh
fi
java -cp core/build/offline/main vn.ledat.itemupgrader.demo.QuoteDemo
