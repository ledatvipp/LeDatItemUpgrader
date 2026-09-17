# Build & Verification — Phase 8

Source **`0.8.0-phase08`**, ngày kiểm chứng **2026-09-17**. Baseline thật: `LeDatItemUpgrader-phase07-source.zip`.
Báo cáo Phase7 giữ tại `phase07-original/BUILD_REPORT.md`; evidence các phase trước chỉ là lịch sử.

## 1. Kết luận và giới hạn

**Core đã compile/test thực tế. Module Paper chưa compile/type-check/link hoặc boot trên server thật.**
Không có plugin JAR đã xác minh. Không tạo Paper/Platform API stub hoặc SDK giả để làm build thành công.

Phase8 thêm history projection, completed statistics, scoped pity, atomic SQL hook, journal codec v3 và
source native read-only history/admin/PAPI. `upgrades-enabled` và `packet-renderer-enabled` vẫn false.
History mặc định false; native loader từ chối `pity.enabled=true` cho đến khi native quote và live writer được nối.
Bootstrap chưa gọi initialize progress schema hoặc khởi tạo live TransactionEngine/escrow/economy/ledger/delivery.

## 2. Compile và Java core

```bash
./scripts/test-core.sh
```

JDK **21.0.11**; compile cả core main và test bằng `javac --release 21 -encoding UTF-8 -Xlint:all -Werror`.
Chạy 8 self-contained Java test suites. Không chạy Gradle và không tải dependency trong bước kiểm chứng này.

| Suite | Nhóm test | Assertion | Failures |
|---|---:|---:|---:|
| Phase0–1 | 126 | 3.643 | 0 |
| Phase2 | 123 | 17.897 | 0 |
| Phase3 | 182 | 15.730 | 0 |
| Phase4 | 105 | 9.341 | 0 |
| Phase5 | 105 | 5.318 | 0 |
| Phase6 | 88 | 1.196 | 0 |
| Phase7 | 80 | 6.581 | 0 |
| **Phase8 mới** | **98** | **1.554** | **0** |
| **Tổng core** | **907** | **61.260** | **0** |

Log: `phase08/core-tests.log`; XML suite trong `phase08/*self-test.xml`.
13 nhóm mock JDBC Phase4 và 10 nhóm mock JDBC Phase5 đã nằm trong tổng trên, không cộng thêm.
Parameter loops/assertion loops không phải test độc lập hoặc load benchmark.

Phase8 pure-core kiểm tra policy overlap/limits, scope isolation, points/cap/clamp, quote revalidation/version,
config gate/model, history state/outcome/order/cursor, session/request/callback fencing, stats cache TTL/counters,
codec v3 và bounded parsing. Item/receipt/clock/economy context của pure-core suite là fixture, không phải Minecraft.

## 3. Java repository + SQLite thực qua bridge kiểm thử

```bash
./scripts/test-progress-sqlite.sh
```

**31 nhóm / 535 assertions / 0 lỗi**, tách khỏi tổng core. Evidence:
`phase08/sqlite-repository-bridge.log`, `phase08/phase08-sqlite-bridge.xml`.

Suite gọi **JdbcTransactionRepository và JdbcProgressRepository thật**, chạy SQL/PreparedStatement của source Java
trên SQLite thực bằng Python stdlib. `PythonSqliteBridge` triển khai phần JDBC interface cần dùng cho test,
chuyển lệnh đến Python process giữ cùng SQLite connection. Nó không trả row giả để thay SQL engine.
Đây **KHÔNG phải xerial JDBC driver**, MySQL, Platform DatabaseService, connection pool hoặc Minecraft server.
Bridge/test chỉ nằm dưới test/scripts, không được đưa vào production plugin artifact.

Các tình huống đã chạy:

- Claim ghi journal + lock + history và kiểm tra pity; stale stamp/default hook đều fail-closed.
- Completed win/loss: history, statistics, pity, completion tombstone và unlock cùng transaction.
- Lỗi hook sau khi đã cập nhật counter làm rollback cả journal/event/counter/unlock.
- Lặp CAS, duplicate claim, completion digest conflict, mất phản hồi COMMIT rồi đọc lại không đếm hai lần.
- Reconciliation/abort/reservation không làm tăng loss hoặc pity.
- Keyset pagination owner/filter; missing table/malformed row không biến thành zero statistics.
- Backfill projection-only không sửa stats/pity; retention chỉ xóa terminal UI history theo batch.
- Hai SQLite connections cạnh tranh cùng player; chỉ một claim lấy lock.
- **Hai Java child process halt trước/sau terminal COMMIT**, reopen DB và retry kiểm tra tính nhất quán.

Process-exit test không phải mô phỏng power-loss disk/controller, kill Minecraft inventory hoặc economy provider thật.
SQL busy/query-timeout/connection lifecycle của production JDBC driver vẫn chưa được xác minh.

## 4. Regression và tương thích dữ liệu cũ

```bash
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
python3 scripts/verify-legacy-journal.py
python3 scripts/verify-legacy-output-v2.py
```

| Phạm vi | Kết quả | Giới hạn |
|---|---|---|
| SQLite journal regression | 15 tests, 0 lỗi | SQL thực qua Python; không native JDBC/provider |
| SQLite output regression | 14 tests, 0 lỗi | SQL thực qua Python; output fixture không phải NBT thật |
| Legacy v1 | 18 history records +1 competitor giữ bytes/digest | Không phải migration runtime/item provider |
| Legacy v2 | 4 plans +126 record/state hash pairs giữ nguyên | Golden hashes từ compile actual Phase07 ZIP |

Provenance v2: `phase07-fixtures/README.md`, `phase07-fixtures/output-v2-hashes.txt`.
Non-pity v1/v2 không bị encode lại thành v3. Chỉ plan có PityStamp dùng v3; state envelope vẫn gắn plan digest.
Không tự tái tính lifetime statistics hoặc ordered pity từ một tập history cũ chưa chứng minh đầy đủ.

## 5. Demo và supplemental checks

```bash
./scripts/run-progress-demo.sh
./scripts/run-gui-demo.sh
./scripts/run-animation-demo.sh
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
```

| Phạm vi | Kết quả |
|---|---|
| Pity CLI fixture | 9% → 10.25% →14% →19%; cap10pp; stale version yêu cầu xác nhận lại |
| GUI regression demo | Chạy thành công; preview fixture, không inventory native |
| Animation regression demo | Chạy thành công; predetermined outcome, không live delivery |
| Resource supplement | **1.429 checks /21 YAML**, PyYAML6.0.3 |
| Java syntax parser | **206 Java files /0 syntax errors**, gồm main + tests |

Resource checker không thay Java SnakeYAML/Adventure/Paper config validation. Parser syntax **không** kiểm tra
Paper/API method signatures, generic types, dependency linkage hoặc native thread scheduling.
Demo không tạo SQL transaction/receipt hoặc tiêu phí thật; không phải UI video/server test.

## 6. Native source cần staging

Chưa xác minh: Paper/LeDatPlatform type-check và bootstrap; JDBC driver/MySQL/InnoDB; hook connection ownership
của Platform; config loader/Adventure runtime; inventory event/cursor/client exploit; PAPI provider; native stats
cache invalidation; actual live quote → pinned pity → writer completion; escrow/economy/ledger/mailbox receipt;
load/backpressure trên server. Chưa benchmark TPS/MSPT/FPS/latency. Chưa claim Folia.

History initialization/backfill/retention là seam hoặc API tường minh, **chưa scheduled native maintenance job**.
Stats cache chỉ warm qua `/upgrader statistics`, cold/expired placeholder trả blank, không query SQL từ PAPI.
UI WIN/LOSS không phải delivery receipt. COMPLETED đúng đến mức adapter receipt chứng minh được trong runtime.

## 7. Môi trường và tái lập

Evidence `phase08/environment.log`: JDK21.0.11, Python3.13.5/SQLite3.46.1, Gradle không có trong PATH,
`libs/` chưa có API artifact LeDatPlatform; Maven DNS thất bại khi kiểm tra thực tế. Chỉ có API guide không đủ
để xác minh chữ ký ABI. Không cung cấp plugin JAR hoặc artifact dependency chưa build thật.

```bash
chmod +x scripts/*.sh
./scripts/test-core.sh
./scripts/test-progress-sqlite.sh
./scripts/run-progress-demo.sh
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
python3 scripts/verify-legacy-journal.py
python3 scripts/verify-legacy-output-v2.py
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
```

Python bridge chỉ dùng stdlib; `verify-resources.py` cần PyYAML. Hai script legacy cần core vừa compile.
Archive retest sẽ ghi ở release-check bên ngoài ZIP; không cộng các lần chạy lại vào số test công bố.
Source manifest `SOURCE_SHA256SUMS.txt` bao gồm source/config/docs/evidence, không bao gồm build outputs/SDK jars.
