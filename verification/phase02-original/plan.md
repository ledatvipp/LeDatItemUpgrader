# LeDatItemUpgrader — Implementation Plan & Progress

Baseline: requirement đã chốt trong hội thoại ngày 2026-09-16.
Version bàn giao: `0.2.0-phase02`.

## 1. Requirement đã chốt

Plugin Item Upgrader/Gacha cho Paper, backend-first. Inventory UI đơn giản cấu hình kiểu zMenu; không hard-depend zMenu, chưa dựng world display/cinematic. Server inventory/session là authority; packet chỉ là presentation sau này.

Target selection: predefined path + catalog + recommendation. Main menu hiển thị recommendation; click target mở catalog; admin có thể khóa path.

Failure có nhiều policy. Value engine hybrid. Item ecosystems: Vanilla, MMOItems, ItemsAdder, Oraxen, Nexo; economy/cost dự kiến Vault, PlayerPoints, vật liệu; boost/protection consumable, permission bonus, PAPI condition.

Metadata transfer mặc định CLEAN. Policy theo path chỉ allow-list vanilla data/PDC/stat; provider-specific identity/UUID không copy mù quáng. Chưa tự suy ra policy provider khi không có API xác thực.

## 2. Quyết định kiến trúc

`core` là library Java thuần để test business rules. `paper` là plugin adapter: command, registry/item snapshot, configuration, Platform bridge. Hai module build thành **một plugin JAR** khi dependency và compilation gate đạt; core không phải plugin riêng.

Không dùng NMS/reflection. Không truy cập `vn.ledat.platform.core.*`. Không shade Platform/Paper. Mọi công việc file/database/heavy calculation qua scheduler/database service của Platform; snapshot/native inventory phải ở Paper main thread. Chưa claim Folia.

Data quan trọng sau này phải immutable và gắn `configRevision`, session/attempt identity. Preview là tham khảo, không authorize transaction. GUI không chứa RNG/economy code.

## 3. Tiến độ thực tế

| Phase | Đã có | Còn thiếu / acceptance gate |
|---|---|---|
| 0 — Foundation | Gradle source, main/bootstrap, config/message source, RuntimeStore có unit test, schema repository nền | API artifact, Gradle/Paper compilation, runtime bootstrap/schema/lifecycle tests |
| 1 — Item + Value | Pure item/value models, resolver/modifier/recipe core; 126 nhóm test pass; Paper snapshot/inspection source | CustomItemKey accessor thật; native item/provider tests; Paper command tests; runtime recipe import chưa có |
| 2 — Path + Catalog | Core path/index/query/recommendation/selection/request gate; 123 nhóm mới pass; Paper loader/cache/preview source | Paper/Platform compile/link, native template/provider tests, command/stale/timeout lifecycle staging |
| 3 — Chance/Profile/Boost | Chưa triển khai | Chance math, rounding contract, fees, safe protection semantics, permission/PAPI adapters |
| 4 — Transaction | Chưa triển khai | Ledger, reservations, item/currency journal, durable result, reconciliation |
| 5 — Transfer/Failure | Chưa triển khai | Provider-safe creation, allow-listed transfer, explicit failure effects |
| 6 — Inventory GUI | Matrix/action compiler + sample blueprint | Holder/session, click/drag handling, real SOURCE ownership, catalog buttons, delivery safety |
| 7 — Animation | Chưa triển khai | Dirty-slot renderer, outcome presentation, skip/disconnect behavior |
| 8 — History/Pity/Admin | Inspect/status source; chưa có history/pity | SQL history, bounded retention/cache, pity scope, PAPI routes |
| 9 — Hardening | Core invariants/lifecycle tests | Staging, fault injection, no-dupe coverage, load tests, restart/recovery |
| 10 — Resource Pack/Packets | item-model/CMD/text fields trong blueprint | Renderer/packet adapter, version matrix, RP art aligned to native slots |

**Không đánh dấu Phase 0–2 production-complete khi Paper/Platform module chưa build/test thật.** Core Phase 2 được tiếp tục theo yêu cầu user trong khi chờ API artifact; không mở transaction để vượt qua gate.

## 4. Phase 0: điều kiện hoàn thành

Có dependency API thật. Chỉ sửa `paper/platform/PlatformAccess` và identity bridge để khớp public API; không bẻ kiến trúc sang reflection vì thiếu signature. Compile target Paper 1.21.4, Java 21. Enable tạo config/messages/menu files qua async Platform config service. Startup config lỗi -> runtime unavailable, không mở gacha. Reload invalid -> giữ nguyên revision/config đang dùng. Disable -> stop gate trước, cleanup callbacks và flush storage có timeout.

Storage hiện chỉ dùng shared query có chữ ký trong guide. Sau khi biết `sqliteStore` thật, quyết định rõ isolated SQLite hay Platform shared SQL; cập nhật config migration, docs và tests, không đổi ngầm đường dẫn dữ liệu.

## 5. Phase 1: điều kiện hoàn thành

Giữ resolver order trong README. Manual override phải thắng. Cùng priority mà hai rule cùng khớp -> reject, không chọn theo map iteration. Unknown/provider failure/unsafe metadata -> không được tự dùng Vanilla base material làm fallback.

Phân biệt `unitValue` và `totalValue`; làm tròn unit trước khi nhân quantity. Fingerprint SHA-256 chỉ bảo vệ snapshot payload hiện tại, không làm global anti-dupe registry, không giả định bytes canonical xuyên version/save/load. Khi có transaction phải kiểm tra lại actual stack và ownership ở owner thread, không chỉ hash metadata chọn lọc.

Recipe native import: lấy snapshot registry trên đúng thread với budget; chỉ import loại recipe có semantics rõ. Remainder/container/cooking fee/custom ExactChoice không được giả vờ equivalent với ingredient Material. Phân tích graph bằng detached data; cache theo recipe/config generation. Một ingredient có alternative không biết giá -> không dùng alternative có giá cao để suy ra giá an toàn.

Custom stats/rarity cần capability của API thật; không extract từ lore. Provider adapter phải có integration fixture thật với metadata/socket/upgrades/identity, không thay bằng tên item hoặc CMD.

## 6. Phase 2: Path + Catalog

Thiết kế `UpgradePath`, `TargetDefinition`, `CatalogIndex` immutable. Explicit path có thể khóa target. Recommendation chỉ lọc các target eligible và đã có base value hợp lệ; không random grant từ `weight` khi người chơi đã chọn target cụ thể.

Catalog giữ index giá/category/provider, không scan toàn bộ providers mỗi lần refresh GUI. Lazy page render, page bounds, bounded result count, generation check sau async. Disabled/missing provider loại capability liên quan, không tạo placeholder diamond thay custom reward.

## 7. Phase 3: Chance/Profile/Cost/Boost

Chance calculation phải phân biệt ratio `[0,1]`, phần trăm và percentage points. Fee/currency không dùng double làm authoritative representation; Vault conversion chỉ ở adapter boundary sau range/rounding policy. PlayerPoints kiểm tra integer range. Amount âm/NaN/Infinity/overflow -> reject.

Preview có thời hạn và pin revision. Full revalidation tạo quote mới phải báo cho người chơi nếu odds/cost khác với quote họ đang xác nhận; không im lặng trừ giá khác. Protection/cost consume-on-attempt/on-failure phải có reserve/refund semantics rõ. Không cho cấu hình KEEP + miễn phí trở thành nguồn reward vô hạn ngoài ý đồ admin.

PAPI chỉ là condition adapter tin cậy ở đúng thread; không dùng kết quả text làm bằng chứng debit/reward. LuckPerms không hard dependency nếu chỉ cần Bukkit permission.

## 8. Phase 4: Transaction và giới hạn atomicity

Một UUID có một attempt active; GUI session lock trước khi nhận click kế tiếp. `ledger.claim` là guard retry/idempotency, không thay thế atomicity của inventory/currency. Không consume trước claim. Snapshot toàn bộ input/fee/boost/target/config revision trước critical section.

Tách financial outcome khỏi presentation state. Durable attempt giữ `transactionId`, `idempotencyKey`, input/output snapshot, quote/chance, RNG outcome và từng side effect có bằng chứng. Outcome ghi bền vững trước reveal; đóng GUI/skip/disconnect không roll lại.

**Quan trọng:** DB, Bukkit inventory save và Vault/PlayerPoints không tự có distributed ACID. Crash ở khoảng giữa external side effect và journal update có thể không phân biệt đã thực hiện hay chưa. Không tự refund/reward lại mọi bản ghi unfinished. Cần trạng thái `RECONCILIATION_REQUIRED`, bằng chứng provider/idempotency nếu có và admin recovery; chỉ auto-retry thao tác đã chứng minh idempotent. Không claim exactly-once khi adapter không hỗ trợ.

Kết quả/hoàn trả vào PendingDelivery bền vững thay vì drop đồ quý bừa xuống đất. Mailbox delivery cũng có cùng crash-gap phải kiểm thử. Lỗi animation không thay outcome; lỗi dữ liệu không biến thành roll thua.

## 9. Phase 5: Transfer + Failure

CLEAN tạo target hợp lệ mới. SELECTIVE chỉ copy allow-list và kiểm tra target compatibility. UUID/identity provider của target không bị overwrite. Enchant không hợp lệ, socket/stat incompatible phải có policy explicit.

`DAMAGE` chỉ tác động item có durability. `DOWNGRADE` cần target/tier mapping cụ thể. `LOSE_PERCENT_VALUE` chưa có semantics vật lý cho mọi item: không đơn giản ghi PDC giá thấp trong khi giữ toàn bộ sức mạnh/giá trị trade. Feature unsupported -> disable/reject, không pretend đã giảm item.

## 10. Phase 6: GUI inventory authority

Mỗi viewer có sessionId, page, context, editing/locked state. Dùng holder/session, không check title; action từ slot map, không từ display name/lore. Cancel click mặc định; whitelist thao tác input theo SOURCE role. Mirror/source preview không giữ một bản item thật nữa.

Chặn shift-click, number-key, swap-offhand, collect-to-cursor/double-click, unsupported drag. Chỉ dùng rawSlot của top inventory; bottom behavior explicit. Cursor stack/close/quit/death phải có ownership rule rõ trước khi mở tính năng input. No source ownership = no consume.

Resource Pack chỉ thay assets/layout/presentation. Native slot hitbox không được đổi tùy ý chỉ bằng texture; thiết kế art theo grid thực. Chưa cần packet chỉ để có tên/lore riêng trong inventory riêng từng viewer. Packet adapter chỉ thêm khi có nhu cầu thực tế và test container state/cursor/version đúng.

## 11. Phase 7–10 và kiểm thử

Animation nhận predetermined outcome; dirty-slot update thay vì render 54 slot mỗi tick. Không task lặp riêng cho mọi player. History/pity sử dụng SQL async, indexes và retention. Pity có scope theo nhóm/path tránh farm vật phẩm rẻ để tăng odds item đắt.

Hardening phải có fault injection ở từng side-effect boundary, startup recovery, inventory full/quit/restart, permission/PAPI changes, reload mid-attempt, provider disable, storage unavailable và load metrics thực tế. Phải ghi lại environment/concurrency/duration; không chuyển số unit-test sang tuyên bố TPS/MSPT.

## 12. File và nguồn tham chiếu

`docs/PLATFORM_CONTRACT_GAPS.md` nêu thiếu thông tin cần có.
`docs/TEST_CHECKLIST.md` phân biệt đã chạy/chưa chạy.
`verification/BUILD_REPORT.md` là bằng chứng bàn giao.
`docs/reference/LEDATPLATFORM_API.md` là bản guide được cung cấp, không phải source/binary API.


## 13. Bàn giao Phase 2 thực tế

Version `0.2.0-phase02`: tổng core 249 nhóm / 21.540 assertion; trong đó giữ nguyên regression cũ 126 nhóm và thêm 123 nhóm mới. Java syntax-only 53 file không thay compile module Paper. Báo cáo tại `verification/BUILD_REPORT.md`.

Cấu hình mới `upgrades/catalog.yml`, `upgrades/paths.yml` mỗi file version 1. Không đổi schema main config/messages v1, không đổi storage, không overwrite prices. Command source mới: `/upgrader catalog [page] [category|all] [sort]`, `/upgrader recommend`, `/upgrader paths`, quyền `ledatitemupgrader.admin.catalog`.

Contract Phase 2 chi tiết: `docs/PHASE02.md`. PAPI evaluator, chance/profile/cost/protection chưa có. Selection token chỉ là preview; no exactly-once/payment authorization claim.

Next logical core phase: Phase 3 — Chance/Profile/Boost models và phép toán, vẫn khóa side effects tới Phase 4. Song song, khi có API thật cần compile/link và chạy toàn bộ command/provider tests của Phase 0–2, không cộng dồn các phase core thành tuyên bố plugin đã deploy được.
