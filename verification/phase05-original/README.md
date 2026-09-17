# LeDatItemUpgrader — Phase 0–5

Version **`0.5.0-phase05`**, tiếp nối trực tiếp `LeDatItemUpgrader-phase04-source.zip`.
Target giữ nguyên **Paper 1.21.4, Java 21, LeDatPlatform API 2.10.0 theo guide được cung cấp**; không claim mọi bản 1.21.4+ hoặc Folia.

**Đã bổ sung Phase 5: output riêng theo attempt, transfer chọn lọc, DESTROY/KEEP/DAMAGE/DOWNGRADE, output journal/identity registry. Transaction Engine Phase 4 được giữ và nối thêm PREPARE_OUTPUT. Chưa có native escrow/economy/ledger/delivery adapter đã kiểm chứng, Inventory GUI hoặc packet renderer. `upgrades-enabled` vẫn bắt buộc false.**

Core đã compile/test thực tế. **Module Paper chưa compile/type-check/link/chạy trên server thật** vì thiếu API artifact LeDatPlatform; môi trường hiện tại không phân giải được DNS repo Maven và không có Gradle. Không có plugin JAR đã xác minh để cài server trong bundle. Không dùng API stub để che lỗi dependency.

## Kiểm chứng

| Phạm vi | Kết quả |
|---|---|
| Core compile | JDK 21, `--release 21 -Xlint:all -Werror` |
| Phase 0–1 regression | 126 nhóm / 3.643 assertion / 0 lỗi |
| Phase 2 regression | 123 nhóm / 17.897 assertion / 0 lỗi |
| Phase 3 regression | 182 nhóm / 15.730 assertion / 0 lỗi |
| Phase 4 regression | 105 nhóm / 9.341 assertion / 0 lỗi |
| Phase 5 mới | 105 nhóm / 5.318 assertion / 0 lỗi |
| **Tổng Java core** | **641 nhóm / 51.929 assertion / 0 lỗi** |
| SQLite SQL | **15 journal + 14 output tests pass**, chạy SQL thực qua Python `sqlite3`, không phải JDBC |
| Resource bổ sung | 542 checks / 13 YAML, PyYAML; không thay thế SnakeYAML runtime |
| Java syntax-only | 146 file / 0 syntax error; không resolve Paper/Platform symbols |

13 nhóm mock JDBC nằm **trong** 105 nhóm Phase 4; 10 nhóm mock JDBC output nằm **trong** 105 nhóm Phase 5. Không cộng chúng lần nữa và không gọi là kiểm thử driver thật. Các vòng invariant cũng nằm trong số nhóm ở bảng trên, không phải test server hoặc benchmark độc lập. Chi tiết: `verification/BUILD_REPORT.md`.

## Chạy lại backend

Linux/macOS, JDK 21:

```bash
chmod +x scripts/*.sh
./scripts/test-core.sh
./scripts/run-demo.sh
./scripts/run-catalog-demo.sh
./scripts/run-quote-demo.sh
./scripts/run-transaction-demo.sh
./scripts/run-output-demo.sh
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
```

Test SQL dùng thư viện chuẩn Python, không cần cài JDBC driver. `python3 scripts/verify-resources.py` cần PyYAML. Script SQL tự lấy đúng câu lệnh từ class `JournalSql` và `OutputSql` vừa compile, không giữ một bản SQL test khác với repository.

PowerShell:

```powershell
./scripts/test-core.ps1
python ./scripts/test-sqlite-journal.py
python ./scripts/test-sqlite-outputs.py
```

PowerShell script có source tương ứng; chưa chạy trên Windows trong môi trường bàn giao này.

Gradle khi đã cài công cụ/dependency:

```bash
gradle backendCheck
gradle clean build -PplatformApiJar=/absolute/path/to/ledat-platform-api-2.10.0.jar
```

`backendCheck` chỉ xác minh core. Full build yêu cầu API JAR **thật**; mặc định tìm `libs/ledat-platform-api-2.10.0.jar`. Plugin JAR dự kiến ở `paper/build/libs/`; core không phải một plugin riêng. Demos/SimulationJournal bị loại khỏi JAR phát hành. Project không giả vờ có Gradle wrapper khi thiếu wrapper binary.

**Không đưa lên server production chỉ vì core test pass.** Full Paper build, native provider/escrow/delivery test và các gate ở `docs/CONTINUE.md` phải hoàn tất trước.

## Phase 5 làm gì?

`EffectScript` của quote mới đi qua `LEDGER_CLAIM → PREPARE_OUTPUT → HOLD_SOURCE → giữ phí/debit`. Bước chuẩn bị lưu riêng hai **candidate** thắng/thua, chưa phát item và chưa cấp quyền nhận item. Chỉ settlement có outcome đã commit mới chuyển đúng candidate qua `PreparedDeliveryPort`. Không còn dùng catalog template như reward.

`OutputMaterializer` inspect source/template, tạo target fresh qua `ItemMutationPort`, kiểm tra key/amount/template/giá, loại UUID trùng source/catalog, apply selective transfer rồi tạo failure candidate. Nếu template hoặc giá sau transfer khác điều khoản đã xác nhận: `RECONFIRM_REQUIRED`, không âm thầm đổi chance. Chưa có GUI tự tạo lại offer mới để xác nhận chênh lệch đó.

| Failure | Semantics ở core |
|---|---|
| `DESTROY` | Không có item trả ở nhánh thua |
| `KEEP` | Trả đúng snapshot source, không rebuild/copy metadata vào item mới |
| `DAMAGE` | Tăng damage theo phần trăm **max durability**, làm tròn lên; `DESTROY` hoặc `CLAMP_ONE` khi chạm ngưỡng hỏng |
| `DOWNGRADE` | Fresh-create item khác key theo cấu hình, kiểm tra tổng value sau transfer thấp hơn source |

`CLEAN` giữ nguyên template target mới. Selective transfer hỗ trợ literal custom name, repair cost, enchant allow-list `MERGE_MAX`, tỷ lệ damage làm tròn lên và typed PDC có capability. Không có COPY_ALL_PDC. Native Vanilla helper **chưa hỗ trợ PDC transfer**; custom provider/stats/socket/unique-ID adapter thật còn thiếu. Custom name hiện chuyển plain text, không giữ style Component hoặc parse MiniMessage.

Output store claim một lần theo attempt, `READY` + các fresh identity hashes commit cùng SQL transaction. `PREPARING`/`AMBIGUOUS` không phải lease hết hạn để tạo lại. Identity registry chỉ ngăn trùng các token do adapter cung cấp trong output store này, không phải anti-dupe toàn server.

Thêm file có comment `paper/src/main/resources/upgrades/outputs.yml`. Mặc định `clean`; mẫu damage/downgrade và vanilla-safe chưa gắn profile/path. Không đổi storage mode, không mở giao dịch, không ghi đè values cũ.

Chi tiết/contract nối GUI: `docs/PHASE05.md`. Demo mới `./scripts/run-output-demo.sh` sử dụng **synthetic item payloads và simulated receipts**, không Minecraft NBT/provider thật.

## Transaction nền Phase 4 (vẫn giữ)

### Từ quote tới attempt

`AttemptPlanner` chỉ nhận `VALID_PREVIEW` từ revalidation, không nhận replacement cần xác nhận lại. Nó kiểm tra expiry, source fingerprint/facts, target fingerprint, snapshot phí, loại trừ source slot và số lượng giữ đúng tuyệt đối.

`AttemptPlan` pin player/session/quote, config revision, catalog generation, source snapshot đầy đủ, template target, giá trị, probability threshold, profile/boost/bonus, failure terms, cost plan và các fee item allocation. Key idempotency cố định theo player + quote; cùng quote đổi điều khoản là conflict.

Plan không chứng minh quyền sở hữu item. Owner-thread adapter tương lai vẫn phải kiểm tra lại actual inventory, player/session/expiry/access và giành escrow ownership trước khi xác nhận `HOLD_SOURCE`.

### State machine

```text
PREPARED → RESERVING → DRAW_INTENT → OUTCOME_COMMITTED → SETTLING → COMPLETED
                 ↘ COMPENSATING → ABORTED
                 ↘ RECONCILIATION_REQUIRED
```

Mỗi effect: **persist INTENT → gọi adapter → persist receipt**. Chỉ driver thắng CAS tạo INTENT mới được thực thi effect đó. Đọc thấy INTENT cũ không phải quyền chạy lại.

Receipt có `APPLIED`, `NOT_APPLIED`, `UNKNOWN`. `NOT_APPLIED` phải là không có mutation một phần. Exception, timeout không có bằng chứng, partial mutation hoặc provider trả lời mơ hồ là `UNKNOWN`.

Nếu reserve thất bại chắc chắn, engine bù lại phần reserve đã được xác nhận. Nếu không rõ đã debit/give hay chưa, engine giữ player lock và chuyển đối soát; không tự refund/reward.

### RNG và phí

`SecureTicketSource` dùng `SecureRandom.nextInt(1_000_000_000)`. Sample nguyên so với threshold của Phase 3. Không seed theo player/tick, không dùng modulo map hoặc floating-point roll.

Sau `DRAW_INTENT`, outcome phải lưu thành công trước mọi settlement/reveal. Outcome đã commit không đổi khi resume. Crash trong `DRAW_INTENT` mà chưa có outcome được đưa sang review, **không tự lấy một sample khác**.

Giữ tài nguyên theo `ON_ATTEMPT + max(ON_SUCCESS, ON_FAILURE)`. Sau outcome, chỉ hoàn phần dư đúng nhánh; fee item chia deterministic theo allocation slot. Ví dụ Vault 10/20/30: giữ 40, thắng tiêu 30/hoàn 10, thua tiêu 40/hoàn 0.

Thua RNG vẫn là transaction `COMPLETED` + losing outcome, không phải internal error hoặc mặc định hoàn phí. `LEDGER_SUCCESS` nghĩa settlement hoàn tất, không nghĩa roll thắng.

### SQL journal và recovery

`JdbcTransactionRepository` có claim nguyên tử gồm attempt + player lock + event; CAS nguyên tử gồm state + event + unlock khi terminal. Các query dùng `PreparedStatement`, version/digest guard và keyset pagination. Repository không giữ/đóng connection do Platform cấp, không gọi native effect trong SQL transaction.

**Plan payload lớn chỉ INSERT một lần.** Các CAS chỉ ghi state envelope tối đa 64 KiB + event hash, không rewrite item blob qua mỗi bước. Hard cap tổng item payload của plan là 4 MiB; encoded plan bị giới hạn 8 MiB. Đây là hard bound bảo vệ dữ liệu, không phải benchmark TPS.

Có DDL SQLite và MySQL/InnoDB; MySQL/JDBC runtime vẫn chưa được kiểm chứng. `PlatformTransactionJournal` dùng `PlatformAccess.query(...)` async, không `.join()` và chưa được bootstrap để mở giao dịch thật.

Recovery là phân loại/read-only + resume prefix đã xác nhận sau khi executor cũ đã dừng chắc chắn. Chưa có command admin tự ghi đè receipt, chọn lại kết quả hoặc xóa lock. Mọi pending/ambiguous effect cần adapter có bằng chứng và quy trình đối soát thật ở bước tiếp theo.

### Lifecycle

Admission giới hạn theo player và tổng in-flight; caller truyền bounded executor, engine không tạo pool riêng/common-pool. Journal có API async, không chờ DB trên owner thread. Caller cancel future chỉ cancel view của nó, không cancel một debit đang chạy. Stop chặn việc mới, chờ receipt gốc, không giải phóng lock theo TTL để chạy attempt khác.

## Demo đã chạy

```text
CHANCE=9% SAMPLE=12345678 RESULT=COMPLETED
DUPLICATE=DUPLICATE draws=1 deliveries=1
RECOVERY=COMPLETED sample=12345678 additional-draws=0
AMBIGUOUS_DEBIT=RECONCILIATION_REQUIRED player-lock-retained=true
```

**Toàn bộ item, effect receipt, ledger và currency trong demo là giả lập.** Không có Vault withdraw hoặc inventory mutation thật.

SQLite test dùng file database thật, WAL/FULL, hai connection tranh claim, rollback ở event/state/unlock, close/reopen và child process `os._exit(73)` trước/sau commit outcome. Payload thực từ Java codec được lấy lại từ DB để inspect/replay giả lập. Không gọi đó là thử mất điện hệ điều hành, crash Minecraft hoặc bằng chứng persistence của player inventory.

## Những phần cũ vẫn giữ

Value Engine hybrid, recipe whitelist, unknown-value fail-closed; Catalog/path LOCKED/OPEN/recommendation/pagination; chance RATIO/POWER/TABLE/CURVE; profile/boost/protection, typed PAPI conditions và quote revalidation từ Phase 3. Đọc `docs/PHASE02.md`, `docs/PHASE03.md` để xem chi tiết.

Paper source vẫn chỉ expose preview/admin command:

```text
/upgrader
/upgrader help
/upgrader status
/upgrader value
/upgrader inspect
/upgrader reload
/upgrader catalog [page] [category|all] [recommended|value_asc|value_desc|id]
/upgrader recommend
/upgrader paths
/upgrader quote target-id [profile|default] [boost1,boost2|none]
/upgrader profiles [page]
/upgrader boosts [page]
```

Không thêm command trừ phí hoặc giao dịch thật ở Phase 5. GUI matrix/symbol/action blueprint và khả năng cấu hình item model vẫn giữ nguyên; chưa có holder/click/packet runtime.

## Config an toàn

`config.yml` giữ `config-version: 1`, `storage.mode: PLATFORM_SHARED`; không migrate/ngầm mở database SQLite riêng.

```yaml
features:
  upgrades-enabled: false
  packet-renderer-enabled: false
storage:
  mode: PLATFORM_SHARED
  initialize-schema: false
```

`initialize-schema: true` **chỉ khởi tạo schema_meta nền cũ**, không mở transaction engine. Bật một trong hai feature flags bị validator từ chối. Hai booster mẫu và VIP bonus tiếp tục mặc định tắt.

`messages.yml` có fallback; giữ mọi thông báo player trong YAML, không parse input player thành MiniMessage. Không có message transaction mới gửi ra player vì chưa có native transaction endpoint.

## Các gate còn thiếu quan trọng

API JAR/source LeDatPlatform thật để full build; native source/fee escrow với bằng chứng persistence; Vault/PlayerPoints + Platform Ledger adapter xác minh op/binding; native provider output/transfer integration; durable pending-delivery/mailbox; GUI ownership/close/quit/exploit tests; MySQL/JDBC integration, shutdown/reload/load test.

**Không clone target template cache thành item thật cho mọi attempt.** Core Phase 5 đã materialize và lưu output riêng theo attempt; adapter thật phải chứng minh provider UUID/anti-duplicate identity an toàn trước khi mở live. Snapshot target ở plan hiện là template eligibility được pin, chưa là item phát cho người chơi.

Đường triển khai tiếp: `docs/CONTINUE.md`; contract Phase 4: `docs/PHASE04.md`; contract Phase 5: `docs/PHASE05.md`.
