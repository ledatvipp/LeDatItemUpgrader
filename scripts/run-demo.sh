#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
[ -d core/build/offline/main ] || ./scripts/test-core.sh
java -cp core/build/offline/main vn.ledat.itemupgrader.demo.ValueDemo
