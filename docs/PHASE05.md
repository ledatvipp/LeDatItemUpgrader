# Phase 5 — Prepared Output, Metadata Transfer và Failure

Version `0.5.0-phase05`, tiếp nối source Phase 0–4. Tài liệu này mô tả code/contract đã có, không khẳng định plugin live đã chạy. Code Paper là source chưa compile/link với API Platform thật.

## 1. Phạm vi và thứ tự xử lý

`AttemptPlan.target` vẫn là eligibility template, không phải item được phép phát. Quote mới chứa `OutputSpec`: toàn bộ transfer policy và failure parameters, không chỉ policy ID. Thay tham số cùng ID làm thay plan digest và điều khoản quote.

```text
Attempt claim + player lock (journal)
  → LEDGER_CLAIM intent/receipt
  → PREPARE_OUTPUT intent
      → OutputStore.claim(PREPARING) commit
      → inspect pinned source và target template
      → fresh-create success target qua provider đã attested
      → verify template/key/quantity/new identity
      → apply selective transfer trên target copy
      → kiểm tra total value đúng quote
      → chuẩn bị failure candidate
      → READY + fresh identity registry commit cùng transaction
  → PREPARE_OUTPUT receipt có output digest
  → HOLD_SOURCE / HOLD_FEE_ITEM / DEBIT_CURRENCY
  → DRAW_INTENT / OUTCOME_COMMITTED
  → SETTLING sử dụng đúng prepared candidate
  → COMPLETED
```

Không lấy result từ animation, không gọi provider.create lại khi resume hoặc delivery retry. Candidate thắng/thua đều chuẩn bị trước acquisition nên không gặp transfer/downgrade validation chỉ sau khi đã mất tiền. Chi phí là tạo hai object trong trường hợp downgrade, không cấp hai reward.

## 2. Native boundary và khả năng của adapter

`ItemMutationPort` chỉ cung cấp `inspect`, `createFresh`, `apply`; trả `CompletionStage<ItemDocument>`. Caller quản lý executor; adapter phải route Bukkit về owner thread. Không gọi Bukkit từ worker, không giữ Player qua task dài. Pure facts/bytes được kiểm tra ở core.

`MutationCapabilities` do adapter đáng tin cậy xác nhận: provider, safe fresh creation, stack bound, unique identity requirement, enchant applicability/conflicts/caps, writable typed PDC, protected keys. YAML không được tự tuyên bố một provider an toàn. `pureFreshCreation` nghĩa không tự reserve/grant/modify registry bên ngoài có side effect; provider không đáp ứng phải từ chối trước transaction live.

`MetadataView.templateDigest` là canonical template fingerprint bỏ riêng các per-instance IDs được adapter biết; không bỏ damage/rarity/stat tùy tiện để qua giá. `protectedDigest` bảo vệ mọi field không thuộc patch. Unique tokens là hash có namespace provider; không ghi UUID/token nhạy cảm raw vào SQL registry.

`PaperVanillaOutputItems` có source helper chạy Paper owner thread: deserialize/capture fresh ItemStack, name literal Component, damage/repair/enchant hợp lệ, clone copy và verify. Identity verifier bắt buộc dùng bridge thực, không chỉ Material. Helper hiện từ chối custom PDC/model/lore/container risk theo snapshot factory, không mutation stored enchant books. **Chưa có scheduler adapter `ItemMutationPort` live, chưa test actual NBT hoặc custom plugin API. Chưa claim Folia.**

## 3. Transfer semantics

`CLEAN` không copy source; giữ target vừa tạo (bao gồm identity hợp lệ của target). Không đồng nghĩa xóa tất cả meta.

Selective policy có:

| Thuộc tính | Semantics |
|---|---|
| `custom-name` | Copy plain/literal text, tối đa256 ký tự, không control characters; không parse MiniMessage, chưa preserve Component style |
| `repair-cost` | Copy repair cost đã bounded; cần native target capability |
| `enchantments` | Exact key allow-list và maximum-level; merge `max(target, source)`, không cộng level; incompatible/conflict/quá cap từ chối, không âm thầm clamp |
| `durability` | `TARGET_DEFAULT` hoặc `DAMAGE_RATIO_CEIL`: ceil(sourceDamage × targetMax/sourceMax); không làm target broken |
| `pdc` | Exact key + STRING/INTEGER/LONG/BYTE_ARRAY; <=4096 bytes/value, không nested PDC hoặc wildcard; target adapter phải attest writable/type |

Source không bị mutate. Chỉ apply patch lên fresh target hoặc detached failure copy. Core kiểm tra key/quantity/facts, expected fields, untouched PDC, opaque protected digest, capability/unique tokens không bị sửa ngoài phép. Namespace identity phổ biến bị chặn; namespace lạ vẫn cần adapter bảo vệ. **Không có COPY_ALL_PDC hoặc stat/socket copier generic.**

Nếu thêm enchant/name/metadata khiến giá khác quote: trả `RECONFIRM_REQUIRED` trước HOLD_SOURCE. Hiện chưa có offer-requote UI tự giữ/hiển thị offer đã materialize để người chơi xác nhận lại; không chỉ retry mãi một quote cũ. Provider random stat cần contract preview/build và định giá thống nhất mới mở live.

## 4. Failure semantics

`DESTROY`: failure candidate rỗng; settlement kết thúc giữ source theo effect script, không trả lại.

`KEEP`: candidate đúng source snapshot, quantity và bytes; không create lại source. Thực thi trả source qua escrow return contract, không grant thêm candidate song song. Protection chọn được thay thế **toàn bộ** policy DAMAGE/DOWNGRADE bằng KEEP.

`DAMAGE`: số damage cộng thêm = ceil(maxDurability × basisPoints /10000). Chỉ item damageable/breakable/không stack. Basis points1..10000. Với `max=250,currentDamage=40,bps=2000`, sau thua là90, còn160 durability. Không phải lấy20% remaining durability.

Khi chạm ngưỡng hỏng: `DESTROY` không có item trả; `CLAMP_ONE` đặt damage=max-1. Nếu source đã ở max-1 và không thể hư thêm, từ chối attempt thay vì cho lặp roll không mất mát.

`DOWNGRADE`: cấu hình key khác source và quantity cụ thể, fresh-create qua provider, apply downgrade transfer policy, kiểm tra tổng giá sau transfer **thấp hơn sourceTotal**. Unknown value, cùng key, >=value hoặc reused unique token đều bị từ chối. Không đổi Material trên source rồi giữ PDC cũ. Chưa tự hiểu MMO tier/lore, chưa hỗ trợ generic `LOSE_PERCENT_VALUE` hoặc arbitrary action/script.

## 5. Output persistence và idempotency

`OutputStore`:

```text
PREPARING → READY | REJECTED | AMBIGUOUS
```

Claim phải commit trước factory. READY + payload checksum + fresh identity token claims phải commit cùng SQL transaction. Collision identity roll back READY, giữ pending để reconciliation. Không upsert/replace READY, không expire PREPARING như lease để tạo lại.

`OutputCodec` là bounded binary format, <=3MiB toàn record; ItemSnapshot giới hạn riêng. SHA-256 checksum phát hiện corrupt payload, không phải chống admin/DB chủ động sửa. `JdbcOutputRepository` PreparedStatement/query timeout, không giữ Connection; caller async. Không đặt autocommit=true sau rollback thất bại. Commit acknowledgement mơ hồ được propagate, engine không tự dùng như APPLIED.

Identity registry chỉ chứng minh token chưa bị output store này dùng lại. Không thể tự phát hiện mọi duplicate vốn có trong player inventory hoặc plugin khác. Cả candidate không trúng cũng không tái cấp token trong cùng registry; việc thu gọn cần policy bảo toàn tombstone, không xóa theo history TTL.

`OutputPreparationService` isolate cancellation của caller; callback từ factory vẫn persist record. Exception không chứng minh no-side-effect → AMBIGUOUS; error sink nhận cause. Missing/invalid row binding và CAS conflict không được tạo lại. `PREPARING` sau crash phải review, không gọi factory trong recovery.

## 6. Delivery boundary — chưa phải mailbox thật

`OutputBoundEffectPort` là decorator bắt buộc khi nối live engine: các hold/debit phải có READY + PREPARE_OUTPUT receipt khớp output digest. `DELIVER_TARGET` không forward raw catalog snapshot cho native port. Đúng outcome đã commit mới chuyển item snapshot đã lưu tới `PreparedDeliveryPort`.

`PreparedDeliveryPort.Delivery` gắn player, attempt, plan digest, operation key, output digest, role và item. Adapter tương lai cần durable entitlement/mailbox và inventory delivery evidence; hiện chỉ interface. SQL output READY **không phải quyền nhận reward**. Không dùng output candidate để `/claim` trước outcome, không cấp cả hai branches, không drop item quý lúc inventory full.

Lost ack sau output COMMIT có thể đọc lại READY, nhưng journal effect UNKNOWN vẫn phải theo reconciliation contract; không forcecomplete receipt. Delivery UNKNOWN không redeliver/roll lại.

## 7. Compatibility với Phase 4

`JournalCodec` đọc plan/record v1 và v2. Plans không OutputSpec tiếp tục encode đúng v1 để giữ bytes/digest; plans mới encode v2 có full policies. State envelope v1 tiếp tục bind digest đúng plan. Fixture v1 được xuất từ code Phase 4 trước khi sửa, giữ tại `verification/phase04-fixtures/`.

Không tự rewrite journal cũ thành v2, không claim legacy catalog template là reward hợp lệ. OutputBoundEffectPort từ chối acquire/deliver template của legacy plan và yêu cầu review. Source exact refund của acknowledged escrow vẫn thuộc native compensation contract.

Schema output là component mới, không đổi storage mode/migrate transaction tables cũ. `PlatformOutputStore` chỉ source bridge, chưa bootstrap/migrate live. DDL MySQL/InnoDB có source nhưng chưa chạy MySQL; không claim DDL atomic ở MySQL.

## 8. Config và messages

`paper/src/main/resources/upgrades/outputs.yml`: default clean, two transfer examples, four failure policy examples, mappings theo profile/path. Chỉ khi mapping được bật và profile failure cùng mode thì mới chọn DAMAGE/DOWNGRADE. Unknown profile/path/policy/enchant/PDCtype hoặc mismatched mode từ chối candidate reload. Runtime cũ giữ nguyên.

Ví dụ tích hợp damage: thêm một profile đầy đủ trong `profiles.yml` có failure DAMAGE, rồi thêm mapping trong outputs.yml từ profile đó tới `damage-20`. Không chỉ bật profile mode khi thiếu policy. Kiểm tra tổng phí và protection free-loop guard ở Phase3.

Tên/lore GUI không hardcode trong Java. Các preview text mới ở messages.yml có fallback: quote-output-policy, quote-failure-damage/downgrade, quote-damage-detail, quote-downgrade-detail. Chưa thêm transaction command, storage/feature flags giữ nguyên false.

## 9. Kiểm thử đã chạy và giới hạn

Phase 5:105 nhóm / 5.318 assertion/0 lỗi, gồm10 nhóm mock JDBC output. Toàn core 641 / 51.929 / 0. 14 SQL output tests chạy SQLite Python thực, cộng15 journal SQL regression. SQL tests dùng câu lệnh và payload export từ codeJava, không viết một schema khác để pass. Hai child process bị os._exit trước/sau READY COMMIT; PREPARING còn tombstone hoặc READY giữ exact payload/identity; Java decode lại snapshot damage90.

Fixture item bytes có prefix SYNTHETIC-ITEM, không phải NBT. Test fake provider unique identity không chứng minh MMOItems API. Syntax-only146 file và PyYAML542 checks / 13 YAML không thay Paper compile/runtime loader. Chưa benchmark MSPT/TPS, chưa test actual SQLite JDBC/MySQL/Platform shutdown/native inventory economy.

## 10. Acceptance bắt buộc trước live

Build Paper + Platform API thật; actual provider identity/canonicalization và fresh creation fixture; owner-thread round-trip/tamper tests; nguồn/fee escrow có recovery evidence; ledger/economy adapter; durable delivery/entitlement; config reload/policy drift; crash từng cửa sổ side effect; UI exploit/quit/death/rejoin/disable; bounded queues/backpressure/metrics. Không mở feature chỉ vì pure-core simulation pass.
