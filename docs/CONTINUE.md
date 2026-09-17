# Handoff sau Phase 9A

Baseline `0.9.0-phase09a`, đầy đủ source Phase 0–8 và một phần Phase 9. User đã chốt requirement và cho phép
triển khai: không intake lại, không tạo project mới, không lặp delivery phase cũ, không viết SDK stub để đánh dấu
Paper compile thành công. Đọc `docs/PHASE09A.md`, PHASE08/05/04, BUILD_REPORT và plan trước khi sửa.

## Trạng thái thật

Core compile/test trên JDK21.0.11: 954 nhóm/61.407 assertion; riêng9A 47/147. Hai suite Java→SQLite transport test-only
lần lượt33/183 (storage mới),31/535 (progress regression), không nằm trong tổng core. SQL Python15journal/14output.
Native syntax219files/0 không type-check/link. Resource1480checks/22YAML là PyYAML, không phải loader native.

Thiếu **API artifact thật LeDatPlatform**, Gradle executable, dependencies Paper/PAPI/SnakeYAML/JDBC chưa resolve.
`native-preflight.py` hiện báo2 blockers (Gradle/API) và không probe network. Môi trường đã thử tải Maven driver nhưng
DNS/download thất bại; không claim chạy production JDBC/MySQL. API guide không thay cho binary/source module thật.
Chưa có plugin JAR đã xác minh, chưa boot server hay benchmark. Không phát stub jar để lách gate.

## Giữ nguyên những boundary này

- GUI chọn source reference; item chưa rời túi. Click UPGRADE không gọi engine, không masquerade thành preview win.
- Transaction engine là core có journal/effect contract; native escrow/economy/ledger/output/delivery chưa nối.
- Outcome phải commit trước settlement/reveal; UNKNOWN giữ lock và cần reconciliation, không refund/reward mù.
- Output theo attempt, không clone catalog template và unique identity. Protection/failure/transfer policy chốt trong quote.
- Pity chỉ DESTROY explicit allow-list, authoritative version/scoped stamp; history completion mới count; không lấy PAPI làm truth.
- Giữ codec v1/v2 bytes/digests; v3 pity không rewrite record cũ. Không replay receipt bằng animation/history.
- `upgrades-enabled=false`, packets=false, native pity=false; history=false, schema modeOFF và maintenance=false mặc định.
- Chỉ import public Platform API; scheduler managed/player owner đúng contract, không gọi Bukkit từ SQL worker.
- Không claim Folia/cross-server/distributed ACID.

## Phase 9A đã nối

`JdbcStorageBootstrap` ghép Journal/Output/Progress cùng namespace, journal **inject progress hook**. Factory này chưa
đăng ký live engine. Preflight chỉ columns/names/PK/unique/index/version/InnoDB, không kiểm toán toàn schema/data.
Native-source `PlatformStorageService` dùng public platform.query + single-flight `StorageController`; explicit
INITIALIZE có thể CREATE, VERIFY chỉ đọc, OFF không bật. Versioned data table mất không tự recreate. Không migration lock
đa server. Timeout giữ actual operation, callback cũ không publish; lỗi trễ vẫn report bằng `SafeFailure`.

HistoryUiService dùng store chung, readiness gate, đóng cache/view khi chưa ready và tạo session store mới khi re-open.
PAPI blank khi cold/unavailable. Prune one batch opt-in dùng days/batch trong history.yml; chỉ terminal projection có
stateCOMPLETED/ABORTED; không chạm immutable journal/output/stats/pity/completion receipts. Không automatic backfill.

`/upgrader storage status|recheck|recovery [uuid-cursor]` native source có permission, player+console. Recheck chạy
mode config (có thể CREATE nếu INITIALIZE), recovery chỉ đọc summary/lock, không payload hoặc auto recovery. Không
scan orphan lock; trang trống không chứng minh DB sạch. Query/keyset/batch là bounded work, chưa benchmark server.

## Phase 9B ưu tiên tiếp theo

1. Yêu cầu actual `ledat-platform-api` JAR/source tương ứng runtime, hoặc runtime Platform JAR có public API classes.
   Kiểm tra sourceguide vs ABI: identity accessors, item adapters, scheduler retirement, command/config/query ownership.
   Đặt artifact ngoài bundle, chạy native-preflight rồi Gradle thật. Preflight class entry không xác thực authenticity/ABI.
   Chỉnh adapter theo API thật, không reflection/NMS/stub để che thiếu signature. Không nâng baseline Minecraft ngầm.
2. Chạy `JDBC_TEST_CLASSPATH=/path/driver-and-dependencies ./scripts/test-storage-sqlite.sh`, rồi production Platform
   SQLite/MySQL: autoCommit ownership, catalog metadata/index probes, busy timeout, startup partial DDL, connection close,
   exact schema types/nullability/collation, failure/timeout/reload/disable. Giữ backup, single initializer và test DB riêng.
3. Bootstrap live writer duy nhất từ bundle hooked journal, cùng connection cho claim/CAS/progress. Nối authoritative
   PityQuoteService/revalidation/stale-version reconfirmation. Không warm cache rồi coi đó là snapshot transaction.
4. Native effect ports: actual source custody/slot recheck, exact template identity, inventory reservation, Vault/PlayerPoints
   evidence, Platform ledger contract, output materializer và entitlement/durable delivery. UNKNOWN không suy đoán success.
   Inventory/DB/external economy không cùng ACID; không giải quyết bằng viết trạng thái SUCCESS sau một command reward.
5. End-to-end staging + fault injection quanh từng effect, reload/quit/crash, GUI shift/double/numberkey/drag/cursor, chặn
   source dùng làm fee, unique identity mới mỗi target. Measured TPS/MSPT/packet/queue, không thay bằng synthetic assertions.
6. Chỉ gỡ flags sau acceptance riêng của backend và native. RP/packet Phase10 vẫn out-of-scope ở mốc này. Reconciliation
   mutator cần durable evidence, digest/version check, explicit admin confirmation/audit; không thêm unlock-all hoặc reroll.

## Commands để kiểm tra lại

```bash
./scripts/test-core.sh
./scripts/test-storage-sqlite.sh
./scripts/test-progress-sqlite.sh
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
python3 scripts/verify-legacy-journal.py
python3 scripts/verify-legacy-output-v2.py
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
python3 scripts/native-preflight.py --api-jar /path/to/actual/api.jar
```

Khi sửa, ghi thật phase/tests/limitations vào report/README/plan. Chỉ link file đã tạo/verify trong sandbox; không
trả mã hash hay đường dẫn JAR dự kiến như artifact đã build. ZIP phải giữ source/config/docs/evidence, không nhúng
Platform API hoặc Paper/runtime/driver binaries. Sau đóng gói giải nén và chạy lại tests từ chính bundle.
