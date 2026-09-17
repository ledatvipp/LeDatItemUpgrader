# Build & Verification — Phase 9A

Source **`0.9.0-phase09a`**, kiểm chứng ngày **2026-09-17**. Baseline thực tế là
`LeDatItemUpgrader-phase08-source.zip`; SHA-256 ghi trong `phase09/environment.txt`.
Báo cáo Phase 8 được giữ ở `phase08-original/BUILD_REPORT.md`. Các logs/fixtures phase cũ là evidence lịch sử.

## 1. Kết luận

**Core compile/test thành công. Phase 9A là storage hardening từng phần, không hoàn tất Phase 9.**
Native Paper/LeDatPlatform vẫn chưa compile/type-check/link hoặc boot server. Không có plugin JAR đã xác minh.
Không tạo SDK stub, thay Platform bằng mock trong production, hoặc đưa JDBC transport kiểm thử vào plugin.

Mới: schema bootstrap/readiness chung, native-source lifecycle/wiring, retention opt-in và read-only recovery summary.
Không nối live engine/escrow/economy/ledger/output/delivery; upgrades/packets/native pity vẫn gated. Schema default OFF,
maintenance false, history default false. Không đổi storage backend hoặc journal codec.

## 2. Core — thực thi bằng javac và Java

```bash
./scripts/test-core.sh
```

JDK **21.0.11**, `javac --release 21 -encoding UTF-8 -Xlint:all -Werror` cho toàn bộ core main và test.
Không cần Gradle/dependency để chạy 9 self-contained test suites này.

| Suite | Nhóm kiểm thử | Assertion | Lỗi |
|---|---:|---:|---:|
| Phase 0–1 | 126 | 3.643 | 0 |
| Phase 2 | 123 | 17.897 | 0 |
| Phase 3 | 182 | 15.730 | 0 |
| Phase 4 | 105 | 9.341 | 0 |
| Phase 5 | 105 | 5.318 | 0 |
| Phase 6 | 88 | 1.196 | 0 |
| Phase 7 | 80 | 6.581 | 0 |
| Phase 8 | 98 | 1.554 | 0 |
| **Phase 9A mới** | **47** | **147** | **0** |
| **Tổng core** | **954** | **61.407** | **0** |

Evidence: `phase09/core-tests.log`, `phase09/*self-test.xml`. Core0–1 report tên `self-test.xml`.
Các vòng parameter/assertion không tính thành test riêng. 13 nhóm mock JDBC Phase4 và10 nhóm mock JDBC Phase5 đã
nằm trong tổng, không cộng thêm. Fixture item payload/clock/future/economy ở core không phải Minecraft/server.

47 nhóm mới kiểm tra settings, single-flight, operation capacity thật sau timeout, out-of-order revision,
reload/stop/late failure publication và logging, explicit recheck, no automatic retry, một batch maintenance,
recovery page/parser/summary bounds, compact read SQL và redacted exception summary. 128 lần poll trên8 worker là
synthetic concurrency test, không phải benchmark 128 người chơi hoặc TPS/MSPT.

## 3. Repository Java + SQLite thực qua transport kiểm thử

```bash
./scripts/test-storage-sqlite.sh
./scripts/test-progress-sqlite.sh
```

| Suite | Nhóm kiểm thử | Assertion | Lỗi |
|---|---:|---:|---:|
| **Storage Phase 9A mới** | **33** | **183** | **0** |
| Progress Phase 8 regression | 31 | 535 | 0 |

Các suite này **tách khỏi tổng core**. Evidence `sqlite-storage-bridge.log`, `sqlite-progress-regression.log`,
`phase09-sqlite-bridge.xml`, `phase08-sqlite-bridge.xml` trong `phase09/`.

Java production repository/PreparedStatement chạy SQL trên SQLite3.46.1 của Python stdlib, cùng connection trên
mỗi bridge process. `PythonSqliteBridge` triển khai phần JDBC interface/metadata cần cho test, chuyển probe metadata
sang sqlite_master/pragma thực. Nó không phải xerial JDBC, Platform DatabaseService, MySQL hay connection pool.
Bridge chỉ ở test/scripts, không nằm trong production artifact. Không thay database engine bằng danh sách row giả.

33 nhóm mới bao gồm init12bảng/4index; verify dưới `PRAGMA query_only`; init idempotent không xóa record; preflight
version tất cả component trước CREATE; version1.5 không được getInt truncate thành1; versioned data-table loss;
unversioned populated component; partial empty install; borrowed transaction ownership; missing/wrong/partial index;
PK/unique/view/column rejection; hooked writer→stats/pity completion; compact recovery cursor/owner lock và invalid state;
retention one batch/counter receipts survival/poisoned terminal flag active và reconciliation.

Progress regression31 nhóm giữ test crash process trước/sau terminal commit, lost commit ack, SQL hook rollback,
CAS/pity/history versions, concurrency và pagination. Đây là process/SQL tests, không kill Minecraft với item/currency thật.

**Ghi chú lần chạy:** một invocation ghép nhiều suite bị hết timeout của công cụ, làm bridge process kết thúc sớm
ở progress suite. Log giữ tại `phase09/progress-interrupted.log`. Chạy lại riêng toàn bộ progress suite hoàn tất31/535/0;
invocation bị ngắt không được cộng vào tổng completed tests. Không sửa source để bỏ qua test đó.

Có nhánh tùy chọn `JDBC_TEST_CLASSPATH=/path/to/driver-and-dependencies ./scripts/test-storage-sqlite.sh` gọi DriverManager
thật. Nhánh này **chưa chạy** trong môi trường bàn giao vì không có artifact driver. Không tự tải hoặc nhúng JAR.

## 4. SQL regression và compatibility

```bash
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
python3 scripts/verify-legacy-journal.py
python3 scripts/verify-legacy-output-v2.py
```

**15 journal +14 output SQL tests** đều qua trên SQLite thực qua Python. SQL được export từ source Java; không phải
JDBC driver/native provider integration. Logs: `phase09/sqlite-journal.log`, `phase09/sqlite-outputs.log`.

Legacy v1 giữ bytes/digests của **18 history records +1 competitor fixture**. Legacy v2 giữ hashes của **4 plans và126
record/state pairs** từ Phase7 baseline thật. Logs `legacy-v1.log`, `legacy-v2.log`. Không có schema/codec migration mới;
retention chỉ thêm state guard vào history projection query, không sửa attempt payload hoặc completion receipt.

## 5. Resource và syntax checks bổ sung

```bash
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
```

**1.480 checks /22 YAML**, PyYAML6.0.3. Có kiểm tra flags mặc định, permission/messages, service ownership wiring,
new file registration, field ranges và `'OFF'` phải là string. Không thay loader SnakeYAML/MiniMessage runtime.

**219 Java source files /0 syntax error**, dùng JDK parser syntax-only. **Không** type-check, resolve dependency,
compile native API bridge hoặc link Paper. Gradle script mới và PowerShell runner chưa chạy bằng Gradle/Windows.
Evidence `phase09/resources.log`, `phase09/syntax.log`.

## 6. Native build preflight — blocked, không phải PASS

```bash
python3 scripts/native-preflight.py
```

Kết quả thực tế exit **1**:

```text
PASS JDK: javac 21.0.11
BLOCKED Gradle: installed Gradle executable not found
BLOCKED API: API artifact is missing; supply --api-jar with the actual server API JAR
RESULT blocking_prerequisites=2
```

Script không probe network, không build JAR hoặc tạo API stub. API class presence/hash guard chỉ là kiểm tra cấu trúc,
không xác thực nguồn artifact, API version hay ABI. Môi trường đã có các lần thử Maven/GitHub download không thành
công; không có real driver/Paper/Platform artifact để type-check. Dependency resolve là gate riêng sau preflight.

`paper/build/libs/LeDatItemUpgrader-0.9.0-phase09a.jar` chỉ là output path dự kiến, **không phải file bàn giao có thật**.

## 7. Các giới hạn chưa vượt qua

- Paper/Platform ABI và runtime scheduler/query/config ownership chưa kiểm chứng. Chưa NBT/custom item roundtrip.
- Native source history readiness/retention/admin command cần staging; GUI event/cursor exploit và no-permission chưa test client.
- Schema verifier kiểm named columns/PK/unique/index/version, không audit toàn type/nullability/collation/trigger hoặc mọi row.
- MySQL/MariaDB/InnoDB metadata/DDL/transaction chưa chạy. Initializer không có distributed migration lock; DDL có thể partial.
- Timeout fences UI/state, không đảm bảo kill JDBC. Stop không chứng minh in-flight effect/DDL đã rollback.
- Recovery listing không phải integrity scan, không tìm mọi orphan lock và không sửa transaction.
- Native pity quote, live writer/effect port, escrow/economy/ledger/output/durable delivery vẫn chưa bootstrap.
- Chưa crash test server thật, stress TPS/MSPT, Folia, proxy/network synchronization, packet renderer hoặc Resource Pack.

## 8. Handoff và tái lập

Source/config/docs/evidence được đóng gói kèm `SOURCE_SHA256SUMS.txt` (không tự hash chính manifest). Không nhúng
build outputs, Platform/Paper/driver binaries hoặc data.db. Release check bên ngoài ZIP ghi SHA-256 artifact và kết quả
chạy lại sau giải nén. Chỉ sau native compile và các acceptance gate trong `docs/CONTINUE.md` mới mở live gameplay.
