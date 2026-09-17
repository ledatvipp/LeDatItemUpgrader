#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
[ -f core/build/offline/main/vn/ledat/itemupgrader/demo/ProgressDemo.class ] || { echo 'Run ./scripts/test-core.sh first' >&2; exit 1; }
java -cp core/build/offline/main vn.ledat.itemupgrader.demo.ProgressDemo
