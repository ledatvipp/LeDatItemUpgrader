# Phase 4 — Transaction contract

Source baseline Phase 3; release `0.4.0-phase04`. Tài liệu phân biệt core đã chạy test, SQL đã thực thi qua SQLite, source bridge và native integration chưa có.

## 1. Phạm vi và readiness

Đã có `AttemptPlanner`, `AttemptPlan`, `EffectScript`, `TransactionMachine`, `UpgradeTransactionEngine`, `AttemptRunGate`, `RecoveryPlanner`, `JournalCodec`, `AsyncTransactionJournal`, `JdbcTransactionRepository`, `JournalSql`, source `PlatformTransactionJournal` và simulation/test harness.

Chưa có `EffectPort` production: source/fee native escrow, economy debit/refund adapter, ledger claim/ack adapter và pending-delivery. Chưa có GUI input/click listener/cinematic. Source SQL bridge không tự chạy initialize/migration khi bootstrap. Hai feature flags vẫn false; không thay database mode.

Không dùng `SimulationJournal` để lưu dữ liệu live. Đây là fixture in-memory cho CLI/test, encode/decode ở mỗi lần đọc/ghi, không có durability. Gradle loại package `demo/**` khỏi JAR production.

## 2. Ranh giới trust

API của core chỉ nhận dữ liệu từ server-owned services, không expose attempt/quote constructor làm endpoint để client gửi dữ liệu. Plugin khác có quyền code trong cùng JVM không được coi là đối tượng cô lập an ninh.

`AttemptPlanner.prepare` bắt buộc một kết quả revalidation `VALID_PREVIEW`; `RECONFIRM_REQUIRED`, thiếu resources, stale/expired/access denied đều bị từ chối. Constructor plan còn kiểm tra bounds, known identity facts, risks, prices, exact fee allocation, không lặp physical slot và không dùng source slot trả phí.

Plan giữ full serialized snapshots. Fingerprint/digest phát hiện dữ liệu khác trong protocol; không phải global anti-dupe registry, chữ ký chống admin DB edit, hay bảo đảm bytes canonical xuyên bản Paper/provider.

### Pinned plan

Player/session/quote UUID, config revision/catalog generation, source slot/full snapshot, target ID/template snapshot, giá trị tổng, `UpgradeQuote.Terms`, fee allocations và expiry. Boost/bonus/profile IDs kiểm tra định dạng; quantities/prices có bounds. Không đọc live config để đổi fee/chance/outcome của attempt đã claim.

Key `iup:{playerUuid}:{quoteUuid}`. Attempt UUID được derive ổn định từ key; UUID derive này không phải secret/security token. Cùng key và cùng digest là duplicate; cùng key đổi digest là conflict. Quote mới nhưng player còn ownership lock là busy. Tombstone của attempt terminal không tự bị xóa, nếu không replay quote cũ có thể mất idempotency.

### Target template khác output

Plan hiện giữ template target đã pass eligibility. Không phát nguyên item template cache lặp lại: provider có thể gắn unique IDs/random stats. Phase 5 phải materialize output theo attempt một lần, kiểm chứng item/value/identity/transfer, lưu output bền vững rồi dùng lại cho mọi delivery retry.

Nếu giá/chance cần thay do materialization, phải có xác nhận lại trước acquisition; không mutate odds sau roll. Nếu không giữ được guarantee này cho một provider, tắt capability/path đó.

## 3. Native ownership contract

`EffectPort.execute` được gọi từ worker, adapter phải route player/inventory tới owner thread. Trước `HOLD_SOURCE`, kiểm tra player online, session, expiry, actual source, path/target/access/conditions và khả năng trả phí; giành ownership trong owner critical section. Snapshot lúc preview không thay thế validation này.

Fee item hold chỉ thao tác đúng storage slot, actual stack fingerprint/meta và quantity đã allocate. Không dùng display name/Material làm identity, không dùng cursor/offhand/armor, không dùng phần dư source để trả phí ở model này. Một source/stack thay đổi trong quãng chờ SQL phải bị từ chối, không âm thầm lấy item thay thế.

`APPLIED` không phải "đã schedule task". Callback retired/offline trước mutation có thể `NOT_APPLIED`; xảy ra mutation một phần hoặc không xác minh được thì `UNKNOWN`. Acknowledgement cần đủ guarantee để có thể tin sau restart. Sự thành công của setter Bukkit trong RAM không tự chứng minh playerdata được lưu.

Hệ thống inventory/DB/provider khác nhau không có chung atomic commit. Cần protocol escrow/delivery thực tế, không chỉ thêm PDC marker rồi tuyên bố exactly-once. Không force `saveData()` mỗi click trên main thread để che lỗ hổng bằng synchronous I/O.

## 4. State reducer và side effects

```text
PREPARED
 → RESERVING
 → DRAW_INTENT
 → OUTCOME_COMMITTED
 → SETTLING
 → COMPLETED

RESERVING + definite failure → COMPENSATING → ABORTED
Bất kỳ effect mơ hồ/lỗi settlement → RECONCILIATION_REQUIRED
```

`PREPARED` hết hạn trước start abort không effect. Expiry/access còn phải được native adapter kiểm tra lại khi thực sự lấy source; không chặn completion của attempt đã sở hữu source chỉ vì animation lâu/reload.

Một external operation có stable ordinal, kind, reference, amount, `operationKey`, plan digest. Journal lưu:

```text
INTENT persisted
 → adapter execute
 → APPLIED / NOT_APPLIED / UNKNOWN persisted
 → next action
```

Chỉ code thắng CAS tạo INTENT mới được execute. Khi task bị stop sau CAS nhưng trước dispatch, driver ghi `NOT_APPLIED: stopped-before-dispatch`; không để cleanup hủy nhầm một attempt khác. Khi đã dispatch, phải xử lý receipt của chính future đó.

`TransactionMachine.validate` kiểm tra actual effect script/prefix chứ không chỉ enum state. `JournalCodec.checkTransition` tái dựng một reducer step, đối chiếu plan/version/created time và toàn bộ record. Không được nhảy PREPARED → COMPLETED, chèn delivery vào reservation, sửa sample đã có hoặc thay plan bằng version hiện tại.

Receipt `UNKNOWN` gồm exception, invalid reply, timeout không có bằng chứng, partial mutation và duplicate ledger không biết có phải cùng operation/plan hay không. Không tự đổi một unknown thành NOT_APPLIED bằng việc so balance trước/sau; plugin khác có thể đồng thời thay balance.

## 5. Effect scripts

Reservation: ledger claim → hold source → fee item allocations theo slot → debit từng currency theo canonical resource order.

Thành công: trả phần fee item/currency giữ dư → durable delivery output → ledger success. Thất bại KEEP: trả phần dư + nguồn → ledger success. Thất bại DESTROY: trả phần dư, không trả nguồn/target → ledger success.

Held item payload chưa được deliver được coi là escrow/consumed theo settlement đã pin; xử lý native escrow finalization thuộc adapter, không được để item vẫn dùng được trong inventory sau khi journal COMPLETED.

Với mỗi resource `reserve=a+max(s,f)`, `consume(win)=a+s`, `consume(loss)=a+f`. Fee item consumption đi qua allocation theo slot để tính lượng trả từng hold; snapshot giữ dữ liệu của whole stack trước hold, `amount` mới là phần đã lấy. Native adapter không được trả lại cả whole-stack snapshot khi chỉ hold một phần.

Compensation chỉ đảo những reservation đã APPLIED trước một NOT_APPLIED xác thực; thứ tự ngược để hoàn source sau fee. Nếu compensation fail/unknown, giữ lock và chuyển review. Không auto-refund toàn bộ source sau khi target đã được deliver hoặc ledger terminalization lỗi.

`LEDGER_SUCCESS` đánh dấu transaction settled, không đánh dấu gacha win. `LEDGER_FAIL` dùng cho attempt abort sau compensation, không dùng cho mọi roll thua.

## 6. RNG commit boundary

`SecureTicketSource` dùng `SecureRandom.nextInt(1_000_000_000)`. Không time/client seed, modulo bias mapping, floating RNG hoặc `Random` gameplay. Source injectable ở tests để kiểm thử 0, threshold-1, threshold, denominator-1.

DRAW_INTENT CAS thành công → draw trên worker → persist OUTCOME_COMMITTED → chỉ sau đó mới settlement/reveal. Failure của persistence phải dừng; không gọi native output khi chưa có durable outcome.

Sau crash:

| Durable row | Recovery |
|---|---|
| PREPARED/acknowledged reservation prefix | Có thể resume theo original plan, vẫn native recheck khi acquisition |
| Effect INTENT chưa receipt | Xác minh external effect; không gọi lại mù quáng |
| DRAW_INTENT chưa outcome | Review draw/input; không auto draw thêm |
| OUTCOME_COMMITTED | Dùng đúng sample/terms cũ; không đọc công thức config mới |
| SETTLING prefix APPLIED | Chỉ phần script chưa tạo INTENT mới có thể tiếp tục |
| RECONCILIATION_REQUIRED | Manual/trusted-adapter reconciliation |
| COMPLETED / ABORTED | Không tác vụ nữa, vẫn giữ idempotency record |

Các action resume chỉ hợp lệ sau khi executor/process cũ đã quiescent. Không dùng timeout/lease hết hạn để steal lock khi async callback trước đó vẫn có thể debit/give. V1 không claim distributed multi-server ownership fencing.

Chưa có API admin tự sửa sample, force complete, delete lock hoặc auto reconcile unknown. Bước sau phải thêm bằng chứng operation-specific, actor/audit, expected-version CAS và fencing rồi mới cho mutate reconciliation.

## 7. Persistence

`JournalSql.Tables` nhận tên từ adapter và validate identifier. Test SQL dùng prefix `iup_`; Platform thực tế truyền `platform.tableName(...)`. File `docs/sql/*` là bản export minh họa, không khuyến khích chạy thẳng vào DB live.

Tables mới:

- attempts: PK tx_id, unique idempotency_key, player/config digest/state/version/sample, immutable plan payload, small mutable state envelope.
- player_locks: unique player + unique attempt, không có expiry tự giải phóng.
- tx_events: PK (tx_id, version), state/time/state-envelope digest. Không duplicate item blob trong mỗi event.
- tx_schema: component `transactions-v1`, schema version 1; tách khỏi schema_meta cũ.

Claim transaction insert attempt + lock + event. Competing attempts cùng player rollback candidate; không bỏ lại row mới không có lock. CAS conditional theo tx_id/version/old payload digest/plan digest, kiểm tra owner, insert event rồi unlock nếu terminal trong cùng commit.

Plan BLOB chỉ ghi ở claim. CAS chỉ rewrite state envelope <=64 KiB. Tổng raw item snapshots <=4 MiB, encoded payload hard bound 8 MiB. Loader đọc binary stream có giới hạn, kiểm tra checksum, decode không dùng Java ObjectInputStream, so các column index với dữ liệu giải mã. Payload hỏng thì fail-closed, không xóa/hoàn tiền tự động.

Keyset scan `tx_id > cursor ORDER BY tx_id LIMIT ?`, page 1..8, default caller nên chọn 4; so UUID dạng TEXT, không dùng Java UUID.compareTo. Không có query unbounded/OFFSET trong recovery. Mỗi page vẫn có payload lớn; tránh chạy hàng loạt không backpressure.

Connections do Platform quản lý, chỉ dùng trong callback. Repository không đóng/giữ connection; yêu cầu auto-commit lúc vào mutation, không nest transaction provider. DDL idempotent nhưng không claim transactional DDL ở MySQL. MySQL phải InnoDB; migrations cần một initializer, không chạy song song giữa server.

Rollback lỗi: không setAutoCommit(true), vì có thể commit pending work. Provider/pool phải loại connection hỏng; cần integration test điều này. Commit/ack timeout không phải bằng chứng SQL không commit; đọc lại sau recovery, không chạy effect tiếp dựa vào phỏng đoán.

WAL/FULL và busy timeout trong test do test connection cấu hình. Plugin không tự override SQLite durability mode của Platform. Runtime phải verify provider durability/journal settings trước live release; `synchronous=NORMAL` có trade-off power-loss khác và không được âm thầm quảng cáo như FULL.

## 8. Async/lifecycle

`AsyncTransactionJournal` cho bridge compose thẳng Platform futures. `TransactionJournal` đồng bộ chỉ dùng khi đã có dedicated storage worker; `offload` không tự tạo pool. Không `.join()` Platform DB future trong chính DB executor.

Engine nhận bounded worker, Clock, TicketSource, EffectPort, AsyncJournal và in-flight cap 1..256. Test/demo dùng 16; chưa có runtime engine bootstrap lấy setting trong YAML. Không expose config giả như thể live transaction đã được bật.

`AttemptRunGate` theo player, token identity và total capacity. Stale release không xóa ticket mới. Caller cancel future chỉ ảnh hưởng view, không cancel native effect hoặc giải phóng durable lock. Stop không cấp việc mới; receipt in-flight vẫn cần persist, sau đó drain future mới hoàn tất. Tương lai disable có bounded wait ở lifecycle, nhưng không được release/compensate một future chưa biết kết quả chỉ vì hết timeout.

Hạn chế performance còn lại: digest/encode validate vẫn có CPU cost theo plan, chưa có cache seal hoặc benchmark đa người chơi. SQL tránh rewrite full plan nhưng không coi đó là bằng chứng TPS. Không scan all players/entities/tick. History/pity write-behind chưa nối; critical state tuyệt đối không đưa vào write-behind cache thay cho journal commit.

## 9. Test evidence

105 nhóm Java / 9.341 assertion mới, gồm 13 mock JDBC call-contract. Regression toàn core 536 nhóm / 46.611 assertion. Test overflow/expiry/allocation/source exclusion, codec/checksum/state binding, path của reducer, success/KEEP/DESTROY, definite/unknown rejection, từng persisted boundary, callback exception, outcome/terminal commit ack lost, compensation restart, future cancellation, stop giữa intent/dispatch, rejected executor, concurrent duplicate submit/two engines và CAS.

SQLite: 15 test qua Python sqlite3 3.46.1, exact Java-exported SQL + real Java codec fixture. Có two-connection claim, rollback atomic group, version/digest CAS, immutable plan payload round trip, close/reopen, child `os._exit` trước/sau outcome COMMIT. Java inspect/replay từ payload DB giữ sample cũ hoặc chặn redraw tại DRAW_INTENT.

Không phải JDBC driver test, MySQL test, Paper server crash, power-failure, native playerdata durability, no-dupe proof xuyên plugin, PacketEvents/UI test hoặc load benchmark.

## 10. Primary references

JDK 21 SecureRandom contract: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/SecureRandom.html

SQLite transaction semantics: https://www.sqlite.org/lang_transaction.html

SQLite atomic commit scope/caveats: https://www.sqlite.org/atomiccommit.html

Paper scheduler/thread guidance: https://docs.papermc.io/paper/dev/scheduler/

LeDatPlatform source basis: `LEDATPLATFORM_API.md` do user cung cấp trong hội thoại; chưa có API artifact thực tế để xác minh full link. Các yêu cầu journal/escrow/recovery ở trên là thiết kế của project, không suy diễn thành guarantee do Platform sẵn có cung cấp.
