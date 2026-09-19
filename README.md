# LeDatItemUpgrader — Phase 0–9A

Version **`0.9.0-phase09a`**, tiếp tục trực tiếp source `0.8.0-phase08`. Target giữ nguyên **Paper 1.21.4 / Java 21 / LeDatPlatform API 2.10.0 theo guide được cung cấp**. Không claim Folia hay mọi bản Minecraft mới hơn.

**Phase 9A thêm storage bootstrap/readiness/maintenance:** explicit OFF/VERIFY/INITIALIZE, kiểm tra schema chung
cho journal/output/progress, history chỉ mở khi READ_READY, retention một batch opt-in và command diagnostics.
Đây là **một phần Phase 9**, không chứng nhận production-ready. Không mở source custody, trừ phí hoặc phát reward.
Flags upgrades/packets/native pity vẫn false; history và schema management mặc định off.
Contract chi tiết: `docs/PHASE09A.md`; contract gameplay các phase trước vẫn giữ nguyên.

Core đã compile và chạy test thật. Module Paper đã compile/link và release-JAR guard đã chạy với Paper 1.21.4, PacketEvents 2.13.0 và LeDatPlatform API class-version 65. **Chưa chạy plugin trên Paper/client thật**, nên tương tác protocol với các plugin GUI khác vẫn cần staging acceptance trước production.

## Kiểm chứng

| Phạm vi | Kết quả |
|---|---|
| Core compile | JDK 21.0.11, `--release 21 -Xlint:all -Werror` |
| Phase 0–1 | 126 nhóm / 3.643 assertion / 0 lỗi |
| Phase 2 | 123 nhóm / 17.897 assertion / 0 lỗi |
| Phase 3 | 182 nhóm / 15.730 assertion / 0 lỗi |
| Phase 4 | 105 nhóm / 9.341 assertion / 0 lỗi |
| Phase 5 | 105 nhóm / 5.318 assertion / 0 lỗi |
| Phase 6 regression | 97 nhóm / 2.238 assertion / 0 lỗi |
| Phase 7 regression | 84 nhóm / 6.605 assertion / 0 lỗi |
| Phase 8 regression | 98 nhóm / 1.554 assertion / 0 lỗi |
| Phase 9A mới | 47 nhóm / 147 assertion / 0 lỗi |
| **Tổng core** | **967 nhóm / 62.473 assertion / 0 lỗi** |
| SQL progress repository regression | 31 nhóm /535 assertions /0 lỗi; Java repository thật + SQLite qua bridge kiểm thử, không phải driver production |
| SQL storage repository mới | 33 nhóm /183 assertions /0 lỗi; Java + SQLite qua transport kiểm thử, chưa driver thật |
| SQL SQLite regression | 15 journal + 14 output tests, SQL thực qua Python, không phải JDBC integration |
| Legacy codec | 18 history records + 1 competitor fixture giữ bytes/digest v1 |
| Legacy v2 | 4 plans +126 record/state hash pairs từ ZIP Phase7 thật |
| Resource bổ sung | 1.483 checks / 22 YAML / PyYAML; không thay Java loader |
| Paper compile/JAR | Thành công; release guard xác nhận không bundle Platform/PacketEvents và giữ hard dependencies |

Test policy click dùng **model Java thuần**, không phải Bukkit event hoặc Minecraft client. 13 nhóm mock JDBC Phase 4 và 10 nhóm mock JDBC Phase 5 đã nằm trong tổng trên. Bằng chứng và giới hạn: `verification/BUILD_REPORT.md`; logs/XML hiện tại trong `verification/phase09/`; báo cáo Phase 8 giữ ở `verification/phase08-original/`, evidence các phase trước vẫn giữ nguyên. SQL bridge mới nằm ngoài tổng core.

## Chạy backend đã kiểm chứng

Linux/macOS, JDK 21:

```bash
chmod +x scripts/*.sh
./scripts/test-core.sh
./scripts/run-gui-demo.sh
./scripts/run-animation-demo.sh
./scripts/run-output-demo.sh
./scripts/run-progress-demo.sh
./scripts/test-progress-sqlite.sh
./scripts/test-storage-sqlite.sh
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
python3 scripts/verify-legacy-journal.py
python3 scripts/verify-legacy-output-v2.py
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
```

SQL scripts dùng thư viện chuẩn Python. Resource checker cần PyYAML. SQL được xuất từ class Java vừa compile, không giữ bản SQL riêng cho test. Các script `run-demo.sh`, `run-catalog-demo.sh`, `run-quote-demo.sh`, `run-transaction-demo.sh` vẫn còn.

PowerShell có `scripts/test-core.ps1`, `scripts/run-gui-demo.ps1` và `scripts/run-animation-demo.ps1`; chưa chạy trên Windows trong môi trường bàn giao này.

Kiểm tra prerequisites offline (exit 1 nếu thiếu, không tải SDK hoặc tạo stub):

```bash
python3 scripts/native-preflight.py --api-jar /absolute/path/ledat-platform-api-2.10.0.jar
```

Lệnh build tương đương khi có Gradle wrapper và API artifact thực:

```powershell
# Run the LeDatCanvas wrapper from the ItemUpgrader checkout.
& 'C:\path\to\LeDatCanvas\gradlew.bat' -p 'C:\path\to\LeDatItemUpgrader' `
  backendCheck :paper:build -PledatCanvasDir='C:\path\to\LeDatCanvas' --no-daemon --max-workers=1
```

`ledatCanvasDir` làm sạch và build lại riêng `:ledat-platform-api:jar` bằng JDK 21 trước khi link plugin,
để không lấy nhầm API JAR cũ hoặc JAR build bằng Java khác. Nếu cần pin một API release đã kiểm tra,
có thể dùng `-PplatformApiJar=/absolute/path/ledat-platform-api.jar`; JAR đó vẫn phải là class version 65
(Java 21). Artifact phát hành tự kiểm tra không bundle Platform/PacketEvents và giữ `depend: [LeDatPlatform, packetevents]`.

Hoặc đặt API thật ở `libs/ledat-platform-api-2.10.0.jar`. Không shade Platform/Paper/PacketEvents; core được đưa vào cùng plugin JAR. `core` không phải plugin. Artifact vừa kiểm tra nằm tại `paper/build/libs/LeDatItemUpgrader-0.9.0-phase09a.jar`; đây vẫn chưa phải bằng chứng runtime/staging.

## Storage readiness — Phase 9A

`storage-management.yml` mặc định `schema-mode: 'OFF'`, maintenance=false. Sau khi build/staging và backup DB,
admin có thể opt-in INITIALIZE cho cài mới, rồi chuyển VERIFY. **Không** thay storage backend, không DROP/ALTER,
không tạo lại data table của component đã version, không tự sửa metadata/unknown schema.

```text
/upgrader storage status
/upgrader storage recheck
/upgrader storage recovery [uuid-cursor]
```

Permission: use + `ledatitemupgrader.admin.storage`, default op. Player/console; recovery chỉ đọc 16 summary rows
mỗi trang, không giải mã payload hoặc replay effects. **Recheck chạy mode hiện tại**, nên INITIALIZE có thể CREATE;
OFF không tự bật schema. READ_READY không chứng minh live adapter hoặc database toàn vẹn.

Retention một batch mỗi interval, sử dụng retention-days/batch trong history.yml; chỉ xóa terminal history projection
với state COMPLETED/ABORTED, không đụng journal/output/stats/pity/locks/receipts. Timeout không nhả capacity cho tới
khi SQL future thật kết thúc. Chi tiết giới hạn và setup: `docs/PHASE09A.md`.

## History/Pity/Admin — Phase 8

`docs/PHASE08.md` mô tả đầy đủ state semantics, schema/hook boot và giới hạn.

```text
/upgrader history [all|win|loss|unfinished]
/upgrader history <player-UUID> [filter]      # admin.history
/upgrader statistics
/upgrader diagnose <transaction-UUID>       # admin.diagnostics, read-only
/upgrader pity [scope-hash]                  # core live vẫn gated
```

History/statistics cần use + history; hai quyền admin mặc định op. Menu history source gồm 28 entry slots, keyset
pagination, phiên theo viewer/subject/revision/request, cursor rỗng, cancel click/drag, chỉ navigation khai báo.
Icon chỉ từ config, không có item payload thật. **WIN/LOSS đã roll không đồng nghĩa COMPLETED/delivered.**
History query read-only; không refund/reward/unlock hoặc reset pity từ command.

Pity core chỉ DESTROY theo allow-list cụ thể + source floor; bonus điểm phần trăm sau boosts trước clamp, loss
COMPLETED tăng streak, win COMPLETED reset, version tăng cả hai. Quote có stamp; thay version buộc xác nhận lại.
Hook history/statistics/pity/receipt nằm trong SQL transaction cùng journal CAS và trước terminal unlock. Phải inject
hook vào live writer; constructor không hook sẽ từ chối plan có pity. Chưa bootstrap writer native.

Files mới: `history.yml`, `upgrades/pity.yml`, `menus/history.yml`; defaults history=false, pity=false. Bật history không
auto-create schema; Phase 9A yêu cầu mode/schema READ_READY mới mở read UI. Native pity=true bị validator từ chối
cho đến khi quote/writer nối đúng. Retention scheduling giờ có source trong service mới nhưng mặc định tắt;
không xóa active journal/tombstones.
Backfill history không tự tính lại lifetime statistics/pity. Counter chỉ tính từ khi tracking writer được bật.

PAPI route `completed`, `wins`, `losses`, `win_rate` dưới `%ledat_itemupgrader_...%` chỉ đọc bounded cache. Cache hiện
nạp qua `/upgrader statistics`, cold/expired trả rỗng; không có query trong PAPI, không có scope-less pity placeholder.

## Player flow trong source GUI

Với bản Paper đã build và kiểm chứng trên staging:

`/upgrader` hoặc `/upgrader menu` → reference item tay chính → recommended target → Catalog / Profiles / Boosts → Quote preview.

**Quyền:** `ledatitemupgrader.use`. Chỉ Survival/Adventure, online/alive, cursor trống. Click trái item ở túi dưới chọn **slot reference**; item không rời túi. Click trái SOURCE chọn slot tay chính; click phải SOURCE bỏ chọn. Chọn source mới đặt lại target/profile/boost để tránh tái dùng điều khoản cũ.

Source là **một phần có giới hạn của stack tại slot được chọn**; mặc định 1 item. Slot 39–44 là bước 2/4/8/12/16/20: click trái tăng, click phải giảm, luôn clamp trong `1..số item thật ở source slot`. `amount0/1/2/3` lần lượt là không đổi được / chỉ tăng / chỉ giảm / tăng giảm đều được. Thay hotbar đang cầm không tự đổi reference đã pin. Source slot bị loại khỏi phí. Không dùng armor, offhand, cursor làm source/fee. Túi dưới bị khóa di chuyển khi xem GUI; phải đóng menu để sửa inventory.

Target được chọn bằng ID trong trang catalog đã lọc. Profile/boost kiểm tra lại quyền/path/condition. Đổi profile không âm thầm bỏ booster; tổ hợp không hợp lệ báo quote-denied và có Bỏ chọn tất cả boosts.

INFO hiển thị chance, profile, failure, resources và từng dòng **cần giữ / thắng tiêu / thua tiêu**. Các số này chưa phải phí đã trừ. Quote hết hạn được xóa khỏi preview và chờ REFRESH, không tự kéo dài.

Main menu dùng source slot 1, target slot 7, amount slot 39–44 và vùng xác nhận 36–38 (raw slot, zero-based). Khi `features.upgrades-enabled: true`, UPGRADE revalidate quote/item/phí, tạo ticket RNG server-side, chạy arrow rồi commit inventory/economy. Native test executor chỉ hỗ trợ output CLEAN và failure DESTROY/KEEP; DAMAGE/DOWNGRADE/metadata transfer bị từ chối. Đóng menu trước khi arrow dừng sẽ hủy attempt chưa commit.

## Xem thử animation — command source Phase 7

Sau khi Paper/API build và staging gate đạt:

```text
/upgrader animation win roulette
/upgrader animation loss quick
/upgrader animation win none
```

Cần `ledatitemupgrader.use` + `ledatitemupgrader.admin.animation` (mặc định op), Survival/Adventure, cursor trống. Đây là **admin UI preview** có nhãn riêng: chọn kết quả để xem giao diện, không lấy item, không tính RNG/odds hoặc phát thưởng. Nút UPGRADE không mở animation demo thay cho giao dịch thật.

`roulette` danh nghĩa 4,45s, `quick` 1,6s, `none` chỉ reveal 0,7s. Hold bắt đầu sau owner render/ack; lag có thể làm tổng thời gian dài hơn. Skip hiện cùng outcome, không reset hold. Route xám/marker vàng trung tính; không giả tỷ lệ ô màu thành xác suất.

Mặc định một ticker, tối đa64 session/16 visits mỗi tick, một callback đang chờ/session, cue pulse tối thiểu120ms. Khi đông viewer FPS mỗi người giảm; không phát bù các frame/cue đã bỏ lỡ. Chỉ cập nhật slot khác frame trước. Chưa có item/container packet renderer hoặc benchmark MSPT/TPS; bridge mới chỉ đổi title của inventory native.

Title animation cần plugin server **PacketEvents 2.13+**. Khai báo các frame trong `menus/animation.yml`:

```yaml
title-animation:
  interval-ticks: 1
  titles:
    - '<font:my_pack:gui>\uE001</font>'
    - '<font:my_pack:gui>\uE002</font>'
```

Bridge chỉ capture `OPEN_WINDOW` khi chính `AnimationHolder` đang được mở, rồi gửi title qua pipeline PacketEvents với đúng container id/type để engine Resource Pack có thể render tag. Sau mỗi title packet, server resync nội dung exact container vì client Minecraft xóa slot khi nhận lại `OPEN_WINDOW`. Bridge không nghe/cancel hay cache `WINDOW_ITEMS`, không lưu packet của GUI khác và luôn kiểm tra exact player/session/top-inventory trước mỗi frame. `visits-per-tick` vẫn là backpressure: khi số viewer vượt budget, frame title trễ bị bỏ qua thay vì phát bù.

Read-only `CommittedAnimationReader` đã có core cho outcome thật từ journal; **chưa nối live native**. `SETTLING` chưa đồng nghĩa reward đã giao. Chi tiết source/config/thread/lifecycle tại `docs/PHASE07.md`.

## GUI config / Resource Pack

```text
paper/src/main/resources/
├─ config.yml
├─ messages.yml
├─ menus/
│  ├─ settings.yml
│  ├─ upgrader.yml
│  ├─ catalog.yml
│  ├─ profiles.yml
│  ├─ boosts.yml
│  └─ animation.yml
└─ upgrades/
   ├─ values.yml / recipes.yml
   ├─ catalog.yml / paths.yml
   ├─ chance.yml / profiles.yml / boosts.yml / conditions.yml
   └─ outputs.yml
```

`matrix` có 9 ký tự mỗi hàng, 1–6 hàng. Main có đúng một `SOURCE_INPUT` (tên role được giữ tương thích blueprint cũ; semantics hiện là reference). Các list có `CATALOG_ENTRY`, `PROFILE_ENTRY`, `BOOST_ENTRY`, mặc định 28 entry slots/menu. Số entry slots quyết định page size GUI, không đổi page size command.

Mọi menu có FILLER và CLOSE, list có BACK_MAIN. Entry động không khai báo argument; renderer bind ID từ trang đã lọc. Các action tĩnh SELECT_PROFILE/TOGGLE_BOOST cần ID tồn tại. Đổi layout không đổi business service.

Main title chuẩn nằm trong `menus/upgrader.yml.title-display`: trạng thái CHƯA CÓ; khi đủ source/target thì hiện ngay hai giá trị compact (`100.2K`), amount0..3, chance hai số lẻ (`2.46%`, `100.00%`) và frame bar tương ứng trong `bar_full:0..153:0`. Đổi số lượng sẽ tính lại quote và cập nhật bar ngay. Arrow chỉ xuất hiện sau click Upgrade, chạy ngẫu nhiên qua lại rồi cubic ease-out từ shift -157 tới vị trí kết quả, kèm cue `title-pulse`; mặc định kéo dài 72 tick để dễ theo dõi hơn. Bar dùng xác suất integer-ticket gốc, không dùng text đã làm tròn. `visits-per-tick` giới hạn số viewer được cập nhật mỗi tick; frame trễ được coalesced, không tạo backlog.

Title/name/lore MiniMessage, material, item-model, custom-model-data, glow, action và sounds đều cấu hình được. Title packet luôn hoạt động khi `title-display.enabled: true`; CraftEngine, Nexo và PlaceholderAPI chỉ là hook resolve tùy chọn, không phải cổng bật/tắt title. Khi có PlaceholderAPI, các placeholder VietHUD được expand trước; `<shift>`/`<image>` được giao cho parser CraftEngine qua class loader của chính plugin, sau đó packet refresh đi qua pipeline PacketEvents bình thường. Capture hai giai đoạn LOWEST→MONITOR chỉ được arm trong đúng lời gọi `openInventory` của plugin rồi khóa ngay, không dựa vào title Component có thể đã bị plugin khác biến đổi; packet bị hủy hoặc bị thay container sẽ không được nhận. Sau mỗi `OPEN_WINDOW` đổi title, plugin resync exact inventory để client không làm trống item. Nếu lớp title packet/parse thất bại, preview vẫn mở bằng title ban đầu và chỉ animation title bị tắt cho view đó. Native inventory grid vẫn là hitbox; Resource Pack art phải khớp grid. Không có zMenu dependency hoặc packet-only container; plugin không bundle asset Resource Pack hay world displays.

Icon source/target là **visual projection mới**: material, quantity, item-model và custom-model-data. Không copy nguyên PDC/UUID/provider ID, attributes, effects, container contents hoặc original lore vào top inventory. Item preview không được dùng làm item giao dịch. Heads/dyed armor/potion colors và những cosmetic khác chưa có projection adapter nên có thể khác item gốc. Backend vẫn giữ snapshot thật để tính toán; không biến icon fallback thành target thật.

`maximum-icon-bytes` mặc định 32 KiB; budget icon snapshot 512 KiB/frame. Quá giới hạn/không deserialize được dùng material config và lore cảnh báo. Renderer hiện dựng frame đầy đủ sau action để so sánh, **chỉ setItem ở những slot thay đổi**; không phải đã tối ưu toàn bộ render thành dirty-only computation.

## Reload / migration

Không ghi đè các file cũ. Các file menu được ensure/copy khi thiếu; Phase 7 thêm `menus/animation.yml`; Phase 8 thêm `history.yml`, `upgrades/pity.yml`, `menus/history.yml`; Phase 9A thêm `storage-management.yml`. `messages.yml` cũ thiếu key dùng bundled fallback sinh từ YAML trong lúc build; có thể copy thêm key để tự dịch.

`menus/upgrader.yml` đã tồn tại **không tự bị ghi đè**. Nếu thiếu `title-display`, runtime dùng nguyên bộ title image chuẩn đóng trong plugin để tương thích config cũ; thêm block này khi muốn chỉnh timing/template. Matrix/slot và các symbol amount vẫn cần merge thủ công từ file mẫu trong JAR; `menus/settings.yml` cũ cần thêm cue `title-pulse` nếu muốn có âm thanh. Menu hiện có phải có SOURCE selector, FILLER, CLOSE và reference hợp lệ; nếu không, validator từ chối candidate thay vì suy đoán layout. Phiên bản config/messages giữ 1 vì không rewrite user data.

Load/parse/validate rồi swap toàn runtime. Config sai giữ bản đang dùng; startup sai thì backend unavailable. GUI revision cũ bị khóa thao tác ngay khi detect stale, được đóng bởi sweep batch; callback cũ không reopen. Source-mode khác REFERENCE_ONLY hoặc close-behavior khác DISCARD_SELECTION bị từ chối.

## Limits và scheduler

Defaults: 128 sessions, idle 600s, pending 15s, click cooldown 150ms, open cooldown 750ms. One busy request/session; GUI worker limit 16, shared catalog cache dùng budget riêng hiện có. Một batch sweep mỗi giây, không quét toàn bộ player/server mỗi tick.

Snapshot/inventory/registry/sound ở owner thread; query/value/chance dùng detached data trên Platform worker, rồi quay owner recheck source/access/resources/revision/generation/expiry. PAPI/economy balance adapters vẫn là sync provider calls theo contract; một expansion hoặc provider block vẫn cần staging và profiling thật.

`PaperGuiScheduler` là bridge nhỏ native next-tick + batch sweep/ticker; mỗi instance theo dõi và cancel **task handles riêng**, không cancel mọi task chung plugin owner. Sửa này tránh đóng GUI vô tình tắt ticker animation hoặc ngược lại. Guide Platform chưa có chữ ký delayed, và `runOnlinePlayer` không chứng minh next-tick; không giả API để mở/đóng inventory ngay trong click event. Sau API acceptance có thể thay bridge đúng public contract. **Không hỗ trợ/claim Folia.**

## Những phần vẫn chờ

Native escrow/source custody, Platform ledger, economy debit/refund, durable entitlement/mailbox/delivery, actual item/identity/PDC round-trip, live output provider adapters và re-offer UI khi transfer đổi value. Core Phase 4–5 đã có nhưng chưa bootstrap side effects thật.

History/pity đầy đủ, live journal-to-native animation wiring, packet renderer, API JAR/full Paper build, JDBC/MySQL integration, exploit/client tests và benchmark TPS/MSPT vẫn chưa có. Xem `docs/PHASE07.md`, `docs/PHASE06.md`, `docs/TEST_CHECKLIST.md`, `docs/CONTINUE.md`, `docs/PLATFORM_CONTRACT_GAPS.md`, `plan.md`.
