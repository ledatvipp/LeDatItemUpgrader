# Implementation changes against actual Phase 8 ZIP

35 source/build/resource/test/script paths changed; documents and evidence excluded from this count.

| Status | Path |
|---|---|
| MODIFIED | `build.gradle` |
| MODIFIED | `core/build.gradle` |
| MODIFIED | `core/src/main/java/vn/ledat/itemupgrader/history/storage/ProgressSql.java` |
| MODIFIED | `core/src/main/java/vn/ledat/itemupgrader/runtime/UpgraderRuntime.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/JdbcRecoveryInspector.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/JdbcStorageBootstrap.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/RecoveryPage.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/SafeFailure.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/SchemaProblem.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/SchemaReport.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/StorageArguments.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/StorageController.java` |
| ADDED | `core/src/main/java/vn/ledat/itemupgrader/storage/management/StorageSettings.java` |
| ADDED | `core/src/test/java/vn/ledat/itemupgrader/test/Phase09SelfTest.java` |
| ADDED | `core/src/test/java/vn/ledat/itemupgrader/test/Phase09SqliteRepositoryTest.java` |
| MODIFIED | `core/src/test/java/vn/ledat/itemupgrader/test/PythonSqliteBridge.java` |
| MODIFIED | `paper/build.gradle` |
| MODIFIED | `paper/src/main/java/vn/ledat/itemupgrader/paper/bootstrap/PluginBootstrap.java` |
| MODIFIED | `paper/src/main/java/vn/ledat/itemupgrader/paper/command/UpgraderCommand.java` |
| MODIFIED | `paper/src/main/java/vn/ledat/itemupgrader/paper/config/ConfigLoader.java` |
| ADDED | `paper/src/main/java/vn/ledat/itemupgrader/paper/config/StorageManagementConfigLoader.java` |
| MODIFIED | `paper/src/main/java/vn/ledat/itemupgrader/paper/history/HistoryUiService.java` |
| MODIFIED | `paper/src/main/java/vn/ledat/itemupgrader/paper/platform/PlatformAccess.java` |
| MODIFIED | `paper/src/main/java/vn/ledat/itemupgrader/paper/storage/PlatformHistoryStore.java` |
| ADDED | `paper/src/main/java/vn/ledat/itemupgrader/paper/storage/PlatformStorageService.java` |
| MODIFIED | `paper/src/main/resources/config.yml` |
| MODIFIED | `paper/src/main/resources/history.yml` |
| MODIFIED | `paper/src/main/resources/messages.yml` |
| MODIFIED | `paper/src/main/resources/plugin.yml` |
| ADDED | `paper/src/main/resources/storage-management.yml` |
| ADDED | `scripts/native-preflight.py` |
| MODIFIED | `scripts/test-core.ps1` |
| MODIFIED | `scripts/test-core.sh` |
| ADDED | `scripts/test-storage-sqlite.sh` |
| MODIFIED | `scripts/verify-resources.py` |
