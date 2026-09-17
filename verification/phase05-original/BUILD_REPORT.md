# Build & Verification — LeDatItemUpgrader Phase 0–5

Ngày kiểm chứng **2026-09-17 UTC**. Source **`0.5.0-phase05`**, baseline thật `LeDatItemUpgrader-phase04-source.zip`.

## 1. Kết luận

**Core compile/test thực bằng JDK 21 đã qua. Module Paper/LeDatPlatform chưa compile/type-check/link hoặc chạy server. Không có plugin JAR production đã xác minh.** Không dùng SDK stub để giả compile thành công.

Phase 5 đã thêm output materializer/transfer/failure/persistence và nối PREPARE_OUTPUT vào transaction core. Native Vanilla helper, strict config loader, preview messages và Platform SQL bridge có source; chưa bootstrap live. Native source/fee escrow, economy/Platform ledger, custom provider mutation, durable delivery/mailbox và Inventory/packet UI vẫn chưa triển khai đầy đủ. Hai feature flags bắt buộc false, không đổi storage mode hoặc tự migrate output/transaction schema.

## 2. Môi trường và lệnh thực thi

OpenJDK 21.0.11, javac 21.0.11, Python 3.13.5, SQLite 3.46.1 qua Python, PyYAML 6.0.3. Không có Gradle executable; không có API JAR LeDatPlatform trong libs. Curl Maven Central lần này thất bại DNS. Xem `environment.log`, `dependency-access.log`.

```bash
./scripts/test-core.sh
./scripts/run-output-demo.sh
./scripts/run-transaction-demo.sh
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
python3 scripts/verify-legacy-journal.py
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
```

Core compile với `javac --release 21 -encoding UTF-8 -Xlint:all -Werror`. Không đồng nhất syntax parser với compiler/linker. Gradle full build chưa chạy trong môi trường thiếu công cụ/dependency này.

## 3. Kết quả

| Phạm vi | Kết quả | Bằng chứng |
|---|---:|---|
| Phase 0–1 regression | 126 nhóm /3.643 assertion /0 lỗi | core-self-test.log và self-test.xml |
| Phase2 regression | 123 nhóm /17.897 assertion /0 lỗi | catalog-self-test.log/.xml |
| Phase3 regression | 182 nhóm /15.730 assertion /0 lỗi | phase03-self-test.log/.xml |
| Phase 4 regression | 105 nhóm /9.341 assertion /0 lỗi | phase04-self-test.log/.xml |
| **Phase 5 mới** | **105 nhóm /5.318 assertion /0 lỗi** | phase05-self-test.log/.xml |
| **Tổng Java core** | **641 nhóm /51.929 assertion /0 lỗi** | all-core-tests.log |
| SQLite journal SQL regression | 15 tests pass | sqlite-journal-tests.log |
| SQLite output SQL mới | 14 tests pass | sqlite-output-tests.log |
| Legacy v1 exact compatibility | 18 history states +1 competitor giữ nguyên plan/state bytes và digest | legacy-compatibility.log, phase04-fixtures/journal-contract-v1.json |
| PyYAML supplemental | 542 checks /13 YAML | resources-check.log |
| Java syntax-only | 146 files  / 0 syntax error | java-syntax-only.log |

**10 nhóm mock JDBC output nằm trong105 nhóm Phase 5; 13 nhóm mock JDBC journal nằm trong105 nhóm Phase 4**, không cộng riêng lần nữa. Các vòng damage3.000 trường hợp, corruptpayload500 trường hợp, 64 lời gọi cạnh tranh nằm trong grouped tests, không phải test Minecraft hoặc TPS benchmarks.

Source count: core main: 113 file Java (có demo/simulation, excluded from JAR), core tests: 8 file Java, Paper main: 25 file Java. Tổng 146. Java reflection chỉ dùng JDBC mocks trong tests; production không NMS/reflection. Tests tự chứa xuất XML, không JUnit/MockBukkit.

## 4. Test Phase 5 bao phủ gì?

Policy bounds/types/namespaces/allowlists; typed PDC immutable; enchant cap/applicability/conflict/MERGE_MAX; literal name; protected data/unique ID; durability ratioceil; DAMAGEmaxdurabilityceil/break/clamp-no-loss; KEEP exact snapshot; DESTROY no failurecandidate; DOWNGRADE khác key/strictly lower value.

Fresh target key/quantity/template/value pin; synthetic provider freshUUID khác catalog, clonedUUID bị chặn, purecreation capability; wrong facts/revision/value/unknown target bị từ chối. Full policy nằm trong plan digest/v2 codec. Legacy v1 encode/decode không sửa bytes/digest.

Output store claim trước factory, duplicateREADYkhông recreate, pendingcrashno replay, invalidclaimbinding, cancellationviewstillpersists, exceptiondiagnostics, lostREADYcommitack. Identity conflicts atomic theo simulation và SQLiteSQL.

Engine4 failure × 2 outcome, outputprepareafterledgerbeforehold, chắc chắn reject không hold/roll, creationUNKNOWNgiữlock, legacyacquisitionrefused, deliveryUNKNOWNkhông redraw/redeliver. JDBC output mock xác minh bindparams, statementclose/querytimeout, ready+identity cùng transaction, rollback/autocommitfailure, commitackuncertainty, checksum/read binding, future-schema rejection.

## 5. SQLite SQL thực — và những gì không được chứng minh

`test-sqlite-outputs.py` lấy SQL trực tiếp từ `OutputSqlContract` và payload Java `OutputCodec`. Có WAL/FULL, hai connection tranh claim/finish, CAS owner/digest/time, duplicate/rejection/ambiguous tombstone, identityinsertcollisionrollbackREADY, close/reopen, Java decode lại payload.

Hai childprocess `os._exit(75)` trước/sau READYCOMMIT: trướccommit còn PREPARING/noidentity; saucommit READY/exactpayload/identity. Test không re-callfactory để phục hồi pending. Phase 4 SQL regression tiếp tục thử exit quanh outcomecommit bằng `os._exit(73)`.

**Đây là SQL SQLite thực qua Python, không chạy actual JDBC driver/JdbcOutputRepository/MySQL/Platform, không kill Minecraft, không chứng minh inventory/Vault/PlayerPoints persistence hoặc OS powerloss.** Method-contract mocks kiểm tra codeJDBC riêng; không suy ra driver compatibility.

Fresh identity trong SQL fixture là hash giả để thử uniqueness, không từ MMOItems thật. Item payload của demo là SYNTHETIC-ITEM, không MinecraftNBT. Legacy codec fixturecompatibility không phải tự migrate hoặc grant oldrewards.

## 6. Demo

`output-demo.log` có8 flow DESTROY/KEEP/DAMAGE/DOWNGRADE × WIN/LOSS. Mỗi flow draw một lần, retry DUPLICATE, target creation một lần (downgrade hai lần vì hai candidate), không create lại khi retry. Damage example source40/max250 →90 khi thua; downgrade stone_sword. KEEP loss về exact-source return effect chứ không có prepared-delivery thứ hai.

Mọi nativeeffect/ledger/currency/delivery trong demo là simulatedreceipt; chưa withdraw/hold/addItem thật.

## 7. Gate còn thiếu

- Real Platform API artifact/package/signatures, Gradle/Paper full build và native lifecycle test.
- `ItemMutationPort` scheduler/capability adapters thật; Vanilla helper source chưa compile/test NBT/registry/thread model. Không Folia claim.
- Custom provider canonical/template/uniqueIDs/stats/rarity/socket/mutation; typedPDCcore không phải nativeMMO support hoàn tất.
- Transfer price drift được từ chối trước cost; UI fresh-offer/reconfirm chưa có. Literal customname không preserve styledComponent.
- Native source/fee escrow, costdebit/refund, Platform ledger, durable entitlement/mailbox, owner retirement/shutdown/reconciliation.
- JDBC SQLite/MySQL/pool thực và broken-connection contract. MySQLDDL không claim atomic.
- GUI input/click/drag/cursor/close/quit/exploit; RP/packet rendering; server restart/no-dupe/load/MSPT/TPS.
- PowerShell source cập nhật nhưng chưa chạy Windows.

Các giới hạn này là acceptance đang mở, không phải test pass bị bỏ qua. Chỉ corephase đã được xác minh trong phạm vi nêu trên.

## 8. Bàn giao và tính lặp lại

`SOURCE_SHA256SUMS.txt` liệt kê file nguồn/tài liệu/evidence, không bao gồm buildclasses hoặc chính manifest. Archive không chứa provider/API JAR hoặc SDKstub, không có output binary để cài server.

Kiểm chứng lại ZIP bằng giải nén, verify manifest, chạy scripts với JDK 21/Python. Kết quả release check được ghi ở artifact riêng `LeDatItemUpgrader-phase05-release-check.md`. Giữ file fixture v1 và chạy từ projectroot (scripts tự cd root); không xóa fixture vì test cần đối chiếu legacy codec.
