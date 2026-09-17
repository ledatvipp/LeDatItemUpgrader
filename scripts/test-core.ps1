$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$out = Join-Path $root 'core/build/offline'
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out/main", "$out/test", 'core/build/reports' | Out-Null
# -Encoding ascii keeps source-list compatible with javac (file paths in this project are ASCII).
Get-ChildItem 'core/src/main/java' -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName + '"' } | Set-Content "$out/main-sources.txt" -Encoding ascii
Get-ChildItem 'core/src/test/java' -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName + '"' } | Set-Content "$out/test-sources.txt" -Encoding ascii
& javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d "$out/main" "@$out/main-sources.txt"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp "$out/main" -d "$out/test" "@$out/test-sources.txt"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.CoreSelfTest "$root/core/build/reports/self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.CatalogSelfTest "$root/core/build/reports/catalog-self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.Phase03SelfTest "$root/core/build/reports/phase03-self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.Phase04SelfTest "$root/core/build/reports/phase04-self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.Phase05SelfTest "$root/core/build/reports/phase05-self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.Phase06SelfTest "$root/core/build/reports/phase06-self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.Phase07SelfTest "$root/core/build/reports/phase07-self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.Phase08SelfTest "$root/core/build/reports/phase08-self-test.xml"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$out/main;$out/test" vn.ledat.itemupgrader.test.Phase09SelfTest "$root/core/build/reports/phase09-self-test.xml"
exit $LASTEXITCODE
