$ErrorActionPreference = 'Stop'
Set-Location (Split-Path -Parent $PSScriptRoot)
if (!(Test-Path 'core/build/offline/main/vn/ledat/itemupgrader/demo/GuiDemo.class')) { throw 'Run ./scripts/test-core.ps1 first' }
& java -cp core/build/offline/main vn.ledat.itemupgrader.demo.GuiDemo
exit $LASTEXITCODE
