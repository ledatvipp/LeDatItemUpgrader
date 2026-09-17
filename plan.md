# LeDatItemUpgrader — Implementation Plan & Progress

Baseline: requirement đã chốt trong hội thoại ngày 2026-09-16.
Version bàn giao: `0.9.0-phase09a` — Phase 9 **chưa hoàn tất**.

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
| 3 — Chance/Profile/Cost/Boost | 182 nhóm core mới; exact formulas, immutable quote, cost allocation, profile/boost/permission/typed conditions; Paper preview/hook source | Full Paper/Platform/PAPI/economy build/staging; chưa reserve/debit/roll outcome |
| 4 — Transaction | Core reducer/driver/plan/effect script/recovery classifier; JDBC repo + async SQL bridge source; 105 nhóm mới, 15 SQLite SQL tests | Native escrow/economy/ledger/delivery, JDBC driver/MySQL/Platform integration, evidence-based reconciliation mutator; live engine chưa bootstrap |
| 5 — Transfer/Failure | Output preparation/materializer, full policy pinned vào quote, typed transfer, 4 failure modes, output store/codec/SQL + native Vanilla helper source; 105 nhóm mới và 14 SQLite SQL tests | Provider-specific metadata/identity/native item tests; Platform scheduler wiring; durable entitlement/delivery; re-quote UI cho giá thay đổi; live vẫn đóng |
| 6 — Inventory GUI | Core session/click-policy/preview/diff; native holder/renderer/listener/controller source; 4 configurable screens; 88 nhóm mới pass | Full Paper/API compile + client/lifecycle tests; source chỉ reference; native custody/live upgrade vẫn khóa |
| 7 — Animation | Timeline/presets/acknowledged reveal/skip/fenced session/round-robin; read-only committed journal reader; native admin preview renderer/listener/command source; 80 nhóm mới pass | Full Paper/API compile + native event/scheduler tests; live journal-origin wiring chưa mở; RP/packet chưa có |
| 8 — History/Pity/Admin | 98 core tests; quote stamp + codec v3; atomic history/stats/pity journal hook; keyset query/session/cache; 31 SQL repository bridge tests; native read-only menu/command/PAPI source | Paper/API/driver/MySQL staging; native hook/quote bootstrap; pity live gated; history default off; maintenance source ở Phase 9A; backfill orchestration chưa có |
| 9A — Storage hardening (partial Phase 9) | Bundle hooked journal/output/progress; schema preflight + explicit init; single-flight lifecycle/readiness; retention source opt-in; read-only recovery; 47 core và 33 SQL transport tests mới | Paper/API/production driver/MySQL staging; schema toàn vẹn/column types; native writer/quote/pity/escrow/economy/ledger/delivery; crash/exploit/MSPT tests chưa xong |
| 10 — Resource Pack/Packets | item-model/CMD/text fields trong blueprint | Renderer/packet adapter, version matrix, RP art aligned to native slots |

**Không đánh dấu Phase 0–8 production-complete khi Paper/Platform module chưa build/test thật.** Core các phase được tiếp tục theo yêu cầu user trong khi chờ API artifact; không mở transaction để vượt qua gate.

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


## 13. Lịch sử bàn giao Phase 2 (không phải trạng thái hiện tại)

Version `0.2.0-phase02`: tổng core 249 nhóm / 21.540 assertion; trong đó giữ nguyên regression cũ 126 nhóm và thêm 123 nhóm mới. Java syntax-only 53 file không thay compile module Paper. Báo cáo tại `verification/BUILD_REPORT.md`.

Cấu hình mới `upgrades/catalog.yml`, `upgrades/paths.yml` mỗi file version 1. Không đổi schema main config/messages v1, không đổi storage, không overwrite prices. Command source mới: `/upgrader catalog [page] [category|all] [sort]`, `/upgrader recommend`, `/upgrader paths`, quyền `ledatitemupgrader.admin.catalog`.

Contract Phase 2 chi tiết: `docs/PHASE02.md`. PAPI evaluator, chance/profile/cost/protection chưa có. Selection token chỉ là preview; no exactly-once/payment authorization claim.

Next step tại thời điểm Phase2 là Phase3. Xem mục15 cho source hiện tại; không cộng dồn các phase core thành tuyên bố plugin đã deploy được.


## 14. Lịch sử bàn giao Phase 3

`0.3.0-phase03`: core 431 nhóm/37.270 assertion/0 failure (126 + 123 + 182). 87 file Java syntax-only,506 resource checks / 12 YAML không thay build/staging. Báo cáo gốc tại `verification/phase03-original/BUILD_REPORT.md`; contract chi tiết tại `docs/PHASE03.md`.

Thực thi RATIO/POWER/TABLE/CURVE bằng phân số exact,ticket grid 1e9; profile/permission/boost stages; cost peroutcome reserve=a+max(s,f); source-slot exclusion; strict matching; KEEP guard; typed PAPI condition core và optional source adapter; quote expiry/reconfirm. Bốn YAML mới chance/profiles/boosts/conditions; command preview quote/profiles/boosts, permission admin.quote. Main flags false, storage unchanged.

Pity chưa triển khai, damage/downgrade/transfer chưa giả lập. Phần sau tại thời điểm Phase3 là Phase 4 transaction journal/ledger/ownership/outcome/reconciliation, không phải chỉ thêm withdraw rồi gọi giveItem. Không sửa quote thành auto-authorization để nhanh tới GUI. Full dependency build và staging gate của tất cả phase vẫn mở.

## 15. Bàn giao Phase 4 và gate chuyển tiếp

`0.4.0-phase04`: tổng core **536 nhóm / 46.611 assertion / 0 lỗi**; Phase 4 mới **105 nhóm / 9.341 assertion**, gồm 13 nhóm mock JDBC. **15 kiểm thử SQL SQLite thực qua Python** chạy riêng; chưa kiểm thử JDBC driver/MySQL/Paper. Java syntax-only **113 files**, resource checks **506 / 12 YAML**. Bằng chứng hiện tại: `verification/BUILD_REPORT.md`.

Đọc `docs/PHASE04.md` và `docs/CONTINUE.md`. Plan payload chỉ ghi ở claim, CAS ghi state envelope nhỏ. Ledger/effect vẫn là port; không có native adapter giả trả success. Target template không được clone UUID provider để phát, Phase 5 phải materialize output theo attempt một lần. Backend recovery không auto retry unknown hoặc redraw DRAW_INTENT. GUI/packet chưa mở, full Paper compile và native no-dupe staging vẫn còn thiếu.


## 16. Bàn giao Phase 5 và gate chuyển tiếp

`0.5.0-phase05`: core **641 nhóm / 51.929 assertion / 0 lỗi**. Phase 5 mới **105 nhóm / 5.318 assertion**, gồm 10 nhóm mock JDBC output; Phase 4 13 nhóm mock JDBC giữ nguyên. **14 kiểm thử SQL output + 15 journal SQL regression** chạy SQLite thực qua Python. Không phải test JDBC driver/MySQL/server. Resource542 checks/13 YAML; Java syntax-only146 files. Báo cáo hiện tại `verification/BUILD_REPORT.md`; báo cáo Phase 4 giữ trong `verification/phase04-original/`.

Output PRIVATE candidate chuẩn bị/lưu trước giữ source/phí. READY+fresh ID hashes cùng SQL transaction. Không tái tạo khi pending/unknown, không dùng candidate như một mailbox entitlement. Journal v2 pin đầy đủ policy; legacy v1 plan/state vẫn decode và encode giữ nguyên bytes/digest. OutputBoundEffectPort từ chối acquire/deliver template legacy chưa có prepared output.

Tiếp tục Phase 6 theo `docs/CONTINUE.md`: holder/session/action renderer trước; không mở SOURCE nhận item thật hoặc UPGRADE thực thi nếu chưa có escrow/delivery đã chứng minh crash safety. Artifact LeDatPlatform vẫn cần để compile/link. Không lấy số test backend thay thế staging.


## 17. Bàn giao Phase6 — preview không custody

`0.6.0-phase06`: tổng729nhóm/53.125assertions/0. Phase6 mới88nhóm/1.196assertions; core model/policy/facade, không nativeclientevents. Resources1.019/17YAML; syntax162/0; SQLite15+14 regression và legacy18+1 giữ nguyên. Báo cáo current `verification/BUILD_REPORT.md`, evidencePhase5 chuyển `verification/phase05-original/`.

MAIN/CATALOG/PROFILES/BOOSTS, source slot reference, recommendation/select, profile/boost/quote/error, access-before-pagination, slot actionIDs, customholder/session/view/request fence, all clicks/drag cancel allowlist, next-tick native deferral, batchsweep, dirty updates. Icons là visual projection material/amount/itemmodel/CMD, không raw PDC/unique item clone. Texture/model fields có nhưng packet/gacha animation chưa có.

Settings/menu files/comments/messages mới không overwrite configs cũ. UPGRADE vẫn blocked; oldquote không tự gia hạn hoặc authorize debit. Source input custody/cursor transfer không mở. Đọc PHASE06.md và CONTINUE.md trước Phase7, giữ nguyên các gates compile/API/staging/native effects.


## 18. Bàn giao Phase 7 — animation không giao dịch

`0.7.0-phase07`: **809 nhóm / 59.706 assertion / 0 lỗi**; Phase7 mới80/6.581. Resources1.175/18YAML; syntax177/0; SQLite15+14 và legacy18+1 regression giữ nguyên. Báo cáo hiện tại `verification/BUILD_REPORT.md`, báo cáo Phase6 giữ ở `verification/phase06-original/`.

Timeline neutral route, acceleration/cruise/deceleration/land/reveal; preset roulette4,45s/quick1,6s/none0,7s danh nghĩa; không new RNG hoặc chance arc. Session/incarnation/revision/sequence fences, một outstanding frame, ack-then-hold, skip không kéo dài, lag không catch-up queue, round-robin bounded. Native admin preview WIN/LOSS tách khỏi journal-origin thật, config/menu/sounds/model/text mới; task handle cleanup scoped thay cancel tất cả task plugin.

CommittedAnimationReader chỉ đọc đúng owner + committed outcome, không claim/CAS/settle; live native chưa nối. Preview không nhận đồ/trừ tiền/reward; flagsfalse, schema/storagekhôngđổi. Không nói WIN preview là giao dịch đã thành công. Phần tiếp theo Phase8 History/Pity/Admin và build/native acceptance gates được ghi trong CONTINUE.md. Không dùng coretest hoặc syntaxparse để đánh dấu plugin cài server được.

## 19. Bàn giao Phase 8 — history không biến reveal thành receipt

`0.8.0-phase08`: 907 core groups /61.260 assertions; Phase8 mới98/1.554. Ngoài core có31 test/535 assertions actual
Java repository + SQLite qua test-only bridge (không phải JDBC driver). Resource1429/21YAML, syntax206/0. SQL regression15+14,
v1 legacy18+1, v2 golden4plans/126record-statepairs giữ nguyên. Docs PHASE08 và report ghi scope kiểm chứng chi tiết.

Journal hook được gọi cùng connection/CAS transaction trước unlock: history projection, completion tombstone,
statistics, pity version/failures commit hoặc rollback cùng nhau. Không count ABORTED/RECONCILIATION/OUTCOME_COMMITTED.
Pity stamp pin explicit scope/epoch/version; source value/quantity, target, profile, boosts/fee/output changes tách scope.
Pity chỉ DESTROY, after-boost percentage points before clamp, version drift phải re-confirm. Non-pity codec v1/v2 giữ bytes;
v3 chỉ cho pity. Không dùng PAPI/cache làm nguồn pity authoritative.

History GUI/command read-only, keyset server cursor, UUID/session/revision fencing, bounded query/cache. Legacy backfill
chỉ history; retention chỉ terminal UI rows và hiện chỉ API, chưa scheduled job. PAPI chỉ cached statistics, warm bằng
statistics command. Schema không tự init từ UI toggle. Native live writer/pity/escrow/delivery chưa bootstrap, flagsfalse.

## 20. Phase 9B — các gate vẫn chưa giải quyết

Ưu tiên giải quyết **Paper/LeDatPlatform build gate + native integration**, không thay bằng thêm GUI giả thành công.
Khi có API artifact thật, type-check public adapter signatures, item identity/template/snapshot roundtrip và delayed
scheduler ownership. Cần cùng physical DB transaction cho journal + progress hook; initialize schema async rồi injecthook.
Native quote phải dùng PityQuoteService trên worker/read authoritative; chỉ gỡ gate sau revalidation/version/CAS tests.
Sau đó escrow/economy/ledger/entitlement/delivery evidence với crash tests, lifecycle/reload/client inventory exploit và
load/MSPT measurements. Chưa claim cross-server/Folia/distributed atomicity. Reconciliation command mutating nếu làm
phải có durable operation evidence, digest/version guard, audit và explicit admin confirmation; không reset/unlock blind.

## 21. Bàn giao Phase 9A — tiến độ có thể kiểm chứng

`0.9.0-phase09a`: core 954 groups /61.407 assertions; riêng phase mới47/147. Thêm33 repository groups/183 assertions
qua TEST_ONLY_PYTHON_BRIDGE, tách khỏi31/535 progress regression. Java syntax219/0; resource1480/22YAML;
SQL journal15/output14; legacyv1 18+1, v2 4plans/126pairs. Không thêm codec hoặc tự chuyển storage backend.

`JdbcStorageBootstrap` preflight tất cả12 bảng/4index rồi chỉ INITIALIZE explicit CREATE, không sửa versioned data
loss hoặc unknown version; journal factory inject progress hook. Native source PlatformStorageService dùng managed
query và một ticker; history dùng chung store với readiness/revision guard. Timeout giữ actual operation permit,
reload/stop fence late callbacks; lỗi trễ vẫn có log đã lọc. Maintenance dùng history retention settings, một batch,
state + terminal predicate, defaultoff. `/upgrader storage status|recheck|recovery` có permission; recheck tuân mode,
recovery chỉ compact summary không payload/effect. Initializer không có distributed lock/schema-full-integrity audit.

Build preflight thật báo thiếu Gradle và API artifact. Không tạo SDK giả, không mở nativeflags; syntax checker không
thay typechecker. Nút UPGRADE vẫn khóa. `docs/PHASE09A.md`, `docs/CONTINUE.md`, report và checklist quy định rõ Phase9B.
