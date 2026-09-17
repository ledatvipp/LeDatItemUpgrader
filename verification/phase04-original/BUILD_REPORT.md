# Build & Verification — LeDatItemUpgrader Phase 0–4

Ngày bàn giao: **2026-09-16**. Version source **`0.4.0-phase04`**.
Baseline thật: `LeDatItemUpgrader-phase03-source.zip`; tiếp tục project hiện có, không thay plugin hoặc dùng SDK giả.

## 1. Kết luận và phạm vi

**Core compile và kiểm thử thực tế thành công. SQL journal đã được thực thi trên SQLite thật qua Python. Module Paper chỉ có source + syntax parse: chưa compile/type-check/link/chạy server với Paper, LeDatPlatform, PAPI, Vault, PlayerPoints hoặc item providers thật. Không có plugin JAR đã xác minh.**

Phase 4 bổ sung transaction reducer/driver, immutable attempt plan, effect script/intents/receipts, integer RNG, recovery classifier, JDBC repository và async Platform SQL bridge source. Không có production `EffectPort`, native source escrow, currency debit/refund, Platform Ledger adapter hoặc durable output/mailbox. Bridge journal chưa bootstrap; `upgrades-enabled`/`packet-renderer-enabled` vẫn bị khóa false. Không đổi database mode, không tự chạy transaction migration. Core test pass không đồng nghĩa plugin đã production-ready.

`core/src/main/java`: 85 file (có CLI/demo/simulation, bị loại khỏi JAR production); core test: 6 file; Paper main source: 22 file. Tổng **113 file Java** trong `src/`. Không có native SDK stub. Java reflection trong JDBC **test doubles** không phải NMS/reflection fallback trong code plugin.

## 2. Các lệnh đã chạy

```bash
sh scripts/test-core.sh
sh scripts/run-demo.sh
sh scripts/run-catalog-demo.sh
sh scripts/run-quote-demo.sh
sh scripts/run-transaction-demo.sh
python3 scripts/test-sqlite-journal.py
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
```

| Kiểm tra | Kết quả | Evidence |
|---|---|---|
| Core main + tests compile | PASS JDK 21; `--release 21 -encoding UTF-8 -Xlint:all -Werror` | `scripts/test-core.sh`, `all-core-tests.log` |
| Phase 0–1 regression | **126 nhóm / 3.643 assertion / 0 lỗi** | `core-self-test.log`, `.xml` |
| Phase 2 regression | **123 nhóm / 17.897 assertion / 0 lỗi** | `catalog-self-test.log`, `.xml` |
| Phase 3 regression | **182 nhóm / 15.730 assertion / 0 lỗi** | `phase03-self-test.log`, `.xml` |
| Phase 4 mới | **105 nhóm / 9.341 assertion / 0 lỗi** | `phase04-self-test.log`, `.xml` |
| **Tổng Java core** | **536 nhóm / 46.611 assertion / 0 lỗi** | `all-core-tests.log` |
| SQLite SQL thực | **15 unittest tests / 0 failures / 0 errors** | `sqlite-journal-tests.log` |
| Value/Catalog/Quote/Transaction CLI | PASS detached synthetic fixtures | `demo.log`, `catalog-demo.log`, `quote-demo.log`, `transaction-demo.log` |
| Supplemental resource consistency | **506 checks / 12 YAML**, PyYAML 6.0.3 | `resources-check.log` |
| Java syntax-only | **113 files / 0 syntax error** | `java-syntax-only.log` |

JDK thực tế: OpenJDK 21.0.11. Các test harness Java tự chứa, xuất XML; không phải JUnit/MockBukkit. **13 nhóm mock JDBC nằm trong 105 nhóm Phase 4**, không cộng lần nữa. Các vòng invariant, 128 request duplicate, 1.000 case phí và 4.096 sample nằm trong các nhóm này, không phải test server/benchmark độc lập.

## 3. Nội dung core Phase 4 đã kiểm tra

### Plan, terms và payload

Quote phải là kết quả revalidation `VALID_PREVIEW`; nguồn/đích/snapshot fee khớp, chưa hết hạn. Reject reconfirm/thiếu fee/stale identity/duplicate slot/fee dùng source. Pin revision, generation, player/session/quote, terms/cost/probability, source snapshot và target template. Idempotency không đổi theo click hoặc restart.

Binary codec có version, checksum, bounds, defensive copies; không dùng Java serialization. Tests gồm round-trip, cross-plan state splice, checksum/truncation/500 bit faults, invalid state transitions, sample/plan mutation. Snapshot giả lập 500 KiB kiểm tra state envelope không phình theo item blob. Target template **không phải** output có provider identity đã materialize.

### State machine và side effects

Persist INTENT bằng CAS trước effect; chỉ driver thắng CAS được dispatch. Receipt APPLIED/NOT_APPLIED/UNKNOWN và script prefix được validate. Reservation có ledger/source/fee/currency; success/failure settlement trả phí dư đúng nhánh; loss vẫn là transaction hoàn tất. Compensation chỉ đảo acknowledged holds trước lỗi chắc chắn.

UNKNOWN, intent chưa có ack, delivery/refund không xác minh được giữ durable ownership để review. Không auto hoàn tiền sau exception, không mở lock theo TTL, không coi ledger duplicate bất kỳ là bằng chứng đã debit. Reconciliation **classifier** và resume prefix đã xác nhận có sẵn; mutator quyết định effect mơ hồ dựa vào bằng chứng provider chưa triển khai.

### RNG và fault injection

Threshold boundary, uniform-range API checks, pin sample và outcome; test không thay đổi odds theo UI/timing. Commit outcome trước settlement. Không redraw một DRAW_INTENT không còn executor gốc. Khôi phục committed outcome giữ đúng sample, không gọi RNG lần nữa.

Injected faults ở claim, intent write, effect dispatch/exception/null, ack write, draw commit và terminal commit; bao gồm lost acknowledgement sau commit. Kiểm tra duplicate, hai engine tranh journal, full success/compensation prefix restart, cancellation view, stop/drain, executor rejection và diagnostic handler lỗi.

### Mock JDBC contract — không phải driver test

Kiểm tra bindings/query timeout, transaction boundaries, row CAS/version/digest, indexed-field agreement, ownership check, rollback/restore errors, caller-owned connection không bị close, schema version guard. Dynamic proxies là fixture SQL contract; không xác minh Xerial/MySQL/Platform driver behavior.

## 4. SQLite thực đã làm gì?

`scripts/test-sqlite-journal.py` dùng Python `sqlite3` **3.46.1**, temporary database thật, WAL + synchronous FULL + busy timeout. SQL được export trực tiếp từ class `JournalSql` vừa compile; không dùng một bản SQL giả riêng cho test. JSON SQL contract và DDL mẫu nằm trong `verification/journal-sql-contract.json`, `docs/sql/`.

15 test kiểm tra claim attempt+lock+event nguyên tử; collision key/player; rollback khi event insert fail; CAS version/digest; terminal state+audit+unlock cùng commit; unfinished keyset pagination; state binary do Java codec tạo lưu DB và đọc lại bằng Java; close/reopen; competing independent connections.

Hai test dùng child process kết thúc bằng **`os._exit(73)`** trước/sau outcome COMMIT, không phải chỉ exception hoặc normal connection close:

- Trước commit: database còn DRAW_INTENT, chưa có sample. Java recovery từ bytes đọc DB yêu cầu review, additional RNG draws = 0.
- Sau commit: database giữ OUTCOME_COMMITTED với sample **12.345.678**. Java simulated resume hoàn tất với chính sample đó, additional RNG draws = 0.

**Giới hạn:** đây là SQLite engine/SQL/process-exit tests qua Python, không chạy `JdbcTransactionRepository` với JDBC driver thật, không chạy MySQL, không mô phỏng host mất điện/fsync failure, không kiểm thử Minecraft playerdata/provider persistence. Native effects trong Java resume là synthetic receipts; không dùng bằng chứng này để claim no-dupe trên server.

## 5. Persistence và performance design — chưa phải benchmark

Plan chứa item payload giới hạn 4 MiB raw/8 MiB encoded, INSERT một lần. CAS chỉ ghi state envelope tối đa 64 KiB + event hash. Plan/state digest, version và script validation chống stale/corrupt transition. Chỉ unlock terminal trong cùng SQL transaction với state/event.

Repository không giữ Connection, không gọi Bukkit/provider trong SQL transaction. Async journal bridge không `.join()` future Platform; driver dùng executor do caller quản lý, bounded admission/player guard. Không tự tạo pool hoặc task mỗi tick. Recovery page bound 1–8, không tải toàn bộ unfinished BLOB cùng lúc.

Chưa đo allocation/throughput/latency/TPS/MSPT hoặc tune pool/WAL cho production. Full plan checksum/revalidation vẫn có CPU cost trên worker. Bộ nhớ từng attempt và queued payload phải được tính vào budget khi nối live service, không coi hard max256 là cấu hình khuyên dùng cho server.

## 6. Phần chưa kiểm chứng / chưa triển khai

- Full Gradle build: môi trường không có Gradle, không tải được repo vì DNS; chưa có `ledat-platform-api-2.10.0.jar` thật. Không tạo wrapper binary giả hoặc SDK stub. `environment.log`, `dependency-access.log` ghi lần kiểm tra thực tế.
- Paper/LeDatPlatform/PAPI compile/type-check/link/runtime; `javac` syntax parser không thay type resolution.
- SQL JDBC driver, MySQL dialect/locking, actual Platform query connection contract, schema startup/concurrent migration; DDL mẫu không phải migration đã chạy trên server.
- Native inventory escrow và acknowledgement restart-durable; actual economy debit/refund; ledger claim/ack; provider reload/unavailability.
- Target materialization theo attempt, không clone template UUID; transfer policy/failure damage/downgrade; durable mailbox/delivery và evidence-based manual reconciliation.
- Inventory GUI/session click/drag/cursor safety, animation, packet/resource-pack rendering.
- Disable/reload giữa transaction live, kill server và playerdata sync, load/staging test. Stop/drain có core tests nhưng chưa wired vào live plugin transaction engine.
- PowerShell script đã cập nhật Phase 4, chưa chạy trên Windows.

## 7. Chạy lại và đóng gói

`SOURCE_SHA256SUMS.txt` liệt kê hash SHA-256 các file được giao (trừ chính manifest). ZIP không chứa build outputs, runtime API jars hoặc simulation database. CLI/demo source được giao để test nhưng Gradle excludes `demo/**` khỏi core/plugin JAR.

Sau giải nén, chạy `sha256sum -c SOURCE_SHA256SUMS.txt`, rồi các lệnh tại mục2. Bản report này mô tả những lệnh đã chạy trong source workspace. Kiểm tra lại archive cuối có log ngoài ZIP để không tạo vòng lặp hash khi cập nhật manifest.

Báo cáo phase trước là lịch sử trong `phase01-original/`, `phase02-original/`, `phase03-original/`; không dùng số source/test cũ làm trạng thái Phase4.
