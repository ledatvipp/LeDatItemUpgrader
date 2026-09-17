#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
# Compile/test first when classes are not yet available; no dependency downloads.
if [ ! -f core/build/offline/main/vn/ledat/itemupgrader/demo/CatalogDemo.class ]; then
    ./scripts/test-core.sh
fi
java -cp core/build/offline/main vn.ledat.itemupgrader.demo.CatalogDemo
