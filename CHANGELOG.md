# Changelog — current 0.9.0-phase09a

## Unreleased — scoped animated inventory title packets

- Thêm native test upgrade flow opt-in: revalidate quote/item/phí, server-side SecureRandom ticket, arrow chỉ chạy sau click, rồi commit CLEAN + DESTROY/KEEP với economy rollback khi debit bị từ chối.
- Bỏ toàn bộ glass filler; slot `.` để trống thật, icon còn lại dùng `PAPER` + `thanhviet:empty`.
- Sửa title refresh làm trống GUI: mỗi `OPEN_WINDOW` giờ resync exact inventory ngay sau khi đổi title.
- Title refresh đi qua pipeline PacketEvents bình thường để CraftEngine/Nexo/plugin Resource Pack khác render được `<shift>`/`<image>`; fence đã disarm nên không capture nhầm packet refresh.
- Thêm optional CraftEngine parser bridge cho tag `<shift>`/`<image>`; VietHUD/PAPI expansion vẫn chạy trước, Nexo/literal là fallback khi CraftEngine không có.
- Áp title RP chuẩn cho main upgrader: CHƯA CÓ → giá trị/amount/chance bar cập nhật ngay theo số lượng; arrow chỉ xuất hiện sau click, chạy ngẫu nhiên chậm hơn rồi cubic ease-out tới kết quả.
- Chance trên title cập nhật theo quote hiện tại với định dạng cố định hai số lẻ (`2.46%`, `100.00%`); bar vẫn ánh xạ từ xác suất gốc chưa làm tròn.
- Config `menus/upgrader.yml` cũ thiếu `title-display` tự nhận bộ title image chuẩn thay vì rơi về `Item Upgrader`.
- Bỏ cổng kích hoạt phụ thuộc Nexo/PAPI: title packet main menu luôn chạy; hai plugin này chỉ tham gia resolve khi hiện diện.
- Main layout dùng raw slot source1, target7, confirm36–38 và amount39–44; amount bước 2/4/8/12/16/20, trái tăng/phải giảm trong stack thật.
- Thêm Nexo glyph resolver + PAPI/VietHUD expansion có fallback title plain; config cũ không bị tự ghi đè và cần merge `title-display` thủ công.
- Main GUI có packet fence riêng theo viewer/session/view/inventory; chỉ bắt đúng `OPEN_WINDOW` ban đầu, không nghe `WINDOW_ITEMS` và không cancel packet GUI khác.
- Sửa lỗi mở menu báo nhầm `gui-load-failed` khi VietHUD/Nexo biến đổi `Component` title: capture hai giai đoạn LOWEST→MONITOR chỉ được arm đồng bộ quanh đúng `openInventory` của plugin, không còn so title tuyệt đối; lỗi title chỉ tắt animation của view đó và không đóng preview.
- Port cơ chế đổi title bằng `OPEN_WINDOW` từ zMenu qua PacketEvents 2.13+, giữ Adventure component/custom font và cùng container id/type.
- Không dùng listener lưu mọi GUI của zMenu: chỉ capture đúng một packet trong lúc mở `AnimationHolder`; không nghe/cancel `WINDOW_ITEMS`, không sửa GUI plugin khác.
- Packet title refresh cho phép listener Resource Pack xử lý title, sau đó resync item; session/token/incarnation/top-inventory identity đều phải còn khớp trước mỗi lần gửi.
- Thêm `title-animation.titles` + `interval-ticks` (1–20), round-robin/backpressure hiện có vẫn giới hạn callback và bỏ frame trễ thay vì replay.
- Thêm packet fence regression, title-frame regression và release-JAR guard không bundle PacketEvents.

## 0.9.0-phase09a — 2026-09-17 — partial Phase 9

- Shared repository bundle luôn nối progress hook vào journal; schema preflight/readiness cho 12 bảng/4 index.
- OFF/VERIFY/INITIALIZE explicit; không downgrade version, tự tái tạo bảng mất dữ liệu hoặc coi view là table.
- Single-flight schema/retention lifecycle, revision/timeout/stop fences, giữ permit tới actual completion; log lỗi trễ có lọc.
- Native source history dùng store chung và đóng UI/cache khi storage chưa ready; re-enable tạo session store mới.
- Retention scheduling một batch opt-in; thêm state COMPLETED/ABORTED predicate cạnh terminal flag ở SELECT và DELETE.
- Read-only compact recovery keyset với đối chiếu lock; admin storage status/recheck/recovery, permission và messages.
- Thêm storage-management.yml; quote 'OFF' chống YAML boolean; defaults giữ nguyên flags/history/storage mode.
- Offline native preflight và Gradle API entry guard; không tạo stub/download dependency/claim ABI chỉ bằng class presence.
- 47 core tests/147 assertions và33 Java repository SQLite transport tests/183 assertions mới. Native compiler/driver/runtime chưa kiểm chứng.


## 0.8.0-phase08 — 2026-09-17

- Thêm history public projection/diagnostic tách roll outcome khỏi completed settlement; không raw RNG/item payload/receipt token.
- Keyset owner-filter-window query, server-held pagination session, bounded TTL statistics cache, no PAPI DB reads.
- Pity explicit policy/scopes/source floor, percentage-point bonus trước clamp, stamped quote/version/reconfirmation.
- Journal codec v3 cho pity; giữ encoding v1/v2 và thêm golden regression từ ZIP Phase7 thật.
- Atomic SQL JournalCommitHook: history + statistics + pity + completion tombstone cùng journal CAS/terminal unlock.
- Explicit history-only backfill và bounded terminal-only retention API; không scheduled purge hoặc retroactive pity replay.
- Native source history inventory + readonly history/statistics/diagnose/pity commands, Platform query/PAPI bridge, new YAML/messages.
- History default off, native pity/live transaction/packets vẫn gated; không đổi storage/value profile cũ.
- 98 core groups mới +31 SQL repository tests qua test-only SQLite bridge; native API/driver/client build còn thiếu.

---

# Changelog

## 0.7.0-phase07 — Inventory roulette / reveal

- Thêm pure timeline/presets, config menu/palette, presentation-only click policy, read-only committed journal request và admin preview request tách biệt.
- Monotonic sampling, neutral route independent outcome, first acknowledged reveal hold, skip không reroll/reset hold, callback timeout/incarnation/revision/sequence fences.
- Native source admin animation command, customholder/listener, cosmetic renderer/cache, dirty slot updates, one bounded round-robin ticker, no transaction/RNG/effect calls.
- PaperGuiScheduler cancel own task handles, không cancel toàn plugin khi đóng một service.
- menus/animation.yml, messages/fallback/permission, atomic config load, 3 presets và configurable cues/models/text. Không đổi storage/schema/main flags, không mở UPGRADE.
- Phase7:80 nhóm/6.581 assertions; tổng809/59.706/0; SQL15+14 regression, legacy18+1, resources1175/18YAML và syntax177/0. Chưa full Paper/API build/staging/native events/benchmark.
- Live committed-to-native wiring vẫn chưa bootstrap; admin preview không có fee/reward hoặc fake odds.

## 0.6.0-phase06 — Inventory preview framework

- Thêm core GuiContext/Settings/Menus/SessionStore/ClickPolicy/PreviewService/SlotDiff, strict compiled grid/source invariants.
- MAIN/CATALOG/PROFILES/BOOSTS source Paper: holder, ID-bound actions, all-click/drag cancellation, reference-only source, quote, filtered pagination, dirty-slot updates.
- Next-tick scheduler bridge, exact session/view/request fences, source/resource/access recheck, bounded pending/worker/session state, lifecycle/reload cleanup.
- Icons dùng material/quantity/itemmodel/CMD projection; không clone raw provider PDC/identity/container contents.
- Thêm4 menu/settings files, text/sounds/model config và fallback messages; existing config không overwrite, storage và liveflags không đổi.
- 88 nhóm mới/1.196assertions; tổng729/53.125/0. SQL15+14 regressions, resources1019/17YAML, syntax162/0. Không native inventory/build/driver tests.
- API artifact/full Paper build/staging vẫn thiếu; UPGRADE không gọi transaction/RNG/economy. Chưa packet hoặc animation.

## 0.5.0-phase05 — 2026-09-17 — Prepared output / Transfer / Failure

- Nối `PREPARE_OUTPUT` sau ledger claim và trước source/fee acquisition, pin đầy đủ policy trong quote/plan codec v2; giữ byte/digest v1 khi đọc/ghi legacy plans.
- `OutputMaterializer`: fresh provider creation, template/price/quantity recheck, unique token separation, explicit metadata patch + verification; drift cần reconfirm trước chi phí.
- `CLEAN`, selective name/repair/enchant/durability/typed PDC capability; DESTROY/KEEP/DAMAGE/DOWNGRADE với damage-break/lower-value semantics rõ.
- Async output store + checksummed payload + SQL READY/identity atomic finish; không regenerate PREPARING/AMBIGUOUS. Lost commit ack không trở thành success receipt.
- `OutputBoundEffectPort` giao đúng prepared snapshot; `PreparedDeliveryPort` mới chỉ contract, chưa native mailbox/reward endpoint.
- Thêm `upgrades/outputs.yml`, strict loader, message YAML cho preview policy; mẫu không tự bật. Native Vanilla helper/Platform output bridge source chưa build/test Paper.
- 105 nhóm mới/5.318 assertion; tổng 641 / 51.929 / 0; 10 nhóm JDBC mock output nằm trong số trên. SQLite output 14 test + journal 15 regression; không test driver/MySQL/Minecraft.
- Giao dịch/packet flags vẫn false. Không thay storage, không giả SDK/JAR production.

## 0.4.0-phase04 — Transaction backend

- Pin validated quote into bounded immutable AttemptPlan, source/target/fee snapshots and deterministic idempotency.
- Journaled effect intents/receipts; versioned CAS state reducer; secure integer sample and durable outcome gate.
- Outcome-aware settlement/refunds and reverse compensation only for confirmed reservations.
- Unknown effects retain durable player lock for reconciliation; no blind replay/refund/redraw.
- Async journal port, bounded admission, cancellation-safe caller views, stop/drain and identity-safe cleanup.
- JDBC repository + SQLite/MySQL DDL; immutable plan inserted once, small state-envelope CAS; Platform SQL bridge source only.
- Added 105 Java test groups (13 mock JDBC), 15 real SQLite SQL tests including abrupt process exit around outcome commit.
- Keeps native upgrades/GUI/packets disabled; no production EffectPort, no verified Paper JAR, no Folia claim.

## 0.3.0-phase03 — 2026-09-16

Added exact ChanceFormula RATIO/POWER/TABLE/CURVE and1e9-ticket probability; riskprofiles, groupedpermission bonuses, consumablechance/protectiondefinitions; typedconditions; outcome-aware costplanning, deterministic allocation with source-slot exclusion; immutablequote/revalidation/reconfirm contract. Added optional PAPI source hook and read-only Platform balance adapter. Added quote/profiles/boosts source commands, config4files and messagefallbacks. Existing Phase0–2 tests preserved.

Verified core 431 groups/37.270 assertions/0 failure; quoteCLI;506 supplemental checks / 12 YAML;87 Java source syntaxparse. Not a Paper build/servertest. No actualgacha/effects/GUI; flags remainfalse, storageunchanged. SDKstillmissing; no stub/reflection fallback. Boost/VIPexamples remainoff.


## 0.2.0-phase02 — 2026-09-16

Tiếp tục trên source `0.1.0-phase01`, không tạo plugin mới hoặc đổi stack.

Thêm domain Path/Catalog/Recommendation/Selection, indexes bất biến, query filter/sort/pagination, preview request timeout/capacity. Thêm 123 nhóm test core và CLI demo catalog.

Nối Paper source config loader, lazy template cache, query preview service, `/catalog`, `/recommend`, `/paths`, permission/admin help/tab completion, plugin enable/disable/quit invalidation. Tách template capture khỏi worker indexing/query.

Thêm catalog/paths YAML được comment chi tiết, fallback messages. Giữ schema config/messages cũ, giá trị cũ và storage mode. Các feature chưa implement vẫn khóa false.

Cập nhật verification, docs handoff, phase plan và source manifest. Core 249 nhóm/21.540 assertion; Paper chưa compile/link/runtime do thiếu API thật và dependency download lỗi DNS.

## 0.1.0-phase01

Nền identity/snapshot/value/recipe, runtime reload, menu compiler và Paper inspection source. Báo cáo gốc lưu trong `verification/phase01-original/`.
