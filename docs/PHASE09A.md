# Phase 9A — Storage bootstrap, readiness và maintenance

Version `0.9.0-phase09a`, tiếp tục trực tiếp ZIP Phase 8. Đây là **một phần Phase 9**, không phải chứng nhận
production-ready và không mở nâng cấp thật. Target không đổi: Paper 1.21.4, Java 21; Platform API theo guide 2.10.0.

## 1. Mục tiêu và ranh giới

Các phase trước đã có journal/output/progress repository nhưng chưa có một bootstrap chung để kiểm tra schema,
chặn history khi storage chưa sẵn sàng và điều phối retention. Phase này bổ sung bundle repository cùng namespace,
preflight schema không sửa dữ liệu, single-flight lifecycle, native-source wiring và read-only recovery listing.

`READ_READY` chỉ có nghĩa **schema đã qua những probe được mô tả dưới đây trên revision hiện tại**. Nó không chứng
minh provider, snapshot, escrow, economy, ledger, output materializer hay delivery hoạt động. Không construct hoặc
register `TransactionEngine` trong `PlatformStorageService`. `upgrades-enabled`, `packet-renderer-enabled` và native
pity vẫn false. Source reference chưa chuyển đồ khỏi túi; animation vẫn chỉ là trình diễn.

## 2. Kiến trúc và các file chính

`storage/management/JdbcStorageBootstrap` ghép `JournalSql`, `OutputSql`, `ProgressSql`, chặn trùng tên bảng giữa
component. Nó tạo `JdbcTransactionRepository` **với** `JdbcProgressRepository` làm commit hook; không trả unhooked
journal từ bundle. Tất cả vẫn nhận Connection của provider trong từng callback, không sở hữu connection lâu dài.

`StorageController` là core thuần: giữ revision, ticket, timeout, single-flight và lịch một batch retention.
`PlatformStorageService` chỉ làm native bridge: Platform managed query, ticker chung, status/recheck/inspection,
shared `PlatformHistoryStore` và lifecycle. `HistoryUiService` không còn tự tạo hoặc tự đóng một store SQL riêng.
`JdbcRecoveryInspector` đọc cột trạng thái gọn; không giải mã payload giao dịch. `SafeFailure` tạo log đã loại bỏ
raw driver message và JDBC URL.

## 3. Cấu hình opt-in

### `paper/src/main/resources/storage-management.yml`

```yaml
config-version: 1
schema-mode: 'OFF'
operation-timeout-seconds: 30
maintenance:
  enabled: false
  interval-seconds: 3600
```

Phải quote `'OFF'`: YAML 1.1 có thể coi OFF là boolean. Giá trị này đã được phát hiện và sửa qua resource check.
Reload parser vẫn yêu cầu chuỗi enum, không tự biến boolean false thành mode.

| Mode | Hành vi của service mới |
|---|---|
| `OFF` | Không chạy schema query hoặc retention. History ở trạng thái unavailable. |
| `VERIFY` | Chỉ đọc cấu trúc/version, không CREATE/ALTER/DROP hoặc tự sửa dữ liệu. |
| `INITIALIZE` | Preflight tất cả component trước; explicit CREATE bảng/index còn thiếu cho cài mới/cài dở không có dữ liệu; sau đó verify lại. |

Mode không thay đổi storage backend: tiếp tục `PLATFORM_SHARED`; không tự chuyển thành SQLite riêng. Flag cũ
`config.yml.storage.initialize-schema` giữ nguyên nghĩa với `schema_meta` nền, **không** tự mở rộng thành init
12 bảng mới. Các query độc lập của Platform/plugin khác không bị mode này kiểm soát.

Maintenance lấy `retention-days` và `retention-batch` từ `history.yml`, không có hai bộ số liệu mâu thuẫn. Default
hiện tại là 90 ngày, 200 bản ghi/batch; service mặc định không chạy purge. OFF + maintenance=true bị từ chối.

## 4. Quy trình setup sau khi có native build đã kiểm chứng

Trước khi thao tác schema trên server: có API artifact thật, compile/type-check Paper, staging cùng Platform/driver,
backup DB và đảm bảo chỉ **một initializer** đang chạy. Không có distributed migration lock ở Phase 9A.

Trên DB dành cho staging/cài mới, đặt `schema-mode: INITIALIZE`, để maintenance=false rồi reload. Service dispatch
query async và chỉ công bố READ_READY khi đầy đủ component qua verify. Sau thành công, chuyển `VERIFY` và reload.
Khi đó mới bật `history.enabled` để đọc các bản ghi đã có. History rỗng ở cài mới là bình thường: native live writer
chưa chạy. Không tạo history giả để lấp menu.

INITIALIZE không phải công cụ sửa corruption. Component đã có version nhưng mất data table phải FAILED, không
recreate một bảng rỗng rồi báo thành công. DDL có thể để lại trạng thái cài dở, nhất là MySQL; không tuyên bố toàn
bộ init là một transaction atomic. Cài dở rỗng hợp lệ có thể chạy lại; dữ liệu không version hoặc version lạ phải
xử lý nguyên nhân trước. Không tự backup hoặc tự downgrade schema.

## 5. Phạm vi schema verification

Bundle kiểm tra 12 bảng và 4 index có tên:

- Journal: `attempts`, `player_locks`, `tx_events`, `tx_schema`.
- Outputs: `outputs`, `output_identities`, `output_schema`.
- Progress: `history`, `statistics`, `pity`, `completions`, `progress_schema`.

Prefix/name thật đi qua `PlatformAccess.tableName`, constructor SQL whitelist và kiểm tra collision. Không lấy
identifier từ command input. Metadata component IDs là `transactions-v1`, `outputs-v1`, `progress-v1`, version 1.
Không đổi journal codec v1/v2/v3 hoặc bytes của plan/state.

Kiểm tra gồm: bảng thật không phải view, đúng component/version, named columns truy vấn được, thứ tự primary key,
unique guard của idempotency/lock, thứ tự cột của index bắt buộc. SQLite dùng thêm `pragma_index_list` để phát hiện
partial unique index mà metadata driver có thể không mô tả filter. MySQL branch yêu cầu InnoDB và database product
được nhận diện rõ. MySQL branch **chưa được kiểm thử trên MySQL/MariaDB thật**.

Không kiểm toán mọi SQL type, nullability, default, collation, foreign key, trigger, dữ liệu trong từng row hoặc
index ngoài danh sách. Không có `quick_check`, `integrity_check` hay benchmark query plan. Vì vậy READ_READY không
phải xác nhận toàn bộ database toàn vẹn. Future live-writer acceptance cần driver, schema và data integrity review.

Connection phải bắt đầu autoCommit=true. Service không chiếm transaction của caller. Shared provider cần đáp ứng
contract này; nếu provider mượn connection đang trong transaction, bootstrap từ chối với BORROWED_TRANSACTION.

## 6. Lifecycle, timeout, reload, stop

```text
OFF
  hoặc WAITING → CHECKING → READ_READY
                          → FAILED
STOPPED chặn mọi dispatch mới
```

Một operation thật duy nhất tại một thời điểm, gồm schema hoặc retention. Timeout đánh FAILED và đóng readiness
nhưng không cancel future, không giả rollback, không nhả permit sớm để xếp thêm operation khác. Callback đến trễ
không thể publish READ_READY; lỗi vật lý đến trễ vẫn log một lần bằng thông tin đã lọc. Query timeout không cam kết
ngắt được mọi JDBC/DDL. Khi DB treo, retry phải chờ operation thật kết thúc; đây là deliberate fail-closed.

Config swap sang revision mới đóng readiness ngay. Old flight vẫn giữ capacity tới khi hoàn tất; result không được
áp vào revision mới. Tick kế tiếp mới dispatch operation mới; không loop retry trong whenComplete. Runtime revision
không đổi mà settings khác bị core từ chối. Một prune lỗi/timeout cũng đóng history cho tới explicit recheck/reload.

Stop hủy ticker của service, đóng gate/store; Platform owner shutdown đảm nhiệm flush/close với ngân sách hiện có.
Operation đã chạy có thể còn commit sau khi UI đã đóng; không tuyên bố close có thể hoàn tác DDL/DELETE.

## 7. History readiness và retention

History GUI/command/PAPI chỉ đọc khi mode/schema/revision hiện tại READ_READY và history.enabled=true. Reload,
storage failure hoặc stop dọn view/cache và session store; khi sẵn sàng trở lại tạo session store mới thay vì tái
sử dụng store đã đóng. Async query checks lại readiness; callback cũ không làm cache mới sống lại. Cold/unavailable
PAPI trả blank, không giả 0 hoặc query DB ngay trong placeholder callback.

Sau opt-in, mỗi khoảng maintenance chạy tối đa một batch. Không drain backlog trong while-loop. Chỉ xóa history
projection cũ thỏa cả `terminal=1` **và** `state IN ('COMPLETED','ABORTED')` và cutoff thời gian. DELETE cũng kiểm tra
lại cùng predicate, không chỉ SELECT. Cờ terminal sai trên active/reconciliation không đủ để xóa row.

Không xóa journal, event, output, identity guard, player lock, statistics, pity hoặc completion tombstone. Counter
không giảm khi history hết retention. Source data nền vì vậy vẫn có thể tăng kích thước; đây không phải giải pháp
archival toàn bộ transaction journal. Chưa có automatic backfill.

## 8. Admin diagnostics và permission

Paper source bổ sung:

```text
/upgrader storage
/upgrader storage status
/upgrader storage recheck
/upgrader storage recovery [canonical-transaction-uuid-cursor]
```

Cần `ledatitemupgrader.use` + `ledatitemupgrader.admin.storage` (mặc định op, thuộc admin parent). Player và console
được hỗ trợ; sender khác báo message từ YAML. No-permission/help/invalid argument/fallback đều trong messages.yml.
Tab completion chỉ gợi ý status/recheck/recovery, không scan người chơi hoặc transaction list.

Status đọc memory. Recheck là yêu cầu chạy **mode đang cấu hình**, có global cooldown 10 giây: ở INITIALIZE nó có
thể thực hiện CREATE; không phải lệnh chỉ đọc tuyệt đối. Ở OFF nó không tự bật schema. Không có `initialize`/`repair`
subcommand để override config. Recovery là chỉ đọc, global single-flight và cooldown 2 giây; query tối đa 16 rows
(+1 để biết còn trang), deadline 10 giây, UUID-only requester. Không giữ Player hoặc CommandSender trong worker.
Player được resolve lại và kiểm tra permission khi trả kết quả; console dùng logger/serializer.

## 9. Recovery listing không phải recovery executor

Query đọc tx UUID/player/state/version/time và đối chiếu lock thành OWNED/MISSING/CONFLICT. UUID keyset cursor,
PreparedStatement, không dynamic sort/filter SQL từ client, không load payload/plan/sample/receipt token.

Không sửa outcome, gọi RNG, refund, phát output, xóa player lock hoặc chuyển trạng thái. Đúng owner/state trong list
không phải evidence đủ để replay effect. Corrupt payload không cản đọc summary; việc đó không xác thực payload.
Unknown enum/version/time trong summary làm query fail closed thay vì gán trạng thái mặc định.

List không phải snapshot toàn DB và không scan orphan lock không có attempt. UUID mới nằm trước cursor có thể chỉ
xuất hiện khi refresh từ đầu. Một trang rỗng không chứng minh server không còn vấn đề transaction. Đây là tool chẩn
đoán gọn; cần full reconciliation tooling riêng sau khi native effect receipts đã được chứng minh.

## 10. Build gates và cách test

`python3 scripts/native-preflight.py --api-jar /path/to/actual-api.jar` kiểm tra JDK21, Gradle executable và hai class
entry API công khai trong ZIP. Nó không tải dependency, tạo stub hoặc build plugin. Class presence/sha256 không
xác minh authenticity/API ABI. `paper:verifyPlatformApi` cũng kiểm tra public entry trước compile. Khi đủ artifacts,
chạy Gradle thật và staging, không coi preflight PASS là native compile PASS.

Core: 47 nhóm mới /147 assertions. Repository Java + SQLite qua transport test-only: 33 nhóm /183 assertions.
Bộ này dùng DatabaseMetaData proxy để chuyển các probe sang SQLite thực; **không** chạy xerial driver/Platform
pool/MySQL. `JDBC_TEST_CLASSPATH` trong `scripts/test-storage-sqlite.sh` cho phép người vận hành tự cung cấp driver
và dependencies để chạy nhánh DriverManager thật; nhánh đó chưa được chạy ở lần bàn giao này.

Bằng chứng đầy đủ và các regression ở `verification/BUILD_REPORT.md`. Không benchmark TPS/MSPT, GUI packet, click
exploit, server crash/native inventory/provider. Phase 9B tiếp theo cần actual Platform API/server JAR, full native
compile và integration; chưa chuyển sang Resource Pack/packet Phase 10 để che những gate còn thiếu.
