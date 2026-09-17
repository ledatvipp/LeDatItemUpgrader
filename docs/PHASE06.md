# Phase 6 — Inventory GUI / Preview contract

**Baseline:** `0.5.0-phase05`; **version:** `0.6.0-phase06`. Đây là UI integration source và core GUI model đã kiểm chứng; không phải chứng nhận full Paper deployment.

## 1. Phạm vi

Bốn screen MAIN/CATALOG/PROFILES/BOOSTS, configurable matrix, source reference, target recommendation/selection, permission/condition filtered pagination, profile/boost, quote, costs, warning, reload/timeout cleanup. Không xử lý input custody hoặc gọi live upgrade. Native inventory authoritative để chặn thao tác; session/action map authoritative cho UI selection. Không packet-only container, không phụ thuộc zMenu.

Không tăng scope economy/transaction của Phase 4–5: database schema/storage modes/output journals không bị thay. `features.upgrades-enabled` và `features.packet-renderer-enabled` tiếp tục bắt buộc false. `menus.settings.enabled=true` chỉ cho preview.

## 2. Domain / session

`GuiContext`: screen/page/sort/category/sourceSlot/target/profile/boosts, immutable, ID validation, sorted unique boosts. SourceSlot -1 là chưa chọn, 0–35 là player storage reference; không ItemStack/Player/cursor/escrow ownership.

`GuiSessionStore`: UUID viewer/session/view, configRevision, request sequence, busy, monotonic timestamps, immutable per-session limits. Reserve ngay lúc click đã cancel để chặn double queue. Transition tạo view mới khi đổi screen/page/sort/category **trước** openInventory. Close đối chiếu session+view; callback async đối chiếu cả sequence. Old close không đóng new page; old response không ghi lên selection mới.

Complete chỉ giữ context cũ hoặc điền recommended target khi target cũ trống. Không âm thầm đổi source/profile/boost/page hoặc thay explicit target invalid bằng recommendation. Một request chỉ có một deadline; transition không gia hạn deadline. Nano-time wrap và concurrent claim có core test. Idle/request timeout + config revision sweep dọn session; không tự mở lại GUI.

Paper coordinator còn lưu `pendingOpens` trước next tick: batch sweep không đóng old holder giữa command reopen và callback open mới. Map này xóa theo quit/exit/expiry/disable. Đây là native source logic, chưa kiểm thử trên server.

## 3. Thread flow

```text
InventoryClickEvent (owner)
  cancel first -> rawSlot/cursor/click guard -> reserve request handle
       ↓ next tick (native bridge), then Platform player owner
  recheck native top inventory/session/permission/mode/cursor
  transition context -> render loading -> capture source/access/resources
       ↓ Platform worker, immutable inputs only
  CatalogService + UpgradeQuoteService -> GuiPreviewService.Preview
       ↓ Platform player owner
  recheck session/revision/catalog/source/access/resources/quote expiry
  complete exact request -> render current view only
```

Không retain InventoryClickEvent/Player trong task. Callback retired/drop không có global Bukkit fallback. Request bị bỏ được batch timeout dọn. Không file/SQL trong GUI listener/renderer. PAPI/balance snapshot vẫn theo provider sync contract cũ; budget không ngắt được một blocking expansion.

**Scheduler exception có chủ đích:** guide `LEDATPLATFORM_API.md` không mô tả overload delay/next-tick. `PaperGuiScheduler` dùng Paper GlobalRegionScheduler.run (next tick), sau đó Platform.player; một fixed-rate sweep owner-tracked. Không raw executor/BukkitScheduler/NMS/reflection. Khi có API thật, thay bridge nếu Platform có delayed ownership contract tương đương. Paper-only, không claim Folia vì coordinator maps/views dùng main-thread model.

## 4. Inventory / exploit boundary

Holder riêng với plugin owner token, viewer/session/view và inventory reference. Không nhận diện bằng title/name/lore. Các inventory view của plugin khác không bị can thiệp.

Mọi click trên owned GUI được cancel, kể cả bottom. Chỉ LEFT + PICKUP_ALL/NOTHING, RIGHT + PICKUP_HALF/NOTHING mới có thể dispatch selection. Already-cancelled, cursor nonempty, stale/busy, creative event, shift, number, offhand, collect/double, drop/middle/unknown đều không dispatch. Right chỉ thực thi clear trên SOURCE. Top rawSlot map button; bottom chỉ native player inventory storage0–35, left selects reference, không move stack. Drag owned view hủy toàn bộ kể cả bottom để reference không bị di chuyển.

Creative/Spectator không được mở preview. Gamemode/death/teleport/kick cleanup theo lifecycle; quit forget. Escape hoặc foreign menu close session. Cancelled replacement open đóng đúng old owned holder còn sót, không đóng foreign menu. Disable đóng đúng active holder trước unregister listener/cancel tasks.

Đây là source và pure policy test; chưa chứng minh exploit-free trong Bukkit/vanilla/Geyser/ViaVersion/third-party inventory plugins. Malicious plugin tự un-cancel event hoặc sửa inventory ngoài contract không nằm trong bảo đảm.

## 5. Preview / pricing

Facade dùng lại catalog và quote engines, không tự có chance formula riêng. Path permission và typed condition trước pagination. Explicit denied selection không tự đổi. Lists chỉ chứa enabled/eligible rows, selected boost bị mất điều kiện có CLEAR_BOOSTS để tháo.

Source là whole stack của slot pin. Đổi source reset target/profile/boost; selected slot không tự đi theo hotbar scroll. Source slot excluded khỏi resource capture. Preview quote có phase3 UUID/selection/expiry; không phải quyền thanh toán. Thiếu balance/provider vẫn hiển thị yêu cầu với warning, không gọi là đã reserve. INFO có từng dòng reserve / consumed(success) / consumed(failure), tối đa 32 resources theo CostPlan.

UPGRADE luôn trả message khóa rồi refresh; không có reference tới transaction/output engine trong GUI package. Phase 5 output re-offer sau transfer price drift vẫn chưa có. Refresh ở Phase 6 là lấy preview mới, không approve một attempt cũ.

## 6. Rendering / Resource Pack

`GuiRenderer.Frame` là full top-slot map + action bindings. `SlotDiff` chỉ trả slot thay đổi; next frame luôn có filler ở empty entries để xóa row/action trang trước. Filler fallback chọn slot FILLER nhỏ nhất, deterministic. Reopen container khi đổi screen/page/filter/sort; giữ cùng container khi đổi selection trong screen. Title config được render lúc open, không gửi packet đổi title mỗi refresh. Vì vậy `{pages}` trong title có thể là0 ở loading; đặt `{page}` trong title và `{pages}` ở item lore như mẫu.

Menu text qua MiniMessage + unparsed dynamic placeholders; player-derived text không được parse thành tags. Không dùng original display name/lore làm action hoặc state. Color/style do config admin kiểm soát. Name/lore tắt italic.

Source/target icon **không** là clone nguyên native snapshot. Deserialize bounded snapshot chỉ để tạo projection gồm material/amount/item-model/CustomModelDataComponent; metadata còn lại từ configured display. Không copy provider PDC/UUID, container children, attributes, potion effects, original lore/custom name. Config item-model/CMD ghi đè cosmetic projection. Heads/dyed armor/potion colors chưa hỗ trợ projection nên có thể khác vật phẩm thật; không gọi đó là full visual fidelity.

Snapshot vượt `maximum-icon-bytes` hoặc budget512KiB/frame/deserialize lỗi => configured material fallback + warning lore. Không ảnh hưởng backend source fingerprint/value/target. Filler cache tối đa256 templates/revision; render fullframe mỗi action nhưng native setItem chỉ diff. Không có per-tick roulette trong Phase6, không có chứng cứ TPS/MSPT hoặc giảm packet bao nhiêu phần trăm.

Pack title/font/model fields sẵn để user thiết kế art. Native hitboxes vẫn9-column grid; không cam kết giống screenshot1:1 hoặc freeform controls. Không native packet dependency ở phase này.

## 7. Config và migration

Mới: `menus/settings.yml`, `menus/catalog.yml`, `menus/profiles.yml`, `menus/boosts.yml`. Cập nhật bundled main menu và message keys. Settings chỉ chấp nhận REFERENCE_ONLY / DISCARD_SELECTION; capacity1..256, idle30..1800s, request1..30s, click100..3000ms, open250..10000ms, native icon1..64KiB. Sounds key/volume/pitch validates against startup registry snapshot; empty key tắt. Custom resource-pack sound chưa qua validator; hiện chỉ registry-native keys.

Main SOURCE selector đúng1; lists không có SOURCE selector; mỗi list1..45 entry slots. Dynamic role/action phải đúng và không hardcode row ID. Static profile/boost ID phải tồn tại; disabled ID không tự enabled bởi GUI. Matrix9columns1..6rows, covered slots, FILLER/CLOSE/BACK_MAIN requirements được compiler/domain kiểm tra. Source-index giả trong CompiledMenu bị từ chối.

Loader file/YAML off-thread + registry snapshots; publish whole runtime only after valid. On reload error old runtime remains. Old GUI invalidates immediately on interaction/revision check và batch closes; no partial definition swap. Existing user files không overwrite; merge main menu mới bằng tay khi muốn thêm nút. Không tăng main config-version/schema storage, không reset values.

## 8. Kiểm thử / giới hạn

88 nhóm core mới,1.196assertion; tổng729/53.125/0. Bao gồm real pure session/domain/compiler/click policy/diff/facade tests và fixture qua engines cũ. Không tạo fake Bukkit API, không tuyên bố mouse click/drag qua client đã chạy. Thread test32 reservation contenders có1winner; permutation/boundary loops nằm trong số nhóm, không cộng riêng.

Demo sourceiron90, recommendedgold180, catalog2rows/page: gold/emerald rồi diamond; explicitdiamond900 chance9%; lucky_shard +5points=14%, reserve2emerald. Itembytes và resources của demo là synthetic, không gọi Platform hay Minecraft.

15 journal +14 output SQL regression thật qua PythonSQLite, legacy18+1fixture unchanged; chưa JDBC driver/MySQL/Platform.1019resource checks/17YAML +162Java syntax parses là bổ sung, không phải compile/link Paper. Bằng chứng logs trong `verification/phase06/`.

## 9. Acceptance tiếp theo

Full Paper compile với API artifact thật, verify bundle contents và boot. Native lifecycle/source/metadata/identity/cost snapshots; cursor/shift/drag/hotbar/offhand/creative tests từclient; resourcepack accept/decline và contentprojection; PAPI/permission/provider unload/reload mid-request; config/reopen/timeout/foreignGUI interactions; large metadata/many viewers đoMSPT/queues. Native escrow/debit/ledger/output entitlement/delivery/reconciliation trước nút live. Đừng mở gacha bằng cách chỉ đổi featureflag.

Phase7 có thể thêm timeline/presentation nhận predetermined outcome, nhưng không giả WIN/LOSS cho nút đang khóa và không để animation gọi RNG hoặc phát đồ.

## 10. Nguồn API đối chiếu

Tài liệu kỹ thuật bên ngoài dùng để đối chiếu API, không thay thế source/API JAR của Platform:

- https://docs.papermc.io/paper/dev/custom-inventory-holder/
- https://jd.papermc.io/paper/1.21.4/org/bukkit/event/inventory/InventoryClickEvent.html
- https://jd.papermc.io/paper/1.21.4/io/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler.html
- https://jd.papermc.io/paper/1.21.4/org/bukkit/inventory/meta/ItemMeta.html
- https://jd.papermc.io/paper/1.21.4/org/bukkit/Registry.html
- User-supplied guide: `docs/reference/LEDATPLATFORM_API.md` (reference, không executable SDK).
