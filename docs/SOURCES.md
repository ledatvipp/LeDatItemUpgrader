# Nguồn và phạm vi đối chiếu

## Baseline do hội thoại cung cấp

`LeDatItemUpgrader-phase04-source.zip` là baseline source thật của Phase 5. `LeDatItemUpgrader-phase03-source.zip` là baseline source thật của Phase 4. Phase 3 trước đó tiếp nối `LeDatItemUpgrader-phase02-source.zip`. Requirement đã chốt trong hội thoại cùng `FULL_Instructions.md` và `LEDATPLATFORM_API.md`. API guide tự ghi2.10.0, chưa có toàn bộ public signatures/artifact/playbook; không phải binary compatibility evidence.

## Đối chiếu online — primary sources (2026-09-16)

Paper Scheduling: `https://docs.papermc.io/paper/dev/scheduler/`. Dùng để xác nhận boundary Bukkit owner-thread/async; không thay Platform scheduler chỉ từ example Paper và không suy Folia compatibility.

PlaceholderAPI Using PlaceholderAPI: `https://wiki.placeholderapi.com/developers/using-placeholderapi/`. Dùng String entrypoint `PlaceholderAPI.setPlaceholders(player, text)`, compileOnly/softdepend và xử lý placeholder chưa resolve. Pinned2.11.6 là compatibility baseline của source, không phải claim latest. Không dùng Component entrypoints yêu cầu bản mới hơn. Source xử lý unresolved/missing theo policy bảo thủ của plugin.

Java 21 BigDecimal: `https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html`. Dùng cho precision/rounding/bounded decimals. Probability exact rational và ticketgrid là thiết kế riêng của plugin, không lấy từ tài liệu như một công thức gacha được chuẩn hóa.

## Bằng chứng thực thi của bundle

Các số compile/test/demo/sourcecounts lấy từ lệnh chạy thực tế trong `verification/`, không lấy từ website, không suy TPS/MSPT. Supplemental PyYAML checks không chạy Java loader. JavacTask.parse syntax-only không resolve types. Không có runtime Paper/Platform/economy/PAPI benchmark hoặc JAR plugin đã xác minh.


## Phase 4 primary technical references

- JDK 21 SecureRandom: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/SecureRandom.html
- SQLite transactions: https://www.sqlite.org/lang_transaction.html
- SQLite atomic commit: https://www.sqlite.org/atomiccommit.html
- Paper scheduler/thread guidance: https://docs.papermc.io/paper/dev/scheduler/

Đã tra cứu trong lượt triển khai này. Các yêu cầu state machine/receipt/idempotency/escrow là thiết kế riêng của project. Nguồn trên không xác minh SDK LeDatPlatform hoặc provider persistence và không thay kết quả staging.


## Phase 5 — API references và giới hạn

Đối chiếu primary documentation trong lượt triển khai 2026-09-17:

- Paper 1.21.4 ItemStack (native create/serialize/deserialize): https://jd.papermc.io/paper/1.21.4/org/bukkit/inventory/ItemStack.html
- Paper 1.21.4 Damageable: https://jd.papermc.io/paper/1.21.4/org/bukkit/inventory/meta/Damageable.html
- Paper 1.21.4 PersistentDataContainer: https://jd.papermc.io/paper/1.21.4/org/bukkit/persistence/PersistentDataContainer.html
- SQLite UPSERT docs (phân biệt SQL upsert với create-once claim; project không overwrite output READY): https://www.sqlite.org/lang_upsert.html

Materializer, output claim/receipt/candidate semantics và policies là thiết kế của project. Nguồn API không xác minh actual LeDatPlatform binary hay provider identity contracts. Java/Python tests trong verification là bằng chứng chạy riêng, không suy ra runtime SDK compatibility.


## Phase 7 — baseline và primary API reference (2026-09-17)

Baseline được giải nén thật từ `LeDatItemUpgrader-phase06-source.zip`, xác minh source manifest trước sửa; các instruction/API guide do hội thoại cung cấp. Không tự sửa provider contract bằng tài liệu ngoài.

Đã đối chiếu tài liệu official, pinned Paper1.21.4 khi có Javadoc version:

- InventoryClickEvent, defer container open/close khỏi click callback: https://jd.papermc.io/paper/1.21.4/org/bukkit/event/inventory/InventoryClickEvent.html
- Custom inventory holder: https://docs.papermc.io/paper/dev/custom-inventory-holder/
- GlobalRegionScheduler task handles/next tick: https://jd.papermc.io/paper/1.21.4/io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.html
- ScheduledTask cancellation contract: https://jd.papermc.io/paper/1.21.4/io/papermc/paper/threadedregions/scheduler/ScheduledTask.html
- ItemMeta itemmodel/CMD: https://jd.papermc.io/paper/1.21.4/org/bukkit/inventory/meta/ItemMeta.html
- CustomModelDataComponent: https://jd.papermc.io/paper/1.21.4/org/bukkit/inventory/meta/components/CustomModelDataComponent.html

Nguồn trên hỗ trợ lựa chọn API, không chứng minh compile/link LeDatPlatform hoặc ItemMeta roundtrip/event behavior thực tế. Timeline easing/neutral stop/ACK hold, scope/admission và preview/live separation là thiết kế project, kiểm tra bằng source/tests đi kèm. Không lấy reference để claim Folia hoặc TPS/MSPT.

## Đối chiếu Phase 8 (2026-09-17)

Nguồn chính là ZIP Phase 7 và API guide user cung cấp; chưa có SDK binary để type-check Platform.
Đối chiếu thêm tài liệu chính thức, không dùng để thay kết quả build:

- SQLite transaction semantics: https://www.sqlite.org/lang_transaction.html
- SQLite keyset/row comparison: https://www.sqlite.org/rowvalue.html
- Paper 1.21.4 ItemMeta/CMD/item-model: https://jd.papermc.io/paper/1.21.4/org/bukkit/inventory/meta/ItemMeta.html
- InventoryClickEvent view modification warning: https://jd.papermc.io/paper/1.21.4/org/bukkit/event/inventory/InventoryClickEvent.html

Python/JDBC-interface proxy chỉ là transport kiểm thử, không dùng thư viện giả thay Paper/Platform và không ship trong jar.
