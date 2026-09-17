# Phase 8 — History, Pity và Admin diagnostics

Version: **0.8.0-phase08**. Baseline thật: `LeDatItemUpgrader-phase07-source.zip`.
Ngày kiểm chứng: 2026-09-17. Requirement đã được chốt; không đổi UI inventory sang display, không thêm dependency zMenu.

## 1. Phạm vi và trạng thái thực tế

Core History/Pity, quote có version, journal codec v3, SQL progression hook và read model đã compile/test.
Có source native menu history, command, async query bridge, PAPI statistics route và config validator.
**Chưa type-check/link Paper/LeDatPlatform hoặc chạy client thật. Không có plugin JAR đã xác minh.**
Không bootstrap live Transaction Engine, escrow, economy/ledger, mailbox hoặc delivery. `upgrades-enabled` và
`packet-renderer-enabled` vẫn false. `history.yml.enabled` mặc định false; `upgrades/pity.yml.enabled` phải false
và bật true bị loader từ chối cho đến khi native quote + atomic writer được nối/kiểm chứng.

History có thể bật read-side sau khi schema và writer tương ứng được chuẩn bị đúng; chỉ đổi toggle không tạo schema,
không backfill journal, không sinh attempt từ admin animation. Thiếu bảng/kết nối báo unavailable, không giả lịch sử rỗng.

## 2. Không đồng nhất kết quả roll với giao thưởng

`HistoryEntry` là projection gọn, không chứa ItemStack bytes/PDC, raw RNG sample hoặc receipt evidence.
Nó giữ transaction/player ID, source/target key và số lượng, value, chance, profile, failure, timestamps,
state/version và outcome `NOT_ROLLED`, `WIN`, `LOSS`.

| State | Outcome | Có tính completed statistics/pity? |
|---|---|---|
| PREPARED / RESERVING / DRAW_INTENT | NOT_ROLLED | Không |
| OUTCOME_COMMITTED / SETTLING | WIN hoặc LOSS | Không; settlement chưa hoàn tất |
| COMPLETED | WIN hoặc LOSS | Một lần, cùng SQL transaction với completion |
| ABORTED | NOT_ROLLED | Không; hủy trước roll không phải gacha loss |
| RECONCILIATION_REQUIRED | NOT_ROLLED hoặc kết quả đã lưu | Không; giữ unresolved evidence/player ownership |

COMPLETED là trạng thái engine sau những receipt đã được adapter xác nhận. Độ đúng của receipt native vẫn cần
integration test; GUI/history không được dùng làm chứng cứ delivery độc lập.
Thống kê hiển thị **từ khi progression writer được dùng**, không tự xưng là lifetime totals của journal cũ.

## 3. Atomic journal hook

`JournalCommitHook` chỉ được thực hiện SQL trên **cùng Connection/transaction** do `JdbcTransactionRepository` sở hữu.
Không được gọi provider, Bukkit, HTTP, commit/rollback riêng hoặc schedule một job cập nhật thống kê sau đó.

Claim flow:

```text
BEGIN
  attempt insert + durable per-player lock
  progression.claimed:
    ensure statistics row
    verify pinned pity version/failures/scope (nếu có)
    ensure pity row khi thực sự chưa tồn tại
    insert initial history projection
  journal event
COMMIT
```

Transition flow:

```text
BEGIN
  journal CAS(version + digests)
  verify active player ownership
  progression.transitioned:
    history CAS theo đúng predecessor
    nếu COMPLETED:
      verify completion tombstone
      statistics CAS
      pity CAS theo snapshot của quote (nếu có)
      insert completion tombstone
  journal event
  terminal player unlock
COMMIT
```

Bất kỳ lỗi giữa chừng đều rollback toàn bộ. Mất phản hồi sau COMMIT không đồng nghĩa rollback; caller cần đọc lại
journal trước khi quyết định tiếp tục. CAS retry không thắng thì không chạy hook lần hai.
Tombstone giữ plan/state digest và transaction ID, không tự xóa theo UI retention.

Constructor cũ của journal vẫn tồn tại để tương thích non-pity. `JournalCommitHook.NONE` **từ chối plan có pity**;
không âm thầm bỏ stamp. Muốn ghi history/stats/pity phải truyền `JdbcProgressRepository` hook vào journal.
`PlatformTransactionJournal` đã có overload nhận hook; `PlatformHistoryStore.commitHook()` cung cấp hook tương ứng.
**Bootstrap native hiện chưa tạo live writer hoặc gọi initialize.** Không ghi rằng gacha native đã hoạt động.

SQL atomicity ở đây là một database connection, không phải distributed ACID giữa SQL, Vault, PlayerPoints,
player inventory hoặc Platform Ledger. Những receipt/effect bên ngoài vẫn giữ contract Phase 4/5.

## 4. Pity có scope và điều khoản cố định

`PityPolicies` yêu cầu allow-list cụ thể cho paths, targets, profiles, source-value floor, increment và cap.
Không wildcard/global fallback; policy overlap bị từ chối. Epoch/rule đổi tạo scope mới, không reset bằng một
admin command thiếu audit. Counter/version có giới hạn; overflow từ chối thay vì quay lại 0.

Chỉ profile **DESTROY** đủ điều kiện trong Phase 8. KEEP/protection/DAMAGE/DOWNGRADE không kiếm hoặc sử dụng pity.
Đây là phạm vi an toàn hiện tại, không phải tất cả failure mode đều đã hỗ trợ pity.

Scope hash gắn với policy ID/epoch, path, target, profile, source key/amount/total, target total, base chance,
selected boosts/permission bonuses, fee plan và output/transfer spec. Source unique payload/UUID không nằm trong
scope để một logical item tương đương không tạo nhóm mới. Player UUID là phần khóa riêng của DB.
Đi đường rẻ hơn, đổi target hoặc đổi boost/fee/value không được dùng chung streak. Đây là rule chống chuyển
progress giữa điều khoản, không phải chứng minh mọi lỗ hổng kinh tế của server đã được giải quyết.

Công thức:

```text
bonusPoints = min(completedFailureStreak × incrementPoints, maximumPoints)
formula → profile → permissions → boosts → + bonusPoints → min/max clamp → ticket grid
```

Bonus là **điểm phần trăm**, không nhân chance; cap không ngụ ý bảo đảm thắng.
Fixture source 90 / target 900, multiplier 0.9, increment 1.25pp, cap 10pp:

| Streak | Bonus | Final chance |
|---:|---:|---:|
| 0 | 0pp | 9% |
| 1 | 1.25pp | 10.25% |
| 4 | 5pp | 14% |
| 8 hoặc hơn | 10pp | 19% |

Streak tăng khi **loss COMPLETED**, reset khi **win COMPLETED**; version tăng ở cả hai.
Không update từ animation, preview, close, failed reservation hoặc attempt cần reconciliation.
Không dùng PAPI/cache thống kê làm nguồn pity authoritative.

`PityQuoteService` gọi reader trên worker, phân biệt DB error với row vắng thực sự. Stamp pin ID/epoch/scope,
version/failures/increment/cap vào Terms. Revalidation thấy version đổi, kể cả bonus đã chạm cap và chance bằng
nhau, trả `RECONFIRM_REQUIRED`. Quote không đổi giữ ID/expiry cũ, không kéo dài vô hạn.
Claim hook kiểm tra lại snapshot sau khi giữ durable player lock; prefetch pity không tự authorize debit.
Native Phase 6 quote hiện vẫn dùng pipeline cũ, vì vậy toggle pity live bị khóa thay vì hiển thị một bonus chưa được chốt.

## 5. Codec và migration

Journal vẫn dùng bounded binary encoding, không Java ObjectInputStream. Non-output legacy ghi v1, output non-pity ghi v2,
chỉ plan có stamp ghi v3. State envelope vẫn v1 và gắn plan digest. Decoder đọc được cả ba.
Exact v1 fixtures từ Phase 4 được giữ. Thêm golden hashes của **4 v2 plans và 126 record/state pairs**, được xuất bằng
cách compile core từ ZIP Phase 7 thật với `LegacyOutputContract`, rồi so sánh bản hiện tại.

`ProgressSql` có schema component `progress-v1`, tách khỏi `transactions-v1`. Tables:

| Table suffix | Vai trò |
|---|---|
| history | Projection hiển thị; owner/time và terminal/time indexes |
| statistics | Completed/wins/losses theo UUID |
| pity | Primary key UUID + scope, version/failures |
| completions | Durable idempotency tombstone theo transaction ID |
| progress_schema | Component/schema version |

Tên bảng qua validated Platform prefix; tham số query dùng PreparedStatement. DDL có SQLite và MySQL/InnoDB,
nhưng **MySQL và JDBC driver production chưa được chạy**. Init chỉ một owner, không nested transaction.
Không tự đổi storage mode, file path hoặc schema của các phase cũ.

`backfillHistoryOnly` chỉ insert projection còn thiếu, không ghi đè live projection mới hơn, conflict digest bị từ chối.
Không dùng nó để tái tạo ordered pity hoặc lifetime statistics từ một tập journal không chứng minh là đầy đủ.
Live attempt legacy có thể nhận projection ở transition kiểm chứng tiếp theo; completion từ đó được tính theo writer mới.

## 6. History query, cache và retention

Query bắt buộc subject UUID, filter `ALL/WIN/LOSS/UNFINISHED`, upper-created-time và keyset cursor
`created_at DESC, tx_id DESC` theo UUID TEXT. Không OFFSET scan; limit 1..45, fetch limit+1 để biết next page.
Cursor bind subject/filter/window và được giữ server-side, không gửi cursor điều khiển SQL từ client.
Page validator từ chối foreign owner, wrong filter/window, duplicate, unordered rows và partial page có hasMore sai.

Upper window giúp hạn chế attempt mới đẩy trang, **không phải MVCC snapshot xuyên nhiều lần query**. Attempt còn chạy
có thể đổi state/outcome và backfill có thể thêm bản ghi cũ; REFRESH là một cửa sổ đọc mới.

`StatisticsCache` bounded TTL/LRU, một load/player, incarnation ticket chống callback cũ ghi đè sau quit/reload.
Từ chối counter lùi hoặc cùng total nhưng wins/losses mâu thuẫn. Placeholder cold/expired trả chuỗi rỗng, không giả 0.
Cache hiện được nạp bởi `/upgrader statistics`; không có background refresh/join warm hoặc SQL trong PAPI callback.

`pruneHistory` là **API maintenance tường minh**, batch tối đa 1000, chỉ terminal history older-than-cutoff.
Không purge unfinished/reconciliation, journal, output, pity, statistics hoặc completion tombstone.
Retention config 90 ngày / batch 200 đã có, nhưng **chưa có scheduled purge job native**.
Không được suy ra data toàn plugin đã có lifetime bound chỉ từ retention của bảng UI.

## 7. Native history GUI/commands — source chưa staging

```text
/upgrader history [all|win|loss|unfinished]
/upgrader history <full-player-UUID> [filter]   # admin.history
/upgrader statistics
/upgrader diagnose <full-transaction-UUID>    # admin.diagnostics, chỉ đọc
/upgrader pity [64-char-scope-hash]           # admin.diagnostics, đọc scope của chính caller
```

Root cần `ledatitemupgrader.use`; history/statistics thêm `ledatitemupgrader.history`.
Hai quyền admin mặc định op. Command mới hiện player-only; không tự làm name/offline lookup sync.
UUID phải đủ 36 ký tự, scope 64 lowercase hex; cooldown/query capacity chặn spam. Có help/tab completion cho filter.
Không có reset-pity/refund/reward/unlock/force-result command. Chẩn đoán không expose raw sample, item bytes,
receipt token hoặc thông tin kết nối. Warning giữ exception type/SQLState/vendor code/source frames, không in raw
JDBC message có thể chứa connection string/credentials; throttled 30s.

History GUI 54 slots, 28 entry cells mặc định, Previous/Refresh/Next/Close. Khung matrix/name/lore/models/sounds
nằm trong YAML. Holder/session riêng; không check title. Listener cancel trước, chỉ left-click top navigation với
cursor rỗng; drag/bottom/creative/shift/number/offhand/collect/drop không chạy action. Open/close từ click hoãn next tick.
Session có viewer/subject/revision/incarnation/request ID/TTL; một request pending/session. Callback cũ không đóng hoặc
render nhầm phiên mới. Page chỉ commit vào trail sau query thành công. Controller re-check permission trước render.

Icons được tạo mới từ config, không deserialize source/target item. MiniMessage chỉ parse template admin; data
placeholder được truyền unparsed. ISO timestamps hiện UTC. Renderer so sánh slot trước update; chưa benchmark frame/MSPT.
Close/quit/reload chỉ dọn view/cache, không refund/drop vì history không sở hữu tài sản.

Default limits: 64 sessions, 16 queries, 64 pages/session, cache 2000 / TTL30s, request10s, session300s.
Read timeout của UI không giải phóng permit DB trước khi future thật hoàn tất. SQL/DB ở Platform query worker;
inventory/message/sound trở về player scheduler. Không claim Folia vì native lifecycle vẫn theo Paper owner model.

PAPI routes source:

```text
%ledat_itemupgrader_completed%
%ledat_itemupgrader_wins%
%ledat_itemupgrader_losses%
%ledat_itemupgrader_win_rate%
```

Không có global pity placeholder không rõ scope. Route unregister lúc disable. PAPI/Platform thật chưa được test.

## 8. Config rollout

File mới `history.yml`, `upgrades/pity.yml`, `menus/history.yml` có comment. Bundled menu/version được ensure khi thiếu,
không ghi đè file cũ. messages.yml thêm key nhưng giữ version1/fallback build-generated. History/Pity validation chạy
cùng config snapshot; lỗi giữ runtime cũ. Không đổi values/chance/profile/boost cũ để tự bật pity.

Để mở live trong phase kế tiếp phải: build thật với Paper/API; chốt cùng storage ownership; initialize progress schema
async; inject hook vào live journal; route quote worker qua authoritative PityQuoteService; pin/reconfirm version;
kiểm chứng escrow/ledger/economy/delivery; cập nhật UI preview; rồi mới tháo native pity gate có test.
Không chỉ xóa một dòng validation hoặc đổi YAML true.

## 9. Kiểm thử và còn thiếu

**907 core groups / 61.260 assertions / 0 lỗi**; Phase 8 mới98/1.554. Thêm **31 tests /535 assertions** chạy actual
Java repositories trên SQLite thực qua bridge kiểm thử Python/JDBC-interface. Đây không phải xerial JDBC driver;
query-timeout/driver/pool behavior không được xác minh bởi bridge. Không ship test bridge trong plugin.
Hai child process bị halt trước/sau terminal COMMIT kiểm chứng rollback/commit của history/stats/pity/receipt/unlock
và retry không đếm lại; không mô phỏng kill Minecraft hoặc power-loss disk controller.

Regression SQL cũ15 journal +14 output; v1/v2 golden hashes; resource1429/21YAML; syntax206Java/0errors.
Core compile dùng JDK21 với -Xlint:all -Werror. Không cộng archive retest, parameter loops hoặc SQLite tests
vào con số core để tăng số lượng. Test/fixture không dùng item/provider/economy thật.

Cần native compile/client GUI tests, JDBC driver/MySQL/Platform connection ownership, config/Adventure/PAPI, live
writer boot, receipt crash integration, proper bounded recovery/backfill/maintenance orchestration và load test thật.
Checklist: `TEST_CHECKLIST.md`; handoff: `CONTINUE.md`; evidence: `../verification/BUILD_REPORT.md`.
