#!/usr/bin/env sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"
command -v javac >/dev/null 2>&1 || { echo 'JDK 21 required' >&2; exit 1; }
OUT="$ROOT/core/build/offline"
rm -rf "$OUT"
mkdir -p "$OUT/main" "$OUT/test" "$ROOT/core/build/reports"
find core/src/main/java -name '*.java' | sort > "$OUT/main-sources.txt"
find core/src/test/java -name '*.java' | sort > "$OUT/test-sources.txt"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$OUT/main" @"$OUT/main-sources.txt"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$OUT/main" -d "$OUT/test" @"$OUT/test-sources.txt"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.CoreSelfTest "$ROOT/core/build/reports/self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.CatalogSelfTest "$ROOT/core/build/reports/catalog-self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.Phase03SelfTest "$ROOT/core/build/reports/phase03-self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.Phase04SelfTest "$ROOT/core/build/reports/phase04-self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.Phase05SelfTest "$ROOT/core/build/reports/phase05-self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.Phase06SelfTest "$ROOT/core/build/reports/phase06-self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.Phase07SelfTest "$ROOT/core/build/reports/phase07-self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.Phase08SelfTest "$ROOT/core/build/reports/phase08-self-test.xml"
java -cp "$OUT/main:$OUT/test" vn.ledat.itemupgrader.test.Phase09SelfTest "$ROOT/core/build/reports/phase09-self-test.xml"
echo 'Verified: core only. Paper/LeDatPlatform integration is NOT compiled or tested by this command.'
