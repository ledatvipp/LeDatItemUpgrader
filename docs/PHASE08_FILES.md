# Phase8 — actual file changes

Đối chiếu với ZIP Phase7 SHA-256 `47155473cb5954ed41b1c524a792277e588158ffce8e6363e34683a7d618dc5c`.

**36 files mới; 18 files sửa; 0 files xóa** trong source/build/config/scripts. Không tính README/report/evidence.

## Files mới

- `core/src/main/java/vn/ledat/itemupgrader/demo/LegacyOutputContract.java`
- `core/src/main/java/vn/ledat/itemupgrader/demo/ProgressDemo.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/AttemptDiagnostic.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/HistoryEntry.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/HistoryPage.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/HistoryQuery.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/HistorySessionStore.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/HistorySettings.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/PlayerStatistics.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/StatisticsCache.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/storage/JdbcProgressRepository.java`
- `core/src/main/java/vn/ledat/itemupgrader/history/storage/ProgressSql.java`
- `core/src/main/java/vn/ledat/itemupgrader/pity/PityPolicies.java`
- `core/src/main/java/vn/ledat/itemupgrader/pity/PityPolicy.java`
- `core/src/main/java/vn/ledat/itemupgrader/pity/PityQuoteService.java`
- `core/src/main/java/vn/ledat/itemupgrader/pity/PityScope.java`
- `core/src/main/java/vn/ledat/itemupgrader/pity/PitySnapshot.java`
- `core/src/main/java/vn/ledat/itemupgrader/pity/PityStamp.java`
- `core/src/main/java/vn/ledat/itemupgrader/transaction/storage/JournalCommitHook.java`
- `core/src/test/java/vn/ledat/itemupgrader/test/Phase08SelfTest.java`
- `core/src/test/java/vn/ledat/itemupgrader/test/Phase08SqliteRepositoryTest.java`
- `core/src/test/java/vn/ledat/itemupgrader/test/ProgressFixtures.java`
- `core/src/test/java/vn/ledat/itemupgrader/test/PythonSqliteBridge.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/config/HistoryConfigLoader.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/history/HistoryHolder.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/history/HistoryInventoryListener.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/history/HistoryRenderer.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/history/HistoryUiService.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/storage/PlatformHistoryStore.java`
- `paper/src/main/resources/history.yml`
- `paper/src/main/resources/menus/history.yml`
- `paper/src/main/resources/upgrades/pity.yml`
- `scripts/run-progress-demo.sh`
- `scripts/test-progress-sqlite.sh`
- `scripts/testing/sqlite_bridge.py`
- `scripts/verify-legacy-output-v2.py`

## Files sửa

- `build.gradle`
- `core/build.gradle`
- `core/src/main/java/vn/ledat/itemupgrader/chance/ChanceCalculator.java`
- `core/src/main/java/vn/ledat/itemupgrader/quote/QuoteResult.java`
- `core/src/main/java/vn/ledat/itemupgrader/quote/UpgradeQuote.java`
- `core/src/main/java/vn/ledat/itemupgrader/runtime/UpgraderRuntime.java`
- `core/src/main/java/vn/ledat/itemupgrader/transaction/storage/JdbcTransactionRepository.java`
- `core/src/main/java/vn/ledat/itemupgrader/transaction/storage/JournalCodec.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/bootstrap/PluginBootstrap.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/command/UpgraderCommand.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/config/ConfigLoader.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/platform/PlatformAccess.java`
- `paper/src/main/java/vn/ledat/itemupgrader/paper/storage/PlatformTransactionJournal.java`
- `paper/src/main/resources/messages.yml`
- `paper/src/main/resources/plugin.yml`
- `scripts/test-core.ps1`
- `scripts/test-core.sh`
- `scripts/verify-resources.py`

## Files xóa

Không có.

Danh sách được tính từ bytes thực; không phải danh sách hứa triển khai.
